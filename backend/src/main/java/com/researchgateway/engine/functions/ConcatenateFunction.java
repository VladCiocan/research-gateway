package com.researchgateway.engine.functions;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ConcatenateFunction implements BackendFunction {

    @Override
    public String slug() { return "concatenate"; }

    @Override
    public String description() { return "Join an array of text fragments into a single string."; }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "items", Map.of("type", "array", "items", Map.of("type", "string")),
                        "separator", Map.of("type", "string", "description", "Defaults to newline")),
                "required", List.of("items"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(Map<String, Object> args) {
        Object raw = args.get("items");
        String sep = args.get("separator") != null ? String.valueOf(args.get("separator")) : "\n";
        if (!(raw instanceof List<?> list)) {
            return Map.of("result", "");
        }
        String joined = ((List<Object>) list).stream().map(String::valueOf)
                .reduce((a, b) -> a + sep + b).orElse("");
        return Map.of("result", joined, "count", list.size());
    }
}
