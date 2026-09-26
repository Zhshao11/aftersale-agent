package com.aftersale.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 轨迹落库实现。
 *
 * 刻意用 JdbcTemplate 而不是 JPA：轨迹是只追加的宽表，不需要实体生命周期，也不需要被事务回滚。
 * 因此这里的写入失败只告警，不向上抛——观测数据丢失不应该反过来把业务事务搞崩。
 *
 * <h3>为什么要 REQUIRES_NEW</h3>
 *
 * 这一条不是"顺手加的"，而是**实时进度的前提**。写路径的 {@code PlanService.createPlan}
 * 带 {@code @Transactional}，而它的埋点（TOOL / PLAN）都在那个事务内部。如果轨迹跟着业务事务
 * 一起提交，那么另一个连接按 traceId 轮询只能读到**旧快照**——TOOL 与 PLAN 两行会几乎同时出现。
 *
 * 实测（改动前，一次真实写请求，见 scripts/trace_live_probe.py）：
 * <pre>
 *   0.0s → 7.8s   stepCount=0            ← 意图路由期间完全空白
 *   7.8s → 55.6s  stepCount=1 ['INTENT'] ← 47.8 秒里只有一行，纹丝不动
 *   请求返回后才补上：TOOL listMyOrders(6ms) 与 PLAN planGenerator(51947ms)
 * </pre>
 * 也就是说：**一个只花了 6ms 的工具调用，被扣在一个 51.9 秒的事务里**，
 * 用户看到的进度就是"卡住"。加 REQUIRES_NEW 后轨迹逐条独立提交，这条延迟消失。
 *
 * 两个副作用，都是有意接受的：
 * <ul>
 *   <li><b>业务事务回滚时轨迹仍然留下</b>——这对审计是**想要**的行为：
 *       "谁在何时试图越权"不应该因为那次请求失败而被抹掉。</li>
 *   <li><b>长事务期间多占用一个连接</b>——PlanService 持有一个连接跨过 LLM 调用，
 *       轨迹写入再要一个。演示规模下连接池足够；真要压测，应先解决
 *       "把 DB 事务开在一个 51 秒的 LLM 调用外面"这件事，而不是回退这个改动。</li>
 * </ul>
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
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
