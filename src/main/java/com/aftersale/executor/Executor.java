package com.aftersale.executor;

import com.aftersale.agent.AgentPropsProvider;
import com.aftersale.domain.ExecutionLogEntity;
import com.aftersale.domain.IdempotencyKeyEntity;
import com.aftersale.domain.OrderEntity;
import com.aftersale.domain.PlanEntity;
import com.aftersale.domain.PlanStepEntity;
import com.aftersale.enums.OrderStatus;
import com.aftersale.repo.ExecutionLogRepository;
import com.aftersale.repo.IdempotencyKeyRepository;
import com.aftersale.repo.OrderRepository;
import com.aftersale.repo.PlanRepository;
import com.aftersale.repo.PlanStepRepository;
import com.aftersale.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 执行器：CONFIRMED 的 Plan 按步串行执行。
 *
 * 故障恢复三件套：
 * 1. 幂等：执行前 INSERT idempotency_keys(planId:stepId:attempt)（原生 SQL，触发唯一索引）。
 *    冲突 → 说明同 attempt 已执行过：按已记录结果回放；无结果记录 → 上次中断在飞行中 → 对账。
 * 2. 超时二分：
 *    - FAIL_BEFORE_SEND（连接失败/未发出）→ 明确失败 FAILED → attempt+1 安全重试（上限 3）
 *    - TIMEOUT_UNKNOWN（已发出未响应）→ 结果未知 UNKNOWN → 禁止盲目重试，转对账：
 *      订单已达期望终态 → 补记 SUCCESS；否则标记 FAILED 待人工/补偿
 * 3. 断点续跑：每步落 execution_log；resume() 扫描 EXECUTING 的 Plan，从首个非 SUCCESS 步骤继续。
 */
@Service
public class Executor {

    private static final Logger log = LoggerFactory.getLogger(Executor.class);
    static final int MAX_ATTEMPTS = 3;

    private final PlanRepository planRepository;
    private final PlanStepRepository planStepRepository;
    private final ExecutionLogRepository executionLogRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final OrderRepository orderRepository;
    private final WriteToolRegistry writeToolRegistry;
    private final FaultInjector faultInjector;
    private final AgentPropsProvider props;
    private final JdbcTemplate jdbcTemplate;

