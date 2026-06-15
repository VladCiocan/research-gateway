package com.researchgateway.dto;

import com.researchgateway.domain.Flow;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record FlowDto(
        UUID id,
        String slug,
        String name,
        Integer version,
        String status,
        String description,
        Map<String, Object> config,
        List<CapabilityDto> capabilities,
        Instant createdAt,
        Instant updatedAt) {

    public static FlowDto from(Flow f) {
        return new FlowDto(
                f.getId(), f.getSlug(), f.getName(), f.getVersion(), f.getStatus(),
                f.getDescription(), f.getConfig(),
                f.getCapabilities().stream().map(CapabilityDto::from).sorted(
                        (a, b) -> a.name().compareToIgnoreCase(b.name())).toList(),
                f.getCreatedAt(), f.getUpdatedAt());
    }
}
