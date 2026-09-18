package com.aftersale.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** aftersale.* 配置：阈值、消融开关、基线模式 */
@ConfigurationProperties(prefix = "aftersale")
public record AgentProps(
        long confirmThresholdCents,
        Llm llm,
        Agent agent
) {
    public record Llm(String baseUrl, String apiKey, String model) {}

    public record Agent(
            boolean disableConfirmGate,
            boolean disableIdempotency,
            boolean disableTimeoutClassify,
            boolean reactBaselineMode
    ) {}
}
