package com.researchgateway.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.researchgateway.domain.Capability;
import com.researchgateway.domain.Flow;
import com.researchgateway.domain.Provider;
import com.researchgateway.domain.Run;
import com.researchgateway.domain.RunStep;
import com.researchgateway.engine.functions.BackendFunction;
import com.researchgateway.engine.functions.FunctionRegistry;
import com.researchgateway.llm.LlmClient;
import com.researchgateway.llm.LlmTypes.ChatResult;
import com.researchgateway.llm.LlmTypes.ToolCall;
import com.researchgateway.llm.LlmTypes.ToolDef;
import com.researchgateway.repository.ProviderRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Flow execution engine.
 *
 * <p>When a provider is enabled it runs a real orchestrator loop against an
 * OpenAI-compatible (vLLM) endpoint with tool calling — executing native functions
 * and builtin MCP REST integrations live. With no provider configured it falls back
 * to a deterministic simulation so the product is still demoable offline.
 */
@Component
public class FlowEngine {

    private static final double COST_PER_TOKEN = 0.0000004;

    private final ProviderRepository providerRepository;
    private final LlmClient llmClient;
    private final FunctionRegistry functions;
    private final McpConnector mcp;
    private final ObjectMapper mapper = new ObjectMapper();

    private int seq;

    public FlowEngine(ProviderRepository providerRepository, LlmClient llmClient,
                      FunctionRegistry functions, McpConnector mcp) {
        this.providerRepository = providerRepository;
        this.llmClient = llmClient;
        this.functions = functions;
        this.mcp = mcp;
    }

    public void execute(Run run, Flow flow) {
        seq = 0;
        run.setStatus("running");
        Optional<Provider> provider = providerRepository.findFirstByEnabledTrueOrderByUpdatedAtDesc();
        if (provider.isPresent()) {
            runLive(run, flow, provider.get());
        } else {
            simulate(run, flow);
        }
    }

    // ---------------------------------------------------------------- live

    private void runLive(Run run, Flow flow, Provider provider) {
        String query = inputText(run);
        int maxIterations = guardrailInt(flow, "max_iterations", 8);

        List<ToolDef> tools = buildTools(flow);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", systemPrompt(flow, tools)));
        messages.add(message("user",
                "Request input (JSON):\n" + query + "\n\nProduce the requested research output."));

        addStep(run, "plan", "Orchestrator initialized",
                "Loaded provider '" + provider.getModel() + "', " + tools.size()
                        + " tool(s) and the attached skills.",
                Map.of("model", provider.getModel(), "tools",
                        tools.stream().map(ToolDef::name).toList()), 0);

        int totalTokens = 0;
        String finalContent = null;

        for (int i = 0; i < maxIterations; i++) {
            ChatResult res = llmClient.chat(provider, messages, tools);
            totalTokens += res.totalTokens();

            if (!res.hasToolCalls()) {
                finalContent = res.content();
                break;
            }

            messages.add(assistantWithToolCalls(res));
            for (ToolCall call : res.toolCalls()) {
                Object result = dispatch(flow, call);
                addStep(run, "tool_call", "Tool: " + call.name(),
                        "Executed tool with model-provided arguments.",
                        Map.of("name", call.name(), "arguments", call.arguments(), "result", result),
                        res.totalTokens() / Math.max(1, res.toolCalls().size()));
                messages.add(toolMessage(call.id(), call.name(), result));
            }
        }

        // Force a final answer if the loop ended still wanting tools.
        if (finalContent == null) {
            ChatResult res = llmClient.chat(provider, messages, null);
            totalTokens += res.totalTokens();
            finalContent = res.content();
        }

        addStep(run, "synthesis", "Synthesis", "Final grounded answer composed by the orchestrator.",
                Map.of("length", finalContent == null ? 0 : finalContent.length()), 0);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("summary", finalContent == null ? "" : finalContent);
        output.put("model", provider.getModel());
        finalize(run, output, totalTokens);
    }

