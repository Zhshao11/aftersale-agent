package com.aftersale.tools;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 所有工具（读/写）的统一返回结构。
 * ok=true 时 data 携带业务数据；ok=false 时 code+message 说明拒绝原因。
 * 三态语义由 status 表达（与 ExecutionStatus 对齐）：
 *  - SUCCESS：执行成功
 *  - FAILED：明确失败（含 POLICY_DENIED 等，可安全重试或换参数）
 *  - UNKNOWN：结果未知（超时未响应，禁止盲目重试）
 */
public record ToolResult(boolean ok, String status, ToolErrorCode code, String message, Object data) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ToolResult success(Object data) {
        return new ToolResult(true, "SUCCESS", null, null, data);
    }

    public static ToolResult failure(ToolErrorCode code, String message) {
        return new ToolResult(false, "FAILED", code, message, null);
    }

    public static ToolResult unknown(ToolErrorCode code, String message) {
        return new ToolResult(false, "UNKNOWN", code, message, null);
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            return "{\"ok\":false,\"message\":\"serialize error\"}";
        }
    }
}
