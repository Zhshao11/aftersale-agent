package com.aftersale.agent;

import com.aftersale.domain.OrderEntity;
import com.aftersale.domain.PlanEntity;
import com.aftersale.domain.PlanStepEntity;
import com.aftersale.enums.OrderStatus;
import com.aftersale.repo.OrderRepository;
import com.aftersale.repo.PlanRepository;
import com.aftersale.repo.PlanStepRepository;
import com.aftersale.tools.PolicyService;
import com.aftersale.tools.ToolResult;
import com.aftersale.tools.read.ListMyOrdersTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规划路径核心：订单定位 → LLM 生成结构化 Plan → 确定性校验（白名单/归属/政策）
 * → 落库 PENDING_CONFIRM。确认前任何代码路径都无法执行其步骤。
 *
 * 订单定位分两段（这是本类唯一需要解释"为什么这么绕"的地方）：
 *  - 用户报了订单号 → 正则快路径，一次查库搞定；
 *  - 用户没报订单号（"我想把耳机退掉"）→ 复用只读侧的 {@link ListMyOrdersTool} 取候选，
 *    由 LLM 依据商品名/时间/状态选单。
 * 之所以不是"直接让 LLM 从对话历史里猜一个订单号"，是因为那样得到的正确结果
 * 无法与"猜对了"区分——历史上写路径确实能跑通，靠的是上一轮对话里恰好出现过订单号，
 * 属于上下文泄露的运气，不是能力（见 TECH_JOURNAL 第 9 条）。
 */
