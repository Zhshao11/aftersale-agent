package com.aftersale.agent;

import com.aftersale.tools.ToolErrorCode;
import com.aftersale.tools.ToolResult;
import com.aftersale.tools.read.GetLogisticsTool;
import com.aftersale.tools.read.GetOrderTool;
import com.aftersale.tools.read.GetPolicyTool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * V0 基线工具集：读 + 写全部挂载给 LLM（与完整版唯一共享的安全边界：
 * userId 经 ToolContext 注入 + 政策规则表在工具内部生效）。
 * 写工具首次调用返回 CONFIRMATION_REQUIRED（工具级确认对话框），
 * 经 /api/baseline/confirm 执行——工程能力保留，消融的是 Plan 编排。
 */
@Component
public class BaselineToolBundle {

    public static final String CONFIRM_MARKER = "PENDING_CONFIRM";

    private final GetOrderTool getOrderTool;
    private final GetLogisticsTool getLogisticsTool;
    private final GetPolicyTool getPolicyTool;
    private final PendingWriteStore pendingStore;

    public BaselineToolBundle(GetOrderTool getOrderTool, GetLogisticsTool getLogisticsTool,
                              GetPolicyTool getPolicyTool, PendingWriteStore pendingStore) {
        this.getOrderTool = getOrderTool;
        this.getLogisticsTool = getLogisticsTool;
        this.getPolicyTool = getPolicyTool;
        this.pendingStore = pendingStore;
    }

    @Tool(description = "查询订单详情：状态、商品、金额、时间。只能查当前用户自己的订单。")
    public ToolResult getOrder(@ToolParam(description = "订单号") String orderNo, ToolContext ctx) {
        return getOrderTool.getOrder(orderNo, ReadToolBundle.userId(ctx));
    }

    @Tool(description = "查询订单物流状态。")
    public ToolResult getLogistics(@ToolParam(description = "订单号") String orderNo, ToolContext ctx) {
        return getLogisticsTool.getLogistics(orderNo, ReadToolBundle.userId(ctx));
    }

    @Tool(description = "查询售后政策：CANCEL/REFUND/EXCHANGE。")
    public ToolResult getPolicy(@ToolParam(description = "政策范围，可空", required = false) String scope) {
        return getPolicyTool.getPolicy(scope);
    }

    @Tool(description = "取消订单（写操作，需用户确认）。首次调用会生成待确认操作。")
    public ToolResult cancelOrder(@ToolParam(description = "订单号") String orderNo,
                                  @ToolParam(description = "原因") String reason,
                                  ToolContext ctx) {
        return pendingOrIgnore("cancelOrder", orderNo, reason, ctx);
    }

    @Tool(description = "退款（写操作，需用户确认）。")
    public ToolResult refundOrder(@ToolParam(description = "订单号") String orderNo,
                                  @ToolParam(description = "原因") String reason,
                                  ToolContext ctx) {
        return pendingOrIgnore("refundOrder", orderNo, reason, ctx);
    }

    @Tool(description = "换货（写操作，需用户确认）。")
    public ToolResult exchangeOrder(@ToolParam(description = "订单号") String orderNo,
                                    @ToolParam(description = "原因") String reason,
                                    ToolContext ctx) {
        return pendingOrIgnore("exchangeOrder", orderNo, reason, ctx);
    }

    /** 首次调用 → 记 pending 返回待确认；确认接口执行时会移除 pending，LLM 再次调用则直接放行 */
    private ToolResult pendingOrIgnore(String tool, String orderNo, String reason, ToolContext ctx) {
        String userId = ReadToolBundle.userId(ctx);
        Object convObj = ctx.getContext().get("conversationId");
        String convId = convObj == null ? "anonymous" : convObj.toString();

        PendingWriteStore.Pending existing = pendingStore.peek(convId);
        if (existing != null && existing.toolName().equals(tool)
                && existing.orderNo().equals(orderNo == null ? "" : orderNo.trim())) {
            // 已确认过的重放（confirm 接口执行后 pending 已被 take——走到这里说明未确认）
            return ToolResult.failure(ToolErrorCode.CONFIRMATION_REQUIRED,
                    "操作待用户确认（" + CONFIRM_MARKER + "），请提示用户确认后再执行");
        }
        pendingStore.put(new PendingWriteStore.Pending(convId, tool,
                orderNo == null ? "" : orderNo.trim(), userId, reason));
        return ToolResult.failure(ToolErrorCode.CONFIRMATION_REQUIRED,
                "已生成操作请求（" + CONFIRM_MARKER + "）：" + tool + " " + orderNo
                        + "，等待用户确认后执行");
    }
}
