package com.researchgateway.service;

import com.researchgateway.domain.Capability;
import com.researchgateway.dto.CapabilityDto;
import com.researchgateway.dto.CapabilityRequest;
import com.researchgateway.repository.CapabilityRepository;
import com.researchgateway.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class CapabilityService {

    private final CapabilityRepository repository;

    public CapabilityService(CapabilityRepository repository) {
        this.repository = repository;
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
