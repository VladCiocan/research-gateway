package com.researchgateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.Map;

public record CapabilityRequest(
        @NotBlank
        @Pattern(regexp = "skill|tool|function|mcp", message = "type must be one of: skill, tool, function, mcp")
        String type,
        @NotBlank String name,
        String slug,
        String description,
        Map<String, Object> spec) {
}
