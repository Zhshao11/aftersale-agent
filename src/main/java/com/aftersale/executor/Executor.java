package com.aftersale.executor;

import com.aftersale.domain.PlanEntity;
import com.aftersale.domain.PlanStepEntity;
import com.aftersale.repo.PlanRepository;
import com.aftersale.repo.PlanStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
 *
 * ── 事务边界（这是本类唯一需要小心的地方）──
 * 本类**刻意不加 @Transactional**。事务边界在 PlanStateWriter（计划状态迁移）与
 * StepRunner（单次步骤尝试）里，各自独立提交。原因是崩溃恢复：
 *   - 若整个计划共用一个事务，崩溃时已成功步骤的 execution_log、plan_steps.status
 *     和**幂等键**会一起回滚。幂等键消失意味着重试时没有任何东西能拦住重复执行，
 *     而在真实系统里写工具的副作用（真实退款/真实取消）已经发生在外部、本地回滚不了。
 *   - 若 EXECUTING 标记和大事务绑定，崩溃后计划会退回 CONFIRMED，
 *     resume() 扫不到它，续跑直接失效。
 * 把边界放在"一次尝试"上，才能同时保住这两条。
 */
@Service
public class Executor {

    private static final Logger log = LoggerFactory.getLogger(Executor.class);
    static final int MAX_ATTEMPTS = 3;

    private final PlanRepository planRepository;
    private final PlanStepRepository planStepRepository;
    private final StepRunner stepRunner;
    private final PlanStateWriter planStateWriter;

    public Executor(PlanRepository planRepository, PlanStepRepository planStepRepository,
                    StepRunner stepRunner, PlanStateWriter planStateWriter) {
        this.planRepository = planRepository;
        this.planStepRepository = planStepRepository;
        this.stepRunner = stepRunner;
        this.planStateWriter = planStateWriter;
    }

    public record ExecuteResult(String planStatus, int stepsTotal, int stepsDone, String message) {}

    /** 执行一个 CONFIRMED 的 Plan（幂等：重复调用会续跑而不是重复执行） */
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

        // 先把 EXECUTING 独立落盘：这一步决定了"崩溃之后还知不知道有事没做完"
        planStateWriter.markExecuting(planId);

        List<PlanStepEntity> steps = planStepRepository.findByPlanIdOrderBySeq(planId);
        for (PlanStepEntity step : steps) {
            if ("SUCCESS".equals(step.status)) {
                continue; // 断点续跑：跳过已完成步骤
            }
            StepRunner.Outcome outcome = runWithRetry(planId, step.id, step.seq, step.attempt);
            if (!"SUCCESS".equals(outcome.status())) {
                planStateWriter.mark(planId, "FAILED");
                return new ExecuteResult("FAILED", steps.size(), doneCount(planId),
                        "步骤 " + (step.seq + 1) + " 未完成: " + outcome.message());
            }
        }
        planStateWriter.mark(planId, "COMPLETED");
        return new ExecuteResult("COMPLETED", steps.size(), steps.size(), "全部步骤执行成功");
    }

    /** 应用重启/崩溃后恢复：扫描 EXECUTING 的计划继续执行，返回恢复数量 */
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

    /**
     * 重试循环（不含事务，事务在 StepRunner.attempt 内）：
     * FAILED_FINAL（业务拒绝）与 UNKNOWN（结果未知）立即返回；只有 FAILED（瞬态）才换新 attempt。
     */
    private StepRunner.Outcome runWithRetry(Long planId, Long stepId, int seq, int attemptFrom) {
        int attempt = Math.max(attemptFrom, 0);
        while (attempt < MAX_ATTEMPTS) {
            StepRunner.Outcome o = stepRunner.attempt(planId, stepId, attempt);
            if (!"FAILED".equals(o.status())) {
                return o;
            }
            attempt++;
            if (attempt < MAX_ATTEMPTS) {
                log.info("step {} attempt 失败，换新 attempt={} 重试", stepId, attempt);
            }
        }
        stepRunner.markExhausted(stepId);
        return new StepRunner.Outcome("FAILED", "重试 " + MAX_ATTEMPTS + " 次仍失败");
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
