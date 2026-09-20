package com.aftersale.agent;

import com.aftersale.domain.ConversationEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排层入口：意图路由 → 查询路径 / 规划路径。
 * 安全不变式：无论路由结果如何，LLM 能接触到的工具集永远是 ReadToolBundle；
 * 写操作只能通过规划路径产生 Plan，经用户确认后由 Executor 执行（D3/D4）。
 */
@Service
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    /** 历史窗口：最近 6 轮（12 条消息） */
    private static final int HISTORY_TURNS = 6;

    private final IntentRouter intentRouter;
    private final QueryAgent queryAgent;
    private final ConversationService conversationService;
    private final PlanService planService;
    private final BaselineToolBundle baselineToolBundle;
    private final LlmPort llmPort;
    private final AgentPropsProvider props;

    public AgentOrchestrator(IntentRouter intentRouter, QueryAgent queryAgent,
                             ConversationService conversationService, PlanService planService,
                             BaselineToolBundle baselineToolBundle, LlmPort llmPort,
                             AgentPropsProvider props) {
        this.intentRouter = intentRouter;
        this.queryAgent = queryAgent;
        this.conversationService = conversationService;
        this.planService = planService;
        this.baselineToolBundle = baselineToolBundle;
        this.llmPort = llmPort;
        this.props = props;
    }

    private static final String REACT_BASELINE_SYSTEM = """
            你是电商售后客服助手。当前用户身份由系统注入（userId），无需询问身份。
            你可以查询订单、物流、政策，也可以为用户发起取消/退款/换货操作。
            写操作工具首次调用会返回"待用户确认"，此时请告知用户已生成操作请求，
            用户确认后系统会自动执行，你不要重复调用同一写工具。
            金额使用美元，回答保持简洁中文。
            """;

    /**
     * 单次请求的执行元信息。
     * 把终止原因和 token 用量暴露到响应里，是为了让"这次为什么没答上来"当场可见——
     * 否则边界触发和正常回答在用户那里长得一样，等于白设了边界。
     * 基线模式下 termination 固定为 FRAMEWORK_LOOP：那一轮循环跑在框架里，我们量不到。
     */
    public record TraceMeta(String traceId, String termination, int steps,
                            int promptTokens, int completionTokens, long elapsedMs) {}

    public record ChatResponse(Long conversationId, String intent, String reply,
                               Map<String, Object> planCard, TraceMeta trace) {}

    public ChatResponse chat(Long conversationId, String userId, String message) {
        ConversationEntity conv = conversationService.getOrCreate(conversationId, userId);
        conversationService.append(conv.id, "USER", message);
        List<Message> history = conversationService.history(conv.id, HISTORY_TURNS);

        // V0 基线模式：单环 ReAct 直连（读写工具全挂载），无意图路由、无 Plan。
        // 刻意保留框架内部循环——基线要消融的是 Plan-and-Execute，不是循环边界，
        // 两个变量一起关掉的话，跑出来的差异就归因不清了。
        if (props.reactBaselineMode()) {
            long baselineStart = System.currentTimeMillis();
            String reply = llmPort.completeWithTools(REACT_BASELINE_SYSTEM, history, message,
                    baselineToolBundle,
                    Map.of("userId", userId, "conversationId", conv.id.toString()));
            conversationService.append(conv.id, "ASSISTANT", reply);
            return new ChatResponse(conv.id, "REACT_BASELINE", reply, null,
                    new TraceMeta(null, "FRAMEWORK_LOOP", 0, 0, 0,
                            System.currentTimeMillis() - baselineStart));
        }

        IntentRouter.IntentResult ir = intentRouter.route(message, history);
        log.info("意图路由: {} -> {} (request={})", message, ir.intent(), ir.normalizedRequest());

        String reply;
        Map<String, Object> planCard = null;
        TraceMeta trace = null;

        if (ir.intent() == IntentRouter.Intent.WRITE) {
            PlanService.CreatePlanResult pr = planService.createPlan(conv.id, userId, message, history);
            if (pr.refused()) {
                reply = pr.refusalMessage();
            } else {
                var plan = pr.plan();
                Map<String, Object> card = new LinkedHashMap<>();
                card.put("planId", plan.id);
                card.put("orderNo", plan.orderNo);
                card.put("summary", plan.summary);
                card.put("estimatedAmount", plan.estimatedAmountCents == null ? null
                        : "$" + plan.estimatedAmountCents / 100.0);
                card.put("secondConfirmRequired", plan.secondConfirmRequired);
                card.put("status", plan.status);
                planCard = card;
                reply = "已生成执行计划：" + plan.summary
                        + (plan.secondConfirmRequired
                        ? "（金额 ≥ $" + props.confirmThresholdCents() / 100 + "，确认后需二次确认）" : "")
                        + "。请确认后执行，如需修改请直接告诉我。";
            }
        } else {
            ReadLoop.ReadOutcome outcome = queryAgent.answer(userId, conv.id, message, history);
            reply = outcome.reply();
            trace = new TraceMeta(outcome.traceId(), outcome.termination(), outcome.steps(),
                    outcome.promptTokens(), outcome.completionTokens(), outcome.elapsedMs());
            if (outcome.degraded()) {
                log.warn("只读路径降级 traceId={} termination={} steps={} unsupported={}",
                        outcome.traceId(), outcome.termination(), outcome.steps(),
                        outcome.unsupportedOrderNos());
            }
        }

        conversationService.append(conv.id, "ASSISTANT", reply);
        return new ChatResponse(conv.id, ir.intent().name(), reply, planCard, trace);
    }
}
