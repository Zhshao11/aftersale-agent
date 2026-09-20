package com.aftersale.agent;

import com.aftersale.config.AgentProps;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * LLM 端口抽象：生产走 Spring AI（OpenAI 兼容端点），
 * 测试可注入固定回复，评测可在不依赖网络时跑非 LLM 环节。
 */
public interface LlmPort {

    /** 单轮补全（系统提示 + 可选历史 + 用户输入），返回文本 */
    String complete(String systemPrompt, List<Message> history, String userMessage);

    /**
     * 携带工具的单轮代理调用。
     * 注意：ReAct 循环由 Spring AI ChatClient 内部完成，调用方拿不到步数、token、中间轨迹。
     * 正式路径已改用 {@link #callOnce}；这个方法保留给 V0 基线模式做对照——
     * 基线本来就是要用框架循环，否则消融掉的就不是你想消融的那一项了。
     */
    String completeWithTools(String systemPrompt, List<Message> history, String userMessage,
                             Object toolBundle, Map<String, Object> toolContext);

    /**
     * 只做"一次"模型调用，把是否继续调用工具的决定权交回调用方。
     *
     * 实现上把 internalToolExecutionEnabled 置 false，框架只负责发请求与解析，
     * 不再替我们跑循环。这样步数、token 预算、超时、重复动作才成为项目可控的变量，
     * 而不是框架内部的实现细节。
     */
    ChatResponse callOnce(String systemPrompt, List<Message> messages,
                          Object toolBundle, Map<String, Object> toolContext);

    @Component
    class SpringAiLlmPort implements LlmPort {

        private final ChatClient.Builder chatClientBuilder;
        private final AgentProps props;

        public SpringAiLlmPort(ChatClient.Builder chatClientBuilder, AgentProps props) {
            this.chatClientBuilder = chatClientBuilder;
            this.props = props;
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
                                        Object toolBundle, Map<String, Object> toolContext) {
            var spec = chatClientBuilder.build().prompt().system(systemPrompt);
            if (history != null && !history.isEmpty()) {
                spec.messages(history);
            }
            return spec.user(userMessage)
                    .tools(toolBundle)
                    .toolContext(toolContext)
                    .call().content();
        }

        @Override
        public ChatResponse callOnce(String systemPrompt, List<Message> messages,
                                     Object toolBundle, Map<String, Object> toolContext) {
            // 显式带模型名：运行期 options 会与默认 options 合并，留空会让最终取值依赖合并实现
            var options = DefaultToolCallingChatOptions.builder()
                    .model(props.llm() == null ? null : props.llm().model())
                    .toolCallbacks(ToolCallbacks.from(toolBundle))
                    .toolContext(toolContext)
                    .internalToolExecutionEnabled(false)
                    .build();

            var spec = chatClientBuilder.build().prompt().system(systemPrompt);
            if (messages != null && !messages.isEmpty()) {
                spec.messages(messages);
            }
            return spec.options(options).call().chatResponse();
        }
    }
}
