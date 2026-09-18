package com.aftersale.tools;

/**
 * 工具统一错误码（工具契约的一部分，README 与 LLM 工具描述共用）。
 * 语义分组：
 *  - 参数/数据类：INVALID_ARGS, ORDER_NOT_FOUND, FORBIDDEN
 *  - 政策类：POLICY_DENIED（含拒绝原因）
 *  - 故障类：TIMEOUT_KNOWN_FAIL（明确失败，可安全重试）/ TIMEOUT_UNKNOWN（结果未知，禁止盲目重试）
 *  - 幂等类：IDEMPOTENT_REPLAY（同键重复执行被拦截，回放上次结果）
 *  - 确认类：CONFIRMATION_REQUIRED（V0 基线工具级确认对话框）
 */
public enum ToolErrorCode {
    INVALID_ARGS,
    ORDER_NOT_FOUND,
    FORBIDDEN,
    POLICY_DENIED,
    TIMEOUT_KNOWN_FAIL,
    TIMEOUT_UNKNOWN,
    IDEMPOTENT_REPLAY,
    CONFIRMATION_REQUIRED
}
