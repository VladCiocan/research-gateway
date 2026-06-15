package com.researchgateway.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record FlowRequest(
        @NotBlank String name,
        String slug,
        String status,
        String description,
        Map<String, Object> config,
        List<UUID> capabilityIds) {
}
