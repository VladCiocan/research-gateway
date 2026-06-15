package com.researchgateway.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.researchgateway.domain.Provider;
import com.researchgateway.llm.LlmTypes.ChatResult;
import com.researchgateway.llm.LlmTypes.ToolCall;
import com.researchgateway.llm.LlmTypes.ToolDef;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal OpenAI-compatible chat client — works against a custom vLLM endpoint
 * (its {@code /v1/chat/completions} API). Supports tool/function calling.
 */
@Component
public class LlmClient {

    private final ObjectMapper mapper = new ObjectMapper();

    public ChatResult chat(Provider provider, List<Map<String, Object>> messages, List<ToolDef> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", provider.getModel());
        body.put("messages", messages);
        body.put("max_tokens", provider.getMaxTokens());
        body.put("temperature", provider.getTemperature());
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", toOpenAiTools(tools));
            body.put("tool_choice", "auto");
        }

        JsonNode root = client(provider).post()
                .uri("/chat/completions")
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        return parse(root);
    }

    /** Lightweight connectivity check; returns null on success or an error message. */
    public String ping(Provider provider) {
        try {
            List<Map<String, Object>> messages = List.of(
                    Map.of("role", "user", "content", "ping"));
            chat(provider, messages, null);
            return null;
        } catch (Exception ex) {
            return ex.getMessage();
        }
    }

    private RestClient client(Provider provider) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(120).toMillis());
        RestClient.Builder b = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(stripTrailingSlash(provider.getBaseUrl()))
                .defaultHeader("Content-Type", "application/json");
        if (provider.getApiKey() != null && !provider.getApiKey().isBlank()) {
            b.defaultHeader("Authorization", "Bearer " + provider.getApiKey());
        }
        return b.build();
    }

    private List<Map<String, Object>> toOpenAiTools(List<ToolDef> tools) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolDef t : tools) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", t.name());
            fn.put("description", t.description() == null ? "" : t.description());
            fn.put("parameters", t.parameters() != null ? t.parameters()
                    : Map.of("type", "object", "properties", Map.of()));
            out.add(Map.of("type", "function", "function", fn));
        }
        return out;
    }

    private ChatResult parse(JsonNode root) {
        if (root == null) return new ChatResult("", List.of(), 0, 0);
        JsonNode choices = root.path("choices");
        String content = "";
        List<ToolCall> toolCalls = new ArrayList<>();
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode message = choices.get(0).path("message");
            content = message.path("content").asText("");
            JsonNode tc = message.path("tool_calls");
            if (tc.isArray()) {
                for (JsonNode call : tc) {
                    String id = call.path("id").asText("");
                    JsonNode fn = call.path("function");
                    String name = fn.path("name").asText("");
                    Map<String, Object> args = parseArgs(fn.path("arguments").asText("{}"));
                    toolCalls.add(new ToolCall(id, name, args));
                }
            }
        }
        JsonNode usage = root.path("usage");
        int prompt = usage.path("prompt_tokens").asInt(0);
        int completion = usage.path("completion_tokens").asInt(0);
        return new ChatResult(content, toolCalls, prompt, completion);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgs(String json) {
        try {
            return mapper.readValue(json == null || json.isBlank() ? "{}" : json, Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String stripTrailingSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
