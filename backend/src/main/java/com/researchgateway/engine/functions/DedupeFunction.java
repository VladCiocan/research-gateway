package com.researchgateway.engine.functions;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class DedupeFunction implements BackendFunction {

    @Override
    public String slug() { return "dedupe_by_embedding"; }

    @Override
    public String description() {
        return "Remove duplicate text items (normalized case/whitespace comparison).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "items", Map.of("type", "array", "items", Map.of("type", "string"))),
                "required", List.of("items"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(Map<String, Object> args) {
        Object raw = args.get("items");
        if (!(raw instanceof List<?> list)) {
            return Map.of("items", List.of(), "removed", 0);
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> unique = new ArrayList<>();
        for (Object o : list) {
            String s = String.valueOf(o);
            String key = s.toLowerCase().replaceAll("\\s+", " ").trim();
            if (seen.add(key)) unique.add(s);
        }
        return Map.of("items", unique, "removed", list.size() - unique.size());
    }
}
