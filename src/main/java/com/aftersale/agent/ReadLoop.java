package com.aftersale.agent;

import com.aftersale.config.AgentProps;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 只读路径的自研 ReAct 循环。
 *
 * 为什么要自己写这个循环，而不是继续用 Spring AI 的 {@code ChatClient.tools(...).call()}：
 * 框架内部循环里，步数、token、超时、重复动作都不在项目控制面内——"控制环自研"这个论点
 * 原先只覆盖写路径，只读路径是把控制权交出去的。代价是四件套（max_steps / token 预算 /
 * 墙钟上限 / 停止条件）一个都设不了，而这正是"玩具感"的判定项。
 *
 * 本类用 {@code internalToolExecutionEnabled(false)} 让框架只做"一次"调用，
 * 循环、边界、去重、截断、退避全部在下面这段代码里，因此每一项都可测、可观测、可消融。
 *
 * 与写路径的关键区别：只读操作没有副作用，所以模型调用超时可以安全重试；
 * 写路径的 TIMEOUT_UNKNOWN 必须禁止盲目重试、转对账（见 {@code Executor}）。
 * 两者用不同策略不是不一致，是因为风险面不同。
 */
@Service
public class ReadLoop {

    private static final Logger log = LoggerFactory.getLogger(ReadLoop.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String TERM_ANSWERED = "ANSWERED";
    public static final String TERM_MAX_STEPS = "MAX_STEPS";
    public static final String TERM_WALL_CLOCK = "WALL_CLOCK";
    public static final String TERM_TOKEN_BUDGET = "TOKEN_BUDGET";
    public static final String TERM_REPEATED_ACTION = "REPEATED_ACTION";
    public static final String TERM_LLM_ERROR = "LLM_ERROR";
    public static final String TERM_FABRICATION_BLOCKED = "FABRICATION_BLOCKED";

    /** 退避上限：超过这个值说明上游病得不轻，继续等不如尽快失败 */
    private static final long MAX_BACKOFF_MS = 8_000L;

    /**
     * 抵达边界且拿不到可信答案时的兜底话术。
     * 关键点：宁可明说"没查到"，也不给一个看起来合理的答案——后者在售后场景会直接导致
     * 用户按错误信息去操作（退错款、换错货）。
     */
    public static final String SAFE_FALLBACK =
            "抱歉，我没能从系统里确认到这条信息，所以不能给你答复。请确认订单号是否正确，或稍后再问一次。";

    private static final String DUPLICATE_NOTICE =
            "{\"ok\":false,\"status\":\"FAILED\",\"code\":\"DUPLICATE_SKIPPED\",\"message\":\""
                    + "这个工具调用本轮已经执行过了，结果就在上下文里，请直接使用已有结果作答，不要重复调用。\"}";

    private static final String UNKNOWN_TOOL_NOTICE =
            "{\"ok\":false,\"status\":\"FAILED\",\"code\":\"TOOL_NOT_FOUND\","
                    + "\"message\":\"不存在这个工具，请只使用已提供的工具。\"}";

    private static final String TOOL_ERROR_NOTICE =
            "{\"ok\":false,\"status\":\"FAILED\",\"code\":\"TOOL_ERROR\","
                    + "\"message\":\"工具执行异常，请如实告知用户暂时查不到，不要猜测结果。\"}";

    /**
     * 模型调用线程池：有界队列 + 拒绝策略。
     * 用 AbortPolicy 而不是 CallerRunsPolicy——后者会让任务在调用线程里同步跑完，
     * 超时就静默失效了。宁可明确拒绝并返回结构化错误，也不要一个"看起来设了超时其实没有"的实现。
     */
    private static final ThreadPoolExecutor CALL_EXECUTOR = new ThreadPoolExecutor(
            4, 16, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64),
            daemonThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy());

    private static final Map<Object, Map<String, ToolCallback>> CALLBACK_CACHE = new ConcurrentHashMap<>();

    private final LlmPort llmPort;
    private final TraceSink traceSink;
    private final FabricationGuard guard;
    private final AgentPropsProvider props;

    public ReadLoop(LlmPort llmPort, TraceSink traceSink, FabricationGuard guard, AgentPropsProvider props) {
        this.llmPort = llmPort;
        this.traceSink = traceSink;
        this.guard = guard;
        this.props = props;
    }

