package com.trading.signal.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 把 Agent 的工具定义转成 OpenAI function calling 格式的 JSON。
 * 
 * 现在只有一个工具：switchStrategy。
 * 如果后续要加工具，在 build() 里追加就行。
 */
public class ToolDefinitionBuilder {

    private final ObjectMapper mapper;

    public ToolDefinitionBuilder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 返回 tools 数组节点，直接放进请求体的 "tools" 字段。
     */
    public ArrayNode build() {
        ArrayNode tools = mapper.createArrayNode();
        tools.add(buildSwitchStrategyTool());
        return tools;
    }

    // ── 工具定义 ──────────────────────────────────────────────────────────

    private ObjectNode buildSwitchStrategyTool() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ObjectNode function = tool.putObject("function");
        function.put("name", "switchStrategy");
        function.put("description",
                "切换当前交易策略。当市场状态发生明显变化时调用此工具。" +
                "如果不确定，或市场状态不明确，不要调用。");

        // parameters schema
        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");

        ObjectNode properties = parameters.putObject("properties");

        // strategyName 参数
        ObjectNode strategyName = properties.putObject("strategyName");
        strategyName.put("type", "string");
        strategyName.put("description", "要切换到的策略名称");
        ArrayNode enumValues = strategyName.putArray("enum");
        enumValues.add("aggressive");
        enumValues.add("mean-reversion");

        // reason 参数
        ObjectNode reason = properties.putObject("reason");
        reason.put("type", "string");
        reason.put("description", "切换原因，说明当前市场状态和切换理由");

        ArrayNode required = parameters.putArray("required");
        required.add("strategyName");
        required.add("reason");

        return tool;
    }
}
