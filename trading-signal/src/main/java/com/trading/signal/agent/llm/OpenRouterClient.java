package com.trading.signal.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trading.signal.config.TradingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * OpenRouter API 客户端。
 * 
 * 封装了对 OpenRouter /chat/completions 接口的调用，
 * 支持 OpenAI 兼容的 function calling（tools 参数）。
 * 
 * 复用项目现有的代理配置（trading.proxy.*），
 * 和 OkxClient 保持一致的 HttpClient 初始化方式。
 */
@Component
public class OpenRouterClient {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterClient.class);
    private static final String OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final TradingProperties properties;
    private final ToolDefinitionBuilder toolDefinitionBuilder;

    public OpenRouterClient(ObjectMapper objectMapper, TradingProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.toolDefinitionBuilder = new ToolDefinitionBuilder(objectMapper);

        TradingProperties.Proxy proxyCfg = properties.getProxy();
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15));
        if (proxyCfg.isEnabled()) {
            builder.proxy(ProxySelector.of(
                    new InetSocketAddress(proxyCfg.getHost(), proxyCfg.getPort())));
        }
        this.httpClient = builder.build();
    }

    /**
     * 发送 chat 请求，返回模型决定调用的工具（如果有的话）。
     * 
     * 如果模型返回了 tool_calls，解析第一个并返回；
     * 如果模型只返回了文本（觉得不需要调用工具），返回 empty。
     * 
     * @param messages 对话上下文（system + user）
     * @return 模型决定调用的工具，empty 表示模型决定不操作
     * @throws Exception 网络错误或 API 返回非 200 时抛出
     */
    public Optional<ToolCallResult> chat(List<LlmMessage> messages) throws Exception {
        TradingProperties.Agent cfg = properties.getAgent();

        // 构造请求体
        String requestBody = buildRequestBody(cfg, messages);
        log.debug("[OpenRouterClient] 请求体: {}", requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OPENROUTER_URL))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + cfg.getApiKey())
                .header("HTTP-Referer", "https://github.com/trading-signal")
                .header("X-Title", "TradeCompiler")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        log.debug("[OpenRouterClient] 响应状态: {} 响应体: {}", response.statusCode(), response.body());

        if (response.statusCode() != 200) {
            throw new RuntimeException(
                    String.format("OpenRouter 返回非200: %d, body=%s",
                            response.statusCode(), response.body()));
        }

        return parseToolCall(response.body());
    }

    // ─────────────────────────────────────────────────────────────────────
    // 请求体构造
    // ─────────────────────────────────────────────────────────────────────

    private String buildRequestBody(TradingProperties.Agent cfg, List<LlmMessage> messages) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", cfg.getModel());
        body.put("temperature", cfg.getTemperature());
        body.put("max_tokens", cfg.getMaxTokens());
        body.put("tool_choice", "auto");

        // messages 数组
        ArrayNode msgsNode = body.putArray("messages");
        for (LlmMessage msg : messages) {
            ObjectNode m = msgsNode.addObject();
            m.put("role", msg.role());
            m.put("content", msg.content());
        }

        // tools 数组（来自 ToolDefinitionBuilder）
        body.set("tools", toolDefinitionBuilder.build());

        return objectMapper.writeValueAsString(body);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 响应解析
    // ─────────────────────────────────────────────────────────────────────

    private Optional<ToolCallResult> parseToolCall(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);

        // 取 choices[0].message
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            log.warn("[OpenRouterClient] 响应中 choices 为空");
            return Optional.empty();
        }

        JsonNode message = choices.get(0).path("message");

        // 检查是否有 tool_calls
        JsonNode toolCalls = message.path("tool_calls");
        if (!toolCalls.isArray() || toolCalls.isEmpty()) {
            // 模型没有调用工具，只返回了文本
            String content = message.path("content").asText("");
            log.info("[OpenRouterClient] 模型决定不调用工具，回复: {}", content);
            return Optional.empty();
        }

        // 取第一个 tool_call
        JsonNode firstCall = toolCalls.get(0);
        String toolName = firstCall.path("function").path("name").asText();
        String argumentsJson = firstCall.path("function").path("arguments").asText();

        log.info("[OpenRouterClient] 模型调用工具: {} 参数: {}", toolName, argumentsJson);

        // 解析 arguments JSON 字符串为 Map<String, String>
        Map<String, String> args = parseArguments(argumentsJson);
        return Optional.of(new ToolCallResult(toolName, args));
    }

    private Map<String, String> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }

        String json = argumentsJson.trim();

        // 先尝试直接解析
        if (json.endsWith("}")) {
            Map<String, String> result = tryParseJson(json);
            if (result != null) return result;
        }

        // 模型返回的 arguments 偶尔会被 max_tokens 截断，末尾缺 }。
        // 依次尝试几种常见的修复方案：
        // 1. 最后是值的闭合引号："value"  → 补 }
        // 2. 最后是不完整的值字符串       → 补 "}
        for (String candidate : new String[]{json + "}", json + "\"}"}) {
            Map<String, String> result = tryParseJson(candidate);
            if (result != null) {
                log.warn("[OpenRouterClient] arguments JSON 不完整，修复为: {}", candidate);
                return result;
            }
        }

        // 所有 JSON 修复方案都失败，退化为正则提取 strategyName
        log.warn("[OpenRouterClient] arguments JSON 解析全部失败，使用正则提取: {}", argumentsJson);
        Map<String, String> fallback = new HashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"strategyName\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(argumentsJson);
        if (m.find()) {
            fallback.put("strategyName", m.group(1));
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> tryParseJson(String json) {
        try {
            Map<?, ?> raw = objectMapper.readValue(json, Map.class);
            Map<String, String> result = new HashMap<>();
            raw.forEach((k, v) -> {
                if (v != null) result.put(String.valueOf(k), String.valueOf(v));
            });
            return result;
        } catch (Exception e) {
            return null;
        }
    }
}
