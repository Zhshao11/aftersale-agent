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

/**
 * 工具契约 — refundOrder（写操作）
 * 用途：已送达订单全额退款（当前版本不支持部分退款，金额=订单总额）
 * 输入：orderNo, userId, reason
 * 输出：orderNo, newStatus=REFUNDED, refundAmount
 * 错误码：INVALID_ARGS / ORDER_NOT_FOUND / FORBIDDEN / POLICY_DENIED（超 7 天窗口等）
 * 权限边界：写操作；本人订单；政策前置；金额二次确认由 ConfirmGate 保证
 */
@Component
public class RefundOrderTool extends AbstractWriteTool implements WriteTool {

    public RefundOrderTool(OrderRepository orderRepository, PolicyService policyService) {
        super(orderRepository, policyService);
    }

    @Transactional
    public ToolResult refundOrder(String orderNo, String userId, String reason) {
        try {
            OrderEntity order = resolveAndCheck(orderNo, userId);
            PolicyService.PolicyDecision d = policyService.evaluate("REFUND", order, LocalDateTime.now());
            if (!d.allowed()) {
                return policyDenied(d.ruleCode(), d.reason());
            }
            order.setStatus(OrderStatus.REFUNDED);
            orderRepository.saveAndFlush(order);
            Map<String, Object> data = new HashMap<>();
            data.put("orderNo", order.getOrderNo());
            data.put("newStatus", "REFUNDED");
            data.put("refundAmount", String.format("$%.2f", order.getAmountCents() / 100.0));
            data.put("reason", reason);
            return ToolResult.success(data);
        } catch (AbstractWriteTool.ToolRejectException e) {
            return reject(e);
        }
    }
}
