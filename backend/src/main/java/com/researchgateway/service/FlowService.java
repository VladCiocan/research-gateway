package com.researchgateway.service;

import com.researchgateway.domain.Capability;
import com.researchgateway.domain.Flow;
import com.researchgateway.dto.FlowDto;
import com.researchgateway.dto.FlowRequest;
import com.researchgateway.repository.CapabilityRepository;
import com.researchgateway.repository.FlowRepository;
import com.researchgateway.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class FlowService {

    private final FlowRepository flowRepository;
    private final CapabilityRepository capabilityRepository;

    public FlowService(FlowRepository flowRepository, CapabilityRepository capabilityRepository) {
        this.flowRepository = flowRepository;
        this.capabilityRepository = capabilityRepository;
    }

    @Transactional(readOnly = true)
    public List<FlowDto> list() {
        return flowRepository.findAllByOrderByUpdatedAtDesc().stream().map(FlowDto::from).toList();
    }

    @Transactional(readOnly = true)
    public FlowDto get(UUID id) {
        return FlowDto.from(find(id));
    }

    @Transactional(readOnly = true)
    public FlowDto getBySlug(String slug) {
        return FlowDto.from(flowRepository.findBySlug(slug)
                .orElseThrow(() -> new NotFoundException("Flow not found: " + slug)));
    }

    public FlowDto create(FlowRequest req) {
        Flow f = new Flow();
        f.setName(req.name());
        f.setSlug(uniqueSlug(req.slug(), req.name()));
        f.setStatus(req.status() != null ? req.status() : "draft");
        f.setDescription(req.description());
        f.setConfig(req.config() != null ? req.config() : new HashMap<>());
        f.setCapabilities(resolveCapabilities(req.capabilityIds()));
        return FlowDto.from(flowRepository.save(f));
    }

    public FlowDto update(UUID id, FlowRequest req) {
        Flow f = find(id);
        f.setName(req.name());
        if (req.status() != null) f.setStatus(req.status());
        f.setDescription(req.description());
        if (req.config() != null) f.setConfig(req.config());
        if (req.capabilityIds() != null) f.setCapabilities(resolveCapabilities(req.capabilityIds()));
        return FlowDto.from(flowRepository.save(f));
    }

    public FlowDto publish(UUID id) {
        Flow f = find(id);
        f.setStatus("published");
        f.setVersion(f.getVersion() + 1);
        return FlowDto.from(flowRepository.save(f));
    }

    public void delete(UUID id) {
        flowRepository.delete(find(id));
    }

    private Flow find(UUID id) {
        return flowRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Flow not found: " + id));
    }

    private Set<Capability> resolveCapabilities(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return new HashSet<>();
        return new HashSet<>(capabilityRepository.findAllById(ids));
    }

    private String uniqueSlug(String requested, String name) {
        String base = (requested != null && !requested.isBlank())
                ? Slugs.slugify(requested) : Slugs.slugify(name);
        String slug = base;
        int i = 2;
        while (flowRepository.existsBySlug(slug)) {
            slug = base + "-" + i++;
        }
        return slug;
    }
}
