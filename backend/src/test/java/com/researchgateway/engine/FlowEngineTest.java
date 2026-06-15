package com.researchgateway.engine;

import com.researchgateway.domain.Capability;
import com.researchgateway.domain.Flow;
import com.researchgateway.domain.Provider;
import com.researchgateway.domain.Run;
import com.researchgateway.engine.functions.ConcatenateFunction;
import com.researchgateway.engine.functions.DedupeFunction;
import com.researchgateway.engine.functions.FunctionRegistry;
import com.researchgateway.engine.functions.RankFunction;
import com.researchgateway.engine.functions.ReadUrlFunction;
import com.researchgateway.llm.LlmClient;
import com.researchgateway.llm.LlmTypes.ChatResult;
import com.researchgateway.llm.LlmTypes.ToolCall;
import com.researchgateway.repository.ProviderRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class FlowEngineTest {

    private FunctionRegistry registry() {
        return new FunctionRegistry(List.of(
                new ConcatenateFunction(), new DedupeFunction(),
                new RankFunction(), new ReadUrlFunction()));
    }

    private Provider enabledProvider() {
        Provider p = new Provider();
        p.setModel("test-model");
        p.setBaseUrl("http://localhost/v1");
        p.setMaxTokens(128);
        p.setTemperature(BigDecimal.ZERO);
        p.setEnabled(true);
        return p;
    }

    private Capability function(String slug, String name) {
        Capability fn = new Capability();
        fn.setType("function");
        fn.setSlug(slug);
        fn.setName(name);
        return fn;
    }

    private Capability skill(String slug, String name, String loads, String instructions) {
        Capability s = new Capability();
        s.setType("skill");
        s.setSlug(slug);
        s.setName(name);
        s.setDescription(name + " skill");
        s.setSpec(Map.of("loads", loads, "instructions", instructions));
        return s;
    }

    private Flow flowWithFunction() {
        Flow flow = new Flow();
        flow.setName("Unit Flow");
        flow.setConfig(Map.of("guardrails", Map.of("max_iterations", 6)));
        flow.setCapabilities(Set.of(function("concatenate", "Concatenate")));
        return flow;
    }

    private FlowEngine engine(ProviderRepository providers, LlmClient llm) {
        return new FlowEngine(providers, llm, registry(), new McpConnector());
    }

    private ProviderRepository providersWithEnabled() {
        ProviderRepository providers = mock(ProviderRepository.class);
        when(providers.findFirstByEnabledTrueOrderByUpdatedAtDesc())
                .thenReturn(Optional.of(enabledProvider()));
        return providers;
    }

    @Test
    void liveLoopExecutesToolThenTerminatesWithSynthesis() {
        ProviderRepository providers = providersWithEnabled();

        // Stub model: ask for a tool first, then (once a tool result is present) give the answer.
        LlmClient llm = mock(LlmClient.class);
        when(llm.chat(any(), any(), any())).thenAnswer(inv -> {
            List<Map<String, Object>> messages = inv.getArgument(1);
            boolean toolResultSeen = messages.stream()
                    .anyMatch(m -> "tool".equals(m.get("role")));
            if (toolResultSeen) {
                return new ChatResult("Final grounded answer [1].", List.of(), 20, 8);
            }
            return new ChatResult("", List.of(
                    new ToolCall("call_1", "concatenate",
                            Map.of("items", List.of("alpha", "beta")))), 15, 5);
        });

        FlowEngine engine = engine(providers, llm);

        Run run = new Run();
        run.setInput(Map.of("query", "merge things"));
        engine.execute(run, flowWithFunction());

        assertEquals("completed", run.getStatus());
        assertEquals("Final grounded answer [1].", run.getOutput().get("summary"));

        // Exactly one tool call happened, the loop terminated well before max_iterations.
        long toolCalls = run.getSteps().stream().filter(s -> s.getType().equals("tool_call")).count();
        assertEquals(1, toolCalls, "should stop after the model is satisfied");
        assertTrue(run.getSteps().stream().anyMatch(s -> s.getType().equals("synthesis")));
        assertTrue(run.getTokens() > 0);

        // The model only needed 2 calls: tool request + final answer.
        verify(llm, times(2)).chat(any(), any(), any());
    }

    @Test
    void fallsBackToSimulationWhenNoProviderEnabled() {
        ProviderRepository providers = mock(ProviderRepository.class);
        when(providers.findFirstByEnabledTrueOrderByUpdatedAtDesc()).thenReturn(Optional.empty());
        LlmClient llm = mock(LlmClient.class);

        FlowEngine engine = engine(providers, llm);
        Run run = new Run();
        run.setInput(Map.of("query", "anything"));
        engine.execute(run, flowWithFunction());

        assertEquals("completed", run.getStatus());
        assertNotNull(run.getOutput().get("summary"));
        verifyNoInteractions(llm);
    }

    @Test
    void loadsSkillOnDemandThroughLoadSkillTool() {
        ProviderRepository providers = providersWithEnabled();

        LlmClient llm = mock(LlmClient.class);
        when(llm.chat(any(), any(), any())).thenAnswer(inv -> {
            List<Map<String, Object>> messages = inv.getArgument(1);
            boolean skillLoaded = messages.stream().anyMatch(m -> "tool".equals(m.get("role")));
            if (skillLoaded) {
                return new ChatResult("Ranked and answered [1].", List.of(), 18, 6);
            }
            return new ChatResult("", List.of(new ToolCall("call_s", "load_skill",
                    Map.of("skill", "ranking-triage"))), 12, 4);
        });

        Flow flow = new Flow();
        flow.setName("Skill Flow");
        flow.setConfig(Map.of());
        flow.setCapabilities(Set.of(
                skill("ranking-triage", "Ranking & Triage", "on-demand", "Score and rank candidates.")));

        Run run = new Run();
        run.setInput(Map.of("query", "rank these"));
        engine(providers, llm).execute(run, flow);

        assertEquals("completed", run.getStatus());
        assertEquals("Ranked and answered [1].", run.getOutput().get("summary"));

        // The on-demand skill was disclosed via a `skill` trace step, not a generic tool_call.
        var skillStep = run.getSteps().stream()
                .filter(s -> s.getType().equals("skill")).findFirst();
        assertTrue(skillStep.isPresent(), "expected a skill-load step");
        assertEquals("ranking-triage", skillStep.get().getPayload().get("skill"));
        assertEquals(0, run.getSteps().stream().filter(s -> s.getType().equals("tool_call")).count());
        verify(llm, times(2)).chat(any(), any(), any());
    }

    @Test
    void spawnsParallelSubagentsThenSynthesizes() {
        ProviderRepository providers = providersWithEnabled();

        LlmClient llm = mock(LlmClient.class);
        when(llm.chat(any(), any(), any())).thenAnswer(inv -> {
            List<Map<String, Object>> messages = inv.getArgument(1);
            String system = String.valueOf(messages.get(0).get("content"));
            if (system.contains("focused research sub-agent")) {
                return new ChatResult("Sub-agent finding.", List.of(), 9, 4);
            }
            boolean delegated = messages.stream().anyMatch(m -> "tool".equals(m.get("role")));
            if (delegated) {
                return new ChatResult("Synthesized from sub-agents [1].", List.of(), 22, 7);
            }
            return new ChatResult("", List.of(new ToolCall("call_d", "spawn_subagents",
                    Map.of("mode", "parallel",
                            "tasks", List.of(Map.of("objective", "Task A"),
                                    Map.of("objective", "Task B"))))), 14, 6);
        });

        Run run = new Run();
        run.setInput(Map.of("query", "delegate this"));
        engine(providers, llm).execute(run, flowWithFunction());

        assertEquals("completed", run.getStatus());
        assertEquals("Synthesized from sub-agents [1].", run.getOutput().get("summary"));

        long subagentSteps = run.getSteps().stream().filter(s -> s.getType().equals("subagent")).count();
        assertEquals(2, subagentSteps, "one trace step per delegated sub-agent");
        assertTrue(run.getSteps().stream()
                .anyMatch(s -> s.getTitle().startsWith("Delegating to 2 sub-agent")));

        // 2 orchestrator turns + 1 chat per sub-agent.
        verify(llm, times(4)).chat(any(), any(), any());
    }

    @Test
    void enforcesHardIterationCapWhenModelNeverStops() {
        ProviderRepository providers = providersWithEnabled();

        // Model keeps asking for a tool forever; when tools are withheld (forced final) it answers.
        LlmClient llm = mock(LlmClient.class);
        when(llm.chat(any(), any(), isNull())).thenReturn(new ChatResult("Forced final.", List.of(), 5, 2));
        when(llm.chat(any(), any(), argThat(t -> t != null))).thenReturn(new ChatResult("",
                List.of(new ToolCall("call_x", "concatenate", Map.of("items", List.of("x")))), 6, 2));

        Flow flow = new Flow();
        flow.setName("Runaway Flow");
        flow.setConfig(Map.of("guardrails", Map.of("max_iterations", 500)));   // clamped to 120
        flow.setCapabilities(Set.of(function("concatenate", "Concatenate")));

        Run run = new Run();
        run.setInput(Map.of("query", "loop"));
        engine(providers, llm).execute(run, flow);

        assertEquals("completed", run.getStatus());
        long toolCalls = run.getSteps().stream().filter(s -> s.getType().equals("tool_call")).count();
        assertEquals(FlowEngine.MAX_TOOL_ITERATIONS, toolCalls, "tool iterations are hard-capped");
        assertEquals(FlowEngine.MAX_TOOL_ITERATIONS, run.getOutput().get("toolIterations"));

        // 120 tool-bearing turns + exactly one forced-final call.
        verify(llm, times(FlowEngine.MAX_TOOL_ITERATIONS)).chat(any(), any(), argThat(t -> t != null));
        verify(llm, times(1)).chat(any(), any(), isNull());
    }
}
