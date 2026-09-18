package com.aftersale.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LLM 端口抽象：生产走 Spring AI（Qwen OpenAI 兼容端点），
 * 测试可注入固定回复，评测可在不依赖网络时跑非 LLM 环节。
 */
public interface LlmPort {

    /** 单轮补全（系统提示 + 可选历史 + 用户输入），返回文本 */
    String complete(String systemPrompt, List<Message> history, String userMessage);

    /** 携带工具的单轮代理调用（ReAct 循环由 Spring AI ChatClient 内部完成），返回最终文本 */
    String completeWithTools(String systemPrompt, List<Message> history, String userMessage,
                             Object toolBundle, java.util.Map<String, Object> toolContext);

    @Component
    class SpringAiLlmPort implements LlmPort {

        private final ChatClient.Builder chatClientBuilder;

        public SpringAiLlmPort(ChatClient.Builder chatClientBuilder) {
            this.chatClientBuilder = chatClientBuilder;
        }

        @Override
        public String complete(String systemPrompt, List<Message> history, String userMessage) {
            var spec = chatClientBuilder.build().prompt().system(systemPrompt);
            if (history != null && !history.isEmpty()) {
                spec.messages(history);
            }
            return spec.user(userMessage).call().content();
        }

        @Override
        public String completeWithTools(String systemPrompt, List<Message> history, String userMessage,
                                        Object toolBundle, java.util.Map<String, Object> toolContext) {
            var spec = chatClientBuilder.build().prompt().system(systemPrompt);
            if (history != null && !history.isEmpty()) {
                spec.messages(history);
            }
            return spec.user(userMessage)
                    .tools(toolBundle)
                    .toolContext(toolContext)
                    .call().content();
        }
    }
}