    /**
     * @param reply              给用户的答复（可能是答案，也可能是边界兜底话术）
     * @param termination        终止原因，见 TERM_* 常量
     * @param degraded           是否降级（true 表示没拿到可信答案，用户看到的是兜底话术）
     * @param unsupportedOrderNos 被闸门拦下的无出处订单号，便于排查
     */
    public record ReadOutcome(String reply, String traceId, String termination, int steps,
                              int promptTokens, int completionTokens, long elapsedMs,
                              List<String> unsupportedOrderNos, boolean degraded) {
    }

    public ReadOutcome run(String userId, Long conversationId, String systemPrompt,
                           List<Message> history, String userMessage, Object toolBundle) {
        AgentProps.Read cfg = props.read();
        String traceId = newTraceId();
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + cfg.wallClockMs();

        Map<String, ToolCallback> callbacks = callbacksOf(toolBundle);
        ToolContext toolContext = new ToolContext(Map.of("userId", userId));

        List<Message> messages = new ArrayList<>();
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(new UserMessage(userMessage));

        // 事实来源：用户原话 + 每一个工具返回。反幻觉闸门的判定基准。
        Set<String> knownFacts = new LinkedHashSet<>(guard.extract(userMessage));
        // 已执行过的 (工具名 + 参数) 集合，用于识别重复动作
        Set<String> seenCalls = new LinkedHashSet<>();

        int stepsUsed = 0;
        int promptTokens = 0;
        int completionTokens = 0;
        int duplicated = 0;
        String answer = null;
        String termination;

        while (true) {
            if (stepsUsed >= cfg.maxSteps()) {
                termination = TERM_MAX_STEPS;
                break;
            }
            if (System.currentTimeMillis() >= deadline) {
                termination = TERM_WALL_CLOCK;
                break;
            }
            if (promptTokens + completionTokens >= cfg.tokenBudget()) {
                termination = TERM_TOKEN_BUDGET;
                break;
            }

            stepsUsed++;
            long stepStart = System.currentTimeMillis();
            ModelCall call = callModel(cfg, systemPrompt, messages, toolBundle, toolContext,
                    deadline, traceId, conversationId, userId, stepsUsed);
            promptTokens += call.promptTokens();
            completionTokens += call.completionTokens();

            trace(new AgentStep(traceId, conversationId, userId, AgentStep.NODE_MODEL, stepsUsed,
                    null, null, call.errorCode() == null ? "SUCCESS" : "FAILED", call.errorCode(),
                    call.note(), call.model(), call.promptTokens(), call.completionTokens(),
                    System.currentTimeMillis() - stepStart));

            if (call.errorCode() != null) {
                termination = TERM_LLM_ERROR;
                break;
            }

            if (call.toolCalls().isEmpty()) {
                answer = call.text();
                termination = TERM_ANSWERED;
                break;
            }

            // 必须原样回填带 toolCalls 的 assistant 消息，否则下一轮消息序列协议不合法
            messages.add(call.assistantMessage());

            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            for (AssistantMessage.ToolCall toolCall : call.toolCalls()) {
                long toolStart = System.currentTimeMillis();
                String digest = toolCall.name() + "|" + normalizeArgs(toolCall.arguments());

                if (!seenCalls.add(digest)) {
                    duplicated++;
                    trace(new AgentStep(traceId, conversationId, userId, AgentStep.NODE_TOOL, stepsUsed,
                            toolCall.name(), argsDigest(toolCall.arguments()), "DUPLICATE_SKIPPED",
                            "DUPLICATE_SKIPPED", "同参数调用本回合已执行过，未重复执行",
                            null, null, null, System.currentTimeMillis() - toolStart));
                    responses.add(new ToolResponseMessage.ToolResponse(
                            toolCall.id(), toolCall.name(), DUPLICATE_NOTICE));
                    continue;
                }

                ToolCallback callback = callbacks.get(toolCall.name());
                if (callback == null) {
                    trace(new AgentStep(traceId, conversationId, userId, AgentStep.NODE_TOOL, stepsUsed,
                            toolCall.name(), argsDigest(toolCall.arguments()), "FAILED", "TOOL_NOT_FOUND",
                            "模型请求了不存在的工具，已返回结构化错误", null, null, null,
                            System.currentTimeMillis() - toolStart));
                    responses.add(new ToolResponseMessage.ToolResponse(
                            toolCall.id(), toolCall.name(), UNKNOWN_TOOL_NOTICE));
                    continue;
                }

                String payload;
                String status;
                String errorCode = null;
                try {
                    String raw = callback.call(
                            toolCall.arguments() == null ? "{}" : toolCall.arguments(), toolContext);
                    payload = truncate(raw, cfg.toolOutputMaxChars());
                } catch (Exception e) {
                    payload = TOOL_ERROR_NOTICE;
                    status = "FAILED";
                    errorCode = "TOOL_ERROR";
                    log.warn("只读工具执行异常 traceId={} tool={} err={}",
                            traceId, toolCall.name(), e.toString());
                    knownFacts.addAll(guard.extract(payload));
                    trace(new AgentStep(traceId, conversationId, userId, AgentStep.NODE_TOOL, stepsUsed,
                            toolCall.name(), argsDigest(toolCall.arguments()), status, errorCode,
                            summarize(e.toString()), null, null, null,
                            System.currentTimeMillis() - toolStart));
                    responses.add(new ToolResponseMessage.ToolResponse(
                            toolCall.id(), toolCall.name(), payload));
                    continue;
                }
                status = "SUCCESS";
                knownFacts.addAll(guard.extract(payload));
                trace(new AgentStep(traceId, conversationId, userId, AgentStep.NODE_TOOL, stepsUsed,
                        toolCall.name(), argsDigest(toolCall.arguments()), status, errorCode,
                        summarize(payload), null, null, null, System.currentTimeMillis() - toolStart));
                responses.add(new ToolResponseMessage.ToolResponse(
                        toolCall.id(), toolCall.name(), payload));
            }

            messages.add(ToolResponseMessage.builder().responses(responses).build());

            if (duplicated >= cfg.maxRepeatedActions()) {
                termination = TERM_REPEATED_ACTION;
                break;
            }
        }

        long elapsed = System.currentTimeMillis() - startedAt;
        List<String> unsupported = List.of();
        boolean degraded = false;

        if (TERM_ANSWERED.equals(termination)) {
            if (cfg.fabricationGuardEnabled()) {
                Set<String> violations = guard.violations(answer == null ? "" : answer, knownFacts);
                if (!violations.isEmpty()) {
                    unsupported = List.copyOf(violations);
                    answer = SAFE_FALLBACK;
                    termination = TERM_FABRICATION_BLOCKED;
                    degraded = true;
                    log.warn("反幻觉闸门拦截 traceId={} 无出处订单号={}", traceId, unsupported);
                }
            }
        } else {
            degraded = true;
            answer = boundaryMessage(termination, stepsUsed, promptTokens + completionTokens);
        }

        trace(new AgentStep(traceId, conversationId, userId, AgentStep.NODE_TERMINAL, stepsUsed,
                null, null, termination, degraded ? "DEGRADED" : null,
                "steps=" + stepsUsed + " tokens=" + (promptTokens + completionTokens)
                        + " elapsedMs=" + elapsed
                        + (unsupported.isEmpty() ? "" : " unsupportedOrderNos=" + unsupported),
                null, promptTokens, completionTokens, elapsed));

        return new ReadOutcome(answer, traceId, termination, stepsUsed,
                promptTokens, completionTokens, elapsed, unsupported, degraded);
    }

