package com.aftersale.tools.write;

import com.aftersale.domain.OrderEntity;
import com.aftersale.repo.OrderRepository;
import com.aftersale.tools.PolicyService;
import com.aftersale.tools.ToolErrorCode;
import com.aftersale.tools.ToolResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 工具契约 — cancelOrder（写操作）
 * 用途：取消未发货订单
 * 输入：orderNo: string，userId: string，reason: string
 * 输出：orderNo, newStatus=CANCELLED, refundAmount（全额退回支付渠道）
 * 错误码：INVALID_ARGS / ORDER_NOT_FOUND / FORBIDDEN / POLICY_DENIED（已发货等）
 * 权限边界：写操作；只能操作本人订单；政策规则表评估前置条件；
 *           幂等与确认由 Executor 层保证，本工具自身不做重试
 */
public abstract class AbstractWriteTool {

    protected final OrderRepository orderRepository;
    protected final PolicyService policyService;

    protected AbstractWriteTool(OrderRepository orderRepository, PolicyService policyService) {
        this.orderRepository = orderRepository;
        this.policyService = policyService;
    }

    protected record ResolvedOrder(OrderEntity order) {}

    /** 写工具公共前置：参数 → 订单存在 → 归属校验。失败直接返回 ToolResult（抛出带标记的异常交给调用方）。 */
    protected OrderEntity resolveAndCheck(String orderNo, String userId) {
        if (orderNo == null || orderNo.isBlank() || userId == null || userId.isBlank()) {
            throw new ToolRejectException(ToolErrorCode.INVALID_ARGS, "orderNo 与 userId 均为必填");
        }
        OrderEntity order = orderRepository.findByOrderNo(orderNo.trim()).orElse(null);
        if (order == null) {
            throw new ToolRejectException(ToolErrorCode.ORDER_NOT_FOUND, "订单不存在: " + orderNo);
        }
        if (!order.ownedBy(userId)) {
            throw new ToolRejectException(ToolErrorCode.FORBIDDEN, "订单不属于当前用户，禁止操作");
        }
        return order;
    }

    protected ToolResult reject(ToolRejectException e) {
        return ToolResult.failure(e.code, e.message);
    }

    protected ToolResult policyDenied(String ruleCode, String reason) {
        return ToolResult.failure(ToolErrorCode.POLICY_DENIED, "政策拒绝[" + ruleCode + "]: " + reason);
    }

    protected static class ToolRejectException extends RuntimeException {
        final ToolErrorCode code;
        final String message;
        ToolRejectException(ToolErrorCode code, String message) {
            super(message);
            this.code = code;
            this.message = message;
        }
    }
}
