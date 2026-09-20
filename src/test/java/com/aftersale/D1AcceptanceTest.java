package com.aftersale;

import com.aftersale.enums.OrderStatus;
import com.aftersale.repo.IdempotencyKeyRepository;
import com.aftersale.repo.OrderRepository;
import com.aftersale.tools.ToolErrorCode;
import com.aftersale.tools.ToolResult;
import com.aftersale.tools.read.GetOrderTool;
import com.aftersale.tools.read.GetPolicyTool;
import com.aftersale.tools.read.ListMyOrdersTool;
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
    @Autowired ListMyOrdersTool listMyOrdersTool;
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

    // ---------------------------------------------------------------- listMyOrders
    // 覆盖「用户用商品描述指代订单」这条路径。没有它，查询链路一旦拿不到订单号就无路可走。

    @SuppressWarnings("unchecked")
    private java.util.List<java.util.Map<String, Object>> ordersOf(ToolResult r) {
        assertTrue(r.ok(), "应为成功: " + r.message());
        return (java.util.List<java.util.Map<String, Object>>)
                ((java.util.Map<String, Object>) r.data()).get("orders");
    }

    @Test
    void listMyOrders_returnsOnlyOwnOrdersByTimeDesc() {
        ToolResult r = listMyOrdersTool.listMyOrders(null, 20, U1);
        java.util.List<java.util.Map<String, Object>> orders = ordersOf(r);

        // U001 在种子数据里有 10 单（12 条里另外 2 条属 U002）
        assertEquals(10, orders.size(), "U001 应能列出自己的 10 单");

        // 时间倒序：第一条应是 createdAt 最大者（ORD202609160012，09-16）
        assertEquals("ORD202609160012", orders.get(0).get("orderNo"), "应按时间倒序，最近的在最前");

        // 越权边界：U002 的订单绝不能出现在 U001 的列表里
        for (java.util.Map<String, Object> o : orders) {
            assertNotEquals("ORD202609060006", o.get("orderNo"), "不得看到他人订单");
            assertNotEquals("ORD202609120007", o.get("orderNo"), "不得看到他人订单");
        }
    }

    @Test
    void listMyOrders_keywordMatchesItemName() {
        // 「咖啡机」应命中 ORD202609050002（全自动咖啡机）
        java.util.List<java.util.Map<String, Object>> hit =
                ordersOf(listMyOrdersTool.listMyOrders("咖啡机", null, U1));
        assertEquals(1, hit.size(), "「咖啡机」应恰好命中 1 单");
        assertEquals("ORD202609050002", hit.get(0).get("orderNo"));

        // 「耳机」应命中两单：无线蓝牙耳机 + 降噪头戴耳机
        java.util.List<java.util.Map<String, Object>> ear =
                ordersOf(listMyOrdersTool.listMyOrders("耳机", null, U1));
        assertEquals(2, ear.size(), "「耳机」应命中 2 单（蓝牙耳机 / 头戴耳机）");
    }

    @Test
    void listMyOrders_keywordMissFallsBackWithNote() {
        // 用户口中的商品名常与系统记录不一致，未命中时退回最近订单并附说明，
        // 而不是返回空让模型误判"用户没有这个订单"
        ToolResult r = listMyOrdersTool.listMyOrders("电饭煲", null, U1);
        java.util.List<java.util.Map<String, Object>> orders = ordersOf(r);

        assertFalse(orders.isEmpty(), "未命中应退化为最近订单而非空列表");
        assertEquals(5, orders.size(), "默认返回 5 条");
        String note = String.valueOf(((java.util.Map<String, Object>) r.data()).get("note"));
        assertTrue(note.contains("电饭煲"), "note 应说明是哪个关键词没匹配到");
        assertTrue(note.contains("没有匹配"), "note 应说明未命中，避免模型误以为就是用户要的那单");
    }

    @Test
    void listMyOrders_limitAndArgsBoundary() {
        // limit 上限 20：即使传 999 也不会把全部历史倒出来
        assertEquals(10, ordersOf(listMyOrdersTool.listMyOrders(null, 999, U1)).size());
        // limit 非法值退化为默认 5，而不是让整次查询失败
        assertEquals(5, ordersOf(listMyOrdersTool.listMyOrders(null, 0, U1)).size());
        assertEquals(5, ordersOf(listMyOrdersTool.listMyOrders(null, -3, U1)).size());
        // userId 缺失 → INVALID_ARGS（身份由系统注入，模型无法省略或伪造）
        assertEquals(ToolErrorCode.INVALID_ARGS, listMyOrdersTool.listMyOrders(null, null, "").code());
    }

    @Test
    void listMyOrders_otherUserSeesOwnListOnly() {
        // 同一工具、不同身份 → 看到的是各自的订单，这是归属校验的构造性体现
        java.util.List<java.util.Map<String, Object>> u2 =
                ordersOf(listMyOrdersTool.listMyOrders(null, 20, U2));
        assertEquals(2, u2.size(), "U002 只应看到自己的 2 单");
        for (java.util.Map<String, Object> o : u2) {
            assertTrue(String.valueOf(o.get("orderNo")).equals("ORD202609060006")
                            || String.valueOf(o.get("orderNo")).equals("ORD202609120007"),
                    "U002 的列表里只能有自己的订单");
        }
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
