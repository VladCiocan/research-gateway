package com.researchgateway.web;

import com.researchgateway.dto.CapabilityDto;
import com.researchgateway.dto.CapabilityRequest;
import com.researchgateway.service.CapabilityService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/capabilities")
public class CapabilityController {

    private final CapabilityService service;

    public CapabilityController(CapabilityService service) {
        this.service = service;
    }

    @GetMapping
    public List<CapabilityDto> list(@RequestParam(required = false) String type) {
        return service.list(type);
    }

    @GetMapping("/{id}")
    public CapabilityDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<CapabilityDto> create(@Valid @RequestBody CapabilityRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PutMapping("/{id}")
    public CapabilityDto update(@PathVariable UUID id, @Valid @RequestBody CapabilityRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable UUID id,
                                    @RequestBody(required = false) Map<String, Object> body) {
        return service.test(id, body);
    }
}
