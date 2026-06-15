package com.researchgateway.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static authoring guides — one per capability type — that explain how to create each kind of
 * capability and what its {@code spec} should contain. Surfaced at {@code GET /api/capabilities/help}
 * and rendered as a help panel in the Capability Registry.
 */
public final class CapabilityHelp {

    private CapabilityHelp() {}

    public static List<Map<String, Object>> guides() {
        return List.of(skill(), function(), tool(), mcp());
    }

    private static Map<String, Object> skill() {
        return guide("skill", "Skill",
                "An instruction package the orchestrator injects into the model. Skills carry no code — "
                        + "they shape how the flow reasons.",
                "Set the model-visible description, then put the guidance under spec.instructions. "
                        + "Use spec.loads to control disclosure: \"always\" skills are injected up front; "
                        + "\"on-demand\" skills are only advertised, and the orchestrator pulls them in with "
                        + "the load_skill tool when the task needs them. This lets a flow carry 100 skills "
                        + "yet load only the handful it uses on a given run — and re-load any of them as often "
                        + "as needed.",
                List.of(
                        field("loads", false, "\"always\" (inject every run) or \"on-demand\" (load only when needed). Defaults to always."),
                        field("instructions", true, "The guidance injected into the orchestrator/sub-agent prompt."),
                        field("resources", false, "Optional list of attached reference files the skill refers to.")),
                map("loads", "on-demand",
                        "instructions", "Score each candidate 0-1 for relevance and discard duplicates. "
                                + "Return a ranked shortlist with one-line justifications.",
                        "resources", List.of("frameworks/triage.md")),
                List.of("Keep \"always\" skills short — they cost tokens on every turn.",
                        "Write the description so the orchestrator can decide whether to load the skill on demand.",
                        "Skills can be loaded repeatedly within a session; instructions should be idempotent."));
    }

    private static Map<String, Object> function() {
        return guide("function", "Native function",
                "Backend code that runs in-process — fast, deterministic, no external call. The slug must "
                        + "match a registered BackendFunction bean (e.g. concatenate, dedupe_by_embedding, "
                        + "rank_by_relevance, read_url_fn).",
                "Pick a name and slug that map to an existing backend implementation, then describe the "
                        + "arguments under spec.input_schema as JSON Schema. The orchestrator calls it like any "
                        + "tool and may call it as many times as needed. Use the Test live panel to dry-run it.",
                List.of(
                        field("runtime", false, "Informational — always \"backend\" for native functions."),
                        field("input_schema", true, "JSON Schema (type/properties/required) describing the arguments.")),
                map("runtime", "backend",
                        "input_schema", map("type", "object",
                                "properties", map("items", map("type", "array", "items", map("type", "string")),
                                        "separator", map("type", "string")),
                                "required", List.of("items"))),
                List.of("The slug is the contract: only slugs backed by a bean execute live.",
                        "Return JSON-serializable values; errors should be returned as {\"error\": \"...\"}.",
                        "Prefer functions over MCP for pure, side-effect-free transforms."));
    }

    private static Map<String, Object> tool() {
        return guide("tool", "Tool",
                "A declarative capability reference — typically an external action whose execution lives "
                        + "outside this build. Tools document the interface in the registry; back them with a "
                        + "native function or a builtin MCP operation to run them live.",
                "Describe the call shape under spec.input_schema so flows and the model understand the "
                        + "interface. To make a documented tool executable, add a function with the same slug or "
                        + "a builtin MCP operation.",
                List.of(field("input_schema", true, "Shape of the tool's arguments (JSON Schema or a simple field map).")),
                map("input_schema", map("query", "string", "limit", "integer")),
                List.of("Tools alone are not executed live — pair them with a function or builtin MCP op.",
                        "Keep the slug stable; flows reference capabilities by slug."));
    }

    private static Map<String, Object> mcp() {
        return guide("mcp", "MCP server",
                "A Model Context Protocol integration. Two kinds: \"builtin\" is an in-platform REST "
                        + "integration you configure here (each operation becomes a callable tool, executed live); "
                        + "\"external\" models a real MCP server connection.",
                "Choose the kind. For builtin, set base_url and an optional auth_header (\"Name: Value\"), "
                        + "then declare operations[] — each with a name, method, path and params. {placeholders} in "
                        + "the path are filled from arguments; remaining args become query params (GET) or the body. "
                        + "Mark an operation paginated:true to receive pageInfo (hasMore + nextPage/nextCursor) so the "
                        + "orchestrator can page through results by calling it again. Operations can be called any "
                        + "number of times per run.",
                List.of(
                        field("kind", true, "\"builtin\" (REST, executed here) or \"external\" (MCP server)."),
                        field("base_url", false, "Builtin: root URL for the REST API."),
                        field("auth_header", false, "Builtin: optional header as \"Name: Value\"."),
                        field("operations", false, "Builtin: list of { name, method, path, description, params, paginated }.")),
                map("kind", "builtin",
                        "base_url", "https://jsonplaceholder.typicode.com",
                        "auth_header", "",
                        "operations", List.of(map(
                                "name", "list_posts",
                                "method", "GET",
                                "path", "/posts",
                                "description", "List posts, page by page",
                                "paginated", true,
                                "params", map("_page", "integer (page number, 1-based)",
                                        "_limit", "integer (page size)")))),
                List.of("Each builtin operation is advertised to the model as <slug>__<operation>.",
                        "Use the Test live panel to call an operation in isolation before attaching it to a flow.",
                        "External MCP servers are modelled but not invoked live in this build."));
    }

    // ---- builders ----

    private static Map<String, Object> guide(String type, String title, String summary, String howTo,
                                             List<Map<String, Object>> fields, Map<String, Object> example,
                                             List<String> tips) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("type", type);
        g.put("title", title);
        g.put("summary", summary);
        g.put("howTo", howTo);
        g.put("fields", fields);
        g.put("example", example);
        g.put("tips", tips);
        return g;
    }

    private static Map<String, Object> field(String key, boolean required, String description) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("key", key);
        f.put("required", required);
        f.put("description", description);
        return f;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
}
