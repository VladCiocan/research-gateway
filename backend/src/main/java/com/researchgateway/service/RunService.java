package com.researchgateway.service;

import com.researchgateway.domain.Flow;
import com.researchgateway.domain.Run;
import com.researchgateway.dto.RunDto;
import com.researchgateway.engine.FlowEngine;
import com.researchgateway.repository.FlowRepository;
import com.researchgateway.repository.RunRepository;
import com.researchgateway.web.NotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class RunService {

    private final RunRepository runRepository;
    private final FlowRepository flowRepository;
    private final FlowEngine engine;

    public RunService(RunRepository runRepository, FlowRepository flowRepository, FlowEngine engine) {
        this.runRepository = runRepository;
        this.flowRepository = flowRepository;
        this.engine = engine;
    }

    public RunDto run(String slug, Map<String, Object> input) {
        Flow flow = flowRepository.findBySlug(slug)
                .orElseThrow(() -> new NotFoundException("Flow not found: " + slug));

        Run run = new Run();
        run.setFlowId(flow.getId());
        run.setFlowSlug(flow.getSlug());
        run.setFlowVersion(flow.getVersion());
        run.setInput(input != null ? input : new HashMap<>());
        run.setStatus("pending");

        try {
            engine.execute(run, flow);
        } catch (Exception ex) {
            run.setStatus("failed");
            run.setError(ex.getMessage());
            run.setEndedAt(Instant.now());
        }

        return RunDto.from(runRepository.save(run));
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
