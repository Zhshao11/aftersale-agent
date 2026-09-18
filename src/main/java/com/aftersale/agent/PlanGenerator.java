package com.aftersale.agent;

import com.aftersale.domain.PlanStepEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Plan 生成器：LLM 只负责「把自然语言诉求翻译成结构化计划」，
 * 工具白名单校验、归属校验、政策评估全部由确定性代码完成。
 */
@Component
public class PlanGenerator {

    private static final Logger log = LoggerFactory.getLogger(PlanGenerator.class);
    static final ObjectMapper MAPPER = new ObjectMapper();

    /** 写工具白名单：LLM 输出中出现的工具名不在名单内即整份 Plan 拒绝 */
    static final Set<String> WRITE_TOOLS = Set.of("cancelOrder", "refundOrder", "exchangeOrder");

    public record GeneratedPlan(String summary, String orderNo, List<Step> steps) {
        public record Step(String tool, String reason) {}
    }

    public static class PlanParseException extends RuntimeException {
        public PlanParseException(String msg) { super(msg); }
    }

    private static final String SYSTEM = """
            你是售后操作规划器。用户已提出写操作诉求（取消/退款/换货）。
            根据对话与订单信息，输出一份执行计划 JSON，格式：
            {"summary":"给用户看的一句话计划说明","orderNo":"订单号","steps":[{"tool":"cancelOrder|refundOrder|exchangeOrder","reason":"操作原因"}]}
            规则：
            1. 只能使用 cancelOrder、refundOrder、exchangeOrder 三个工具
            2. 一次计划通常只有 1 个步骤；不要虚构订单号
            3. 只输出 JSON，不要输出任何其他文字
            4. 工具选择语义（务必依据订单当前状态）：
               - 订单尚未发货（PAID）时，用户表达"不要了/退掉/取消/退款"一律用 cancelOrder（取消即原路退款）
               - 订单已收货（DELIVERED）后，退款用 refundOrder、换货用 exchangeOrder
            5. 若订单状态与用户诉求矛盾（如已收货却要求取消），仍按实际诉求输出工具，政策校验由系统完成
            """;

    private final LlmPort llmPort;

    public PlanGenerator(LlmPort llmPort) {
        this.llmPort = llmPort;
    }

    public GeneratedPlan generate(String userMessage, List<Message> history, String orderContextJson) {
        String prompt = "用户诉求: " + userMessage + "\n相关订单信息: " + orderContextJson;
        String raw = llmPort.complete(SYSTEM, history, prompt);
        return parse(raw);
    }

    /** 纯函数，独立可测 */
    public static GeneratedPlan parse(String llmOutput) {
        try {
            String cleaned = extractBalancedJson(stripThinking(llmOutput));
            JsonNode root = MAPPER.readTree(cleaned);
            String summary = root.path("summary").asText("").trim();
            String orderNo = root.path("orderNo").asText("").trim();
            if (summary.isEmpty() || orderNo.isEmpty()) {
                throw new PlanParseException("summary 或 orderNo 缺失");
            }
            JsonNode steps = root.path("steps");
            if (!steps.isArray() || steps.isEmpty()) {
                throw new PlanParseException("steps 为空");
            }
            List<GeneratedPlan.Step> parsed = new ArrayList<>();
            for (JsonNode s : steps) {
                String tool = s.path("tool").asText("").trim();
                if (!WRITE_TOOLS.contains(tool)) {
                    throw new PlanParseException("非法工具: " + tool + "（白名单: " + WRITE_TOOLS + "）");
                }
                String reason = s.path("reason").asText("").trim();
                if (reason.isEmpty()) {
                    throw new PlanParseException("步骤缺少 reason");
                }
                parsed.add(new GeneratedPlan.Step(tool, reason));
            }
            return new GeneratedPlan(summary, orderNo, parsed);
        } catch (PlanParseException e) {
            throw e;
        } catch (Exception e) {
            throw new PlanParseException("无法解析 Plan JSON: " + e.getMessage());
        }
    }

    private static String stripCodeFence(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("```")) {
            t = t.replaceFirst("^```[a-zA-Z]*\\n?", "").replaceFirst("```$", "").trim();
        }
        return t;
    }

    /**
     * 去除思考标签（MiniMax-M3 / DeepSeek-R1 等模型的 interleaved thinking）。
     * 覆盖已闭合 <think>...</think> 与未闭合（截断输出）两种情况。
     */
    static String stripThinking(String s) {
        if (s == null) return "";
        String t = s.replaceAll("(?s)<think>.*?</think>", "")
                .replaceAll("(?s)<thinking>.*?</thinking>", "")
                .replaceAll("(?s)<reasoning>.*?</reasoning>", "");
        // 未闭合的思考标签：从开标签到结尾全部视为思考内容
        t = t.replaceAll("(?s)<think>.*$", "").replaceAll("(?s)<thinking>.*$", "");
        return t.trim();
    }

    /** 从任意文本中提取第一个大括号平衡的 JSON 块（跳过字符串字面量中的括号） */
    static String extractBalancedJson(String s) {
        if (s == null || s.isEmpty()) return "";
        String t = stripCodeFence(s);
        int start = t.indexOf('{');
        if (start < 0) return t;
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int i = start; i < t.length(); i++) {
            char c = t.charAt(i);
            if (esc) { esc = false; continue; }
            if (c == '\\') { esc = true; continue; }
            if (c == '"') { inStr = !inStr; continue; }
            if (inStr) continue;
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return t.substring(start, i + 1);
            }
        }
        return t;
    }

    static String argsJsonFor(GeneratedPlan.Step step, String userId) {
        try {
            return MAPPER.writeValueAsString(java.util.Map.of(
                    "orderNo", "", "userId", userId, "reason", step.reason(), "tool", step.tool()));
        } catch (Exception e) {
            return "{}";
        }
    }

    static PlanStepEntity toStepEntity(Long planId, int seq, GeneratedPlan.Step step) {
        PlanStepEntity e = new PlanStepEntity();
        e.planId = planId;
        e.seq = seq;
        e.toolName = step.tool();
        e.argsJson = "{}";
        e.riskLevel = "WRITE";
        e.status = "PENDING";
        e.attempt = 0;
        return e;
    }
}