@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    /**
     * 商品描述定位订单时的候选条数。与 {@link ListMyOrdersTool} 的 MAX_LIMIT 一致：
     * 这里要的是"够召回"，不是"把用户历史全导出"——候选集本身也要有界。
     */
    private static final int LOCATE_LIMIT = 20;

    /** 入参/详情写进轨迹前的截断长度：观测是给人看的，不是拿来存全文的 */
    private static final int TRACE_MAX_CHARS = 500;

    private final PlanGenerator planGenerator;
    private final PlanRepository planRepository;
    private final PlanStepRepository planStepRepository;
    private final OrderRepository orderRepository;
    private final PolicyService policyService;
    private final AgentPropsProvider props;
    private final ListMyOrdersTool listMyOrdersTool;
    private final TraceSink traceSink;

    public PlanService(PlanGenerator planGenerator, PlanRepository planRepository,
                       PlanStepRepository planStepRepository, OrderRepository orderRepository,
                       PolicyService policyService, AgentPropsProvider props,
                       ListMyOrdersTool listMyOrdersTool, TraceSink traceSink) {
        this.planGenerator = planGenerator;
        this.planRepository = planRepository;
        this.planStepRepository = planStepRepository;
        this.orderRepository = orderRepository;
        this.policyService = policyService;
        this.props = props;
        this.listMyOrdersTool = listMyOrdersTool;
        this.traceSink = traceSink;
    }

    /**
     * 计划生成结果。
     *
     * refusalMessage 与 refusalCode 并存而不是合并：前者是给用户看的话术（可以随时改文风），
     * 后者是给机器看的分类（评测统计、告警聚合、排障筛选都依赖它稳定）。
     * 之前只有前者，于是"订单不存在 / 越权 / 政策拒绝 / 解析失败"四种完全不同的失败
     * 在用户侧长得一模一样，无法归因。
     */
    public record CreatePlanResult(PlanEntity plan, String refusalMessage, RefusalCode refusalCode) {
        public boolean refused() { return refusalMessage != null; }

        static CreatePlanResult ok(PlanEntity plan) {
            return new CreatePlanResult(plan, null, null);
        }

        static CreatePlanResult refused(RefusalCode code, String message) {
            return new CreatePlanResult(null, message, code);
        }
    }

    /**
     * 兼容签名：调用方不关心 traceId 时由本方法自行生成。
     * 保留它同时是为了不让既有测试/调用方为了一个新参数而改签名。
     */
    @Transactional
    public CreatePlanResult createPlan(Long conversationId, String userId, String userMessage,
                                       List<Message> history) {
        return createPlan(conversationId, userId, userMessage, history, null);
    }

    /**
     * 从用户诉求生成 Plan。
     * 拒绝场景（订单定位不到/订单不存在/越权/政策拒绝/LLM 输出非法）返回 refusalMessage + refusalCode，不落 Plan。
     *
     * @param externalTraceId 由编排层在**请求入口**生成的轨迹 id。编排层先有 id，
     *                        前端才能在请求还在跑的时候按 id 查进度；传 null 则本方法自行生成。
     */
    @Transactional
    public CreatePlanResult createPlan(Long conversationId, String userId, String userMessage,
                                       List<Message> history, String externalTraceId) {
        String traceId = (externalTraceId == null || externalTraceId.isBlank())
                ? ReadLoop.newTraceId() : externalTraceId;

        // ── 第一段：订单定位（确定性代码 + 只读工具，LLM 不参与）─────────────
        // stepIndex 从 0 开始，慢路径会先用掉 0 给 TOOL 节点
        String reportedNo = extractOrderNo(userMessage);
        String orderContext;
        int seq = 0;

        if (reportedNo != null) {
            // 快路径：用户直接报了订单号
            OrderEntity order = orderRepository.findByOrderNo(reportedNo).orElse(null);
            if (order == null) {
                // 报了号但查无此单：明确告知，**不擅自替换成用户别的订单**——
                // 静默换单比报错糟糕得多（用户以为退的是 A，实际退的是 B）
                return refuse(traceId, conversationId, userId, RefusalCode.ORDER_NOT_FOUND,
                        "订单 " + reportedNo + " 不存在，请确认订单号。", seq);
            }
            if (!order.ownedBy(userId)) {
                // 越权：直接拒绝，不进入规划
                return refuse(traceId, conversationId, userId, RefusalCode.FORBIDDEN,
                        "该订单不属于当前用户，无法为您操作。", seq);
            }
            orderContext = singleOrderContext(order);
        } else {
            // 慢路径：用户没报订单号（"我想把耳机退掉"）。
            // 复用只读路径**同一个** listMyOrders，而不是在这里再写一份"取最近 N 单"的逻辑——
            // 写第二份实现正是第 9 条记录的能力不对称本身。
            // keyword 传 null：中文商品名在 Java 侧做包含匹配需要分词，"耳机"既不等于
            // "降噪头戴耳机"也不等于"无线蓝牙耳机"，硬提取关键词只会引入假阴性；
            // 把候选集交给模型做语义判断，比在 Java 里做字符串匹配更准。
            long locateStart = System.currentTimeMillis();
            ToolResult found = listMyOrdersTool.listMyOrders(null, LOCATE_LIMIT, userId);
            List<Map<String, Object>> candidates = ordersOf(found);
            traceTool(traceId, conversationId, userId, seq, found, candidates.size(),
                    System.currentTimeMillis() - locateStart);
            seq++;
            if (candidates.isEmpty()) {
                return refuse(traceId, conversationId, userId, RefusalCode.ORDER_NOT_LOCATED,
                        "没有查到您名下的订单，请确认账号，或直接告诉我订单号。", seq);
            }
            orderContext = describedOrdersContext(candidates);
        }

        // ── 第二段：LLM 把诉求翻译成结构化 Plan（只翻译，不执行）──────────────
        // 先落一行"进行中"：计划生成是 10s 级的长尾，只写"返回后"那一行的话，
        // 前端在整个生成期间会停在 TOOL 那一行不动（而 TOOL 只花了 10ms）。
        tracePlan(traceId, conversationId, userId, seq, AgentStep.STATUS_RUNNING, null,
                "正在把诉求翻译成结构化计划", 0L);
        long llmStart = System.currentTimeMillis();
        PlanGenerator.GeneratedPlan generated;
        try {
            generated = planGenerator.generate(userMessage, history, orderContext);
        } catch (PlanGenerator.PlanParseException e) {
            tracePlan(traceId, conversationId, userId, seq, "FAILED",
                    RefusalCode.PLAN_PARSE_FAILED.name(), e.getMessage(),
                    System.currentTimeMillis() - llmStart);
            log.warn("Plan 解析失败 traceId={} err={}", traceId, e.getMessage());
            return refuse(traceId, conversationId, userId, RefusalCode.PLAN_PARSE_FAILED,
                    "抱歉，计划生成失败，请重新描述您的诉求。", seq + 1);
        }
        tracePlan(traceId, conversationId, userId, seq, "SUCCESS", null,
                "orderNo=" + generated.orderNo() + ",steps=" + generated.steps().size(),
                System.currentTimeMillis() - llmStart);
        seq++;

        // 归属/存在性二次校验（LLM 可能改写订单号）
        OrderEntity target = orderRepository.findByOrderNo(generated.orderNo()).orElse(null);
        if (target == null) {
            return refuse(traceId, conversationId, userId, RefusalCode.ORDER_NOT_FOUND,
                    "订单 " + generated.orderNo() + " 不存在，请确认订单号。", seq);
        }
        if (!target.ownedBy(userId)) {
            return refuse(traceId, conversationId, userId, RefusalCode.FORBIDDEN,
                    "该订单不属于当前用户，无法为您操作。", seq);
        }

        // 政策前置评估（拒绝则不生成 Plan，直接给用户解释）
        String scope = scopeOf(generated.steps().get(0).tool());
        PolicyService.PolicyDecision decision = policyService.evaluate(scope, target, LocalDateTime.now());
        if (!decision.allowed()) {
            return refuse(traceId, conversationId, userId, RefusalCode.POLICY_DENIED,
                    "很抱歉，该操作不符合售后政策：" + decision.reason() + "。如需帮助请转人工客服。", seq);
        }

        // ── 落库 Plan + Steps（PENDING_CONFIRM）─────────────────────────────
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
        return CreatePlanResult.ok(plan);
    }

    // ────────────────────────── 订单定位辅助 ──────────────────────────

    /** 快路径的订单上下文：单张订单的扁平视图 */
    private String singleOrderContext(OrderEntity order) {
        return "{\"orderNo\":\"" + order.getOrderNo() + "\",\"status\":\"" + order.getStatus()
                + "\",\"itemName\":\"" + order.getItemName() + "\",\"amountUsd\":"
                + (order.getAmountCents() / 100.0) + "}";
    }

    /**
     * 慢路径的订单上下文。
     *
     * 刻意把 listMyOrders 返回的字段原样透传，不另起一套 schema——
     * 这样写路径的模型看到的订单视图与只读路径**完全同一份**。
     * 否则同一件事（"订单长什么样"）在两条路径上有两种表述，
     * 出现行为差异时就分不清是机制不同还是表述不同导致的。
     */
    private String describedOrdersContext(List<Map<String, Object>> candidates) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orders", candidates);
        payload.put("note", "用户消息里没有出现订单号，以上是该用户的候选订单；"
                + "请依据商品名、下单时间与状态判断用户指的是哪一单，不要凭空构造订单号。");
        try {
            return PlanGenerator.MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"orders\":[]}";
        }
    }

    /** 从 listMyOrders 的 ToolResult 里取出 orders 数组；任何形状不符都退化为空列表 */
    private List<Map<String, Object>> ordersOf(ToolResult result) {
        if (result == null || !result.ok() || !(result.data() instanceof Map<?, ?> data)) {
            return List.of();
        }
        if (!(data.get("orders") instanceof List<?> rows)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) m;
                out.add(cast);
            }
        }
        return out;
    }

    // ────────────────────────── 轨迹埋点 ──────────────────────────
    // 只读路径有轨迹、写路径一行都没有，等于"写请求为什么失败"在库里查不到。
    // 越权与解析失败恰恰是最需要事后追溯的两类，所以写路径也必须留痕。

    private void traceTool(String traceId, Long conversationId, String userId, int seq,
                           ToolResult result, int rows, long latencyMs) {
        boolean ok = result != null && result.ok();
        // 成功时 message 为 null（ToolResult.success 不带 message），拼 detail 时要跳过，
        // 否则轨迹里会出现 "candidates=10; null" 这种一看就没擦干净的输出
        StringBuilder detail = new StringBuilder("candidates=").append(rows);
        if (result != null && result.message() != null && !result.message().isBlank()) {
            detail.append("; ").append(result.message());
        }
        traceSink.record(new AgentStep(traceId, conversationId, userId, AgentStep.NODE_TOOL, seq,
                "listMyOrders", "keyword=null,limit=" + LOCATE_LIMIT,
                ok ? "SUCCESS" : "FAILED",
                result == null || result.code() == null ? null : result.code().name(),
                detail.toString(), null, null, null, latencyMs));
    }

    private void tracePlan(String traceId, Long conversationId, String userId, int seq,
                           String status, String errorCode, String detail, long latencyMs) {
        traceSink.record(new AgentStep(traceId, conversationId, userId, AgentStep.NODE_PLAN, seq,
                "planGenerator", null, status, errorCode, truncate(detail),
                props.model(), null, null, latencyMs));
    }

    private CreatePlanResult refuse(String traceId, Long conversationId, String userId,
                                    RefusalCode code, String message, int seq) {
        // 确定性拒绝没有模型调用可记，但必须留痕——否则"谁在何时试图越权"库里查不到
        traceSink.record(new AgentStep(traceId, conversationId, userId, AgentStep.NODE_REFUSAL, seq,
                null, null, "REFUSED", code.name(), truncate(message), null, null, null, null));
        log.info("计划被拒绝 traceId={} code={} userId={}", traceId, code, userId);
        return CreatePlanResult.refused(code, message);
    }

    private static String truncate(String s) {
        if (s == null || s.length() <= TRACE_MAX_CHARS) return s;
        return s.substring(0, TRACE_MAX_CHARS) + "…";
    }

    // ────────────────────────── 指纹 / 静态工具 ──────────────────────────

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
            return PlanGenerator.MAPPER.writeValueAsString(Map.of(
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
