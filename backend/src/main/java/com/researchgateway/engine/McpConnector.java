package com.researchgateway.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.researchgateway.domain.Capability;
import com.researchgateway.llm.LlmTypes.ToolDef;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes "builtin" MCP capabilities — in-platform REST integrations described by the
 * capability spec ({@code base_url}, {@code auth_header}, {@code operations[]}).
 * External MCP servers are modelled but not invoked live in this build.
 */
@Component
public class McpConnector {

    public static final String SEP = "__";

    public boolean isBuiltin(Capability mcp) {
        return "builtin".equalsIgnoreCase(String.valueOf(mcp.getSpec().get("kind")));
    }

    /** Build one ToolDef per operation, named {@code <slug>__<operation>}. */
    @SuppressWarnings("unchecked")
    public List<ToolDef> toolDefs(Capability mcp) {
        List<ToolDef> defs = new ArrayList<>();
        Object ops = mcp.getSpec().get("operations");
        if (!(ops instanceof List<?> list)) return defs;
        for (Object o : list) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> op = (Map<String, Object>) o;
            String name = String.valueOf(op.get("name"));
            String desc = String.valueOf(op.getOrDefault("description", ""));
            Map<String, Object> params = op.get("params") instanceof Map
                    ? (Map<String, Object>) op.get("params") : Map.of();
            Map<String, Object> props = new LinkedHashMap<>();
            for (String key : params.keySet()) {
                props.put(key, Map.of("type", "string",
                        "description", String.valueOf(params.get(key))));
            }
            Map<String, Object> schema = Map.of("type", "object", "properties", props);
            defs.add(new ToolDef(mcp.getSlug() + SEP + name,
                    "[" + mcp.getName() + "] " + desc, schema));
        }
        return defs;
    }

    /** Execute an operation by name with model-supplied arguments. */
    @SuppressWarnings("unchecked")
    public Object execute(Capability mcp, String operationName, Map<String, Object> args) {
        Object ops = mcp.getSpec().get("operations");
        if (!(ops instanceof List<?> list)) return Map.of("error", "No operations configured");

        Map<String, Object> op = null;
        for (Object o : list) {
            if (o instanceof Map && operationName.equals(String.valueOf(((Map<String, Object>) o).get("name")))) {
                op = (Map<String, Object>) o; break;
            }
        }
        if (op == null) return Map.of("error", "Unknown operation: " + operationName);

        String baseUrl = String.valueOf(mcp.getSpec().getOrDefault("base_url", ""));
        String method = String.valueOf(op.getOrDefault("method", "GET")).toUpperCase();
        String path = String.valueOf(op.getOrDefault("path", ""));
        String authHeader = String.valueOf(mcp.getSpec().getOrDefault("auth_header", ""));

        // Substitute {placeholders} in the path with provided args (path params).
        Map<String, Object> remaining = new LinkedHashMap<>(args);
        for (Map.Entry<String, Object> e : args.entrySet()) {
            String token = "{" + e.getKey() + "}";
            if (path.contains(token)) {
                path = path.replace(token, String.valueOf(e.getValue()));
                remaining.remove(e.getKey());
            }
        }

        try {
            var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
            factory.setReadTimeout((int) Duration.ofSeconds(30).toMillis());
            RestClient.Builder b = RestClient.builder().requestFactory(factory)
                    .baseUrl(stripTrailingSlash(baseUrl));
            if (authHeader != null && authHeader.contains(":")) {
                int idx = authHeader.indexOf(':');
                b.defaultHeader(authHeader.substring(0, idx).trim(),
                        authHeader.substring(idx + 1).trim());
            }
            RestClient client = b.build();

            if ("GET".equals(method)) {
                UriComponentsBuilder uri = UriComponentsBuilder.fromPath(path);
                remaining.forEach((k, v) -> uri.queryParam(k, String.valueOf(v)));
                JsonNode body = client.get().uri(uri.build().toUriString())
                        .retrieve().body(JsonNode.class);
                return Map.of("ok", true, "data", body);
            } else {
                JsonNode body = client.method(org.springframework.http.HttpMethod.valueOf(method))
                        .uri(path).body(remaining).retrieve().body(JsonNode.class);
                return Map.of("ok", true, "data", body);
            }
        } catch (Exception ex) {
            return Map.of("ok", false, "error", ex.getMessage());
        }
    }

    private String stripTrailingSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
