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
 * 工具契约 — exchangeOrder（写操作）
 * 用途：已送达订单换货（同款优先，15 天窗口）
 * 输入：orderNo, userId, reason
 * 输出：orderNo, newStatus=EXCHANGED, exchangeNote
 * 错误码：INVALID_ARGS / ORDER_NOT_FOUND / FORBIDDEN / POLICY_DENIED（超 15 天窗口等）
 * 权限边界：写操作；本人订单；政策前置
 */
@Component
public class ExchangeOrderTool extends AbstractWriteTool implements WriteTool {

    public ExchangeOrderTool(OrderRepository orderRepository, PolicyService policyService) {
        super(orderRepository, policyService);
    }

    @Transactional
    public ToolResult exchangeOrder(String orderNo, String userId, String reason) {
        try {
            OrderEntity order = resolveAndCheck(orderNo, userId);
            PolicyService.PolicyDecision d = policyService.evaluate("EXCHANGE", order, LocalDateTime.now());
            if (!d.allowed()) {
                return policyDenied(d.ruleCode(), d.reason());
            }
            order.setStatus(OrderStatus.EXCHANGED);
            orderRepository.saveAndFlush(order);
            Map<String, Object> data = new HashMap<>();
            data.put("orderNo", order.getOrderNo());
            data.put("newStatus", "EXCHANGED");
            data.put("exchangeNote", "换货申请已受理，请保持商品包装完好等待上门取件");
            data.put("reason", reason);
            return ToolResult.success(data);
        } catch (AbstractWriteTool.ToolRejectException e) {
            return reject(e);
        }
    }
}
