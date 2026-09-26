package com.aftersale.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轨迹查询：GET /api/trace/{traceId}
 *
 * 有了这个端点，"轨迹可回放"才不是一句自我声明——拿 chat 响应里的 traceId 直接查，
 * 就能看到每一步用了哪个工具、什么参数、成没成、花了多少 token 和毫秒。
 * 没有它，轨迹只是躺在数据库里的表，只能靠口头描述。
 *
 * 覆盖写成两条路径：只读路径记 MODEL/TOOL/TERMINAL，
 * 写路径记 INTENT/PLAN/REFUSAL（写路径原先一行都不写，于是"这次写请求为什么被拒"查不到）。
 *
 * 另一个用途：请求进行中轮询。前端在发出 /api/chat 时自带 traceId，
 * 于是可以在等模型返回的那 20~40 秒里不断查这个端点，把"正在定位订单/正在生成计划"
 * 逐步显示出来——把不可见的等待变成可见的进度。
 */
@RestController
@RequestMapping("/api")
public class TraceController {

    private static final String QUERY = """
            SELECT step_index, node, tool_name, args_digest, status, error_code,
                   detail, model, prompt_tokens, completion_tokens, latency_ms, created_at
            FROM agent_trace
            WHERE trace_id = ?
            ORDER BY step_index ASC, id ASC
            """;

    private final JdbcTemplate jdbcTemplate;

    public TraceController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/trace/{traceId}")
    public Map<String, Object> get(@PathVariable String traceId) {
        List<Map<String, Object>> steps = jdbcTemplate.queryForList(QUERY, traceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", traceId);
        result.put("stepCount", steps.size());
        result.put("steps", steps);
        return result;
    }
}
