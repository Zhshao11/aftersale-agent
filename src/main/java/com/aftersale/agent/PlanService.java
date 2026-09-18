package com.aftersale.agent;

import com.aftersale.domain.OrderEntity;
import com.aftersale.domain.PlanEntity;
import com.aftersale.domain.PlanStepEntity;
import com.aftersale.enums.OrderStatus;
import com.aftersale.repo.OrderRepository;
import com.aftersale.repo.PlanRepository;
import com.aftersale.repo.PlanStepRepository;
import com.aftersale.tools.PolicyService;
import com.aftersale.tools.ToolErrorCode;
import com.aftersale.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * 规划路径核心：LLM 生成结构化 Plan → 确定性校验（白名单/归属/政策）
 * → 落库 PENDING_CONFIRM。确认前任何代码路径都无法执行其步骤。
 */
@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    private final PlanGenerator planGenerator;
    private final PlanRepository planRepository;
    private final PlanStepRepository planStepRepository;
    private final OrderRepository orderRepository;
    private final PolicyService policyService;
    private final AgentPropsProvider props;

    public PlanService(PlanGenerator planGenerator, PlanRepository planRepository,
                       PlanStepRepository planStepRepository, OrderRepository orderRepository,
                       PolicyService policyService, AgentPropsProvider props) {
        this.planGenerator = planGenerator;
        this.planRepository = planRepository;
        this.planStepRepository = planStepRepository;
        this.orderRepository = orderRepository;
        this.policyService = policyService;
        this.props = props;
    }

    public record CreatePlanResult(PlanEntity plan, String refusalMessage) {
        public boolean refused() { return refusalMessage != null; }
    }

    /**
     * 从用户诉求生成 Plan。
     * 拒绝场景（订单不存在/越权/政策拒绝/LLM 输出非法）返回 refusalMessage，不落 Plan。
     */
    @Transactional
    public CreatePlanResult createPlan(Long conversationId, String userId, String userMessage,
                                       List<Message> history) {
        // 先用确定性代码解析订单号（ORD + 数字），LLM 只补全计划语义
        String orderNo = extractOrderNo(userMessage);
        String orderContext;
        OrderEntity order = null;
        if (orderNo != null) {
            order = orderRepository.findByOrderNo(orderNo).orElse(null);
        }
        if (order == null) {
            orderContext = "{\"orders\":[]}（用户消息中未识别到有效订单号，请依据对话历史判断；如仍无法确定请让用户提供订单号）";
        } else if (!order.ownedBy(userId)) {
            // 越权：直接拒绝，不进入规划
            return new CreatePlanResult(null, "该订单不属于当前用户，无法为您操作。");
        } else {
            orderContext = "{\"orderNo\":\"" + order.getOrderNo() + "\",\"status\":\"" + order.getStatus()
                    + "\",\"itemName\":\"" + order.getItemName() + "\",\"amountUsd\":"
                    + (order.getAmountCents() / 100.0) + "}";
        }

        PlanGenerator.GeneratedPlan generated;
        try {
            generated = planGenerator.generate(userMessage, history, orderContext);
        } catch (PlanGenerator.PlanParseException e) {
            log.warn("Plan 解析失败: {}", e.getMessage());
            return new CreatePlanResult(null, "抱歉，计划生成失败，请重新描述您的诉求。");
        }

        // 归属/存在性二次校验（LLM 可能改写订单号）
        OrderEntity target = orderRepository.findByOrderNo(generated.orderNo()).orElse(null);
        if (target == null) {
            return new CreatePlanResult(null, "订单 " + generated.orderNo() + " 不存在，请确认订单号。");
        }
        if (!target.ownedBy(userId)) {
            return new CreatePlanResult(null, "该订单不属于当前用户，无法为您操作。");
        }

        // 政策前置评估（拒绝则不生成 Plan，直接给用户解释）
        String scope = scopeOf(generated.steps().get(0).tool());
        PolicyService.PolicyDecision decision = policyService.evaluate(scope, target, java.time.LocalDateTime.now());
        if (!decision.allowed()) {
            return new CreatePlanResult(null, "很抱歉，该操作不符合售后政策："
                    + decision.reason() + "。如需帮助请转人工客服。");
        }

        // 落库 Plan + Steps（PENDING_CONFIRM）
        PlanEntity plan = new PlanEntity();
        plan.conversationId = conversationId;
        plan.userId = userId;
        plan.orderNo = target.getOrderNo();
        plan.summary = generated.summary();
        // 退款与取消（已支付订单全额退回）均涉及资金回流，都参与 ≥$500 二次确认
        String firstTool = generated.steps().get(0).tool();
        plan.estimatedAmountCents = ("refundOrder".equals(firstTool) || "cancelOrder".equals(firstTool))
                ? target.getAmountCents() : null;
        plan.contextFingerprint = fingerprintOf(target, generated);
        plan.status = "PENDING_CONFIRM";
        plan.secondConfirmRequired = plan.estimatedAmountCents != null
                && plan.estimatedAmountCents >= props.confirmThresholdCents();
        plan = planRepository.saveAndFlush(plan);

        for (int i = 0; i < generated.steps().size(); i++) {
            PlanStepEntity step = new PlanStepEntity();
            step.planId = plan.id;
            step.seq = i;
            step.toolName = generated.steps().get(i).tool();
            step.argsJson = argsJson(target, userId, generated.steps().get(i));
            step.riskLevel = "WRITE";
            step.status = "PENDING";
            step.attempt = 0;
            planStepRepository.save(step);
        }
        return new CreatePlanResult(plan, null);
    }

    /** 上下文指纹 = SHA-256(orderNo|status|amountCents|steps 规范化串)。条件变化 → 不一致 → EXPIRED */
    public static String fingerprint(String orderNo, OrderStatus status, long amountCents, String stepsCanonical) {
        String canonical = orderNo + "|" + status + "|" + amountCents + "|" + stepsCanonical;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 生成时的指纹计算（公开给测试与 PlanManager 复用） */
    public static String fingerprintOf(OrderEntity order, PlanGenerator.GeneratedPlan plan) {
        return fingerprint(order.getOrderNo(), order.getStatus(), order.getAmountCents(),
                stepsCanonical(plan.steps().stream().map(PlanGenerator.GeneratedPlan.Step::tool).toList()));
    }

    /** 确认时重算指纹（TOCTOU 防护）：当前订单状态 + 已落库步骤 */
    public boolean fingerprintMatches(PlanEntity plan) {
        OrderEntity current = orderRepository.findByOrderNo(plan.orderNo).orElse(null);
        if (current == null) {
            return false;
        }
        List<PlanStepEntity> steps = planStepRepository.findByPlanIdOrderBySeq(plan.id);
        String now = fingerprint(current.getOrderNo(), current.getStatus(), current.getAmountCents(),
                stepsCanonical(steps.stream().map(s -> s.toolName).toList()));
        return now.equals(plan.contextFingerprint);
    }

    static String stepsCanonical(List<String> tools) {
        return "steps=" + String.join(",", tools);
    }

    static String scopeOf(String tool) {
        return switch (tool) {
            case "cancelOrder" -> "CANCEL";
            case "refundOrder" -> "REFUND";
            case "exchangeOrder" -> "EXCHANGE";
            default -> throw new IllegalArgumentException("unknown tool " + tool);
        };
    }

    static String argsJson(OrderEntity order, String userId, PlanGenerator.GeneratedPlan.Step step) {
        try {
            return PlanGenerator.MAPPER.writeValueAsString(java.util.Map.of(
                    "orderNo", order.getOrderNo(),
                    "userId", userId,
                    "reason", step.reason()));
        } catch (Exception e) {
            return "{}";
        }
    }

    static String extractOrderNo(String message) {
        if (message == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("ORD\\d{6,}").matcher(message);
        return m.find() ? m.group() : null;
    }
}
