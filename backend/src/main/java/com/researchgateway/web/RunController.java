package com.researchgateway.web;

import com.researchgateway.dto.RunDto;
import com.researchgateway.service.RunService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunService service;

    public RunController(RunService service) {
        this.service = service;
    }

    @GetMapping
    public List<RunDto> list(@RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "25") int size) {
        return service.list(page, size);
    }

    @GetMapping("/{id}")
    public RunDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return service.stats();
    }
}
