package com.researchgateway.service;

import com.researchgateway.domain.Flow;
import com.researchgateway.domain.Run;
import com.researchgateway.dto.RunDto;
import com.researchgateway.engine.FlowRunner;
import com.researchgateway.repository.FlowRepository;
import com.researchgateway.repository.RunRepository;
import com.researchgateway.web.NotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RunService {

    private final RunRepository runRepository;
    private final FlowRepository flowRepository;
    private final RunStore store;
    private final FlowRunner runner;

    public RunService(RunRepository runRepository, FlowRepository flowRepository,
                      RunStore store, FlowRunner runner) {
        this.runRepository = runRepository;
        this.flowRepository = flowRepository;
        this.store = store;
        this.runner = runner;
    }

    /** Execute the flow synchronously and return the completed run (steps and output included). */
    public RunDto run(String slug, Map<String, Object> input) {
        Run run = createPending(slug, input);
        runner.execute(run.getId(), run.getFlowId());
        return store.getDto(run.getId());
    }

    /**
     * Kick off the flow on a background thread and return the still-pending run immediately. The
     * caller polls {@code GET /api/runs/{id}} to watch steps stream in and observe the final state.
     */
    public RunDto runAsync(String slug, Map<String, Object> input) {
        Run run = createPending(slug, input);
        runner.launch(run.getId(), run.getFlowId());
        return store.getDto(run.getId());
    }

    private Run createPending(String slug, Map<String, Object> input) {
        Flow flow = flowRepository.findBySlug(slug)
                .orElseThrow(() -> new NotFoundException("Flow not found: " + slug));
        // createPending commits in its own transaction so the run is visible before execution.
        return store.createPending(flow, input != null ? input : new HashMap<>());
    }

    @Transactional(readOnly = true)
    public List<RunDto> list(int page, int size) {
        Page<Run> runs = runRepository.findAllByOrderByStartedAtDesc(PageRequest.of(page, size));
        return runs.stream().map(RunDto::summary).toList();
    }

    @Transactional(readOnly = true)
    public RunDto get(UUID id) {
        return RunDto.from(runRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Run not found: " + id)));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> stats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalRuns", runRepository.count());
        stats.put("completed", runRepository.countByStatus("completed"));
        stats.put("failed", runRepository.countByStatus("failed"));
        return stats;
    }
}
