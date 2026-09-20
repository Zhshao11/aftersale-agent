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

            用户用商品描述指代订单时（如"我最近买的那个咖啡机"、"我上次买的蓝牙耳机"、
            "我一个月前买的那个台灯"），处理顺序是：
            1. 先调用 listMyOrders 定位订单。keyword 填你从用户话里提取到的商品名片段
               （"咖啡机"→keyword="咖啡机"；只说了"那个耳机"→keyword="耳机"；
               完全没提商品名→keyword 留空，直接看最近的订单）。
            2. 从返回的 orders[] 里，按商品名、下单时间、状态的匹配度判断用户指的是哪一单。
               "最近买的"看 createdAt 最大者；"一个月前"看日期最接近的那些。
            3. 定位到订单后，再用 getOrder / getLogistics 查详情。
            4. 如果候选只有一单且明显吻合，直接用它回答；
               如果有多个都可能，简要列出让用户选，而不是自己猜一个。

            硬性要求：
            1. 禁止编造任何订单号、金额、时间。工具没有返回的信息，就如实说没有查到。
            2. 禁止在没有订单号时反问用户"请提供订单号"——先用 listMyOrders 自己找。
            3. 同一个工具、同样的参数不要重复调用；结果已经在上下文里了，直接用它作答。
            4. 工具查询失败时如实转述原因（如订单不存在、无权查询）。
            5. listMyOrders 返回的 note 若说明"关键词未匹配到、已返回最近订单"，要如实告知
               用户没找到他说的那件商品，并把最近的订单列出来让他确认。
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
