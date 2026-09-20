package com.aftersale.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 轨迹落库实现。
 *
 * 刻意用 JdbcTemplate 而不是 JPA：轨迹是只追加的宽表，不需要实体生命周期，
 * 也不需要被事务回滚——观测数据丢失不应该反过来把业务事务搞崩。
 * 因此这里的写入失败只告警，不向上抛。
 */
@Component
public class JdbcTraceSink implements TraceSink {

    private static final Logger log = LoggerFactory.getLogger(JdbcTraceSink.class);

    private static final String INSERT = """
            INSERT INTO agent_trace
              (trace_id, conversation_id, user_id, node, step_index, tool_name, args_digest,
               status, error_code, detail, model, prompt_tokens, completion_tokens, latency_ms)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcTraceSink(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void record(AgentStep s) {
        try {
            jdbcTemplate.update(INSERT,
                    s.traceId(), s.conversationId(), s.userId(), s.node(), s.stepIndex(),
                    s.toolName(), s.argsDigest(), s.status(), s.errorCode(), s.detail(),
                    s.model(), s.promptTokens(), s.completionTokens(), s.latencyMs());
        } catch (Exception e) {
            // 观测写入失败不允许影响主链路，但必须留下痕迹，不能静默
            log.warn("轨迹落库失败 traceId={} step={} node={} err={}",
                    s.traceId(), s.stepIndex(), s.node(), e.toString());
        }
    }
}
