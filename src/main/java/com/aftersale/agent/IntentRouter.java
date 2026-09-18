package com.aftersale.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 意图路由：读写分流。
 * QUERY → 查询路径（只读工具 ReAct）
 * WRITE → 规划路径（Plan-and-Execute，写工具对 LLM 不可见）
 * 解析失败默认 QUERY（安全侧：查询无副作用）。
 */
@Component
public class IntentRouter {

    private static final Logger log = LoggerFactory.getLogger(IntentRouter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern JSON_BLOCK = Pattern.compile("\\{[^{}]*\"intent\"[^{}]*}");

    public enum Intent { QUERY, WRITE }

    public record IntentResult(Intent intent, String normalizedRequest) {}

    private static final String SYSTEM = """
            你是售后意图分类器。判断用户消息属于哪类：
            - QUERY：查询信息（查订单、查物流、查政策、咨询规则等，无状态变更诉求）
            - WRITE：请求执行操作（取消订单、退款、退货、换货，或"不想要了/退了吧"等隐含写操作的表达）
            同时把用户诉求改写成一句规范的中文短句。
            只输出 JSON，格式：{"intent":"QUERY|WRITE","request":"改写后的诉求"}，不要输出其他内容。
            """;

    private final LlmPort llmPort;

    public IntentRouter(LlmPort llmPort) {
        this.llmPort = llmPort;
    }

    public IntentResult route(String userMessage, List<Message> history) {
        String raw = llmPort.complete(SYSTEM, history, userMessage);
        return parse(raw, userMessage);
    }

    /** 纯函数，独立可测：从 LLM 输出解析意图，失败时保守归为 QUERY */
    public static IntentResult parse(String llmOutput, String fallbackRequest) {
        try {
            Matcher m = JSON_BLOCK.matcher(llmOutput == null ? "" : llmOutput);
            String json = m.find() ? m.group() : llmOutput;
            JsonNode node = MAPPER.readTree(json);
            String intent = node.path("intent").asText("").trim().toUpperCase(Locale.ROOT);
            String request = node.path("request").asText("").trim();
            if (intent.equals("WRITE")) {
                return new IntentResult(Intent.WRITE, request.isEmpty() ? fallbackRequest : request);
            }
            return new IntentResult(Intent.QUERY, request.isEmpty() ? fallbackRequest : request);
        } catch (Exception e) {
            log.warn("意图解析失败，保守归为 QUERY: {}", llmOutput, e);
            return new IntentResult(Intent.QUERY, fallbackRequest);
        }
    }

    /** 测试辅助：构造简单历史 */
    static List<Message> simpleHistory(String user, String assistant) {
        return List.of(new UserMessage(user), new AssistantMessage(assistant));
    }

    static Map<String, Object> ctx(String userId) {
        return Map.of("userId", userId);
    }
}
