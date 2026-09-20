package com.aftersale.agent;

/**
 * 轨迹落点。
 *
 * 抽成函数式接口的原因是可测性：ReadLoop 单测在无 Spring 容器、无数据库的环境下跑，
 * 传入 step -> {} 或 trace::add 即可，不需要为了记录轨迹而启动整个应用。
 */
@FunctionalInterface
public interface TraceSink {

    void record(AgentStep step);
}
