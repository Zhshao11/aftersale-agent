package com.aftersale.executor;

import com.aftersale.tools.ToolResult;
import com.aftersale.tools.write.CancelOrderTool;
import com.aftersale.tools.write.ExchangeOrderTool;
import com.aftersale.tools.write.RefundOrderTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Function;

/**
 * 写工具注册表：仅 Executor 可访问（LLM 物理隔离的另一半——
 * 写工具不在任何 ChatClient 的工具集里，只有这里按名字分发）。
 */
@Component
public class WriteToolRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Function<Args, ToolResult>> registry;

    public record Args(String orderNo, String userId, String reason) {}

    public WriteToolRegistry(CancelOrderTool cancelOrderTool,
                             RefundOrderTool refundOrderTool,
                             ExchangeOrderTool exchangeOrderTool) {
        this.registry = Map.of(
                "cancelOrder", a -> cancelOrderTool.cancelOrder(a.orderNo(), a.userId(), a.reason()),
                "refundOrder", a -> refundOrderTool.refundOrder(a.orderNo(), a.userId(), a.reason()),
                "exchangeOrder", a -> exchangeOrderTool.exchangeOrder(a.orderNo(), a.userId(), a.reason())
        );
    }

    public boolean supports(String toolName) {
        return registry.containsKey(toolName);
    }

    public ToolResult invoke(String toolName, String argsJson) {
        Function<Args, ToolResult> fn = registry.get(toolName);
        if (fn == null) {
            return ToolResult.failure(com.aftersale.tools.ToolErrorCode.INVALID_ARGS, "未知写工具: " + toolName);
        }
        try {
            JsonNode node = MAPPER.readTree(argsJson);
            return fn.apply(new Args(
                    node.path("orderNo").asText(),
                    node.path("userId").asText(),
                    node.path("reason").asText("用户请求")));
        } catch (Exception e) {
            return ToolResult.failure(com.aftersale.tools.ToolErrorCode.INVALID_ARGS, "参数解析失败: " + e.getMessage());
        }
    }

    /** 工具对应的订单期望终态（对账用） */
    public static String desiredOrderStatus(String toolName) {
        return switch (toolName) {
            case "cancelOrder" -> "CANCELLED";
            case "refundOrder" -> "REFUNDED";
            case "exchangeOrder" -> "EXCHANGED";
            default -> null;
        };
    }
}
