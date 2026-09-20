package com.aftersale.executor;

import com.aftersale.agent.AgentPropsProvider;
import com.aftersale.domain.ExecutionLogEntity;
import com.aftersale.domain.IdempotencyKeyEntity;
import com.aftersale.domain.OrderEntity;
import com.aftersale.domain.PlanEntity;
import com.aftersale.domain.PlanStepEntity;
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
 * 单步尝试的执行者。存在的唯一理由是**事务边界**：
 *
 * 事务边界必须落在"一次尝试"上，不能落在"整个计划"上。
 * 原因：崩溃时未提交的东西会一起消失。如果整个计划共用一个事务，那么
 *   ① 已成功步骤的 execution_log / plan_steps.status 消失 → 续跑无从判断进度；
 *   ② **幂等键也一起消失** → 重试时没有任何东西能拦住重复执行。
 * ②是真正致命的那条：写工具在真实系统里调的是支付/物流接口，副作用已经在外部发生了，
 * 本地回滚不了；此时幂等键丢了，重试就是实打实的重复退款。
 *
 * 为什么必须是独立的 Bean（而不是 Executor 里的私有方法）：
 * Spring 的事务是**代理**实现的，`this.method()` 这种自调用不走代理，
 * 注解形同虚设。原实现把 @Transactional 标在 Executor 的私有步骤方法上，
 * 既因为自调用不生效，又因为 propagation 是 REQUIRED 而并入外层大事务——
 * 两重原因导致"每步落账"从来没有真正发生过。
 *
 * propagation 用 REQUIRED 而不是 REQUIRES_NEW 是刻意的：
 *   - 生产：Executor.execute 不开启事务 → 这里各自成为独立事务并提交（要的就是这个）
 *   - 测试：测试方法自带事务 → 这里并入测试事务，随测试回滚（保住测试隔离）
 * 用 REQUIRES_NEW 会让测试写入绕开测试事务、污染库，反而把测试搞坏。
 */
@Service
public class StepRunner {

    private static final Logger log = LoggerFactory.getLogger(StepRunner.class);

    private final PlanRepository planRepository;
    private final PlanStepRepository planStepRepository;
    private final ExecutionLogRepository executionLogRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final OrderRepository orderRepository;
    private final WriteToolRegistry writeToolRegistry;
    private final FaultInjector faultInjector;
    private final AgentPropsProvider props;
    private final JdbcTemplate jdbcTemplate;

