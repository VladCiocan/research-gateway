package com.researchgateway.dto;

import com.researchgateway.domain.Provider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProviderDto(
        UUID id,
        String name,
        String type,
        String baseUrl,
        boolean hasApiKey,
        String model,
        Integer contextSize,
        Integer maxTokens,
        BigDecimal temperature,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {

    public static ProviderDto from(Provider p) {
        return new ProviderDto(
                p.getId(), p.getName(), p.getType(), p.getBaseUrl(),
                p.getApiKey() != null && !p.getApiKey().isBlank(),
                p.getModel(), p.getContextSize(), p.getMaxTokens(), p.getTemperature(),
                Boolean.TRUE.equals(p.getEnabled()), p.getCreatedAt(), p.getUpdatedAt());
    }
}
