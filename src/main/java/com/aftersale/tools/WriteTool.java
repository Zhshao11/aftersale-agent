package com.aftersale.tools;

/**
 * 写工具标记接口：只有 Executor（经用户确认的链路）允许调用实现此接口的工具。
 * LLM 的工具白名单在装配时按「未实现 WriteTool」过滤——物理隔离，非提示词约束。
 */
public interface WriteTool {
}
