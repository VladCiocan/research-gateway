package com.researchgateway.service;

import com.researchgateway.domain.Capability;
import com.researchgateway.dto.CapabilityDto;
import com.researchgateway.dto.CapabilityRequest;
import com.researchgateway.engine.McpConnector;
import com.researchgateway.engine.functions.FunctionRegistry;
import com.researchgateway.repository.CapabilityRepository;
import com.researchgateway.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class CapabilityService {

    private final CapabilityRepository repository;
    private final FunctionRegistry functions;
    private final McpConnector mcp;

    public CapabilityService(CapabilityRepository repository,
                             FunctionRegistry functions, McpConnector mcp) {
        this.repository = repository;
        this.functions = functions;
        this.mcp = mcp;
    }

    /** Run a single capability in isolation with caller-supplied input. */
    public Map<String, Object> test(UUID id, Map<String, Object> body) {
        Capability c = find(id);
        Map<String, Object> args = body != null && body.get("args") instanceof Map
                ? (Map<String, Object>) body.get("args")
                : (body != null ? body : Map.of());
        try {
            if ("function".equals(c.getType()) && functions.has(c.getSlug())) {
                return Map.of("ok", true, "result", functions.get(c.getSlug()).execute(args));
            }
            if ("mcp".equals(c.getType()) && mcp.isBuiltin(c)) {
                String op = body != null ? String.valueOf(body.getOrDefault("operation", "")) : "";
                if (op.isBlank()) return Map.of("ok", false, "message", "Provide an 'operation' to test.");
                return Map.of("ok", true, "result", mcp.execute(c, op, args));
            }
        } catch (Exception ex) {
            return Map.of("ok", false, "message", ex.getMessage());
        }
        return Map.of("ok", false,
                "message", "This capability type is not directly testable (no live runtime).");
    }

    @Transactional(readOnly = true)
    public List<CapabilityDto> list(String type) {
        List<Capability> items = (type == null || type.isBlank())
                ? repository.findAllByOrderByTypeAscNameAsc()
                : repository.findByTypeOrderByNameAsc(type);
        return items.stream().map(CapabilityDto::from).toList();
    }

    @Transactional(readOnly = true)
    public CapabilityDto get(UUID id) {
        return CapabilityDto.from(find(id));
    }

    public CapabilityDto create(CapabilityRequest req) {
        Capability c = new Capability();
        c.setType(req.type());
        c.setName(req.name());
        c.setSlug(uniqueSlug(req.slug(), req.name()));
        c.setDescription(req.description());
        c.setSpec(req.spec() != null ? req.spec() : new HashMap<>());
        return CapabilityDto.from(repository.save(c));
    }

    public CapabilityDto update(UUID id, CapabilityRequest req) {
        Capability c = find(id);
        c.setType(req.type());
        c.setName(req.name());
        c.setDescription(req.description());
        if (req.spec() != null) c.setSpec(req.spec());
        c.setVersion(c.getVersion() + 1);
        return CapabilityDto.from(repository.save(c));
    }

    public void delete(UUID id) {
        Capability c = find(id);
        repository.delete(c);
    }

    private Capability find(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Capability not found: " + id));
    }

    private String uniqueSlug(String requested, String name) {
        String base = (requested != null && !requested.isBlank())
                ? Slugs.slugify(requested) : Slugs.slugify(name);
        String slug = base;
        int i = 2;
        while (repository.existsBySlug(slug)) {
            slug = base + "-" + i++;
        }
        return slug;
    }
}
