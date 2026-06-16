package com.researchgateway.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "run_steps")
public class RunStep {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", nullable = false)
    private Run run;

    @Column(nullable = false)
    private Integer seq;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String detail;

    /** Raw, unprocessed response of this step (tool result JSON, LLM output, sub-agent answer, …). */
    @Column(columnDefinition = "text")
    private String raw;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload = new HashMap<>();

    @Column(nullable = false)
    private Integer tokens = 0;

    @Column(name = "cost_usd", nullable = false)
    private BigDecimal costUsd = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Run getRun() { return run; }
    public void setRun(Run run) { this.run = run; }
    public Integer getSeq() { return seq; }
    public void setSeq(Integer seq) { this.seq = seq; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getRaw() { return raw; }
    public void setRaw(String raw) { this.raw = raw; }
    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
    public Integer getTokens() { return tokens; }
    public void setTokens(Integer tokens) { this.tokens = tokens; }
    public BigDecimal getCostUsd() { return costUsd; }
    public void setCostUsd(BigDecimal costUsd) { this.costUsd = costUsd; }
    public Instant getCreatedAt() { return createdAt; }
}
