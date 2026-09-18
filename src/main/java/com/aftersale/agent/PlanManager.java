package com.aftersale.agent;

import com.aftersale.domain.PlanEntity;
import com.aftersale.domain.PlanStepEntity;
import com.aftersale.repo.PlanRepository;
import com.aftersale.repo.PlanStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Plan 状态机 + 确认门（ConfirmGate）。
 *
 * 状态流转：
 *   PENDING_CONFIRM --confirm--> (金额≥阈值? AWAITING_SECOND_CONFIRM : CONFIRMED)
 *   AWAITING_SECOND_CONFIRM --confirm--> CONFIRMED   （二次确认）
 *   PENDING_CONFIRM / AWAITING_SECOND_CONFIRM --reject--> CLOSED
 *   任意未执行状态 --指纹不一致--> EXPIRED（强制重新规划）
 *   CONFIRMED --executor--> EXECUTING --> COMPLETED / FAILED
 *
 * 消融开关 disable-confirm-gate=true 时跳过所有确认直接 CONFIRMED（仅供评测）。
 */
@Service
public class PlanManager {

    private static final Logger log = LoggerFactory.getLogger(PlanManager.class);

    private final PlanRepository planRepository;
    private final PlanStepRepository planStepRepository;
    private final PlanService planService;
    private final AgentPropsProvider props;

    public PlanManager(PlanRepository planRepository, PlanStepRepository planStepRepository,
                       PlanService planService, AgentPropsProvider props) {
        this.planRepository = planRepository;
        this.planStepRepository = planStepRepository;
        this.planService = planService;
        this.props = props;
    }

    public record ConfirmResult(String status, String message, boolean confirmed) {}

    /** 用户确认。返回新状态；指纹不一致 → EXPIRED；二次确认未完成 → AWAITING_SECOND_CONFIRM */
    @Transactional
    public ConfirmResult confirm(Long planId, String userId) {
        Optional<PlanEntity> opt = planRepository.findById(planId);
        if (opt.isEmpty()) {
            return new ConfirmResult("NOT_FOUND", "计划不存在", false);
        }
        PlanEntity plan = opt.get();
        if (!plan.userId.equals(userId)) {
            return new ConfirmResult("FORBIDDEN", "只能确认本人的计划", false);
        }

        switch (plan.status) {
            case "PENDING_CONFIRM", "AWAITING_SECOND_CONFIRM" -> { /* 继续 */ }
            case "CONFIRMED", "EXECUTING", "COMPLETED" -> {
                return new ConfirmResult(plan.status, "计划已确认过", true);
            }
            case "CLOSED" -> {
                plan.status = "PENDING_CONFIRM"; // 允许重新激活（用户改主意）
                planRepository.save(plan);
            }
            case "EXPIRED" -> {
                return new ConfirmResult("EXPIRED", "订单状态已发生变化，原计划失效，请重新发起诉求", false);
            }
            default -> {
                return new ConfirmResult(plan.status, "当前状态不可确认", false);
            }
        }

        // 指纹校验：条件变化 → 强制 EXPIRED
        if (!planService.fingerprintMatches(plan)) {
            plan.status = "EXPIRED";
            planRepository.save(plan);
            log.info("Plan {} 因上下文变化被置为 EXPIRED", planId);
            return new ConfirmResult("EXPIRED", "订单状态已发生变化，原计划失效，请重新发起诉求", false);
        }

        // 消融：no-confirm 直接放行
        if (!props.confirmGateEnabled()) {
            plan.status = "CONFIRMED";
            plan.confirmedAt = java.time.LocalDateTime.now();
            planRepository.save(plan);
            return new ConfirmResult("CONFIRMED", "（消融模式：确认门已关闭）计划已确认", true);
        }

        // 金额 ≥ 阈值 → 首次确认进入二次确认
        if ("PENDING_CONFIRM".equals(plan.status) && plan.secondConfirmRequired) {
            plan.status = "AWAITING_SECOND_CONFIRM";
            planRepository.save(plan);
            return new ConfirmResult("AWAITING_SECOND_CONFIRM",
                    "该操作金额为 $" + (plan.estimatedAmountCents / 100.0)
                            + "（≥$" + (props.confirmThresholdCents() / 100.0)
                            + "），请再次确认执行。", false);
        }

        plan.status = "CONFIRMED";
        plan.confirmedAt = java.time.LocalDateTime.now();
        planRepository.save(plan);
        return new ConfirmResult("CONFIRMED", "计划已确认，开始执行", true);
    }

    @Transactional
    public ConfirmResult reject(Long planId, String userId) {
        Optional<PlanEntity> opt = planRepository.findById(planId);
        if (opt.isEmpty()) {
            return new ConfirmResult("NOT_FOUND", "计划不存在", false);
        }
        PlanEntity plan = opt.get();
        if (!plan.userId.equals(userId)) {
            return new ConfirmResult("FORBIDDEN", "只能操作本人的计划", false);
        }
        if (List.of("COMPLETED", "FAILED", "EXECUTING").contains(plan.status)) {
            return new ConfirmResult(plan.status, "计划已在执行或已结束，无法拒绝", false);
        }
        plan.status = "CLOSED";
        planRepository.save(plan);
        return new ConfirmResult("CLOSED", "已取消该计划", false);
    }

    public record PlanView(Long id, String orderNo, String summary, Long estimatedAmountCents,
                           String status, boolean secondConfirmRequired,
                           List<StepView> steps) {
        public record StepView(int seq, String tool, String riskLevel, String status) {}
    }

    @Transactional(readOnly = true)
    public Optional<PlanView> view(Long planId, String userId) {
        return planRepository.findById(planId)
                .filter(p -> p.userId.equals(userId))
                .map(p -> {
                    List<PlanStepEntity> steps = planStepRepository.findByPlanIdOrderBySeq(p.id);
                    return new PlanView(p.id, p.orderNo, p.summary, p.estimatedAmountCents,
                            p.status, p.secondConfirmRequired,
                            steps.stream().map(s -> new PlanView.StepView(
                                    s.seq, s.toolName, s.riskLevel, s.status)).toList());
                });
    }
}
