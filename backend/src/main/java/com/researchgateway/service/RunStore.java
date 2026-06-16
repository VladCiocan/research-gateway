package com.researchgateway.service;

import com.researchgateway.domain.Flow;
import com.researchgateway.domain.Run;
import com.researchgateway.domain.RunStep;
import com.researchgateway.dto.RunDto;
import com.researchgateway.repository.FlowRepository;
import com.researchgateway.repository.RunRepository;
import com.researchgateway.repository.RunStepRepository;
import com.researchgateway.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persistence boundary for runs and their steps.
 *
 * <p>Every mutation runs in its own {@code REQUIRES_NEW} transaction so steps are committed —
 * and therefore visible to pollers — the instant the engine records them, even while the rest of
 * the run is still executing. Independent transactions also make the writes safe to call from the
 * engine's parallel sub-agent threads.
 */
@Service
public class RunStore {

    private final RunRepository runRepository;
    private final RunStepRepository stepRepository;
    private final FlowRepository flowRepository;

    public RunStore(RunRepository runRepository, RunStepRepository stepRepository,
                    FlowRepository flowRepository) {
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.flowRepository = flowRepository;
    }

    /** Create and immediately commit a pending run so it is queryable before execution starts. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Run createPending(Flow flow, Map<String, Object> input) {
        Run run = new Run();
        run.setFlowId(flow.getId());
        run.setFlowSlug(flow.getSlug());
        run.setFlowVersion(flow.getVersion());
        run.setInput(input != null ? input : new HashMap<>());
        run.setStatus("pending");
        return runRepository.save(run);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveStep(UUID runId, RunStep step) {
        step.setRun(runRepository.getReferenceById(runId));
        stepRepository.save(step);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRunning(UUID runId) {
        runRepository.findById(runId).ifPresent(r -> r.setStatus("running"));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(UUID runId, Map<String, Object> output, int tokens, BigDecimal costUsd) {
        runRepository.findById(runId).ifPresent(r -> {
            r.setStatus("completed");
            r.setOutput(output);
            r.setTokens(tokens);
            r.setCostUsd(costUsd);
            r.setEndedAt(Instant.now());
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID runId, String error) {
        runRepository.findById(runId).ifPresent(r -> {
            r.setStatus("failed");
            r.setError(error);
            r.setEndedAt(Instant.now());
        });
    }

    @Transactional(readOnly = true)
    public Run loadRun(UUID id) {
        return runRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Run not found: " + id));
    }

    @Transactional(readOnly = true)
    public Flow loadFlow(UUID id) {
        return flowRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Flow not found: " + id));
    }

    @Transactional(readOnly = true)
    public RunDto getDto(UUID id) {
        return RunDto.from(runRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Run not found: " + id)));
    }
}
