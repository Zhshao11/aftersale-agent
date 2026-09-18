package com.aftersale.agent;

import com.aftersale.config.AgentProps;
import org.springframework.stereotype.Component;

/** 配置门面：集中暴露消融开关，评测时通过 -D 参数翻转 */
@Component
public class AgentPropsProvider {

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
}
