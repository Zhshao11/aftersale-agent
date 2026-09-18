package com.aftersale;

import com.aftersale.enums.OrderStatus;
import com.aftersale.repo.IdempotencyKeyRepository;
import com.aftersale.repo.OrderRepository;
import com.aftersale.tools.ToolErrorCode;
import com.aftersale.tools.ToolResult;
import com.aftersale.tools.read.GetOrderTool;
import com.aftersale.tools.read.GetPolicyTool;
import com.aftersale.tools.write.CancelOrderTool;
import com.aftersale.tools.write.ExchangeOrderTool;
import com.aftersale.tools.write.RefundOrderTool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D1 验收测试：DB schema + 种子数据 + 工具契约 + 政策矩阵 + 幂等键唯一性。
 * @Transactional 保证写操作测试后回滚，种子数据不被污染（可重复执行）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class D1AcceptanceTest {

    @Autowired OrderRepository orderRepository;
    @Autowired IdempotencyKeyRepository idempotencyKeyRepository;
    @Autowired DataSource dataSource;
    @Autowired GetOrderTool getOrderTool;
    @Autowired GetPolicyTool getPolicyTool;
    @Autowired CancelOrderTool cancelOrderTool;
    @Autowired RefundOrderTool refundOrderTool;
    @Autowired ExchangeOrderTool exchangeOrderTool;

    private static final String U1 = "U001";
    private static final String U2 = "U002";

    @Test
    void seedDataLoaded() {
        assertEquals(12, orderRepository.count(), "种子订单应为 12 条");
        assertNotNull(orderRepository.findByOrderNo("ORD20260901001").orElse(null));
    }

    @Test
    void readTool_orderVisibility() {
        ToolResult own = getOrderTool.getOrder("ORD20260901001", U1);
        assertTrue(own.ok());

        ToolResult others = getOrderTool.getOrder("ORD202609060006", U1);
        assertFalse(others.ok());
        assertEquals(ToolErrorCode.FORBIDDEN, others.code(), "查他人订单必须 FORBIDDEN");

        ToolResult missing = getOrderTool.getOrder("ORD_NOT_EXIST", U1);
        assertEquals(ToolErrorCode.ORDER_NOT_FOUND, missing.code());

        ToolResult badArgs = getOrderTool.getOrder("", U1);
        assertEquals(ToolErrorCode.INVALID_ARGS, badArgs.code());
    }

    @Test
    void readTool_policyQuery() {
        assertTrue(getPolicyTool.getPolicy("CANCEL").ok());
        assertTrue(getPolicyTool.getPolicy(null).ok());
        assertEquals(ToolErrorCode.INVALID_ARGS, getPolicyTool.getPolicy("FOO").code());
    }

    @Test
    void writeTool_cancelPolicyMatrix() {
        // PAID 未发货 → 可取消
        ToolResult ok = cancelOrderTool.cancelOrder("ORD20260901001", U1, "不想要了");
        assertTrue(ok.ok(), "未发货订单应可取消: " + ok.message());
        assertEquals(OrderStatus.CANCELLED,
                orderRepository.findByOrderNo("ORD20260901001").orElseThrow().getStatus());

        // DELIVERED → 政策拒绝
        ToolResult denied = cancelOrderTool.cancelOrder("ORD20260910003", U1, "不想要了");
        assertFalse(denied.ok());
        assertEquals(ToolErrorCode.POLICY_DENIED, denied.code());

        // 他人订单 → FORBIDDEN
        ToolResult forbidden = cancelOrderTool.cancelOrder("ORD202609060006", U1, "hijack");
        assertEquals(ToolErrorCode.FORBIDDEN, forbidden.code());
    }

    @Test
    void writeTool_refundPolicyMatrix() {
        // 送达 6 天（窗口 7 天内）→ 可退款
        ToolResult ok = refundOrderTool.refundOrder("ORD20260910003", U1, "质量问题");
        assertTrue(ok.ok(), "7 天内应可退款: " + ok.message());

        // 送达 44 天（超窗口）→ 政策拒绝
        ToolResult denied = refundOrderTool.refundOrder("ORD202608010004", U1, "质量问题");
        assertFalse(denied.ok());
        assertEquals(ToolErrorCode.POLICY_DENIED, denied.code());

        // 他人订单 → FORBIDDEN
        assertEquals(ToolErrorCode.FORBIDDEN,
                refundOrderTool.refundOrder("ORD202609120007", U1, "hijack").code());
    }

    @Test
    void writeTool_exchangePolicyMatrix() {
        // 送达 1 天（窗口 15 天内）→ 可换货
        assertTrue(exchangeOrderTool.exchangeOrder("ORD202609160012", U1, "颜色发错").ok());
        // 送达 44 天 → 拒绝
        assertEquals(ToolErrorCode.POLICY_DENIED,
                exchangeOrderTool.exchangeOrder("ORD202608010004", U1, "想换").code());
    }

    @Test
    void idempotencyKey_uniqueConstraint() {
        // 用原生 INSERT 绕开 JPA merge 语义，直接验证数据库唯一索引
        org.springframework.jdbc.core.JdbcTemplate jdbc =
                new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO idempotency_keys (idempotency_key, plan_id, step_id, attempt, created_at) VALUES (?,?,?,?,NOW())",
                "999:999:0", 999L, 999L, 0);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO idempotency_keys (idempotency_key, plan_id, step_id, attempt, created_at) VALUES (?,?,?,?,NOW())",
                        "999:999:0", 999L, 999L, 0),
                "相同幂等键第二次插入必须被唯一索引拒绝");
    }
}
