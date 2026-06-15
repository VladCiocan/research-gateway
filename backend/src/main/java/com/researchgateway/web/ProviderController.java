package com.researchgateway.web;

import com.researchgateway.dto.ProviderDto;
import com.researchgateway.dto.ProviderRequest;
import com.researchgateway.service.ProviderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/providers")
public class ProviderController {

    private final ProviderService service;

    public ProviderController(ProviderService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProviderDto> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<ProviderDto> create(@Valid @RequestBody ProviderRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PutMapping("/{id}")
    public ProviderDto update(@PathVariable UUID id, @Valid @RequestBody ProviderRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable UUID id) {
        return service.test(id);
    }
}
