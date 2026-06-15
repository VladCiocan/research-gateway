package com.researchgateway.engine;

import com.researchgateway.domain.Capability;
import com.researchgateway.domain.Flow;
import com.researchgateway.domain.Run;
import com.researchgateway.domain.RunStep;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulated orchestrator–worker research engine.
 *
 * <p>Real LLM providers are not wired in this build; instead the engine produces a
 * deterministic-looking agentic trace (plan → subagents → triage → synthesis) so the
 * gateway, playground and observability surfaces are fully functional end-to-end.
 */
@Component
public class FlowEngine {

    private int seq = 0;

    public void execute(Run run, Flow flow) {
        seq = 0;
        run.setStatus("running");

        String query = String.valueOf(run.getInput().getOrDefault("query",
                run.getInput().getOrDefault("sector", "the requested topic")));

        BigDecimal totalCost = BigDecimal.ZERO;
        int totalTokens = 0;

        // 1. Plan
        List<String> subQuestions = decompose(query);
        totalTokens += addStep(run, "plan",
                "Orchestrator decomposed the request",
                "Lead agent broke the request into " + subQuestions.size() + " sub-questions and selected capabilities.",
                Map.of("subQuestions", subQuestions,
                        "capabilities", flow.getCapabilities().stream().map(Capability::getName).toList()),
                420);

        // 2. Subagents — search (parallel, one per sub-question)
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (int i = 0; i < subQuestions.size(); i++) {
            String sq = subQuestions.get(i);
            List<Map<String, Object>> found = fakeSources(sq, i);
            candidates.addAll(found);
            totalTokens += addStep(run, "subagent",
                    "Search subagent #" + (i + 1),
                    "Searched sources for: \"" + sq + "\"",
                    Map.of("task", sq, "sources", found),
                    300 + ThreadLocalRandom.current().nextInt(200));
        }

        // 3. Triage — filter / dedupe / rank
        List<Map<String, Object>> ranked = candidates.stream()
                .sorted((a, b) -> Double.compare(
                        (double) b.get("relevance"), (double) a.get("relevance")))
                .limit(5)
                .toList();
        totalTokens += addStep(run, "subagent",
                "Triage subagent — rank & dedupe",
                "Deduplicated and ranked " + candidates.size() + " candidates down to " + ranked.size() + " by relevance.",
                Map.of("kept", ranked.size(), "discarded", candidates.size() - ranked.size()),
                260);

        // 4. Guardrail check
        addStep(run, "guardrail",
                "Guardrail check passed",
                "Iterations, cost and timeout within configured limits.",
                Map.of("maxCostUsd", flow.getConfig().getOrDefault("max_cost_usd", 2.5),
                        "iterations", seq),
                0);

        // 5. Synthesis
        List<Map<String, Object>> sources = new ArrayList<>(ranked);
        String summary = synthesize(query, ranked);
        totalTokens += addStep(run, "synthesis",
                "Synthesis with citations",
                "Composed the final answer with inline citations to the selected sources.",
                Map.of("sourceCount", sources.size()),
                650);

        totalCost = BigDecimal.valueOf(totalTokens)
                .multiply(BigDecimal.valueOf(0.000004))
                .setScale(4, RoundingMode.HALF_UP);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("summary", summary);
        output.put("findings", ranked.stream().map(s -> s.get("title")).toList());
        output.put("sources", sources);

        run.setOutput(output);
        run.setTokens(totalTokens);
        run.setCostUsd(totalCost);
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
        step.setCostUsd(BigDecimal.valueOf(tokens)
                .multiply(BigDecimal.valueOf(0.000004))
                .setScale(4, RoundingMode.HALF_UP));
        run.addStep(step);
        return tokens;
    }

    private List<String> decompose(String query) {
        return List.of(
                "Background and current state of " + query,
                "Key players and competitors related to " + query,
                "Recent trends and signals around " + query,
                "Risks, gaps and open questions about " + query);
    }

    private List<Map<String, Object>> fakeSources(String subQuestion, int idx) {
        List<Map<String, Object>> list = new ArrayList<>();
        int n = 2 + ThreadLocalRandom.current().nextInt(2);
        for (int i = 0; i < n; i++) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("title", "Source " + (char) ('A' + idx) + (i + 1) + " — " + truncate(subQuestion));
            s.put("url", "https://example.org/research/" + (idx) + "-" + i);
            s.put("relevance", round(0.55 + ThreadLocalRandom.current().nextDouble() * 0.45));
            list.add(s);
        }
        return list;
    }

    private String synthesize(String query, List<Map<String, Object>> ranked) {
        StringBuilder sb = new StringBuilder();
        sb.append("Based on the agentic research across multiple sources, here is a synthesis on **")
                .append(query).append("**.\n\n");
        sb.append("The orchestrator dispatched parallel search subagents, triaged the candidate set, ")
                .append("and the most relevant findings are summarized below");
        if (!ranked.isEmpty()) {
            sb.append(" with citations");
            for (int i = 0; i < ranked.size(); i++) {
                sb.append(" [").append(i + 1).append("]");
            }
        }
        sb.append(".\n\nKey takeaways were extracted, deduplicated and ranked by relevance to give a ")
                .append("concise, source-backed answer. This is a simulated run — connect a real LLM ")
                .append("provider to produce live results.");
        return sb.toString();
    }

    private String truncate(String s) {
        return s.length() > 48 ? s.substring(0, 45) + "..." : s;
    }

    private double round(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
