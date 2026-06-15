package com.researchgateway.engine.functions;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class RankFunction implements BackendFunction {

    @Override
    public String slug() { return "rank_by_relevance"; }

    @Override
    public String description() {
        return "Score and rank text items by keyword overlap with a query (0..1).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string"),
                        "items", Map.of("type", "array", "items", Map.of("type", "string"))),
                "required", List.of("query", "items"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(Map<String, Object> args) {
        String query = String.valueOf(args.getOrDefault("query", ""));
        Object raw = args.get("items");
        if (!(raw instanceof List<?> list)) {
            return Map.of("ranked", List.of());
        }
        Set<String> q = tokens(query);
        List<Map<String, Object>> ranked = ((List<Object>) list).stream()
                .map(o -> {
                    String s = String.valueOf(o);
                    Set<String> t = tokens(s);
                    long overlap = t.stream().filter(q::contains).count();
                    double score = q.isEmpty() ? 0 : Math.min(1.0, (double) overlap / q.size());
                    return Map.<String, Object>of("text", s,
                            "score", Math.round(score * 100.0) / 100.0);
                })
                .sorted((a, b) -> Double.compare((double) b.get("score"), (double) a.get("score")))
                .collect(Collectors.toList());
        return Map.of("ranked", ranked);
    }

    private Set<String> tokens(String s) {
        return Arrays.stream(s.toLowerCase().split("[^a-z0-9]+"))
                .filter(w -> w.length() > 2).collect(Collectors.toSet());
    }
}
