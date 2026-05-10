package com.trading.signal.agent.llm;

import java.util.Map;

/**
 * LLM 返回的 tool_call 解析结果。
 * 
 * 当模型决定调用工具时，响应中的 tool_calls[0] 会被解析成这个对象。
 * 如果模型没有调用任何工具（只返回文本），则 OpenRouterClient 返回 empty Optional。
 * 
 * - toolName: 模型要调用的工具名，如 "switchStrategy"
 * - arguments: 工具参数，key=参数名, value=参数值
 */
public record ToolCallResult(
        String toolName,
        Map<String, String> arguments
) {

    /** 取某个参数值，不存在时返回 null */
    public String arg(String key) {
        return arguments.get(key);
    }
}
