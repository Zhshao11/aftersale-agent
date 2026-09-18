package com.aftersale.tools.read;

import com.aftersale.domain.OrderEntity;
import com.aftersale.repo.OrderRepository;
import com.aftersale.tools.ToolErrorCode;
import com.aftersale.tools.ToolResult;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 工具契约 — getLogistics
 * 用途：查询订单物流状态
 * 输入：orderNo: string，userId: string
 * 输出：orderNo, logisticsStatus（NOT_SHIPPED/IN_TRANSIT/DELIVERED）, 描述文本
 * 错误码：INVALID_ARGS / ORDER_NOT_FOUND / FORBIDDEN
 * 权限边界：只读；只能查自己的订单
 */
@Component
public class GetLogisticsTool {

    private final OrderRepository orderRepository;

    public GetLogisticsTool(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public ToolResult getLogistics(String orderNo, String userId) {
        if (orderNo == null || orderNo.isBlank() || userId == null || userId.isBlank()) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGS, "orderNo 与 userId 均为必填");
        }
        OrderEntity order = orderRepository.findByOrderNo(orderNo.trim()).orElse(null);
        if (order == null) {
            return ToolResult.failure(ToolErrorCode.ORDER_NOT_FOUND, "订单不存在: " + orderNo);
        }
        if (!order.ownedBy(userId)) {
            return ToolResult.failure(ToolErrorCode.FORBIDDEN, "订单不属于当前用户，无权查询");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("orderNo", order.getOrderNo());
        data.put("logisticsStatus", order.getLogisticsStatus() == null ? "UNKNOWN" : order.getLogisticsStatus());
        data.put("description", describe(order.getLogisticsStatus()));
        return ToolResult.success(data);
    }

    private String describe(String s) {
        if (s == null) return "暂无物流信息";
        return switch (s) {
            case "NOT_SHIPPED" -> "尚未发货";
            case "IN_TRANSIT" -> "运输中";
            case "DELIVERED" -> "已签收";
            default -> "暂无物流信息";
        };
    }
}
