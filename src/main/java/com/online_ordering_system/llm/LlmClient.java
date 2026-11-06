package com.online_ordering_system.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.online_ordering_system.config.LlmProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 客户端（OpenAI 兼容协议，/v1/chat/completions）。
 * 不绑定任何私有 SDK，支持 function calling，可切换百炼/DeepSeek/通义等。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LlmClient {

    private final LlmProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * 一次对话补全。返回 LLM 的原始响应（可能是文本或 tool_calls）。
     *
     * @param messages 消息列表，每项含 role + content（+ tool_calls 可选）
     * @param tools    可用工具定义（OpenAI function 格式），可为 null
     */
    public LlmResponse chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", properties.getModel());
        body.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            // 仅在有工具时才设置 tool_choice，否则百炼会报 "tools must be set"
            body.put("tool_choice", "auto");
        }

        try {
            String payload = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getBaseUrl() + "/chat/completions"))
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.error("LLM 调用失败：HTTP {}，body={}", response.statusCode(), response.body());
                throw new RuntimeException("LLM 调用失败：HTTP " + response.statusCode());
            }

            return parseResponse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("LLM 调用被中断", e);
        } catch (Exception e) {
            throw new RuntimeException("LLM 调用异常：" + e.getMessage(), e);
        }
    }

    /**
     * 解析 OpenAI 兼容响应为内部结构。
     */
    private LlmResponse parseResponse(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode choices = root.get("choices");
        int tokenUsed = root.path("usage").path("total_tokens").asInt(0);

        if (choices == null || choices.size() == 0) {
            return new LlmResponse("", new ArrayList<>(), "tool_calls".equals(root.path("finish_reason").asText()), tokenUsed);
        }

        JsonNode message = choices.get(0).get("message");
        String content = message.path("content").asText("");
        boolean isToolCall = "tool_calls".equals(choices.get(0).path("finish_reason").asText());

        List<ToolCall> toolCalls = new ArrayList<>();
        JsonNode toolCallsNode = message.get("tool_calls");
        if (toolCallsNode != null && toolCallsNode.isArray()) {
            for (JsonNode tc : toolCallsNode) {
                JsonNode fn = tc.get("function");
                String name = fn.path("name").asText();
                String arguments = fn.path("arguments").asText("{}");
                toolCalls.add(new ToolCall(name, arguments));
            }
        }

        return new LlmResponse(content, toolCalls, isToolCall, tokenUsed);
    }

    /** 内部响应结构 */
    public static class LlmResponse {
        public final String content;
        public final List<ToolCall> toolCalls;
        public final boolean isToolCall;
        public final int tokenUsed;

        public LlmResponse(String content, List<ToolCall> toolCalls, boolean isToolCall, int tokenUsed) {
            this.content = content;
            this.toolCalls = toolCalls;
            this.isToolCall = isToolCall;
            this.tokenUsed = tokenUsed;
        }
    }

    /** 单个工具调用 */
    public static class ToolCall {
        public final String name;
        public final String arguments;

        public ToolCall(String name, String arguments) {
            this.name = name;
            this.arguments = arguments;
        }
    }
}
