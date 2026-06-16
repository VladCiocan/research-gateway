package com.researchgateway.dto;

import com.researchgateway.domain.Run;
import com.researchgateway.domain.RunStep;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RunDto(
        UUID id,
        UUID flowId,
        String flowSlug,
        Integer flowVersion,
        String status,
        Map<String, Object> input,
        Map<String, Object> output,
        String error,
        BigDecimal costUsd,
        Integer tokens,
        Instant startedAt,
        Instant endedAt,
        List<StepDto> steps) {

    public record StepDto(
            UUID id,
            Integer seq,
            String type,
            String title,
            String detail,
            String raw,
            Map<String, Object> payload,
            Integer tokens,
            BigDecimal costUsd,
            Instant createdAt) {

        static StepDto from(RunStep s) {
            return new StepDto(s.getId(), s.getSeq(), s.getType(), s.getTitle(),
                    s.getDetail(), s.getRaw(), s.getPayload(), s.getTokens(), s.getCostUsd(), s.getCreatedAt());
        }
    }

    public static RunDto from(Run r) {
        return new RunDto(
                r.getId(), r.getFlowId(), r.getFlowSlug(), r.getFlowVersion(), r.getStatus(),
                r.getInput(), r.getOutput(), r.getError(), r.getCostUsd(), r.getTokens(),
                r.getStartedAt(), r.getEndedAt(),
                r.getSteps().stream().map(StepDto::from).toList());
    }

    public static RunDto summary(Run r) {
        return new RunDto(
                r.getId(), r.getFlowId(), r.getFlowSlug(), r.getFlowVersion(), r.getStatus(),
                r.getInput(), null, r.getError(), r.getCostUsd(), r.getTokens(),
                r.getStartedAt(), r.getEndedAt(), List.of());
    }
}