    private List<ToolDef> buildTools(Flow flow) {
        List<ToolDef> tools = new ArrayList<>();
        for (Capability c : flow.getCapabilities()) {
            if ("function".equals(c.getType()) && functions.has(c.getSlug())) {
                BackendFunction fn = functions.get(c.getSlug());
                tools.add(new ToolDef(fn.slug(), fn.description(), fn.inputSchema()));
            } else if ("mcp".equals(c.getType()) && mcp.isBuiltin(c)) {
                tools.addAll(mcp.toolDefs(c));
            }
        }
        return tools;
    }

    private Object dispatch(Flow flow, ToolCall call) {
        String name = call.name();
        if (functions.has(name)) {
            try {
                return functions.get(name).execute(call.arguments());
            } catch (Exception ex) {
                return Map.of("error", ex.getMessage());
            }
        }
        if (name.contains(McpConnector.SEP)) {
            String slug = name.substring(0, name.indexOf(McpConnector.SEP));
            String op = name.substring(name.indexOf(McpConnector.SEP) + McpConnector.SEP.length());
            for (Capability c : flow.getCapabilities()) {
                if ("mcp".equals(c.getType()) && c.getSlug().equals(slug) && mcp.isBuiltin(c)) {
                    return mcp.execute(c, op, call.arguments());
                }
            }
        }
        return Map.of("error", "Tool '" + name + "' is not available live (external/unconfigured).");
    }

