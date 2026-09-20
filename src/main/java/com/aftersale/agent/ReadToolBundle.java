package com.aftersale.agent;

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
 * 只读工具白名单：唯一暴露给 LLM 的工具集。
 * 关键安全设计——userId 从 ToolContext 注入（由编排层放入），LLM 无法伪造调用者身份。
 * 写工具（取消/退款/换货）物理隔离在 tools.write 包，从不注册到任何 ChatClient。
 */
@Component
public class ReadToolBundle {

    private final GetOrderTool getOrderTool;
    private final GetLogisticsTool getLogisticsTool;
    private final GetPolicyTool getPolicyTool;
    private final ListMyOrdersTool listMyOrdersTool;

    public ReadToolBundle(GetOrderTool getOrderTool, GetLogisticsTool getLogisticsTool,
                          GetPolicyTool getPolicyTool, ListMyOrdersTool listMyOrdersTool) {
        this.getOrderTool = getOrderTool;
        this.getLogisticsTool = getLogisticsTool;
        this.getPolicyTool = getPolicyTool;
        this.listMyOrdersTool = listMyOrdersTool;
    }

    @Tool(description = """
            列出当前用户自己的订单（按时间倒序）。当用户用商品描述指代订单、而没说订单号时，
            必须先调用这个工具定位订单，不要反问用户要订单号，更不要编造订单号。
            keyword 可填商品名片段（如「耳机」「咖啡机」）用于缩小范围；不确定商品名时留空，
            直接看最近的订单。返回的 orders[] 里每条都带 orderNo、下单时间与状态，
            据此判断用户说的是哪一单，再用 getOrder / getLogistics 查详情。""")
    public ToolResult listMyOrders(
            @ToolParam(description = "商品名关键词，如「耳机」「咖啡机」；不确定则留空", required = false) String keyword,
            @ToolParam(description = "返回条数上限，默认 5，最大 20", required = false) Integer limit,
            ToolContext ctx) {
        return listMyOrdersTool.listMyOrders(keyword, limit, userId(ctx));
    }

    @Tool(description = "查询订单详情：状态、商品、金额、支付/发货/送达时间。只能查询当前用户自己的订单。")
    public ToolResult getOrder(
            @ToolParam(description = "订单号，形如 ORD20260901001") String orderNo,
            ToolContext ctx) {
        return getOrderTool.getOrder(orderNo, userId(ctx));
    }

    @Tool(description = "查询订单物流状态：未发货 / 运输中 / 已送达。")
    public ToolResult getLogistics(
            @ToolParam(description = "订单号") String orderNo,
            ToolContext ctx) {
        return getLogisticsTool.getLogistics(orderNo, userId(ctx));
    }

    @Tool(description = "查询售后政策：取消、退款、换货各自的前置条件与时间窗口。scope 可选值 CANCEL/REFUND/EXCHANGE。")
    public ToolResult getPolicy(
            @ToolParam(description = "政策范围：CANCEL 或 REFUND 或 EXCHANGE，可为空", required = false) String scope) {
        return getPolicyTool.getPolicy(scope);
    }

    static String userId(ToolContext ctx) {
        Object v = ctx == null ? null : ctx.getContext().get("userId");
        if (v == null) {
            throw new IllegalStateException("ToolContext 缺少 userId，编排层未注入用户身份");
        }
        return v.toString();
    }
}
