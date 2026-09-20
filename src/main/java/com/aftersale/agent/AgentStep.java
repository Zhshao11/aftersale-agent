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
}
