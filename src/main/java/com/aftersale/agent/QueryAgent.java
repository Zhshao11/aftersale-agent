package com.aftersale.agent;

import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 查询路径：只读工具 ReAct。
 * 工具集 = ReadToolBundle（getOrder/getLogistics/getPolicy），
 * userId 经 ToolContext 注入，LLM 无法伪造身份，也无法调用任何写工具。
 */
@Component
public class QueryAgent {

    private static final String SYSTEM = """
            你是电商售后客服助手。当前用户身份由系统注入（userId），你无法也不需要向用户询问身份。
            回答用户关于订单、物流、售后政策的问题时，主动调用工具查询真实数据，禁止编造订单信息。
            工具查询失败时如实转述原因（如订单不存在、无权查询）。金额使用美元。
            回答保持简洁中文。
            """;

    private final LlmPort llmPort;
    private final ReadToolBundle readToolBundle;

    public QueryAgent(LlmPort llmPort, ReadToolBundle readToolBundle) {
        this.llmPort = llmPort;
        this.readToolBundle = readToolBundle;
    }

    public String answer(String userId, String userMessage, List<Message> history) {
        return llmPort.completeWithTools(SYSTEM, history, userMessage,
                readToolBundle, Map.of("userId", userId));
    }
}
