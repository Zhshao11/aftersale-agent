package com.aftersale;

import com.aftersale.agent.AgentPropsProvider;
import com.aftersale.agent.AgentStep;
import com.aftersale.agent.FabricationGuard;
import com.aftersale.agent.LlmPort;
import com.aftersale.agent.ReadLoop;
import com.aftersale.agent.TraceSink;
import com.aftersale.config.AgentProps;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReadLoop 纯单元测试：不依赖 Spring 容器、数据库、网络。
 *
 * 这六个用例覆盖的都是"把循环交给框架就测不到"的边界：
 * 步数上限、重复动作、主动终止、工具输出截断、编造拦截、幻觉工具名。
 * 这正是自研控制环换来的可测性——不是代码更漂亮，是这些断言原先根本写不出来。
 */
class ReadLoopTest {

    private static final String U1 = "U001";

    // ------------------------------------------------------------------ 用例

    @Test
    void 到达步数上限时主动终止并给出诚实的兜底话术() {
        ScriptedLlm llm = new ScriptedLlm();
        for (int i = 0; i < 10; i++) {
            // 每轮参数不同，避免触发重复动作终止，确保测的是步数上限本身
            llm.script.add(toolCall("echo", "{\"orderNo\":\"ORD2026090100" + i + "\"}", "call-" + i));
        }
        List<AgentStep> trace = new ArrayList<>();
        ReadLoop loop = newLoop(llm, trace, read(3, 12_000, 4_000, 2, true));

        ReadLoop.ReadOutcome out = loop.run(U1, 1L, "sys", List.of(), "帮我把所有订单都查一遍", new FakeTools());

        assertEquals(ReadLoop.TERM_MAX_STEPS, out.termination());
        assertEquals(3, out.steps(), "到达上限后不应再多调一次模型");
        assertEquals(3, llm.callCount);
        assertTrue(out.degraded(), "未拿到可信答案必须标记为降级");
        assertFalse(out.reply().isBlank());
        assertTrue(trace.stream().anyMatch(s -> AgentStep.NODE_TERMINAL.equals(s.node())));
    }

    @Test
    void 同参数重复调用被识别且工具只真实执行一次() {
        ScriptedLlm llm = new ScriptedLlm();
        FakeTools tools = new FakeTools();
        tools.payload = "{\"ok\":true,\"orderNo\":\"ORD20260901001\",\"status\":\"DELIVERED\"}";
        llm.script.add(toolCall("echo", "{\"orderNo\":\"ORD20260901001\"}", "c1"));
        llm.script.add(toolCall("echo", "{\"orderNo\":\"ORD20260901001\"}", "c2"));
        llm.script.add(answer("订单 ORD20260901001 已送达。"));

        List<AgentStep> trace = new ArrayList<>();
        ReadLoop loop = newLoop(llm, trace, read(6, 12_000, 4_000, 2, true));

        ReadLoop.ReadOutcome out = loop.run(U1, 1L, "sys", List.of(), "我的订单到哪了", tools);

        assertEquals(1, tools.calls.get(), "重复调用不应再次打到工具");
        assertEquals(ReadLoop.TERM_ANSWERED, out.termination());
        assertTrue(trace.stream().anyMatch(s -> "DUPLICATE_SKIPPED".equals(s.status())));
        assertTrue(out.unsupportedOrderNos().isEmpty(), "订单号来自工具返回，不应被误判为编造");
        assertFalse(out.degraded());
    }

    @Test
    void 重复动作累计超限时主动终止而不是继续烧预算() {
        ScriptedLlm llm = new ScriptedLlm();
        FakeTools tools = new FakeTools();
        llm.script.add(toolCall("echo", "{\"orderNo\":\"ORD20260901001\"}", "c1"));
        llm.script.add(toolCall("echo", "{\"orderNo\":\"ORD20260901001\"}", "c2"));
        llm.script.add(toolCall("echo", "{\"orderNo\":\"ORD20260901001\"}", "c3"));

        // maxRepeatedActions = 1：出现 1 次重复即终止
        ReadLoop loop = newLoop(llm, new ArrayList<>(), read(10, 12_000, 4_000, 1, false));

        ReadLoop.ReadOutcome out = loop.run(U1, 1L, "sys", List.of(), "查订单", tools);

        assertEquals(ReadLoop.TERM_REPEATED_ACTION, out.termination());
        assertEquals(2, out.steps(), "第 2 步发现重复后应立刻停，不再进入第 3 步");
        assertTrue(out.degraded());
        assertEquals(1, tools.calls.get());
    }

