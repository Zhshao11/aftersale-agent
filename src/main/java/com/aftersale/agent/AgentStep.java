package com.aftersale.agent;

/**
 * 只读循环的单步轨迹记录，落 agent_trace 表。
 *
 * 这是"轨迹可回放"的载体：按 traceId 取回后按 stepIndex 排序，即可还原整次只读推理过程。
 * 只记录工具调用与可观察的决策信息（工具名、参数摘要、状态、错误码、耗时、token 用量），
 * 不保存模型内部思维链——思维链既不稳定也不可审计，记录它只是给自己增加维护负担。
 */
public record AgentStep(
        String traceId,
        Long conversationId,
        String userId,
        String node,
        int stepIndex,
        String toolName,
        String argsDigest,
        String status,
        String errorCode,
        String detail,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Long latencyMs
) {
    /** 一次模型调用 */
    public static final String NODE_MODEL = "MODEL";
    /** 一次工具调用 */
    public static final String NODE_TOOL = "TOOL";
    /** 终止记录：为什么停的、停在哪一步 */
    public static final String NODE_TERMINAL = "TERMINAL";

    /**
     * 写路径的意图路由调用（V0 基线模式外，写请求的第一段 LLM 往返）。
     * 只读路径的轨迹原先只覆盖只读循环，导致"写请求为什么失败"在库里查不到——
     * 越权与解析失败恰恰是最需要事后追溯的两类，所以写路径也要埋点。
     */
    public static final String NODE_INTENT = "INTENT";
    /** 写路径的计划生成调用 */
    public static final String NODE_PLAN = "PLAN";
    /**
     * 写路径的确定性拒绝记录（未进入 LLM 就被代码拒绝，例如越权）。
     * 这类拒绝没有模型调用可记，但**必须留痕**——否则"谁在何时试图越权"库里查不到。
     */
    public static final String NODE_REFUSAL = "REFUSAL";

    /**
     * 请求已受理。**在没有任何模型调用之前**就落一行。
     *
     * 为什么需要它：意图路由本身就是一次 LLM 调用，实测耗时 3.5~13.5 秒。
     * 在这段时间里，前端按 traceId 轮询只能查到空列表——"实时更新思考过程"
     * 于是就变成了"前 13 秒页面一片空白，用户以为没反应"。
     * 这一行的作用只有一个：**让"请求已经到达后端"在毫秒级可见**，
     * 把"什么都没发生"和"正在等模型"区分开。
     */
    public static final String NODE_RECEIVED = "RECEIVED";

    /**
     * 「已发起、尚未返回」的进行中标记。
     *
     * 为什么用"追加一行"而不是"完成后回填同一行"：轨迹表的设计是只追加、不可变，
     * 这样它才是一份可审计的事件流。回填会把事件流变成可变状态表，丢掉"当时确实发起过"这个事实。
     *
     * 代价是同一 stepIndex 会有 RUNNING 与终态两行，需要消费方去重：
     * 前端只把**最后一行**的 RUNNING 显示为"进行中"，被后续行覆盖的那些直接忽略。
     * 见 static/index.html 的 visibleSteps()。
     */
    public static final String STATUS_RUNNING = "RUNNING";
}