    // ------------------------------------------------------------------ 模型调用

    /**
     * 单次模型调用 + 超时 + 限流/瞬态失败的退避重试。
     * 只读路径无副作用，所以超时可安全重试；写路径相反（见 Executor 的超时二分）。
     */
    private ModelCall callModel(AgentProps.Read cfg, String systemPrompt, List<Message> messages,
                                Object toolBundle, ToolContext toolContext, long deadline,
                                String traceId, Long conversationId, String userId, int stepIndex) {
        int attempt = 0;
        while (true) {
            attempt++;
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return ModelCall.error("WALL_CLOCK_EXHAUSTED", "墙钟预算耗尽，未再发起模型调用");
            }
            long callTimeout = Math.min(cfg.callTimeoutMs(), remaining);
            try {
                return ModelCall.from(callWithTimeout(
                        () -> llmPort.callOnce(systemPrompt, messages, toolBundle, toolContext.getContext()),
                        callTimeout));
            } catch (RejectedExecutionException e) {
                return ModelCall.error("EXECUTOR_SATURATED", "模型调用线程池饱和，拒绝本次调用");
            } catch (Exception e) {
                boolean rateLimited = isRateLimited(e);
                boolean retryable = rateLimited || isTransient(e);
                if (!retryable || attempt > cfg.retryMax()) {
                    log.warn("模型调用失败且不再重试 traceId={} attempt={} rateLimited={} err={}",
                            traceId, attempt, rateLimited, e.toString());
                    return ModelCall.error(rateLimited ? "LLM_RATE_LIMITED_EXHAUSTED" : "LLM_CALL_FAILED",
                            (rateLimited ? "限流重试耗尽：" : "调用失败：") + e);
                }
                long backoff = backoffMs(cfg.retryBaseBackoffMs(), attempt, rateLimited);
                if (System.currentTimeMillis() + backoff >= deadline) {
                    return ModelCall.error("BACKOFF_EXCEEDS_DEADLINE",
                            "重试退避将超出墙钟预算，放弃重试：" + e);
                }
                log.info("模型调用失败，{}ms 后第 {} 次重试 traceId={} rateLimited={} err={}",
                        backoff, attempt + 1, traceId, rateLimited, e.toString());
                trace(new AgentStep(traceId, conversationId, userId, AgentStep.NODE_MODEL, stepIndex,
                        null, null, "RETRYING",
                        rateLimited ? "LLM_RATE_LIMITED" : "LLM_TRANSIENT_ERROR",
                        "第 " + attempt + " 次失败，退避 " + backoff + "ms 后重试：" + e,
                        null, null, null, null));
                sleep(backoff);
            }
        }
    }

    private static ChatResponse callWithTimeout(Callable<ChatResponse> task, long timeoutMs)
            throws Exception {
        Future<ChatResponse> future = CALL_EXECUTOR.submit(task);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new LlmTimeoutException("模型调用超过 " + timeoutMs + "ms 未返回");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new LlmTimeoutException("模型调用被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new RuntimeException(cause);
        }
    }

    /** 限额耗尽属于可重试的瞬态错误；用于区分"该退避重试"和"该直接失败" */
    static class LlmTimeoutException extends Exception {
        LlmTimeoutException(String message) {
            super(message);
        }
    }

    private static boolean isRateLimited(Throwable e) {
        return messageContains(e, "429", "rate limit", "rate_limit", "too many requests",
                "quota", "throttl");
    }

    private static boolean isTransient(Throwable e) {
        if (e instanceof LlmTimeoutException) {
            return true;
        }
        return messageContains(e, "timeout", "timed out", "connection", "connect",
                "502", "503", "504", "reset by peer", "temporarily");
    }

    /**
     * 沿异常链找关键字。
     * 这是启发式判断而不是结构化判断：OpenAI 兼容端点的错误体各家不同，
     * 想做结构化识别得先确定你在对接哪家的错误 schema，标 [待核实: 各供应商错误码规范]。
     */
    private static boolean messageContains(Throwable e, String... needles) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < 8; depth++, t = t.getCause()) {
            String message = t.getMessage();
            if (message == null) {
                continue;
            }
            String lower = message.toLowerCase(Locale.ROOT);
            for (String needle : needles) {
                if (lower.contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 指数退避 + 抖动；限流场景退避加倍，避免多个请求对齐后再次撞墙 */
    private static long backoffMs(long baseMs, int attempt, boolean rateLimited) {
        long base = Math.max(1L, baseMs);
        long exponential = base * (1L << Math.min(attempt - 1, 5));
        if (rateLimited) {
            exponential = exponential * 2;
        }
        long jitter = ThreadLocalRandom.current().nextLong(base + 1);
        return Math.min(exponential + jitter, MAX_BACKOFF_MS);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ 工具结果处理

    /**
     * 工具输出截断。
     * 直接 substring 会把 JSON 切坏，模型收到半截 JSON 更容易编——所以截断后重新包一层
     * 合法 JSON，明确标记 truncated，让模型知道这是残缺预览而不是全部事实。
     */
    static String truncate(String raw, int maxChars) {
        if (raw == null) {
            return "{\"ok\":false,\"status\":\"FAILED\",\"code\":\"EMPTY_RESULT\","
                    + "\"message\":\"工具返回空结果\"}";
        }
        if (raw.length() <= maxChars) {
            return raw;
        }
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("truncated", true);
        wrapper.put("originalChars", raw.length());
        wrapper.put("preview", raw.substring(0, Math.max(0, maxChars)));
        try {
            return MAPPER.writeValueAsString(wrapper);
        } catch (Exception e) {
            return "{\"truncated\":true,\"originalChars\":" + raw.length() + "}";
        }
    }

    private static String normalizeArgs(String args) {
        return args == null ? "" : args.replaceAll("\\s+", "");
    }

    private static String argsDigest(String args) {
        String compact = normalizeArgs(args);
        return compact.length() <= 200 ? compact : compact.substring(0, 200) + "...";
    }

    private static String summarize(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 1_000 ? text : text.substring(0, 1_000) + "...";
    }

    private static String boundaryMessage(String termination, int steps, int tokens) {
        return switch (termination) {
            case TERM_MAX_STEPS -> "这个问题需要更多步骤才能查清（已到 " + steps
                    + " 步上限），我没有拿到足够信息，先不下结论。请把问题拆细一点，或直接告诉我订单号。";
            case TERM_WALL_CLOCK -> "这次查询超时了，我没有拿到完整结果，先不下结论。请稍后再试一次。";
            case TERM_TOKEN_BUDGET -> "这次查询消耗的上下文超出了预算（约 " + tokens
                    + " tokens），我主动停下来避免继续消耗。请把问题问得更具体一些。";
            case TERM_REPEATED_ACTION -> "我发现自己陷入了重复查询，已主动停下。请换个说法，"
                    + "或提供更具体的订单号。";
            default -> SAFE_FALLBACK;
        };
    }

    // ------------------------------------------------------------------ 工具注册表

    /**
     * 把工具 bean 反射成 name -> callback 映射并缓存。
     * bundle 是单例，反射结果不会变，没必要每次请求重新扫一遍注解。
     */
    private static Map<String, ToolCallback> callbacksOf(Object toolBundle) {
        return CALLBACK_CACHE.computeIfAbsent(toolBundle, bundle -> {
            Map<String, ToolCallback> byName = new LinkedHashMap<>();
            ToolCallback[] found = ToolCallbacks.from(bundle);
            if (found == null || found.length == 0) {
                throw new IllegalArgumentException(
                        "工具集为空：" + bundle.getClass().getName() + " 上找不到 @Tool 方法");
            }
            for (ToolCallback callback : found) {
                String full = callback.getToolDefinition().name();
                byName.put(full, callback);
                // 模型可能只给短名（不带类名前缀），两种都登记，避免误判成"工具不存在"
                int dot = full.lastIndexOf('.');
                if (dot > 0) {
                    byName.putIfAbsent(full.substring(dot + 1), callback);
                }
            }
            log.info("只读工具注册完成 size={} names={}", byName.size(), byName.keySet());
            return Map.copyOf(byName);
        });
    }

    private void trace(AgentStep step) {
        traceSink.record(step);
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "read-loop-llm-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** 供线程池队列深度观测用，排障时看是否长期饱和 */
    public static int executorQueueDepth() {
        return CALL_EXECUTOR.getQueue().size();
    }

    /** 一次模型调用的归一化结果：成功或失败都收敛成这一个结构，避免调用点到处判空 */
    private record ModelCall(AssistantMessage assistantMessage,
                             List<AssistantMessage.ToolCall> toolCalls,
                             String text, String model,
                             int promptTokens, int completionTokens,
                             String errorCode, String note) {

        static ModelCall from(ChatResponse response) {
            if (response == null || response.getResult() == null
                    || response.getResult().getOutput() == null) {
                return error("EMPTY_RESPONSE", "模型返回空响应");
            }
            AssistantMessage output = response.getResult().getOutput();
            List<AssistantMessage.ToolCall> calls = output.getToolCalls() == null
                    ? List.of() : List.copyOf(output.getToolCalls());
            String text = output.getText() == null ? "" : output.getText().trim();
            if (calls.isEmpty() && text.isEmpty()) {
                return error("EMPTY_ANSWER", "模型既没有返回工具调用也没有返回文本");
            }
            String model = null;
            int promptTokens = 0;
            int completionTokens = 0;
            ChatResponseMetadata metadata = response.getMetadata();
            if (metadata != null) {
                model = metadata.getModel();
                Usage usage = metadata.getUsage();
                if (usage != null) {
                    if (usage.getPromptTokens() != null) {
                        promptTokens = usage.getPromptTokens();
                    }
                    if (usage.getCompletionTokens() != null) {
                        completionTokens = usage.getCompletionTokens();
                    }
                }
            }
            return new ModelCall(output, calls, text, model,
                    promptTokens, completionTokens, null, null);
        }

        static ModelCall error(String errorCode, String note) {
            return new ModelCall(null, List.of(), "", null, 0, 0, errorCode, note);
        }
    }

    /** 暴露给测试与排障：当前注册的工具名，避免反射结果被猜测 */
    public static java.util.Collection<String> registeredToolNames(Object toolBundle) {
        return callbacksOf(toolBundle).keySet();
    }
}
