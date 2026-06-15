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
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Flow execution engine.
 *
 * <p>When a provider is enabled it runs a real orchestrator loop against an
 * OpenAI-compatible (vLLM) endpoint with tool calling. Within a single flow session the
 * orchestrator may:
 * <ul>
 *   <li>call native functions and builtin MCP operations <b>repeatedly</b> (MCP operations
 *       expose pagination metadata so the model can walk through pages);</li>
 *   <li><b>load skills on demand</b> via the synthetic {@code load_skill} tool — a flow can be
 *       assigned many skills yet only pull in the instructions it actually needs
 *       (progressive disclosure);</li>
 *   <li><b>delegate</b> to parallel or chained sub-agents via the synthetic
 *       {@code spawn_subagents} tool.</li>
 * </ul>
 * All tool activity — the orchestrator's plus every sub-agent's — shares a single hard budget
 * of {@value #MAX_TOOL_ITERATIONS} tool iterations per flow session. With no provider configured
 * the engine falls back to a deterministic simulation so the product is still demoable offline.
 */
@Component
public class FlowEngine {

    private static final double COST_PER_TOKEN = 0.0000004;

    /** Hard ceiling on tool iterations per flow session, shared by the orchestrator and all sub-agents. */
    public static final int MAX_TOOL_ITERATIONS = 120;

    /** Synthetic, engine-provided tools (not registry capabilities). */
    static final String LOAD_SKILL = "load_skill";
    static final String SPAWN_SUBAGENTS = "spawn_subagents";

    private final ProviderRepository providerRepository;
    private final LlmClient llmClient;
    private final FunctionRegistry functions;
    private final McpConnector mcp;
    private final ObjectMapper mapper = new ObjectMapper();

    public FlowEngine(ProviderRepository providerRepository, LlmClient llmClient,
                      FunctionRegistry functions, McpConnector mcp) {
        this.providerRepository = providerRepository;
        this.llmClient = llmClient;
        this.functions = functions;
        this.mcp = mcp;
    }

    public void execute(Run run, Flow flow) {
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
        Exec ex = new Exec(run, flow, provider);
        String query = inputText(run);
        int orchestratorLoops = clamp(guardrailInt(flow, "max_iterations", MAX_TOOL_ITERATIONS), 1, MAX_TOOL_ITERATIONS);

        List<ToolDef> tools = ex.orchestratorTools;
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", orchestratorPrompt(ex)));
        messages.add(message("user",
                "Request input (JSON):\n" + query + "\n\nProduce the requested research output."));

        addStep(ex, "plan", "Orchestrator initialized",
                "Loaded provider '" + provider.getModel() + "', " + tools.size()
                        + " tool(s), " + ex.alwaysSkills.size() + " always-on skill(s) and "
                        + ex.onDemandSkills.size() + " skill(s) loadable on demand.",
                Map.of("model", provider.getModel(),
                        "tools", tools.stream().map(ToolDef::name).toList(),
                        "skillsOnDemand", new ArrayList<>(ex.onDemandSkills.keySet()),
                        "iterationBudget", MAX_TOOL_ITERATIONS), 0);

        LoopOut out = runLoop(ex, messages, tools, orchestratorLoops, 0, null);
        String finalContent = out.content();

        // Force a final answer if the loop ended still wanting tools or hit the budget.
        if (finalContent == null) {
            ChatResult res = llmClient.chat(provider, messages, null);
            ex.tokens.addAndGet(res.totalTokens());
            finalContent = res.content();
        }

        addStep(ex, "synthesis", "Synthesis", "Final grounded answer composed by the orchestrator.",
                Map.of("length", finalContent == null ? 0 : finalContent.length(),
                        "toolIterations", ex.iterations.get()), 0);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("summary", finalContent == null ? "" : finalContent);
        output.put("model", provider.getModel());
        output.put("toolIterations", ex.iterations.get());
        finalize(run, output, ex.tokens.get());
    }

    /**
     * The orchestrator/sub-agent loop: chat, run any requested tools, repeat until the model
     * answers, the local loop bound is reached, or the shared iteration budget is exhausted.
     *
     * @param depth 0 for the lead orchestrator, &ge;1 for sub-agents.
     * @param sink  when non-null (sub-agents), each tool call is appended here instead of being
     *              recorded as a top-level trace step.
     */
    private LoopOut runLoop(Exec ex, List<Map<String, Object>> messages, List<ToolDef> tools,
                            int maxLoops, int depth, List<Map<String, Object>> sink) {
        String finalContent = null;
        int localTokens = 0;
        for (int i = 0; i < maxLoops; i++) {
            if (ex.iterations.get() >= MAX_TOOL_ITERATIONS) break;   // shared hard budget exhausted

            ChatResult res = llmClient.chat(ex.provider, messages, tools);
            ex.tokens.addAndGet(res.totalTokens());
            localTokens += res.totalTokens();

            if (!res.hasToolCalls()) {
                finalContent = res.content();
                break;
            }

            // Atomically reserve one tool iteration so concurrent sub-agents cannot overshoot the cap.
            if (ex.iterations.incrementAndGet() > MAX_TOOL_ITERATIONS) {
                ex.iterations.decrementAndGet();
                break;
            }
            messages.add(assistantWithToolCalls(res));
            int share = res.totalTokens() / Math.max(1, res.toolCalls().size());
            for (ToolCall call : res.toolCalls()) {
                Object result = executeCall(ex, call, depth, share);
                if (sink != null) {
                    Map<String, Object> act = new LinkedHashMap<>();
                    act.put("name", call.name());
                    act.put("arguments", call.arguments());
                    act.put("result", result);
                    sink.add(act);
                }
                messages.add(toolMessage(call.id(), call.name(), result));
            }
        }
        return new LoopOut(finalContent, localTokens);
    }

    /** Run a single tool call, routing synthetic tools and recording the appropriate trace step. */
    private Object executeCall(Exec ex, ToolCall call, int depth, int tokenShare) {
        String name = call.name();

        if (LOAD_SKILL.equals(name)) {
            return loadSkill(ex, call.arguments());
        }
        if (SPAWN_SUBAGENTS.equals(name)) {
            if (depth > 0) {
                return Map.of("error", "Sub-agents cannot spawn further sub-agents.");
            }
            return spawnSubagents(ex, call.arguments());
        }

        Object result = dispatchTool(ex, name, call.arguments());
        if (depth == 0) {
            addStep(ex, "tool_call", "Tool: " + name,
                    "Executed tool with model-provided arguments.",
                    Map.of("name", name, "arguments", call.arguments(), "result", result), tokenShare);
        }
        return result;
    }

    /** Dispatch a native function or a builtin MCP operation. */
    private Object dispatchTool(Exec ex, String name, Map<String, Object> args) {
        if (functions.has(name)) {
            try {
                return functions.get(name).execute(args);
            } catch (Exception exn) {
                return Map.of("error", exn.getMessage());
            }
        }
        if (name.contains(McpConnector.SEP)) {
            String slug = name.substring(0, name.indexOf(McpConnector.SEP));
            String op = name.substring(name.indexOf(McpConnector.SEP) + McpConnector.SEP.length());
            for (Capability c : ex.flow.getCapabilities()) {
                if ("mcp".equals(c.getType()) && c.getSlug().equals(slug) && mcp.isBuiltin(c)) {
                    return mcp.execute(c, op, args);
                }
            }
        }
        return Map.of("error", "Tool '" + name + "' is not available live (external/unconfigured).");
    }

    // ---------------------------------------------------------------- skills (progressive disclosure)

    private Object loadSkill(Exec ex, Map<String, Object> args) {
        String slug = String.valueOf(args.getOrDefault("skill", "")).trim();
        Capability skill = ex.onDemandSkills.get(slug);
        if (skill == null) {
            return Map.of("error", "Unknown on-demand skill: '" + slug + "'.",
                    "available", new ArrayList<>(ex.onDemandSkills.keySet()));
        }
        String instructions = skillInstructions(skill);
        boolean first = ex.loadedSkills.add(slug);
        addStep(ex, "skill", "Loaded skill: " + skill.getName(),
                first ? "Progressive disclosure — full instructions pulled in on demand."
                        : "Skill instructions re-read on demand.",
                Map.of("skill", slug, "instructions", instructions), 0);
        return Map.of("ok", true, "skill", slug, "instructions", instructions);
    }

    // ---------------------------------------------------------------- sub-agents (parallel / chained)

    @SuppressWarnings("unchecked")
    private Object spawnSubagents(Exec ex, Map<String, Object> args) {
        String mode = "chained".equalsIgnoreCase(String.valueOf(args.get("mode"))) ? "chained" : "parallel";
        List<Map<String, Object>> tasks = new ArrayList<>();
        if (args.get("tasks") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("objective") != null) {
                    tasks.add((Map<String, Object>) m);
                }
            }
        }
        if (tasks.isEmpty()) {
            return Map.of("error", "Provide at least one task with an 'objective'.");
        }
        if (tasks.size() > 8) tasks = tasks.subList(0, 8);   // keep delegation bounded

        addStep(ex, "plan", "Delegating to " + tasks.size() + " sub-agent(s) [" + mode + "]",
                mode.equals("parallel")
                        ? "Running independent sub-agents concurrently."
                        : "Running sub-agents sequentially, each building on the previous.",
                Map.of("mode", mode, "objectives",
                        tasks.stream().map(t -> String.valueOf(t.get("objective"))).toList()), 0);

        List<SubResult> results = mode.equals("chained")
                ? runChained(ex, tasks)
                : runParallel(ex, tasks);

        List<Map<String, Object>> summary = new ArrayList<>();
        for (SubResult r : results) {
            addStep(ex, "subagent", "Sub-agent: " + truncate(r.objective(), 70),
                    r.skipped() ? "Skipped — iteration budget exhausted." : "Completed delegated objective.",
                    Map.of("objective", r.objective(), "answer", r.answer() == null ? "" : r.answer(),
                            "toolActivity", r.activity()), r.tokens());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("objective", r.objective());
            entry.put("answer", r.answer() == null ? "" : r.answer());
            entry.put("skipped", r.skipped());
            summary.add(entry);
        }
        return Map.of("mode", mode, "count", summary.size(), "results", summary);
    }

    private List<SubResult> runChained(Exec ex, List<Map<String, Object>> tasks) {
        List<SubResult> results = new ArrayList<>();
        String upstream = null;
        for (Map<String, Object> task : tasks) {
            if (ex.iterations.get() >= MAX_TOOL_ITERATIONS) {
                results.add(SubResult.skipped(String.valueOf(task.get("objective"))));
                continue;
            }
            SubResult r = runSubagent(ex, task, upstream);
            results.add(r);
            upstream = r.answer();
        }
        return results;
    }

    private List<SubResult> runParallel(Exec ex, List<Map<String, Object>> tasks) {
        int pool = clamp(subagentsInt(ex.flow, "max_concurrent", 5), 1, 8);
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(pool, tasks.size()));
        try {
            List<Future<SubResult>> futures = new ArrayList<>();
            for (Map<String, Object> task : tasks) {
                Callable<SubResult> job = () -> ex.iterations.get() >= MAX_TOOL_ITERATIONS
                        ? SubResult.skipped(String.valueOf(task.get("objective")))
                        : runSubagent(ex, task, null);
                futures.add(executor.submit(job));
            }
            List<SubResult> results = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                try {
                    results.add(futures.get(i).get());
                } catch (Exception e) {
                    results.add(new SubResult(String.valueOf(tasks.get(i).get("objective")),
                            "Sub-agent failed: " + e.getMessage(), List.of(), 0, false));
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private SubResult runSubagent(Exec ex, Map<String, Object> task, String upstream) {
        String objective = String.valueOf(task.get("objective"));
        String context = String.valueOf(task.getOrDefault("context", ""));
        int workerLoops = clamp(subagentsInt(ex.flow, "max_loops", 8), 1, MAX_TOOL_ITERATIONS);

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", subagentPrompt(ex)));
        StringBuilder user = new StringBuilder("Objective:\n").append(objective);
        if (!context.isBlank()) user.append("\n\nContext:\n").append(context);
        if (upstream != null && !upstream.isBlank()) {
            user.append("\n\nUpstream sub-agent result to build on:\n").append(upstream);
        }
        user.append("\n\nComplete the objective, then return your result with no further tool calls.");
        messages.add(message("user", user.toString()));

        List<Map<String, Object>> activity = new ArrayList<>();
        LoopOut out = runLoop(ex, messages, ex.workerTools, workerLoops, 1, activity);
        String answer = out.content();
        int tokens = out.tokens();
        if (answer == null) {   // force a final answer from the sub-agent
            ChatResult res = llmClient.chat(ex.provider, messages, null);
            ex.tokens.addAndGet(res.totalTokens());
            tokens += res.totalTokens();
            answer = res.content();
        }
        return new SubResult(objective, answer, activity, tokens, false);
    }

    // ---------------------------------------------------------------- prompts

    private String orchestratorPrompt(Exec ex) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are the lead orchestrator for the research flow \"")
                .append(ex.flow.getName()).append("\".\n");
        if (ex.flow.getDescription() != null && !ex.flow.getDescription().isBlank()) {
            sb.append(ex.flow.getDescription()).append("\n");
        }
        sb.append("\nUse the available tools to gather grounded evidence before answering. ");
        sb.append("Call tools as many times as needed — including the same tool repeatedly to paginate ");
        sb.append("through results (watch each result's pageInfo.hasMore and request the next page). ");
        if (guardrailBool(ex.flow)) {
            sb.append("Always include inline citations like [1], [2] for sources you used. ");
        }
        sb.append("When you have enough information, write the final answer with no further tool calls.\n");
        sb.append("You may make up to ").append(MAX_TOOL_ITERATIONS)
                .append(" tool iterations in this session (shared with any sub-agents you spawn).\n");

        appendAlwaysSkills(sb, ex);
        appendOnDemandSkills(sb, ex);

        sb.append("\nDelegation: call ").append(SPAWN_SUBAGENTS)
                .append(" with several focused tasks to fan work out. Use mode \"parallel\" for ")
                .append("independent sub-questions, or \"chained\" when each step must build on the previous. ")
                .append("Sub-agents share the same tools and the session iteration budget.\n");

        appendToolList(sb, ex.orchestratorTools);
        return sb.toString();
    }

    private String subagentPrompt(Exec ex) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a focused research sub-agent working for the flow \"")
                .append(ex.flow.getName()).append("\".\n");
        sb.append("Complete the single objective you are given using the available tools. ");
        sb.append("Call tools as many times as needed (including for pagination). ");
        if (guardrailBool(ex.flow)) {
            sb.append("Preserve source attribution so the lead can cite it. ");
        }
        sb.append("Return a concise, evidence-backed result; do not spawn further sub-agents.\n");
        appendAlwaysSkills(sb, ex);
        appendOnDemandSkills(sb, ex);
        appendToolList(sb, ex.workerTools);
        return sb.toString();
    }

    private void appendAlwaysSkills(StringBuilder sb, Exec ex) {
        if (ex.alwaysSkills.isEmpty()) return;
        sb.append("\nActivated skills:\n");
        for (Capability s : ex.alwaysSkills) {
            sb.append("## ").append(s.getName()).append("\n").append(skillInstructions(s)).append("\n");
        }
    }

    private void appendOnDemandSkills(StringBuilder sb, Exec ex) {
        if (ex.onDemandSkills.isEmpty()) return;
        sb.append("\nSkills available to load on demand (call ").append(LOAD_SKILL)
                .append(" with the slug to pull in full instructions — only when the task needs it):\n");
        for (Map.Entry<String, Capability> e : ex.onDemandSkills.entrySet()) {
            sb.append("- ").append(e.getKey()).append(": ")
                    .append(e.getValue().getDescription() == null ? "" : e.getValue().getDescription())
                    .append("\n");
        }
    }

    private void appendToolList(StringBuilder sb, List<ToolDef> tools) {
        if (tools.isEmpty()) return;
        sb.append("\nAvailable tools: ")
                .append(String.join(", ", tools.stream().map(ToolDef::name).toList())).append(".\n");
    }

    private String skillInstructions(Capability skill) {
        Object instr = skill.getSpec().get("instructions");
        return instr != null ? String.valueOf(instr) : String.valueOf(skill.getDescription());
    }

    // ---------------------------------------------------------------- tool wiring

    private List<ToolDef> capabilityTools(Flow flow) {
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

    private ToolDef loadSkillTool(Map<String, Capability> onDemand) {
        Map<String, Object> skillProp = new LinkedHashMap<>();
        skillProp.put("type", "string");
        skillProp.put("enum", new ArrayList<>(onDemand.keySet()));
        skillProp.put("description", "Slug of the skill whose instructions to load.");
        Map<String, Object> schema = Map.of("type", "object",
                "properties", Map.of("skill", skillProp), "required", List.of("skill"));
        return new ToolDef(LOAD_SKILL,
                "Load an assigned skill's full instructions on demand (progressive disclosure). "
                        + "May be called multiple times.", schema);
    }

    private ToolDef spawnSubagentsTool() {
        Map<String, Object> mode = Map.of("type", "string", "enum", List.of("parallel", "chained"),
                "description", "parallel = independent concurrent sub-agents; chained = each builds on the previous.");
        Map<String, Object> taskItem = Map.of("type", "object", "properties", Map.of(
                "objective", Map.of("type", "string", "description", "What this sub-agent must accomplish."),
                "context", Map.of("type", "string", "description", "Optional context to seed the sub-agent.")),
                "required", List.of("objective"));
        Map<String, Object> tasks = Map.of("type", "array",
                "description", "1-8 focused sub-agent tasks.", "items", taskItem);
        Map<String, Object> schema = Map.of("type", "object",
                "properties", Map.of("mode", mode, "tasks", tasks), "required", List.of("tasks"));
        return new ToolDef(SPAWN_SUBAGENTS,
                "Delegate work to parallel or chained sub-agents that share your tools and budget.", schema);
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
        Exec ex = new Exec(run, flow, null);
        String query = String.valueOf(run.getInput().getOrDefault("query",
                run.getInput().getOrDefault("sector", "the requested topic")));

        addStep(ex, "guardrail", "No LLM provider enabled — running simulation",
                "Enable a vLLM provider in Settings to execute this flow live.", Map.of(), 0);

        List<String> subQuestions = List.of(
                "Background and current state of " + query,
                "Key players and competitors related to " + query,
                "Recent trends and signals around " + query,
                "Risks, gaps and open questions about " + query);
        addStep(ex, "plan", "Orchestrator decomposed the request",
                "Broke the request into " + subQuestions.size() + " sub-questions.",
                Map.of("subQuestions", subQuestions,
                        "capabilities", flow.getCapabilities().stream().map(Capability::getName).toList()),
                420);

        List<Map<String, Object>> candidates = new ArrayList<>();
        for (int i = 0; i < subQuestions.size(); i++) {
            List<Map<String, Object>> found = fakeSources(subQuestions.get(i), i);
            candidates.addAll(found);
            addStep(ex, "subagent", "Search subagent #" + (i + 1),
                    "Searched sources for: \"" + subQuestions.get(i) + "\"",
                    Map.of("task", subQuestions.get(i), "sources", found),
                    300 + ThreadLocalRandom.current().nextInt(200));
        }

        List<Map<String, Object>> ranked = candidates.stream()
                .sorted((a, b) -> Double.compare((double) b.get("relevance"), (double) a.get("relevance")))
                .limit(5).toList();
        addStep(ex, "subagent", "Triage — rank & dedupe",
                "Reduced " + candidates.size() + " candidates to " + ranked.size() + ".",
                Map.of("kept", ranked.size()), 260);

        String summary = "Simulated synthesis on **" + query + "**. Enable a vLLM provider in "
                + "Settings to produce a live, grounded answer with real tool calls.";
        addStep(ex, "synthesis", "Synthesis with citations",
                "Composed a simulated answer.", Map.of("sourceCount", ranked.size()), 650);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("summary", summary);
        output.put("findings", ranked.stream().map(s -> s.get("title")).toList());
        output.put("sources", ranked);
        finalize(run, output, ex.tokens.get());
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

    /** Append a trace step. Synchronized because parallel sub-agents may record concurrently. */
    private void addStep(Exec ex, String type, String title, String detail,
                         Map<String, Object> payload, int tokens) {
        RunStep step = new RunStep();
        step.setType(type);
        step.setTitle(title);
        step.setDetail(detail);
        step.setPayload(new LinkedHashMap<>(payload));
        step.setTokens(tokens);
        step.setCostUsd(BigDecimal.valueOf(tokens).multiply(BigDecimal.valueOf(COST_PER_TOKEN))
                .setScale(4, RoundingMode.HALF_UP));
        synchronized (ex.lock) {
            step.setSeq(ex.seq.incrementAndGet());
            ex.run.addStep(step);
        }
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

    private int subagentsInt(Flow flow, String key, int def) {
        Object s = flow.getConfig().get("subagents");
        if (s instanceof Map<?, ?> m && m.get(key) instanceof Number n) return n.intValue();
        return def;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ------------------------------------------------------------- per-run state

    /** Per-execution context — keeps the engine bean stateless and thread-safe across runs. */
    private final class Exec {
        final Run run;
        final Flow flow;
        final Provider provider;
        final List<Capability> alwaysSkills = new ArrayList<>();
        final Map<String, Capability> onDemandSkills = new LinkedHashMap<>();
        final List<ToolDef> workerTools;
        final List<ToolDef> orchestratorTools;
        final java.util.Set<String> loadedSkills = ConcurrentHashMap.newKeySet();
        final AtomicInteger seq = new AtomicInteger();
        final AtomicInteger iterations = new AtomicInteger();
        final AtomicInteger tokens = new AtomicInteger();
        final Object lock = new Object();

        Exec(Run run, Flow flow, Provider provider) {
            this.run = run;
            this.flow = flow;
            this.provider = provider;
            for (Capability c : flow.getCapabilities()) {
                if (!"skill".equals(c.getType())) continue;
                if ("on-demand".equalsIgnoreCase(String.valueOf(c.getSpec().get("loads")))) {
                    onDemandSkills.put(c.getSlug(), c);
                } else {
                    alwaysSkills.add(c);
                }
            }
            List<ToolDef> worker = new ArrayList<>(capabilityTools(flow));
            if (!onDemandSkills.isEmpty()) worker.add(loadSkillTool(onDemandSkills));
            this.workerTools = List.copyOf(worker);
            List<ToolDef> orch = new ArrayList<>(worker);
            orch.add(spawnSubagentsTool());
            this.orchestratorTools = List.copyOf(orch);
        }
    }

    /** Result of one orchestrator/sub-agent loop. */
    private record LoopOut(String content, int tokens) {}

    /** Outcome of a single sub-agent. */
    private record SubResult(String objective, String answer,
                             List<Map<String, Object>> activity, int tokens, boolean skipped) {
        static SubResult skipped(String objective) {
            return new SubResult(objective, null, List.of(), 0, true);
        }
    }
}
