-- Research Gateway initial schema

CREATE TABLE capabilities (
    id          UUID PRIMARY KEY,
    type        VARCHAR(32)  NOT NULL,            -- skill | tool | function | mcp
    name        VARCHAR(160) NOT NULL,
    slug        VARCHAR(160) NOT NULL UNIQUE,
    version     INTEGER      NOT NULL DEFAULT 1,
    description TEXT,
    spec        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_capabilities_type ON capabilities (type);

CREATE TABLE flows (
    id          UUID PRIMARY KEY,
    slug        VARCHAR(160) NOT NULL UNIQUE,
    name        VARCHAR(160) NOT NULL,
    version     INTEGER      NOT NULL DEFAULT 1,
    status      VARCHAR(32)  NOT NULL DEFAULT 'draft',   -- draft | published | archived
    description TEXT,
    config      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_flows_status ON flows (status);

CREATE TABLE flow_capabilities (
    flow_id       UUID NOT NULL REFERENCES flows (id) ON DELETE CASCADE,
    capability_id UUID NOT NULL REFERENCES capabilities (id) ON DELETE CASCADE,
    PRIMARY KEY (flow_id, capability_id)
);

CREATE TABLE runs (
    id           UUID PRIMARY KEY,
    flow_id      UUID NOT NULL REFERENCES flows (id) ON DELETE CASCADE,
    flow_slug    VARCHAR(160) NOT NULL,
    flow_version INTEGER      NOT NULL,
    status       VARCHAR(32)  NOT NULL DEFAULT 'pending', -- pending | running | completed | failed
    input        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    output       JSONB,
    error        TEXT,
    cost_usd     NUMERIC(12,4) NOT NULL DEFAULT 0,
    tokens       INTEGER       NOT NULL DEFAULT 0,
    started_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ended_at     TIMESTAMPTZ
);

CREATE INDEX idx_runs_flow ON runs (flow_id);
CREATE INDEX idx_runs_status ON runs (status);
CREATE INDEX idx_runs_started ON runs (started_at DESC);

CREATE TABLE run_steps (
    id        UUID PRIMARY KEY,
    run_id    UUID NOT NULL REFERENCES runs (id) ON DELETE CASCADE,
    seq       INTEGER     NOT NULL,
    type      VARCHAR(32) NOT NULL,    -- plan | subagent | tool_call | synthesis | guardrail
    title     VARCHAR(255) NOT NULL,
    detail    TEXT,
    payload   JSONB       NOT NULL DEFAULT '{}'::jsonb,
    tokens    INTEGER     NOT NULL DEFAULT 0,
    cost_usd  NUMERIC(12,4) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_run_steps_run ON run_steps (run_id, seq);
