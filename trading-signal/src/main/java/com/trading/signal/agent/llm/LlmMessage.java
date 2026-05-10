package com.trading.signal.agent.llm;

/**
 * 发给 LLM 的单条消息。
 * 
 * role 取值：system / user / assistant / tool
 * content 为消息正文，tool 角色时 content 是工具执行结果。
 */
public record LlmMessage(String role, String content) {

    public static LlmMessage system(String content) {
        return new LlmMessage("system", content);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage("user", content);
    }
}
