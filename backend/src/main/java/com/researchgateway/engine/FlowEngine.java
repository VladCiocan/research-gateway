package com.researchgateway.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.researchgateway.domain.Capability;
import com.researchgateway.domain.Flow;
import com.researchgateway.domain.Provider;
import com.researchgateway.domain.Run;
import com.researchgateway.domain.RunStep;
import com.researchgateway.engine.js.JsFunctionRuntime;
import com.researchgateway.llm.LlmClient;
import com.researchgateway.llm.LlmTypes.ChatResult;
import com.researchgateway.llm.LlmTypes.ToolCall;
import com.researchgateway.llm.LlmTypes.ToolDef;
import com.researchgateway.repository.ProviderRepository;
import com.researchgateway.service.RunStore;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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
 *   <li>call native JS/TS functions and builtin MCP operations <b>repeatedly</b> (MCP operations
 *       expose pagination metadata so the model can walk through pages);</li>
 *   <li><b>load skills on demand</b> via the synthetic {@code load_skill} tool (progressive
 *       disclosure);</li>
 *   <li><b>delegate</b> to <b>declared sub-agents</b> via the synthetic {@code spawn_subagents}
 *       tool. Each sub-agent is defined by a skill and runs as its own chat instance with its own
 *       context and its own assigned skills / functions / MCPs; its result is returned to the
 *       orchestrator. The flow config names which sub-agents exist and when to activate them.</li>
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
    private final JsFunctionRuntime js;
    private final McpConnector mcp;
    private final RunStore store;
    private final ObjectMapper mapper = new ObjectMapper();

    public FlowEngine(ProviderRepository providerRepository, LlmClient llmClient,
                      JsFunctionRuntime js, McpConnector mcp, RunStore store) {
        this.providerRepository = providerRepository;
        this.llmClient = llmClient;
        this.js = js;
        this.mcp = mcp;
        this.store = store;
    }

    public void execute(Run run, Flow flow) {
        store.markRunning(run.getId());
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
        Agent orch = ex.orchestrator;
        String query = inputText(run);
        int orchestratorLoops = clamp(guardrailInt(flow, "max_iterations", MAX_TOOL_ITERATIONS), 1, MAX_TOOL_ITERATIONS);

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", orchestratorPrompt(ex)));
        messages.add(message("user",
                "Request input (JSON):\n" + query + "\n\nProduce the requested research output."));

        addStep(ex, "plan", "Orchestrator initialized",
                "Loaded provider '" + provider.getModel() + "', " + orch.tools.size()
                        + " tool(s), " + orch.alwaysSkills.size() + " always-on skill(s), "
                        + orch.onDemandSkills.size() + " on-demand skill(s) and "
                        + ex.subagentDefs.size() + " declared sub-agent(s).",
                Map.of("model", provider.getModel(),
                        "tools", orch.tools.stream().map(ToolDef::name).toList(),
                        "skillsOnDemand", new ArrayList<>(orch.onDemandSkills.keySet()),
                        "subagents", new ArrayList<>(ex.subagentDefs.keySet()),
                        "iterationBudget", MAX_TOOL_ITERATIONS), 0);

        LoopOut out = runLoop(ex, orch, messages, orchestratorLoops, 0, null);
        String finalContent = out.content();

        // Force a final answer if the loop ended still wanting tools or hit the budget.
        if (finalContent == null) {
            ChatResult res = llmClient.chat(provider, messages, null);
            ex.tokens.addAndGet(res.totalTokens());
            finalContent = res.content();
        }

        addStep(ex, "synthesis", "Synthesis", "Final grounded answer composed by the orchestrator.",
                Map.of("length", finalContent == null ? 0 : finalContent.length(),
                        "toolIterations", ex.iterations.get()), 0, finalContent);

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
     * @param agent the acting agent (its tools, skills and functions/MCPs).
     * @param depth 0 for the lead orchestrator, &ge;1 for sub-agents.
     * @param sink  when non-null (sub-agents), each tool call is appended here instead of being
     *              recorded as a top-level trace step.
     */
    private LoopOut runLoop(Exec ex, Agent agent, List<Map<String, Object>> messages,
                            int maxLoops, int depth, List<Map<String, Object>> sink) {
        String finalContent = null;
        int localTokens = 0;
        for (int i = 0; i < maxLoops; i++) {
            if (ex.iterations.get() >= MAX_TOOL_ITERATIONS) break;   // shared hard budget exhausted

            ChatResult res = llmClient.chat(ex.provider, messages, agent.tools);
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
                Object result = executeCall(ex, agent, call, depth, share);
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
    private Object executeCall(Exec ex, Agent agent, ToolCall call, int depth, int tokenShare) {
        String name = call.name();

        if (LOAD_SKILL.equals(name)) {
            return loadSkill(ex, agent, call.arguments());
        }
        if (SPAWN_SUBAGENTS.equals(name)) {
            if (depth > 0) {
                return Map.of("error", "Sub-agents cannot spawn further sub-agents.");
            }
            return spawnSubagents(ex, call.arguments());
        }

        Object result = dispatchTool(ex, agent, name, call.arguments());
        if (depth == 0) {
            addStep(ex, "tool_call", "Tool: " + name,
                    "Executed tool with model-provided arguments.",
                    Map.of("name", name, "arguments", call.arguments(), "result", result), tokenShare,
                    toJson(result));
        }
        return result;
    }

    /** Dispatch a JS/TS function or a builtin MCP operation — restricted to the agent's assigned set. */
    private Object dispatchTool(Exec ex, Agent agent, String name, Map<String, Object> args) {
        Capability fn = agent.functionsBySlug.get(name);
        if (fn != null) {
            try {
                return js.run(fn.getSpec(), args);
            } catch (Exception exn) {
                return Map.of("error", exn.getMessage());
            }
        }
        if (name.contains(McpConnector.SEP)) {
            String slug = name.substring(0, name.indexOf(McpConnector.SEP));
            String op = name.substring(name.indexOf(McpConnector.SEP) + McpConnector.SEP.length());
            Capability m = agent.mcpBySlug.get(slug);
            if (m != null) {
                return mcp.execute(m, op, args);
            }
        }
        return Map.of("error", "Tool '" + name + "' is not assigned to this agent or not available live.");
    }

    // ---------------------------------------------------------------- skills (progressive disclosure)

    private Object loadSkill(Exec ex, Agent agent, Map<String, Object> args) {
        String slug = String.valueOf(args.getOrDefault("skill", "")).trim();
        Capability skill = agent.onDemandSkills.get(slug);
        if (skill == null) {
            return Map.of("error", "Unknown on-demand skill: '" + slug + "'.",
                    "available", new ArrayList<>(agent.onDemandSkills.keySet()));
        }
        String instructions = skillInstructions(skill);
        boolean first = ex.loadedSkills.add(agent.name + "/" + slug);
        addStep(ex, "skill", "Loaded skill: " + skill.getName(),
                (first ? "Progressive disclosure — full instructions pulled in on demand"
                        : "Skill instructions re-read on demand") + " by " + agent.name + ".",
                Map.of("skill", slug, "agent", agent.name, "instructions", instructions), 0, instructions);
        return Map.of("ok", true, "skill", slug, "instructions", instructions);
    }

    // ---------------------------------------------------------------- sub-agents (declared, skill-defined)

    @SuppressWarnings("unchecked")
    private Object spawnSubagents(Exec ex, Map<String, Object> args) {
        if (ex.subagentDefs.isEmpty()) {
            return Map.of("error", "This flow declares no sub-agents.");
        }
        String mode = "chained".equalsIgnoreCase(String.valueOf(args.get("mode"))) ? "chained" : "parallel";
        List<Map<String, Object>> tasks = new ArrayList<>();
        if (args.get("tasks") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("agent") != null) {
                    tasks.add((Map<String, Object>) m);
                }
            }
        }
        if (tasks.isEmpty()) {
            return Map.of("error", "Provide at least one task with an 'agent' and 'input'.",
                    "available", new ArrayList<>(ex.subagentDefs.keySet()));
        }
        if (tasks.size() > 8) tasks = tasks.subList(0, 8);   // keep delegation bounded

        addStep(ex, "plan", "Delegating to " + tasks.size() + " sub-agent(s) [" + mode + "]",
                mode.equals("parallel")
                        ? "Activating declared sub-agents concurrently."
                        : "Activating declared sub-agents sequentially, each building on the previous.",
                Map.of("mode", mode, "agents", tasks.stream().map(t -> taskField(t, "agent")).toList()), 0);

        List<SubResult> results = mode.equals("chained")
                ? runChained(ex, tasks)
                : runParallel(ex, tasks);

        List<Map<String, Object>> summary = new ArrayList<>();
        for (SubResult r : results) {
            addStep(ex, "subagent", "Sub-agent: " + r.agent(),
                    r.skipped() ? "Skipped — iteration budget exhausted or unknown agent."
                            : "Completed its task (skill: " + (r.skill().isBlank() ? "—" : r.skill()) + ").",
                    Map.of("agent", r.agent(), "skill", r.skill(), "input", r.input(),
                            "answer", r.answer() == null ? "" : r.answer(), "toolActivity", r.activity()),
                    r.tokens(), r.answer());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("agent", r.agent());
            entry.put("input", r.input());
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
                results.add(SubResult.skipped(task));
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
                        ? SubResult.skipped(task)
                        : runSubagent(ex, task, null);
                futures.add(executor.submit(job));
            }
            List<SubResult> results = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                try {
                    results.add(futures.get(i).get());
                } catch (Exception e) {
                    Map<String, Object> task = tasks.get(i);
                    results.add(new SubResult(taskField(task, "agent"), taskField(task, "input"), "",
                            "Sub-agent failed: " + e.getMessage(), List.of(), 0, false));
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    /** Run one declared sub-agent as its own chat instance with its own context and tools. */
    private SubResult runSubagent(Exec ex, Map<String, Object> task, String upstream) {
        String agentName = taskField(task, "agent");
        String input = taskField(task, "input");
        SubagentDef def = ex.subagentDefs.get(agentName);
        if (def == null) {
            return new SubResult(agentName, input, "",
                    "Unknown sub-agent '" + agentName + "'. Available: " + ex.subagentDefs.keySet(),
                    List.of(), 0, true);
        }
        Agent agent = ex.subagentAgent(def);
        int workerLoops = clamp(subagentsInt(ex.flow, "max_loops", 8), 1, MAX_TOOL_ITERATIONS);

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", subagentPrompt(ex, def, agent)));
        StringBuilder user = new StringBuilder("Your task:\n").append(input.isBlank() ? "(no input given)" : input);
        if (upstream != null && !upstream.isBlank()) {
            user.append("\n\nUpstream sub-agent result to build on:\n").append(upstream);
        }
        user.append("\n\nComplete the task, then return your result with no further tool calls.");
        messages.add(message("user", user.toString()));

        List<Map<String, Object>> activity = new ArrayList<>();
        LoopOut out = runLoop(ex, agent, messages, workerLoops, 1, activity);
        String answer = out.content();
        int tokens = out.tokens();
        if (answer == null) {   // force a final answer from the sub-agent
            ChatResult res = llmClient.chat(ex.provider, messages, null);
            ex.tokens.addAndGet(res.totalTokens());
            tokens += res.totalTokens();
            answer = res.content();
        }
        return new SubResult(agentName, input, def.skillSlug(), answer, activity, tokens, false);
    }

    // ---------------------------------------------------------------- prompts

    private String orchestratorPrompt(Exec ex) {
        Agent orch = ex.orchestrator;
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
                .append(" tool iterations in this session (shared with any sub-agents you activate).\n");

        appendAlwaysSkills(sb, orch);
        appendOnDemandSkills(sb, orch);
        appendSubagents(sb, ex);
        appendToolList(sb, orch.tools);
        return sb.toString();
    }

    private String subagentPrompt(Exec ex, SubagentDef def, Agent agent) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are the \"").append(def.name()).append("\" sub-agent for the flow \"")
                .append(ex.flow.getName()).append("\".\n");
        sb.append("You are a fresh chat instance with your own context. Complete the single task you are ")
                .append("given using your own assigned tools and skills. Call tools as many times as needed ")
                .append("(including for pagination). ");
        if (guardrailBool(ex.flow)) {
            sb.append("Preserve source attribution so the lead can cite it. ");
        }
        sb.append("Return a concise, evidence-backed result; do not spawn further sub-agents.\n");
        appendAlwaysSkills(sb, agent);
        appendOnDemandSkills(sb, agent);
        appendToolList(sb, agent.tools);
        return sb.toString();
    }

    private void appendAlwaysSkills(StringBuilder sb, Agent agent) {
        if (agent.alwaysSkills.isEmpty()) return;
        sb.append("\nActivated skills:\n");
        for (Capability s : agent.alwaysSkills) {
            sb.append("## ").append(s.getName()).append("\n").append(skillInstructions(s)).append("\n");
        }
    }

    private void appendOnDemandSkills(StringBuilder sb, Agent agent) {
        if (agent.onDemandSkills.isEmpty()) return;
        sb.append("\nSkills available to load on demand (call ").append(LOAD_SKILL)
                .append(" with the slug to pull in full instructions — only when the task needs it):\n");
        for (Map.Entry<String, Capability> e : agent.onDemandSkills.entrySet()) {
            sb.append("- ").append(e.getKey()).append(": ")
                    .append(e.getValue().getDescription() == null ? "" : e.getValue().getDescription())
                    .append("\n");
        }
    }

    private void appendSubagents(StringBuilder sb, Exec ex) {
        if (ex.subagentDefs.isEmpty()) return;
        sb.append("\nDeclared sub-agents — delegate by calling ").append(SPAWN_SUBAGENTS)
                .append(" with the agent name and a specific input. Each runs as its own chat instance with ")
                .append("its own context and tools, and returns its result to you. Use mode \"parallel\" for ")
                .append("independent activations, \"chained\" when one builds on another:\n");
        for (SubagentDef d : ex.subagentDefs.values()) {
            sb.append("- ").append(d.name());
            if (!d.skillSlug().isBlank()) sb.append(" (skill: ").append(d.skillSlug()).append(")");
            if (!d.when().isBlank()) sb.append(" — use when: ").append(d.when());
            sb.append("\n");
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

    @SuppressWarnings("unchecked")
    private ToolDef functionToolDef(Capability c) {
        Object schema = c.getSpec().get("input_schema");
        Map<String, Object> params = schema instanceof Map
                ? (Map<String, Object>) schema
                : Map.of("type", "object", "properties", Map.of());
        return new ToolDef(c.getSlug(), c.getDescription() == null ? "" : c.getDescription(), params);
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

    private ToolDef spawnSubagentsTool(Collection<String> agentNames) {
        Map<String, Object> mode = Map.of("type", "string", "enum", List.of("parallel", "chained"),
                "description", "parallel = independent concurrent sub-agents; chained = each builds on the previous.");
        Map<String, Object> agentProp = new LinkedHashMap<>();
        agentProp.put("type", "string");
        agentProp.put("enum", new ArrayList<>(agentNames));
        agentProp.put("description", "Name of the declared sub-agent to activate.");
        Map<String, Object> taskItem = Map.of("type", "object", "properties", Map.of(
                "agent", agentProp,
                "input", Map.of("type", "string", "description", "The specific task/question for this sub-agent.")),
                "required", List.of("agent", "input"));
        Map<String, Object> tasks = Map.of("type", "array",
                "description", "1-8 sub-agent activations.", "items", taskItem);
        Map<String, Object> schema = Map.of("type", "object",
                "properties", Map.of("mode", mode, "tasks", tasks), "required", List.of("tasks"));
        return new ToolDef(SPAWN_SUBAGENTS,
                "Activate declared sub-agents (parallel or chained); each runs in its own context and "
                        + "returns its result to you.", schema);
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
                "Composed a simulated answer.", Map.of("sourceCount", ranked.size()), 650, summary);

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
        BigDecimal cost = BigDecimal.valueOf(totalTokens).multiply(BigDecimal.valueOf(COST_PER_TOKEN))
                .setScale(4, RoundingMode.HALF_UP);
        store.finish(run.getId(), output, totalTokens, cost);
    }

    private void addStep(Exec ex, String type, String title, String detail,
                         Map<String, Object> payload, int tokens) {
        addStep(ex, type, title, detail, payload, tokens, null);
    }

    /**
     * Append a trace step and commit it immediately so pollers see it live.
     *
     * @param raw the step's raw, unprocessed response (tool result, LLM output, sub-agent answer);
     *            {@code null} for steps that produce none.
     */
    private void addStep(Exec ex, String type, String title, String detail,
                         Map<String, Object> payload, int tokens, String raw) {
        RunStep step = new RunStep();
        step.setType(type);
        step.setTitle(title);
        step.setDetail(detail);
        step.setRaw(raw);
        step.setPayload(new LinkedHashMap<>(payload));
        step.setTokens(tokens);
        step.setCostUsd(BigDecimal.valueOf(tokens).multiply(BigDecimal.valueOf(COST_PER_TOKEN))
                .setScale(4, RoundingMode.HALF_UP));
        synchronized (ex.lock) {
            step.setSeq(ex.seq.incrementAndGet());
        }
        store.saveStep(ex.runId, step);
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

    private static String taskField(Map<String, Object> task, String key) {
        Object v = task.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ------------------------------------------------------------- per-run state

    /** A resolved agent: its skills, functions, MCPs and advertised tools. */
    private static final class Agent {
        final String name;
        final List<Capability> alwaysSkills = new ArrayList<>();
        final Map<String, Capability> onDemandSkills = new LinkedHashMap<>();
        final Map<String, Capability> functionsBySlug = new LinkedHashMap<>();
        final Map<String, Capability> mcpBySlug = new LinkedHashMap<>();
        List<ToolDef> tools = List.of();

        Agent(String name) { this.name = name; }
    }

    /** A declared sub-agent: defined by a skill, with its own assigned capabilities and activation hint. */
    private record SubagentDef(String name, String skillSlug, String when, List<String> capabilitySlugs) {}

    /** Per-execution context — keeps the engine bean stateless and thread-safe across runs. */
    private final class Exec {
        final Run run;
        final java.util.UUID runId;
        final Flow flow;
        final Provider provider;
        final Map<String, SubagentDef> subagentDefs;
        final Agent orchestrator;
        final java.util.Set<String> loadedSkills = ConcurrentHashMap.newKeySet();
        final AtomicInteger seq = new AtomicInteger();
        final AtomicInteger iterations = new AtomicInteger();
        final AtomicInteger tokens = new AtomicInteger();
        final Object lock = new Object();

        Exec(Run run, Flow flow, Provider provider) {
            this.run = run;
            this.runId = run.getId();
            this.flow = flow;
            this.provider = provider;
            this.subagentDefs = parseSubagentDefs(flow);
            this.orchestrator = buildAgent("orchestrator", flow.getCapabilities(), null, !subagentDefs.isEmpty());
        }

        /** Build an agent from a set of capabilities, an optional always-on defining skill, and spawn access. */
        Agent buildAgent(String name, Collection<Capability> caps, Capability definingSkill, boolean withSpawn) {
            Agent a = new Agent(name);
            if (definingSkill != null) a.alwaysSkills.add(definingSkill);
            for (Capability c : caps) {
                if (c == definingSkill) continue;
                classify(a, c);
            }
            List<ToolDef> tools = new ArrayList<>();
            for (Capability c : a.functionsBySlug.values()) tools.add(functionToolDef(c));
            for (Capability c : a.mcpBySlug.values()) tools.addAll(mcp.toolDefs(c));
            if (!a.onDemandSkills.isEmpty()) tools.add(loadSkillTool(a.onDemandSkills));
            if (withSpawn) tools.add(spawnSubagentsTool(subagentDefs.keySet()));
            a.tools = List.copyOf(tools);
            return a;
        }

        private void classify(Agent a, Capability c) {
            if ("skill".equals(c.getType())) {
                if ("on-demand".equalsIgnoreCase(String.valueOf(c.getSpec().get("loads")))) {
                    a.onDemandSkills.put(c.getSlug(), c);
                } else {
                    a.alwaysSkills.add(c);
                }
            } else if ("function".equals(c.getType()) && js.isExecutable(c.getSpec())) {
                a.functionsBySlug.put(c.getSlug(), c);
            } else if ("mcp".equals(c.getType()) && mcp.isBuiltin(c)) {
                a.mcpBySlug.put(c.getSlug(), c);
            }
        }

        /** Resolve a declared sub-agent into a runnable agent (its defining skill + assigned capabilities). */
        Agent subagentAgent(SubagentDef def) {
            Capability defining = findCapability(def.skillSlug());
            List<Capability> assigned = new ArrayList<>();
            for (String slug : def.capabilitySlugs()) {
                Capability c = findCapability(slug);
                if (c != null) assigned.add(c);
            }
            return buildAgent(def.name(), assigned, defining, false);
        }

        Capability findCapability(String slug) {
            if (slug == null || slug.isBlank()) return null;
            for (Capability c : flow.getCapabilities()) {
                if (slug.equals(c.getSlug())) return c;
            }
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, SubagentDef> parseSubagentDefs(Flow flow) {
        Map<String, SubagentDef> defs = new LinkedHashMap<>();
        Object s = flow.getConfig().get("subagents");
        if (s instanceof Map<?, ?> sm && sm.get("agents") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m) || m.get("name") == null) continue;
                String name = String.valueOf(m.get("name")).trim();
                if (name.isBlank()) continue;
                String skill = m.get("skill") == null ? "" : String.valueOf(m.get("skill"));
                String when = m.get("when") == null ? "" : String.valueOf(m.get("when"));
                List<String> caps = new ArrayList<>();
                if (m.get("capabilities") instanceof List<?> cl) {
                    for (Object c : cl) caps.add(String.valueOf(c));
                }
                defs.put(name, new SubagentDef(name, skill, when, caps));
            }
        }
        return defs;
    }

    /** Result of one orchestrator/sub-agent loop. */
    private record LoopOut(String content, int tokens) {}

    /** Outcome of a single sub-agent activation. */
    private record SubResult(String agent, String input, String skill, String answer,
                             List<Map<String, Object>> activity, int tokens, boolean skipped) {
        static SubResult skipped(Map<String, Object> task) {
            return new SubResult(taskField(task, "agent"), taskField(task, "input"), "", null, List.of(), 0, true);
        }
    }
}