    public Executor(PlanRepository planRepository, PlanStepRepository planStepRepository,
                    ExecutionLogRepository executionLogRepository,
                    IdempotencyKeyRepository idempotencyKeyRepository,
                    OrderRepository orderRepository, WriteToolRegistry writeToolRegistry,
                    FaultInjector faultInjector, AgentPropsProvider props,
                    JdbcTemplate jdbcTemplate) {
        this.planRepository = planRepository;
        this.planStepRepository = planStepRepository;
        this.executionLogRepository = executionLogRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.orderRepository = orderRepository;
        this.writeToolRegistry = writeToolRegistry;
        this.faultInjector = faultInjector;
        this.props = props;
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ExecuteResult(String planStatus, int stepsTotal, int stepsDone, String message) {}

    /** 执行一个 CONFIRMED 的 Plan（幂等：重复调用会续跑而不是重复执行） */
    @Transactional
    public ExecuteResult execute(Long planId, String userId) {
        PlanEntity plan = planRepository.findById(planId).orElse(null);
        if (plan == null) {
            return new ExecuteResult("NOT_FOUND", 0, 0, "计划不存在");
        }
        if (!plan.userId.equals(userId)) {
            return new ExecuteResult("FORBIDDEN", 0, 0, "只能执行本人的计划");
        }
        if (List.of("COMPLETED", "FAILED").contains(plan.status) && allStepsDone(planId)) {
            return new ExecuteResult(plan.status, 0, 0, "计划已结束（幂等重入）");
        }
        if (!List.of("CONFIRMED", "EXECUTING").contains(plan.status)) {
            return new ExecuteResult(plan.status, 0, 0, "计划状态 " + plan.status + " 不可执行（需先确认）");
        }
        plan.status = "EXECUTING";
        planRepository.saveAndFlush(plan);

        List<PlanStepEntity> steps = planStepRepository.findByPlanIdOrderBySeq(planId);
        for (PlanStepEntity step : steps) {
            if ("SUCCESS".equals(step.status)) {
                continue; // 断点续跑：跳过已完成步骤
            }
            StepOutcome outcome = executeStepWithRetry(plan, step);
            if (!"SUCCESS".equals(outcome.status)) {
                plan.status = "FAILED";
                planRepository.save(plan);
                return new ExecuteResult("FAILED", steps.size(), doneCount(planId),
                        "步骤 " + (step.seq + 1) + " 未完成: " + outcome.message);
            }
        }
        plan.status = "COMPLETED";
        planRepository.save(plan);
        return new ExecuteResult("COMPLETED", steps.size(), steps.size(), "全部步骤执行成功");
    }

    /** 应用重启/崩溃后恢复：扫描 EXECUTING 的计划继续执行，返回恢复数量 */
    @Transactional
    public int resumeAll() {
        int n = 0;
        for (PlanEntity plan : planRepository.findAll()) {
            if ("EXECUTING".equals(plan.status)) {
                log.info("断点续跑: plan {}", plan.id);
                execute(plan.id, plan.userId);
                n++;
            }
        }
        return n;
    }

    record StepOutcome(String status, String message) {

        /** 可重试的瞬态失败 */
        static StepOutcome retryable(String msg) { return new StepOutcome("FAILED", msg); }

        /** 业务拒绝（政策/越权/参数）——重试无意义，直接终止 */
        static StepOutcome finalRejection(String msg) { return new StepOutcome("FAILED_FINAL", msg); }
    }

    /** 单步执行 + 重试循环（FAILED_FINAL 即业务拒绝不重试；FAILED 重试上限 3；UNKNOWN 不重试转对账） */
    private StepOutcome executeStepWithRetry(PlanEntity plan, PlanStepEntity step) {
        while (step.attempt < MAX_ATTEMPTS) {
            StepOutcome o = executeStepOnce(plan, step);
            if ("SUCCESS".equals(o.status()) || "UNKNOWN".equals(o.status())
                    || "FAILED_FINAL".equals(o.status())) {
                return o;
            }
            // FAILED（瞬态）→ attempt+1 重试
            step.attempt = step.attempt + 1;
            step.status = "PENDING";
            planStepRepository.save(step);
            log.info("step {} attempt 失败，换新 attempt={} 重试", step.id, step.attempt);
        }
        step.status = "FAILED";
        planStepRepository.save(step);
        return new StepOutcome("FAILED", "重试 " + MAX_ATTEMPTS + " 次仍失败");
    }

    /** 单次尝试：幂等键抢占 → 故障注入/工具执行 → 三态落账 */
    @Transactional(propagation = Propagation.REQUIRED)
    StepOutcome executeStepOnce(PlanEntity plan, PlanStepEntity step) {
        int attempt = step.attempt;
        String key = plan.id + ":" + step.id + ":" + attempt;
        long t0 = System.currentTimeMillis();

        // 1. 幂等键抢占（消融开关可关）
        if (props.idempotencyEnabled() && !acquireIdempotencyKey(plan.id, step.id, attempt)) {
            // 冲突：同 attempt 已发生过 → 结果回放或对账
            return resolveConflict(plan, step, attempt);
        }

        // 2. 故障注入（评测）
        FaultInjector.Mode mode = faultInjector.takeOnce();
        if (mode == FaultInjector.Mode.FAIL_BEFORE_SEND) {
            log(plan.id, step.id, attempt, key, "FAILED", "INJECTED_FAIL", "请求未发出即失败", t0);
            step.status = "FAILED";
            planStepRepository.save(step);
            return new StepOutcome("FAILED", "连接失败（明确失败，可安全重试）");
        }
        if (mode == FaultInjector.Mode.TIMEOUT_UNKNOWN) {
            log(plan.id, step.id, attempt, key, "UNKNOWN", "INJECTED_TIMEOUT", "请求已发出但无响应", t0);
            step.status = "UNKNOWN";
            planStepRepository.save(step);
            // 结果未知：禁止盲目重试 → 对账
            return reconcile(plan, step, attempt, key, t0);
        }

        // 3. 真实执行
        ToolResult result;
        try {
            result = writeToolRegistry.invoke(step.toolName, step.argsJson);
        } catch (Exception e) {
            // 本地事务异常视为明确失败
            log(plan.id, step.id, attempt, key, "FAILED", "TOOL_ERROR", e.getMessage(), t0);
            step.status = "FAILED";
            planStepRepository.save(step);
            return new StepOutcome("FAILED", "工具异常: " + e.getMessage());
        }

        long status = System.currentTimeMillis();
        if (result.ok()) {
            log(plan.id, step.id, attempt, key, "SUCCESS", null, result.toJson(), status - t0);
            step.status = "SUCCESS";
            step.resultJson = result.toJson();
            planStepRepository.save(step);
            recordIdempotencyResult(key, "SUCCESS", result.toJson());
            return new StepOutcome("SUCCESS", null);
        }
        if ("UNKNOWN".equals(result.status())) {
            log(plan.id, step.id, attempt, key, "UNKNOWN", result.code() == null ? null : result.code().name(),
                    result.message(), status - t0);
            step.status = "UNKNOWN";
            planStepRepository.save(step);
            return reconcile(plan, step, attempt, key, t0);
        }
        // FAILED（政策/参数/越权等业务拒绝）：确定性拒绝，重试无意义，直接终止
        log(plan.id, step.id, attempt, key, "FAILED", result.code() == null ? null : result.code().name(),
                result.message(), status - t0);
        step.status = "FAILED";
        planStepRepository.save(step);
        recordIdempotencyResult(key, "FAILED", result.toJson());
        boolean businessRejection = result.code() != null && List.of(
                com.aftersale.tools.ToolErrorCode.POLICY_DENIED,
                com.aftersale.tools.ToolErrorCode.FORBIDDEN,
                com.aftersale.tools.ToolErrorCode.ORDER_NOT_FOUND,
                com.aftersale.tools.ToolErrorCode.INVALID_ARGS).contains(result.code());
        return businessRejection
                ? StepOutcome.finalRejection(result.message())
                : StepOutcome.retryable(result.message());
    }

    /** 幂等键对账：订单已达期望终态 → 上次飞行中的请求实际已生效；否则转补偿标记 */
    private StepOutcome reconcile(PlanEntity plan, PlanStepEntity step, int attempt, String key, long t0) {
        String desired = WriteToolRegistry.desiredOrderStatus(step.toolName);
        OrderEntity order = orderRepository.findByOrderNo(plan.orderNo).orElse(null);
        if (order != null && desired != null && desired.equals(order.getStatus().name())) {
            log(plan.id, step.id, attempt, key, "SUCCESS", "RECONCILED",
                    "对账成功：订单已处于期望终态 " + desired, System.currentTimeMillis() - t0);
            step.status = "SUCCESS";
            step.resultJson = "{\"reconciled\":true,\"status\":\"" + desired + "\"}";
            planStepRepository.save(step);
            recordIdempotencyResult(key, "SUCCESS", step.resultJson);
            return new StepOutcome("SUCCESS", "对账成功");
        }
        recordIdempotencyResult(key, "UNKNOWN", null);
        log(plan.id, step.id, attempt, key, "UNKNOWN", "RECONCILE_FAILED",
                "对账未通过：订单未达期望终态 " + desired + "，标记待补偿，禁止盲目重试",
                System.currentTimeMillis() - t0);
        return new StepOutcome("UNKNOWN", "结果未知且对账未通过（订单未达终态），已标记待补偿，禁止盲目重试");
    }

    /** 幂等键冲突消解：上次执行留有结果 → 回放；无结果 → 视为飞行中中断，对账 */
    private StepOutcome resolveConflict(PlanEntity plan, PlanStepEntity step, int attempt) {
        String key = plan.id + ":" + step.id + ":" + attempt;
        IdempotencyKeyEntity rec = idempotencyKeyRepository.findById(key).orElse(null);
        if (rec != null && "SUCCESS".equals(rec.resultStatus)) {
            step.status = "SUCCESS";
            step.resultJson = rec.resultJson;
            planStepRepository.save(step);
            return new StepOutcome("SUCCESS", "幂等回放（上次已成功）");
        }
        if (rec != null && "FAILED".equals(rec.resultStatus)) {
            return new StepOutcome("FAILED", "上次同 attempt 已失败: " + rec.resultJson);
        }
        // 无结果记录：上次中断在执行中 → 对账
        return reconcile(plan, step, attempt, key, System.currentTimeMillis());
    }

    /** 原生 INSERT 抢占幂等键（绕过 JPA merge，触发唯一索引冲突） */
    private boolean acquireIdempotencyKey(Long planId, Long stepId, int attempt) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO idempotency_keys (idempotency_key, plan_id, step_id, attempt, created_at) "
                            + "VALUES (?,?,?,?,NOW())",
                    planId + ":" + stepId + ":" + attempt, planId, stepId, attempt);
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.info("幂等键冲突被拦截: {}:{}", stepId, attempt);
            return false;
        }
    }

    private void recordIdempotencyResult(String key, String status, String resultJson) {
        jdbcTemplate.update(
                "UPDATE idempotency_keys SET result_status=?, result_json=? WHERE idempotency_key=?",
                status, resultJson, key);
    }

    private void log(Long planId, Long stepId, int attempt, String key, String status,
                     String errorCode, String detail, Long latencyMs) {
        ExecutionLogEntity e = new ExecutionLogEntity();
        e.planId = planId;
        e.stepId = stepId;
        e.attempt = attempt;
        e.idempotencyKey = key;
        e.status = status;
        e.errorCode = errorCode;
        e.detail = detail;
        e.latencyMs = latencyMs;
        executionLogRepository.save(e);
    }

    private int doneCount(Long planId) {
        return (int) planStepRepository.findByPlanIdOrderBySeq(planId).stream()
                .filter(s -> "SUCCESS".equals(s.status)).count();
    }

    private boolean allStepsDone(Long planId) {
        List<PlanStepEntity> steps = planStepRepository.findByPlanIdOrderBySeq(planId);
        return !steps.isEmpty() && steps.stream().allMatch(s -> "SUCCESS".equals(s.status));
    }
}
