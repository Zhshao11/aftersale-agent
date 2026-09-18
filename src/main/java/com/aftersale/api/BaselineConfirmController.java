package com.aftersale.api;

import com.aftersale.agent.PendingWriteStore;
import com.aftersale.executor.FaultInjector;
import com.aftersale.executor.WriteToolRegistry;
import com.aftersale.tools.ToolResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * V0 基线确认接口：模拟用户在"确认对话框"上点击确定。
 * 执行 pending 写操作，保留工程能力：幂等键（convId:tool:0）+ 故障注入 + 政策（工具内部）。
 */
@RestController
@RequestMapping("/api/baseline")
public class BaselineConfirmController {

    private final PendingWriteStore pendingStore;
    private final WriteToolRegistry writeToolRegistry;
    private final FaultInjector faultInjector;
    private final JdbcTemplate jdbcTemplate;

    public BaselineConfirmController(PendingWriteStore pendingStore,
                                     WriteToolRegistry writeToolRegistry,
                                     FaultInjector faultInjector,
                                     JdbcTemplate jdbcTemplate) {
        this.pendingStore = pendingStore;
        this.writeToolRegistry = writeToolRegistry;
        this.faultInjector = faultInjector;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/confirm")
    @Transactional
    public Map<String, Object> confirm(@RequestParam String conversationId) {
        PendingWriteStore.Pending p = pendingStore.take(conversationId);
        if (p == null) {
            return Map.of("ok", false, "message", "没有待确认的操作");
        }
        // 幂等键（保留工程能力）：conversationId:tool:attempt，attempt 按 key 前缀计数
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key LIKE ?",
                Integer.class, p.conversationId() + ":" + p.toolName() + ":%");
        int attempt = count == null ? 0 : count;
        String key = p.conversationId() + ":" + p.toolName() + ":" + attempt;
        try {
            jdbcTemplate.update(
                    "INSERT INTO idempotency_keys (idempotency_key, plan_id, step_id, attempt, created_at) "
                            + "VALUES (?,?,0,?,NOW())", key, 0L, attempt);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return Map.of("ok", false, "message", "重复确认被幂等键拦截: " + key);
        }

        // 故障注入（评测用）
        FaultInjector.Mode mode = faultInjector.takeOnce();
        if (mode == FaultInjector.Mode.FAIL_BEFORE_SEND) {
            return Map.of("ok", false, "injected", "FAIL_BEFORE_SEND", "message", "请求未发出即失败");
        }
        if (mode == FaultInjector.Mode.TIMEOUT_UNKNOWN) {
            return Map.of("ok", false, "injected", "TIMEOUT_UNKNOWN", "message", "请求已发出但无响应（结果未知）");
        }

        ToolResult r = writeToolRegistry.invoke(p.toolName(),
                "{\"orderNo\":\"" + p.orderNo() + "\",\"userId\":\"" + p.userId()
                        + "\",\"reason\":\"" + (p.reason() == null ? "" : p.reason()) + "\"}");
        jdbcTemplate.update("UPDATE idempotency_keys SET result_status=? WHERE idempotency_key=?",
                r.ok() ? "SUCCESS" : "FAILED", key);
        return Map.of("ok", r.ok(), "tool", p.toolName(), "orderNo", p.orderNo(),
                "status", r.status(), "message", r.message() == null ? "" : r.message());
    }
}
