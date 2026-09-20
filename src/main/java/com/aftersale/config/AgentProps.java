package com.aftersale.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * aftersale.* 配置：阈值、消融开关、基线模式、只读循环边界。
 *
 * 只读循环的边界单独放在 read 段，而不是塞进 agent 段的消融开关里——
 * 前者是"Agent 会不会跑飞"的安全边界，后者是"评测时关掉哪个能力做对照"的实验开关，
 * 两类东西的生命周期不同：边界一旦定下就不该随实验翻转。
 */
@ConfigurationProperties(prefix = "aftersale")
public record AgentProps(
        long confirmThresholdCents,
        Llm llm,
        Agent agent,
        Read read
) {
    public record Llm(String baseUrl, String apiKey, String model) {}

    public record Agent(
            boolean disableConfirmGate,
            boolean disableIdempotency,
            boolean disableTimeoutClassify,
            boolean reactBaselineMode
    ) {}

    /**
     * 只读 ReAct 循环的硬边界。
     *
     * callTimeoutMs 与 wallClockMs 是两件事，不要合并：
     * - callTimeoutMs：单次模型调用的墙钟上限，防"一个请求挂死"
     * - wallClockMs：整次只读请求的上限，防"多步正好都很快但累加起来跑飞"
     * 少了任何一个，另一类故障都拦不住。
     */
    public record Read(
            int maxSteps,
            long wallClockMs,
            int tokenBudget,
            long callTimeoutMs,
            int retryMax,
            long retryBaseBackoffMs,
            int toolOutputMaxChars,
            int maxRepeatedActions,
            boolean fabricationGuardEnabled
    ) {}
}
