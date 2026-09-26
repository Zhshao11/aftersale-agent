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
    private final TraceSink traceSink;

    public AgentOrchestrator(IntentRouter intentRouter, QueryAgent queryAgent,
                             ConversationService conversationService, PlanService planService,
                             BaselineToolBundle baselineToolBundle, LlmPort llmPort,
                             AgentPropsProvider props, TraceSink traceSink) {
        this.intentRouter = intentRouter;
        this.queryAgent = queryAgent;
        this.conversationService = conversationService;
        this.planService = planService;
        this.baselineToolBundle = baselineToolBundle;
        this.llmPort = llmPort;
        this.props = props;
        this.traceSink = traceSink;
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
     *
     * 写路径的 termination 是 PLAN_CREATED / REFUSED_&lt;RefusalCode&gt;，steps 恒为 0——
     * 写路径的权威步数以 /api/trace/{traceId} 为准，这里只负责把 traceId 交出去。
     */
    public record TraceMeta(String traceId, String termination, int steps,
                            int promptTokens, int completionTokens, long elapsedMs) {}

    /**
     * refusalCode 与 reply 并存：reply 是给人看的话术，refusalCode 是给机器看的分类。
     * 此前两者都没有——"订单不存在/越权/政策拒绝/解析失败"四种失败在用户侧长得一模一样，
     * 前端也没法据此做差异化引导（比如"越权"不该提示"请提供订单号"）。
     */
    public record ChatResponse(Long conversationId, String intent, String reply,
                               Map<String, Object> planCard, TraceMeta trace, String refusalCode) {}

    public ChatResponse chat(Long conversationId, String userId, String message) {
        return chat(conversationId, userId, message, null);
    }

    /**
     * @param clientTraceId 客户端指定的 traceId（可为 null，则服务端生成）。
     *                      客户端指定的唯一理由是让它在请求进行中就能按 id 查进度——
     *                      见 ChatController.ChatRequest.traceId 的说明。
     */
    public ChatResponse chat(Long conversationId, String userId, String message, String clientTraceId) {
        // 整次请求的起点。写路径原先是从"进入 PLANNING 分支"开始计时，
        // 于是 elapsedMs 漏掉了意图路由那 3.5~11s，前端显示 5.6s 而实际用了 9.1s——
        // **对外的耗时数字必须覆盖整次请求**，否则它会系统性地比真实值小。
        long reqStart = System.currentTimeMillis();
        ConversationEntity conv = conversationService.getOrCreate(conversationId, userId);
        conversationService.append(conv.id, "USER", message);
        List<Message> history = conversationService.history(conv.id, HISTORY_TURNS);

        // 请求**入口**就定 traceId。原因：轨迹需要在请求还在跑的时候就能按 id 查
        // （前端分阶段轮询的前提）；而 ReadLoop 内部生成的 traceId 是"跑完才知道"，
        // 那时请求已经返回，前端拿不到跑的过程。两条路径共用同一个 id，
        // 一次请求 = 一条轨迹，不因走了哪条分支而分裂。
        String traceId = (clientTraceId == null || clientTraceId.isBlank())
                ? ReadLoop.newTraceId() : clientTraceId.trim();

        // ── 第一行轨迹："请求已受理"────────────────────────────────────────
        // 必须写在这里，而不是等第一次模型调用回来再写。原因：意图路由本身是一次
        // LLM 往返（实测 3.5~13.5s），如果等它返回才写第一行，前端在前 13 秒里
        // 按 traceId 只能查到空列表——"实时更新思考过程"就变成了"页面长时间毫无反应"。
        // 这一行在毫秒级落库，让"请求到了"这件事立刻可见。
        // 延迟传 null 而不是 0：这一行不代表任何"耗时"，它只是"到了"。
        // 传 0 会让前端渲染出一个毫无意义的 "0ms"。
        traceSink.record(new AgentStep(traceId, conv.id, userId, AgentStep.NODE_RECEIVED, 0,
                null, null, "SUCCESS", null, brief(message), null, null, null, null));

        // V0 基线模式：单环 ReAct 直连（读写工具全挂载），无意图路由、无 Plan。
        // 刻意保留框架内部循环——基线要消融的是 Plan-and-Execute，不是循环边界，
        // 两个变量一起关掉的话，跑出来的差异就归因不清了。
        if (props.reactBaselineMode()) {
            String reply = llmPort.completeWithTools(REACT_BASELINE_SYSTEM, history, message,
                    baselineToolBundle,
                    Map.of("userId", userId, "conversationId", conv.id.toString()));
            conversationService.append(conv.id, "ASSISTANT", reply);
            return new ChatResponse(conv.id, "REACT_BASELINE", reply, null,
                    new TraceMeta(traceId, "FRAMEWORK_LOOP", 0, 0, 0,
                            System.currentTimeMillis() - reqStart), null);
        }

        // 「已发出、尚未返回」标记——和 MODEL / PLAN 同样的处理，这里曾经被漏掉。
        // 漏掉的代价实测过：某次意图路由这一次模型调用花了 **32 秒**，而前端在这 32 秒里
        // 只有 RECEIVED 一行（"已收到请求"），行列表一动不动。顶部的秒表还在走，
        // 但"卡在哪一步"是看不到的——用户能看出"它没死"，看不出"它在干什么"。
        // 意图路由是**每一次**请求（读写都算）的必经环节，也是除计划生成外最长的一段，
        // 所以它不是可选的锦上添花：不加这一行，等于所有请求的前 6~32 秒都只有一行。
        traceSink.record(new AgentStep(traceId, conv.id, userId, AgentStep.NODE_INTENT, 0,
                "intentRouter", null, AgentStep.STATUS_RUNNING, null,
                "正在判断这是查询还是写操作，并对诉求做归一化", null, null, null, null));

        long intentStart = System.currentTimeMillis();
        IntentRouter.IntentResult ir = intentRouter.route(message, history);
        long intentMs = System.currentTimeMillis() - intentStart;
        log.info("意图路由: {} -> {} (request={})", message, ir.intent(), ir.normalizedRequest());

        // 写路径此前一行轨迹都不写（grep traceSink = 0），于是"这次写请求为什么被拒"在库里查不到。
        // INTENT 节点记的是路由决策本身：用户原话 → 归一化诉求 → 判定意图。
        // 它是写路径唯一一次"模型看了用户说什么"的调用，也是排查"为什么没被当写操作"的入口。
        traceSink.record(new AgentStep(traceId, conv.id, userId, AgentStep.NODE_INTENT, 0,
                "intentRouter", null, "SUCCESS", null,
                ir.intent().name() + " <- " + ir.normalizedRequest(),
                props.model(), null, null, intentMs));

        String reply;
        Map<String, Object> planCard = null;
        TraceMeta trace = null;
        String refusalCode = null;

        if (ir.intent() == IntentRouter.Intent.WRITE) {
            PlanService.CreatePlanResult pr = planService.createPlan(conv.id, userId, message, history, traceId);
            if (pr.refused()) {
                reply = pr.refusalMessage();
                refusalCode = pr.refusalCode() == null ? null : pr.refusalCode().name();
                trace = new TraceMeta(traceId, "REFUSED_" + refusalCode, 0, 0, 0,
                        System.currentTimeMillis() - reqStart);
                log.info("写路径拒绝 traceId={} code={} reply={}", traceId, refusalCode, reply);
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
                trace = new TraceMeta(traceId, "PLAN_CREATED", 0, 0, 0,
                        System.currentTimeMillis() - reqStart);
            }
        } else {
            ReadLoop.ReadOutcome outcome = queryAgent.answer(userId, conv.id, message, history, traceId);
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
        return new ChatResponse(conv.id, ir.intent().name(), reply, planCard, trace, refusalCode);
    }

    /**
     * 轨迹里放用户原话的摘要。
     *
     * 为什么要截断：轨迹行会被前端逐条渲染，也会被人翻库查看；
     * 把 2000 字的上限直接写进去，等于让一行轨迹挤掉整个面板。
     * 截断长度比 PlanService 里给模型看的候选上下文更短——那一份是喂模型的，这一份只是给人看的索引。
     */
    private static String brief(String message) {
        if (message == null) {
            return null;
        }
        String s = message.strip();
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }
}
