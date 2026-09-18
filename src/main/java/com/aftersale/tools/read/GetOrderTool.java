package com.aftersale.tools.read;

import com.aftersale.domain.OrderEntity;
import com.aftersale.repo.OrderRepository;
import com.aftersale.tools.ToolResult;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 工具契约 — getOrder
 * 用途：查询单个订单的详情（状态/金额/商品/关键时间点）
 * 输入：orderNo: string（订单号），userId: string（当前用户，用于权限校验）
 * 输出：orderNo, status, itemName, amount(美元), paidAt, shippedAt, deliveredAt
 * 错误码：INVALID_ARGS / ORDER_NOT_FOUND / FORBIDDEN（查询他人订单）
 * 权限边界：只读；只能查当前用户自己的订单
 */
@Component
public class GetOrderTool {

    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final OrderRepository orderRepository;

    public GetOrderTool(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public ToolResult getOrder(String orderNo, String userId) {
        if (orderNo == null || orderNo.isBlank() || userId == null || userId.isBlank()) {
            return ToolResult.failure(com.aftersale.tools.ToolErrorCode.INVALID_ARGS, "orderNo 与 userId 均为必填");
        }
        OrderEntity order = orderRepository.findByOrderNo(orderNo.trim()).orElse(null);
        if (order == null) {
            return ToolResult.failure(com.aftersale.tools.ToolErrorCode.ORDER_NOT_FOUND, "订单不存在: " + orderNo);
        }
        if (!order.ownedBy(userId)) {
            return ToolResult.failure(com.aftersale.tools.ToolErrorCode.FORBIDDEN, "订单不属于当前用户，无权查询");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("orderNo", order.getOrderNo());
        data.put("status", order.getStatus().name());
        data.put("itemName", order.getItemName());
        data.put("amount", String.format("$%.2f", order.getAmountCents() / 100.0));
        data.put("currency", order.getCurrency());
        data.put("paidAt", order.getPaidAt() == null ? null : order.getPaidAt().format(F));
        data.put("shippedAt", order.getShippedAt() == null ? null : order.getShippedAt().format(F));
        data.put("deliveredAt", order.getDeliveredAt() == null ? null : order.getDeliveredAt().format(F));
        return ToolResult.success(data);
    }
}
