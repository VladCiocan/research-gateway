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

    private Flow flowWithFunction() {
        Flow flow = new Flow();
        flow.setName("Unit Flow");
        flow.setConfig(Map.of("guardrails", Map.of("max_iterations", 6)));
        Capability fn = new Capability();
        fn.setType("function");
        fn.setSlug("concatenate");
        fn.setName("Concatenate");
        flow.setCapabilities(Set.of(fn));
        return flow;
    }

    @Test
    void liveLoopExecutesToolThenTerminatesWithSynthesis() {
        ProviderRepository providers = mock(ProviderRepository.class);
        when(providers.findFirstByEnabledTrueOrderByUpdatedAtDesc())
                .thenReturn(Optional.of(enabledProvider()));

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

        FlowEngine engine = new FlowEngine(providers, llm, registry(), new McpConnector());

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

        FlowEngine engine = new FlowEngine(providers, llm, registry(), new McpConnector());
        Run run = new Run();
        run.setInput(Map.of("query", "anything"));
        engine.execute(run, flowWithFunction());

        assertEquals("completed", run.getStatus());
        assertNotNull(run.getOutput().get("summary"));
        verifyNoInteractions(llm);
    }
}
