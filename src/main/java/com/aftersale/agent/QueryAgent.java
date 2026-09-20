package com.aftersale.agent;

import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 查询路径：只读工具的 ReAct。
 *
 * 循环本身由 {@link ReadLoop} 实现（步数、token 预算、墙钟、超时、去重、截断都在项目控制面内），
 * 这里只负责两件事：声明工具集、写系统提示词。
 *
 * 安全不变式：只挂载 ReadToolBundle；userId 经 ToolContext 注入，LLM 无法伪造调用者身份；
 * 写工具物理隔离在 tools.write 包，从不注册到任何工具集。
 */
@Component
public class QueryAgent {

    private static final String SYSTEM = """
            你是电商售后客服助手。当前用户身份由系统注入（userId），你无法也不需要向用户询问身份。
            回答用户关于订单、物流、售后政策的问题时，主动调用工具查询真实数据。
            硬性要求：
            1. 禁止编造任何订单号、金额、时间。工具没有返回的信息，就如实说没有查到。
            2. 同一个工具、同样的参数不要重复调用；结果已经在上下文里了，直接用它作答。
            3. 工具查询失败时如实转述原因（如订单不存在、无权查询）。
            金额使用美元。回答保持简洁中文。
            """;

    private final ReadLoop readLoop;
    private final ReadToolBundle readToolBundle;

    public QueryAgent(ReadLoop readLoop, ReadToolBundle readToolBundle) {
        this.readLoop = readLoop;
        this.readToolBundle = readToolBundle;
    }

    public ReadLoop.ReadOutcome answer(String userId, Long conversationId,
                                       String userMessage, List<Message> history) {
        return readLoop.run(userId, conversationId, SYSTEM, history, userMessage, readToolBundle);
    }
}