    public StepRunner(PlanRepository planRepository, PlanStepRepository planStepRepository,
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

    /** 一次尝试的结果。FAILED 表示瞬态失败（可换 attempt 重试），FAILED_FINAL 表示业务拒绝（重试无意义） */
    public record Outcome(String status, String message) {
        static Outcome retryable(String msg) { return new Outcome("FAILED", msg); }
        static Outcome finalRejection(String msg) { return new Outcome("FAILED_FINAL", msg); }
    }

    /**
     * 执行第 attempt 次尝试（一个独立事务）：
     * 记录 attempt 计数 → 幂等键抢占 → 故障注入/工具执行 → 三态落账。
     *
     * attempt 计数也在本事务里落盘：它的价值是"这次尝试已经被消耗掉了"。
     * 崩溃后重试上限因此不会被重置，杜绝"崩溃→重试→再崩溃"把重试次数刷成无限。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public Outcome attempt(Long planId, Long stepId, int attempt) {
        PlanEntity plan = planRepository.findById(planId).orElse(null);
        PlanStepEntity step = planStepRepository.findById(stepId).orElse(null);
        if (plan == null || step == null) {
            return new Outcome("FAILED_FINAL", "计划或步骤不存在");
        }
        step.attempt = attempt;
        planStepRepository.save(step);

        String key = plan.id + ":" + step.id + ":" + attempt;
        long t0 = System.currentTimeMillis();

        // 1. 幂等键抢占（消融开关可关）
        if (props.idempotencyEnabled() && !acquireIdempotencyKey(plan.id, step.id, attempt)) {
            // 冲突：同 attempt 已发生过 → 结果回放或对账
            return resolveConflict(plan, step, attempt, key);
        }

        // 2. 故障注入（评测）
        FaultInjector.Injection inj = faultInjector.takeFor(plan.id, step.seq);
        FaultInjector.Mode mode = inj.mode();
        if (mode == FaultInjector.Mode.HANG) {
            // 真实阻塞：模拟下游长时间无响应。此处若被外部 kill，则是对"执行中被杀"的忠实复现
            log(plan.id, step.id, attempt, key, "IN_FLIGHT", "INJECTED_HANG",
                    "请求已发出，下游无响应（阻塞 " + inj.hangMs() + "ms）", null);
            sleepQuietly(inj.hangMs());
            log(plan.id, step.id, attempt, key, "UNKNOWN", "INJECTED_HANG",
                    "下游无响应超过等待窗口", System.currentTimeMillis() - t0);
            step.status = "UNKNOWN";
            planStepRepository.save(step);
            // 与 TIMEOUT_UNKNOWN 同构：结果未知 → 禁止盲目重试 → 对账
            return reconcile(plan, step, attempt, key, t0);
        }
        if (mode == FaultInjector.Mode.FAIL_BEFORE_SEND) {
            log(plan.id, step.id, attempt, key, "FAILED", "INJECTED_FAIL", "请求未发出即失败",
                    System.currentTimeMillis() - t0);
            step.status = "FAILED";
            planStepRepository.save(step);
            return Outcome.retryable("连接失败（明确失败，可安全重试）");
        }
        if (mode == FaultInjector.Mode.TIMEOUT_UNKNOWN) {
            log(plan.id, step.id, attempt, key, "UNKNOWN", "INJECTED_TIMEOUT", "请求已发出但无响应",
                    System.currentTimeMillis() - t0);
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
            log(plan.id, step.id, attempt, key, "FAILED", "TOOL_ERROR", e.getMessage(),
                    System.currentTimeMillis() - t0);
            step.status = "FAILED";
            planStepRepository.save(step);
            return Outcome.retryable("工具异常: " + e.getMessage());
        }

        long status = System.currentTimeMillis();
        if (result.ok()) {
            log(plan.id, step.id, attempt, key, "SUCCESS", null, result.toJson(), status - t0);
            step.status = "SUCCESS";
            step.resultJson = result.toJson();
            planStepRepository.save(step);
            recordIdempotencyResult(key, "SUCCESS", result.toJson());
            return new Outcome("SUCCESS", null);
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
                ? Outcome.finalRejection(result.message())
                : Outcome.retryable(result.message());
    }

    /** 重试次数耗尽：把步骤定格为 FAILED（独立事务提交，与 attempt 计数一起构成终态证据） */
    @Transactional(propagation = Propagation.REQUIRED)
    public void markExhausted(Long stepId) {
        planStepRepository.findById(stepId).ifPresent(step -> {
            step.status = "FAILED";
            planStepRepository.save(step);
        });
    }

    /** 阻塞等待（仅故障注入用）；使用 Thread.sleep 而非忙等，避免占用 CPU */
    private void sleepQuietly(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 幂等键对账：订单已达期望终态 → 上次飞行中的请求实际已生效；否则转补偿标记 */
    private Outcome reconcile(PlanEntity plan, PlanStepEntity step, int attempt, String key, long t0) {
        String desired = WriteToolRegistry.desiredOrderStatus(step.toolName);
        OrderEntity order = orderRepository.findByOrderNo(plan.orderNo).orElse(null);
        if (order != null && desired != null && desired.equals(order.getStatus().name())) {
            log(plan.id, step.id, attempt, key, "SUCCESS", "RECONCILED",
                    "对账成功：订单已处于期望终态 " + desired, System.currentTimeMillis() - t0);
            step.status = "SUCCESS";
            step.resultJson = "{\"reconciled\":true,\"status\":\"" + desired + "\"}";
            planStepRepository.save(step);
            recordIdempotencyResult(key, "SUCCESS", step.resultJson);
            return new Outcome("SUCCESS", "对账成功");
        }
        recordIdempotencyResult(key, "UNKNOWN", null);
        log(plan.id, step.id, attempt, key, "UNKNOWN", "RECONCILE_FAILED",
                "对账未通过：订单未达期望终态 " + desired + "，标记待补偿，禁止盲目重试",
                System.currentTimeMillis() - t0);
        return new Outcome("UNKNOWN", "结果未知且对账未通过（订单未达终态），已标记待补偿，禁止盲目重试");
    }

    /** 幂等键冲突消解：上次执行留有结果 → 回放；无结果 → 视为飞行中中断，对账 */
    private Outcome resolveConflict(PlanEntity plan, PlanStepEntity step, int attempt, String key) {
        IdempotencyKeyEntity rec = idempotencyKeyRepository.findById(key).orElse(null);
        if (rec != null && "SUCCESS".equals(rec.resultStatus)) {
            step.status = "SUCCESS";
            step.resultJson = rec.resultJson;
            planStepRepository.save(step);
            return new Outcome("SUCCESS", "幂等回放（上次已成功）");
        }
        if (rec != null && "FAILED".equals(rec.resultStatus)) {
            return Outcome.retryable("上次同 attempt 已失败: " + rec.resultJson);
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
}
