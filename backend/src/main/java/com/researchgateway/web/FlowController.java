package com.researchgateway.web;

import com.researchgateway.dto.FlowDto;
import com.researchgateway.dto.FlowRequest;
import com.researchgateway.dto.RunDto;
import com.researchgateway.service.FlowService;
import com.researchgateway.service.RunService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/flows")
public class FlowController {

    private final FlowService service;
    private final RunService runService;

    public FlowController(FlowService service, RunService runService) {
        this.service = service;
        this.runService = runService;
    }

    @GetMapping
    public List<FlowDto> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public FlowDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<FlowDto> create(@Valid @RequestBody FlowRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PutMapping("/{id}")
    public FlowDto update(@PathVariable UUID id, @Valid @RequestBody FlowRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/publish")
    public FlowDto publish(@PathVariable UUID id) {
        return service.publish(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Dynamic, config-driven run endpoint: POST /api/flows/{slug}/run */
    @PostMapping("/{slug}/run")
    public RunDto run(@PathVariable String slug, @RequestBody(required = false) Map<String, Object> input) {
        return runService.run(slug, input);
    }
}
