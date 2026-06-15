package com.researchgateway.dto;

import com.researchgateway.domain.Capability;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CapabilityDto(
        UUID id,
        String type,
        String name,
        String slug,
        Integer version,
        String description,
        Map<String, Object> spec,
        Instant createdAt,
        Instant updatedAt) {

    public static CapabilityDto from(Capability c) {
        return new CapabilityDto(
                c.getId(), c.getType(), c.getName(), c.getSlug(), c.getVersion(),
                c.getDescription(), c.getSpec(), c.getCreatedAt(), c.getUpdatedAt());
    }
}