    @Test
    void 答复里出现无事实来源的订单号时降级为兜底话术() {
        ScriptedLlm llm = new ScriptedLlm();
        FakeTools tools = new FakeTools();
        tools.payload = "{\"ok\":false,\"status\":\"FAILED\",\"code\":\"ORDER_NOT_FOUND\"}";
        // 工具明确说查不到，模型却"查到"了一个订单号——这就是要拦的东西
        llm.script.add(answer("你的订单 ORD99999999999 已于昨天送达。"));

        ReadLoop loop = newLoop(llm, new ArrayList<>(), read(6, 12_000, 4_000, 2, true));
        ReadLoop.ReadOutcome out = loop.run(U1, 1L, "sys", List.of(), "我的订单到哪了", tools);

        assertEquals(ReadLoop.TERM_FABRICATION_BLOCKED, out.termination());
        assertEquals(List.of("ORD99999999999"), out.unsupportedOrderNos());
        assertEquals(ReadLoop.SAFE_FALLBACK, out.reply(), "编造内容不允许出现在用户可见输出里");
        assertTrue(out.degraded());
    }

    @Test
    void 工具输出超长时被截断为合法JSON再回灌模型() {
        ScriptedLlm llm = new ScriptedLlm();
        FakeTools tools = new FakeTools();
        tools.payload = "{\"ok\":true,\"orderNo\":\"ORD20260901001\",\"blob\":\""
                + "x".repeat(50_000) + "\"}";
        llm.script.add(toolCall("echo", "{\"orderNo\":\"ORD20260901001\"}", "c1"));
        llm.script.add(answer("已查到你有一个订单。"));

        // toolOutputMaxChars = 500
        ReadLoop loop = newLoop(llm, new ArrayList<>(), read(6, 12_000, 500, 2, false));
        ReadLoop.ReadOutcome out = loop.run(U1, 1L, "sys", List.of(), "查订单", tools);

        assertEquals(ReadLoop.TERM_ANSWERED, out.termination());
        assertEquals(2, llm.seen.size());
        ToolResponseMessage toolMessage = llm.seen.get(1).stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("回灌上下文里找不到工具消息"));
        String data = toolMessage.getResponses().get(0).responseData();
        assertTrue(data.contains("\"truncated\":true"), "截断必须显式标记，否则模型会把残缺预览当全部事实");
        assertTrue(data.contains("\"originalChars\":"));
        assertTrue(data.length() < 1_000, "截断后长度应受控，实际 " + data.length());
    }

    @Test
    void 模型请求不存在的工具时返回结构化错误而不是崩溃() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.script.add(toolCall("noSuchTool", "{}", "c1"));
        llm.script.add(answer("抱歉，我没能查到相关信息。"));

        List<AgentStep> trace = new ArrayList<>();
        ReadLoop loop = newLoop(llm, trace, read(6, 12_000, 4_000, 2, true));
        ReadLoop.ReadOutcome out = loop.run(U1, 1L, "sys", List.of(), "查订单", new FakeTools());

        assertEquals(ReadLoop.TERM_ANSWERED, out.termination());
        assertTrue(trace.stream().anyMatch(s -> "TOOL_NOT_FOUND".equals(s.errorCode())));
    }

    @Test
    void 模型调用持续失败时在有限次数内结束且不吞掉错误() {
        ScriptedLlm llm = new ScriptedLlm();
        // 真实场景里 HTTP 故障会被框架包成运行时异常，原因挂在 cause 链上——
        // 这里刻意用同样的形状，验证异常链遍历确实找得到 "connection reset"
        llm.failWith = new org.springframework.web.client.ResourceAccessException(
                "I/O error on POST", new java.io.IOException("connection reset by peer"));

        ReadLoop loop = newLoop(llm, new ArrayList<>(), read(6, 12_000, 4_000, 2, true));
        ReadLoop.ReadOutcome out = loop.run(U1, 1L, "sys", List.of(), "查订单", new FakeTools());

        assertEquals(ReadLoop.TERM_LLM_ERROR, out.termination());
        assertTrue(out.degraded());
        // 首次 + 2 次重试 = 3 次，之后放弃；不允许无限重试
        assertEquals(3, llm.callCount, "重试次数必须收敛到 retryMax + 1");
    }

    @Test
    void 工具上下文注入仍然生效即模型无法伪造调用者身份() {
        ScriptedLlm llm = new ScriptedLlm();
        IdentityTools tools = new IdentityTools();
        llm.script.add(toolCall("whoAmI", "{\"orderNo\":\"ORD20260901001\"}", "c1"));
        llm.script.add(answer("OK"));

        ReadLoop loop = newLoop(llm, new ArrayList<>(), read(6, 12_000, 4_000, 2, true));
        loop.run(U1, 1L, "sys", List.of(), "查订单", tools);

        // 这是越权防护的构造性前提：接过框架循环之后，注入链路必须仍然通。
        // 一旦这里变红，ReadToolBundle 里 userId(ctx) 就会抛异常或注入空值，
        // 订单归属校验会从"构造性保证"退化成"看模型自觉"。
        assertEquals(U1, tools.seenUserId.get(),
                "userId 必须来自 ToolContext 注入，而不是模型提供的参数");
    }

    // ------------------------------------------------------------------ 测试夹具

    private static AgentProps.Read read(int maxSteps, int tokenBudget, int toolOutputMaxChars,
                                        int maxRepeatedActions, boolean guardEnabled) {
        return new AgentProps.Read(maxSteps, 30_000L, tokenBudget, 20_000L,
                2, 50L, toolOutputMaxChars, maxRepeatedActions, guardEnabled);
    }

    private static ReadLoop newLoop(LlmPort llm, List<AgentStep> collected, AgentProps.Read read) {
        AgentProps props = new AgentProps(50_000L, null,
                new AgentProps.Agent(false, false, false, false), read);
        TraceSink sink = collected::add;
        return new ReadLoop(llm, sink, new FabricationGuard(), new AgentPropsProvider(props));
    }

    private static ChatResponse toolCall(String name, String args, String id) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, args)))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static ChatResponse answer(String text) {
        return new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content(text).build())));
    }

    /** 按脚本依次返回的假 LLM；脚本用尽或配置了故障时抛错，用来验证重试与终止 */
    static class ScriptedLlm implements LlmPort {

        final Deque<ChatResponse> script = new ArrayDeque<>();
        final List<List<Message>> seen = new ArrayList<>();
        int callCount = 0;
        RuntimeException failWith;

        @Override
        public String complete(String systemPrompt, List<Message> history, String userMessage) {
            throw new UnsupportedOperationException("只读循环不走单轮补全");
        }

        @Override
        public String completeWithTools(String systemPrompt, List<Message> history, String userMessage,
                                        Object toolBundle, Map<String, Object> toolContext) {
            throw new UnsupportedOperationException("只读循环不依赖框架内部循环");
        }

        @Override
        public ChatResponse callOnce(String systemPrompt, List<Message> messages,
                                     Object toolBundle, Map<String, Object> toolContext) {
            callCount++;
            seen.add(List.copyOf(messages));
            if (failWith != null) {
                throw failWith;
            }
            ChatResponse next = script.poll();
            if (next == null) {
                throw new IllegalStateException("脚本用尽：第 " + callCount + " 次调用没有可返回的响应");
            }
            return next;
        }
    }

    /** 测试用只读工具集，用于验证去重、截断与真实调用次数 */
    static class FakeTools {

        final AtomicInteger calls = new AtomicInteger();
        String payload = "{\"ok\":true}";

        @Tool(description = "测试用只读工具：按订单号返回内容")
        public String echo(@ToolParam(description = "订单号") String orderNo) {
            calls.incrementAndGet();
            assertNotNull(orderNo);
            return payload;
        }
    }

    /** 形状与生产 ReadToolBundle 一致：带 ToolContext 参数，身份只能从注入拿 */
    static class IdentityTools {

        final java.util.concurrent.atomic.AtomicReference<String> seenUserId =
                new java.util.concurrent.atomic.AtomicReference<>();
        String payload = "{\"ok\":true,\"orderNo\":\"ORD20260901001\"}";

        @Tool(description = "测试用：验证调用者身份来自工具上下文注入")
        public String whoAmI(@ToolParam(description = "订单号") String orderNo,
                             org.springframework.ai.chat.model.ToolContext ctx) {
            seenUserId.set(String.valueOf(ctx.getContext().get("userId")));
            return payload;
        }
    }
}
