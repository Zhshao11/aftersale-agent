package com.aftersale.tools.write;

import com.aftersale.domain.OrderEntity;
import com.aftersale.enums.OrderStatus;
import com.aftersale.repo.OrderRepository;
import com.aftersale.tools.PolicyService;
import com.aftersale.tools.ToolResult;
import com.aftersale.tools.WriteTool;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/** 取消订单。契约见 AbstractWriteTool 注释与 README 工具契约表。 */
@Component
public class CancelOrderTool extends AbstractWriteTool implements WriteTool {

    public CancelOrderTool(OrderRepository orderRepository, PolicyService policyService) {
        super(orderRepository, policyService);
    }

    @Transactional
    public ToolResult cancelOrder(String orderNo, String userId, String reason) {
        try {
            OrderEntity order = resolveAndCheck(orderNo, userId);
            PolicyService.PolicyDecision d = policyService.evaluate("CANCEL", order, LocalDateTime.now());
            if (!d.allowed()) {
                return policyDenied(d.ruleCode(), d.reason());
            }
            order.setStatus(OrderStatus.CANCELLED);
            orderRepository.saveAndFlush(order);
            Map<String, Object> data = new HashMap<>();
            data.put("orderNo", order.getOrderNo());
            data.put("newStatus", "CANCELLED");
            data.put("refundAmount", String.format("$%.2f", order.getAmountCents() / 100.0));
            data.put("reason", reason);
            return ToolResult.success(data);
        } catch (AbstractWriteTool.ToolRejectException e) {
            return reject(e);
        }
    }
}
