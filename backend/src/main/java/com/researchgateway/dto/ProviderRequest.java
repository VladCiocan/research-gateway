package com.researchgateway.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record ProviderRequest(
        @NotBlank String name,
        String type,
        @NotBlank String baseUrl,
        String apiKey,
        @NotBlank String model,
        Integer contextSize,
        Integer maxTokens,
        BigDecimal temperature,
        Boolean enabled) {
}
