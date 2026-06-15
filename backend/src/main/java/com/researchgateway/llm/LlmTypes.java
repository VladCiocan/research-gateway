package com.researchgateway.llm;

import java.util.List;
import java.util.Map;

/** Shared LLM request/response value types (OpenAI-compatible). */
public final class LlmTypes {

    private LlmTypes() {}

    /** A tool the model may call. {@code parameters} is a JSON Schema object. */
    public record ToolDef(String name, String description, Map<String, Object> parameters) {}

    /** A tool call requested by the model. */
    public record ToolCall(String id, String name, Map<String, Object> arguments) {}

    /** Result of a single chat completion. */
    public record ChatResult(String content, List<ToolCall> toolCalls,
                             int promptTokens, int completionTokens) {
        public boolean hasToolCalls() { return toolCalls != null && !toolCalls.isEmpty(); }
        public int totalTokens() { return promptTokens + completionTokens; }
    }
}
