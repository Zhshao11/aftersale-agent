package com.aftersale.agent;

import com.aftersale.config.AgentProps;
import org.springframework.stereotype.Component;

/** 配置门面：集中暴露消融开关与循环边界，评测时通过 -D 参数翻转 */
@Component
public class AgentPropsProvider {

    /**
     * read 段缺失时的兜底。
     * 不假设 yml 一定写全：配置漏项应该在启动时退化成一组保守默认值，而不是 NPE。
     * 注意这里每一项都是有界的——宁可保守到"很快停"，也不要"没有上限"。
     */
    public static final AgentProps.Read READ_DEFAULTS =
            new AgentProps.Read(6, 30_000L, 12_000, 20_000L, 2, 500L, 4_000, 2, true);

    private final AgentProps props;

    public AgentPropsProvider(AgentProps props) {
        this.props = props;
    }

    public long confirmThresholdCents() {
        return props.confirmThresholdCents();
    }

    public boolean confirmGateEnabled() {
        return !props.agent().disableConfirmGate();
    }

    public boolean idempotencyEnabled() {
        return !props.agent().disableIdempotency();
    }

    public boolean timeoutClassifyEnabled() {
        return !props.agent().disableTimeoutClassify();
    }

    public boolean reactBaselineMode() {
        return props.agent().reactBaselineMode();
    }

    /** 模型名：运行期 options 显式带上它，避免与默认 options 合并时取值不确定 */
    public String model() {
        return props.llm() == null ? null : props.llm().model();
    }

    public AgentProps.Read read() {
        return props.read() == null ? READ_DEFAULTS : props.read();
    }
}
