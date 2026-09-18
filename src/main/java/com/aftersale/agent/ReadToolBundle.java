package com.aftersale.agent;

import com.aftersale.tools.ToolResult;
import com.aftersale.tools.read.GetLogisticsTool;
import com.aftersale.tools.read.GetOrderTool;
import com.aftersale.tools.read.GetPolicyTool;
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

    public ReadToolBundle(GetOrderTool getOrderTool, GetLogisticsTool getLogisticsTool, GetPolicyTool getPolicyTool) {
        this.getOrderTool = getOrderTool;
        this.getLogisticsTool = getLogisticsTool;
        this.getPolicyTool = getPolicyTool;
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