    private String systemPrompt(Flow flow, List<ToolDef> tools) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are the lead orchestrator for the research flow \"")
                .append(flow.getName()).append("\".\n");
        if (flow.getDescription() != null && !flow.getDescription().isBlank()) {
            sb.append(flow.getDescription()).append("\n");
        }
        sb.append("\nUse the available tools to gather grounded evidence before answering. ");
        if (guardrailBool(flow)) {
            sb.append("Always include inline citations like [1], [2] for sources you used. ");
        }
        sb.append("When you have enough information, write the final answer with no further tool calls.\n");

        List<Capability> skills = flow.getCapabilities().stream()
                .filter(c -> "skill".equals(c.getType())).toList();
        if (!skills.isEmpty()) {
            sb.append("\nActivated skills:\n");
            for (Capability s : skills) {
                Object instr = s.getSpec().get("instructions");
                sb.append("## ").append(s.getName()).append("\n")
                        .append(instr != null ? instr : s.getDescription()).append("\n");
            }
        }
        if (!tools.isEmpty()) {
            sb.append("\nAvailable tools: ");
            sb.append(String.join(", ", tools.stream().map(ToolDef::name).toList())).append(".\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------- message helpers

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private Map<String, Object> assistantWithToolCalls(ChatResult res) {
        List<Map<String, Object>> calls = new ArrayList<>();
        for (ToolCall c : res.toolCalls()) {
            calls.add(Map.of("id", c.id(), "type", "function",
                    "function", Map.of("name", c.name(), "arguments", toJson(c.arguments()))));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "assistant");
        m.put("content", res.content() == null ? "" : res.content());
        m.put("tool_calls", calls);
        return m;
    }

    private Map<String, Object> toolMessage(String id, String name, Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "tool");
        m.put("tool_call_id", id);
        m.put("name", name);
        m.put("content", toJson(result));
        return m;
    }

    private String toJson(Object o) {
        try { return mapper.writeValueAsString(o); } catch (Exception e) { return String.valueOf(o); }
    }

    // ------------------------------------------------------------- simulation fallback

    private void simulate(Run run, Flow flow) {
        String query = String.valueOf(run.getInput().getOrDefault("query",
                run.getInput().getOrDefault("sector", "the requested topic")));

        addStep(run, "guardrail", "No LLM provider enabled — running simulation",
                "Enable a vLLM provider in Settings to execute this flow live.", Map.of(), 0);

        int totalTokens = 0;
        List<String> subQuestions = List.of(
                "Background and current state of " + query,
                "Key players and competitors related to " + query,
                "Recent trends and signals around " + query,
                "Risks, gaps and open questions about " + query);
        totalTokens += addStep(run, "plan", "Orchestrator decomposed the request",
                "Broke the request into " + subQuestions.size() + " sub-questions.",
                Map.of("subQuestions", subQuestions,
                        "capabilities", flow.getCapabilities().stream().map(Capability::getName).toList()),
                420);

        List<Map<String, Object>> candidates = new ArrayList<>();
        for (int i = 0; i < subQuestions.size(); i++) {
            List<Map<String, Object>> found = fakeSources(subQuestions.get(i), i);
            candidates.addAll(found);
            totalTokens += addStep(run, "subagent", "Search subagent #" + (i + 1),
                    "Searched sources for: \"" + subQuestions.get(i) + "\"",
                    Map.of("task", subQuestions.get(i), "sources", found),
                    300 + ThreadLocalRandom.current().nextInt(200));
        }

        List<Map<String, Object>> ranked = candidates.stream()
                .sorted((a, b) -> Double.compare((double) b.get("relevance"), (double) a.get("relevance")))
                .limit(5).toList();
        totalTokens += addStep(run, "subagent", "Triage — rank & dedupe",
                "Reduced " + candidates.size() + " candidates to " + ranked.size() + ".",
                Map.of("kept", ranked.size()), 260);

        String summary = "Simulated synthesis on **" + query + "**. Enable a vLLM provider in "
                + "Settings to produce a live, grounded answer with real tool calls.";
        totalTokens += addStep(run, "synthesis", "Synthesis with citations",
                "Composed a simulated answer.", Map.of("sourceCount", ranked.size()), 650);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("summary", summary);
        output.put("findings", ranked.stream().map(s -> s.get("title")).toList());
        output.put("sources", ranked);
        finalize(run, output, totalTokens);
    }

    private List<Map<String, Object>> fakeSources(String subQuestion, int idx) {
        List<Map<String, Object>> list = new ArrayList<>();
        int n = 2 + ThreadLocalRandom.current().nextInt(2);
        for (int i = 0; i < n; i++) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("title", "Source " + (char) ('A' + idx) + (i + 1) + " — "
                    + (subQuestion.length() > 48 ? subQuestion.substring(0, 45) + "..." : subQuestion));
            s.put("url", "https://example.org/research/" + idx + "-" + i);
            s.put("relevance", BigDecimal.valueOf(0.55 + ThreadLocalRandom.current().nextDouble() * 0.45)
                    .setScale(2, RoundingMode.HALF_UP).doubleValue());
            list.add(s);
        }
        return list;
    }

    // ------------------------------------------------------------- shared

    private void finalize(Run run, Map<String, Object> output, int totalTokens) {
        run.setOutput(output);
        run.setTokens(totalTokens);
        run.setCostUsd(BigDecimal.valueOf(totalTokens).multiply(BigDecimal.valueOf(COST_PER_TOKEN))
                .setScale(4, RoundingMode.HALF_UP));
        run.setStatus("completed");
        run.setEndedAt(Instant.now());
    }

    private int addStep(Run run, String type, String title, String detail,
                        Map<String, Object> payload, int tokens) {
        RunStep step = new RunStep();
        step.setSeq(++seq);
        step.setType(type);
        step.setTitle(title);
        step.setDetail(detail);
        step.setPayload(new LinkedHashMap<>(payload));
        step.setTokens(tokens);
        step.setCostUsd(BigDecimal.valueOf(tokens).multiply(BigDecimal.valueOf(COST_PER_TOKEN))
                .setScale(4, RoundingMode.HALF_UP));
        run.addStep(step);
        return tokens;
    }

    private String inputText(Run run) {
        try { return mapper.writeValueAsString(run.getInput()); }
        catch (Exception e) { return String.valueOf(run.getInput()); }
    }

    private int guardrailInt(Flow flow, String key, int def) {
        Object g = flow.getConfig().get("guardrails");
        if (g instanceof Map<?, ?> m && m.get(key) instanceof Number n) return n.intValue();
        return def;
    }

    private boolean guardrailBool(Flow flow) {
        Object g = flow.getConfig().get("guardrails");
        if (g instanceof Map<?, ?> m && m.get("require_citations") instanceof Boolean b) return b;
        return true;
    }
}
