package com.aftersale.api;

import com.aftersale.tools.ToolResult;
import com.aftersale.tools.read.GetLogisticsTool;
import com.aftersale.tools.read.GetOrderTool;
import com.aftersale.tools.read.GetPolicyTool;
import com.aftersale.tools.write.CancelOrderTool;
import com.aftersale.tools.write.ExchangeOrderTool;
import com.aftersale.tools.write.RefundOrderTool;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * D1 工具层自测：只读工具 + 写工具（含政策拒绝、越权、不存在订单）全路径验证。
 *
 * ── 关于"不污染种子数据"这件事 ──
 * 这个自测会真的调用写工具，所以必须显式把事务标记为回滚。
 * 注意 @Transactional 的语义是**正常返回即提交**，只有抛出异常才回滚；
 * `rollbackFor = Exception.class` 只是把回滚范围从 RuntimeException 扩展到受检异常，
 * 并不会让"正常返回"也回滚。早先这里只有 @Transactional，
 * 结果每次调用自测都会把订单真的改成 CANCELLED / REFUNDED / EXCHANGED，
 * 于是"同一份代码、同一份种子数据"的自测结果会从 12/12 变成 9/12，看起来像代码回归。
 * 显式 setRollbackOnly() 才是这里真正需要的机制。
 *
 * 另外：这是一个有副作用的 GET，只因为强制回滚才成立。生产环境不应这样暴露。
 */
@RestController
@RequestMapping("/api/selftest")
public class SelfTestController {

    private final GetOrderTool getOrderTool;
    private final GetLogisticsTool getLogisticsTool;
    private final GetPolicyTool getPolicyTool;
    private final CancelOrderTool cancelOrderTool;
    private final RefundOrderTool refundOrderTool;
    private final ExchangeOrderTool exchangeOrderTool;

    public SelfTestController(GetOrderTool getOrderTool, GetLogisticsTool getLogisticsTool,
                              GetPolicyTool getPolicyTool, CancelOrderTool cancelOrderTool,
                              RefundOrderTool refundOrderTool, ExchangeOrderTool exchangeOrderTool) {
        this.getOrderTool = getOrderTool;
        this.getLogisticsTool = getLogisticsTool;
        this.getPolicyTool = getPolicyTool;
        this.cancelOrderTool = cancelOrderTool;
        this.refundOrderTool = refundOrderTool;
        this.exchangeOrderTool = exchangeOrderTool;
    }

    @GetMapping
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> selftest() {
        List<Map<String, Object>> cases = new ArrayList<>();

        // 只读：正路径
        cases.add(check("read: 查自己的订单", true,
                getOrderTool.getOrder("ORD20260901001", "U001")));
        cases.add(check("read: 查物流", true,
                getLogisticsTool.getLogistics("ORD202609050002", "U001")));
        cases.add(check("read: 查退款政策", true,
                getPolicyTool.getPolicy("REFUND")));
        // 只读：反路径
        cases.add(check("read: 查他人订单 → FORBIDDEN", false,
                getOrderTool.getOrder("ORD202609060006", "U001")));
        cases.add(check("read: 订单不存在 → ORDER_NOT_FOUND", false,
                getOrderTool.getOrder("ORD209901010009", "U001")));

        // 写：正路径（事务回滚，不落库）
        cases.add(check("write: 取消已支付订单", true,
                cancelOrderTool.cancelOrder("ORD20260901001", "U001", "不想要了")));
        cases.add(check("write: 7天内退款", true,
                refundOrderTool.refundOrder("ORD20260910003", "U001", "质量问题")));
        cases.add(check("write: 15天内换货", true,
                exchangeOrderTool.exchangeOrder("ORD202609120007", "U002", "尺码不合适")));
        // 写：政策拒绝
        cases.add(check("write: 取消已发货订单 → POLICY_DENIED", false,
                cancelOrderTool.cancelOrder("ORD202609050002", "U001", "不想要了")));
        cases.add(check("write: 超7天退款 → POLICY_DENIED", false,
                refundOrderTool.refundOrder("ORD202608010004", "U001", "不想要了")));
        // 写：越权
        cases.add(check("write: 取消他人订单 → FORBIDDEN", false,
                cancelOrderTool.cancelOrder("ORD202609060006", "U001", "试试")));
        // 写：参数非法
        cases.add(check("write: 缺订单号 → INVALID_ARGS", false,
                cancelOrderTool.cancelOrder("", "U001", "x")));

        long passed = cases.stream().filter(c -> (boolean) c.get("pass")).count();

        // 显式回滚：本方法会真的调用写工具，必须撤销其副作用（正常返回不会自动回滚）
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", cases.size());
        result.put("passed", passed);
        result.put("failed", cases.size() - passed);
        result.put("cases", cases);
        return result;
    }

    private Map<String, Object> check(String name, boolean expectOk, ToolResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("case", name);
        m.put("expectOk", expectOk);
        m.put("actualOk", r.ok());
        m.put("status", r.status());
        m.put("code", r.code() == null ? "-" : r.code().name());
        m.put("message", r.message());
        m.put("pass", r.ok() == expectOk);
        return m;
    }
}
