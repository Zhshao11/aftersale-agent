package com.aftersale.agent;

import com.aftersale.tools.ToolErrorCode;
import com.aftersale.tools.ToolResult;
import com.aftersale.tools.read.GetLogisticsTool;
import com.aftersale.tools.read.GetOrderTool;
import com.aftersale.tools.read.GetPolicyTool;
import com.aftersale.tools.read.ListMyOrdersTool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * V0 基线工具集：读 + 写全部挂载给 LLM（与完整版唯一共享的安全边界：
 * userId 经 ToolContext 注入 + 政策规则表在工具内部生效）。
 * 写工具首次调用返回 CONFIRMATION_REQUIRED（工具级确认对话框），
 * 经 /api/baseline/confirm 执行——工程能力保留，消融的是 Plan 编排。
 *
 * 注意：只读工具集与 ReadToolBundle **保持一致**（含 listMyOrders）。
 * 消融实验的变量只能是"编排与确认门"，工具能力若不同则对比不成立。
 */
@Component
public class BaselineToolBundle {

    public static final String CONFIRM_MARKER = "PENDING_CONFIRM";

    private final GetOrderTool getOrderTool;
    private final GetLogisticsTool getLogisticsTool;
    private final GetPolicyTool getPolicyTool;
    private final ListMyOrdersTool listMyOrdersTool;
    private final PendingWriteStore pendingStore;

    public BaselineToolBundle(GetOrderTool getOrderTool, GetLogisticsTool getLogisticsTool,
                              GetPolicyTool getPolicyTool, ListMyOrdersTool listMyOrdersTool,
                              PendingWriteStore pendingStore) {
        this.getOrderTool = getOrderTool;
        this.getLogisticsTool = getLogisticsTool;
        this.getPolicyTool = getPolicyTool;
        this.listMyOrdersTool = listMyOrdersTool;
        this.pendingStore = pendingStore;
    }

    @Tool(description = """
            列出当前用户自己的订单（按时间倒序）。当用户用商品描述指代订单、而没说订单号时，
            必须先调用这个工具定位订单，不要反问用户要订单号，更不要编造订单号。
            keyword 可填商品名片段用于缩小范围；不确定时留空，看最近的订单。""")
    public ToolResult listMyOrders(
            @ToolParam(description = "商品名关键词，如「耳机」「咖啡机」；不确定则留空", required = false) String keyword,
            @ToolParam(description = "返回条数上限，默认 5，最大 20", required = false) Integer limit,
            ToolContext ctx) {
        return listMyOrdersTool.listMyOrders(keyword, limit, ReadToolBundle.userId(ctx));
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
