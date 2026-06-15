-- Providers (LLM connections) + richer capability seeds (skills, builtin MCP, functions)

CREATE TABLE providers (
    id           UUID PRIMARY KEY,
    name         VARCHAR(160) NOT NULL,
    type         VARCHAR(32)  NOT NULL DEFAULT 'vllm',
    base_url     VARCHAR(512) NOT NULL,
    api_key      TEXT,
    model        VARCHAR(200) NOT NULL,
    context_size INTEGER      NOT NULL DEFAULT 8192,
    max_tokens   INTEGER      NOT NULL DEFAULT 1024,
    temperature  NUMERIC(4,2) NOT NULL DEFAULT 0.20,
    enabled      BOOLEAN      NOT NULL DEFAULT false,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- A placeholder custom vLLM provider (disabled until you point it at your endpoint).
INSERT INTO providers (id, name, type, base_url, model, context_size, max_tokens, temperature, enabled) VALUES
 ('cccccccc-0000-0000-0000-000000000001', 'Local vLLM', 'vllm',
  'http://host.docker.internal:8000/v1', 'your-model', 8192, 1024, 0.20, false);

-- Base skills (instruction packages, progressive disclosure).
INSERT INTO capabilities (id, type, name, slug, version, description, spec) VALUES
 ('11111111-0000-0000-0000-000000000010', 'skill', 'Orchestration', 'orchestration', 1,
  'Decompose a request into sub-questions and decide which tools/skills to use.',
  '{"loads":"always","instructions":"You are the lead orchestrator. Break the user request into concrete sub-questions. For each, decide whether a tool or MCP operation can answer it, and call it. Prefer parallelizable, narrow steps. Stop once you have enough grounded evidence to answer."}'),
 ('11111111-0000-0000-0000-000000000011', 'skill', 'Concatenation', 'concatenation', 1,
  'Merge partial results from multiple steps into one coherent context.',
  '{"loads":"on-demand","instructions":"Combine the gathered fragments into a single, de-duplicated, ordered context. Preserve source attribution for every fragment so it can be cited later."}'),
 ('11111111-0000-0000-0000-000000000012', 'skill', 'Extraction', 'extraction', 1,
  'Extract structured fields from unstructured source text.',
  '{"loads":"on-demand","instructions":"From the provided sources, extract only the requested fields as strict JSON. Do not invent values; use null when a field is absent."}'),
 ('11111111-0000-0000-0000-000000000013', 'skill', 'Ranking & Triage', 'ranking-triage', 1,
  'Score, rank and filter candidates by relevance and confidence.',
  '{"loads":"on-demand","instructions":"Score each candidate 0-1 for relevance to the question and discard low-confidence or duplicate items. Return the ranked shortlist with brief justifications."}'),
 ('11111111-0000-0000-0000-000000000014', 'skill', 'Synthesis with Citations', 'synthesis-citations', 1,
  'Compose the final answer grounded in sources with inline citations.',
  '{"loads":"always","instructions":"Write a clear, well-structured answer. Ground every claim in the gathered sources and add inline citations like [1], [2]. If evidence is insufficient, say so explicitly."}');

-- New native backend functions.
INSERT INTO capabilities (id, type, name, slug, version, description, spec) VALUES
 ('33333333-0000-0000-0000-000000000010', 'function', 'Concatenate', 'concatenate', 1,
  'Native function: join an array of text fragments into one string.',
  '{"runtime":"backend","input_schema":{"type":"object","properties":{"items":{"type":"array","items":{"type":"string"}},"separator":{"type":"string"}},"required":["items"]}}'),
 ('33333333-0000-0000-0000-000000000011', 'function', 'Read URL', 'read_url_fn', 1,
  'Native function: fetch a URL and return its readable text content.',
  '{"runtime":"backend","input_schema":{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}}');

-- Mark existing MCP servers with their kind.
UPDATE capabilities SET spec = spec || '{"kind":"external"}'::jsonb
 WHERE slug IN ('internal-knowledge-base', 'crm-connector');

-- A builtin (REST integration) MCP example, configurable in-platform.
INSERT INTO capabilities (id, type, name, slug, version, description, spec) VALUES
 ('44444444-0000-0000-0000-000000000010', 'mcp', 'Public Holidays API', 'public-holidays', 1,
  'Builtin REST integration (Nager.Date) — example of an in-platform MCP connector.',
  '{"kind":"builtin","base_url":"https://date.nager.at/api/v3","auth_header":"","operations":[{"name":"next_holidays","method":"GET","path":"/NextPublicHolidaysWorldwide","description":"List upcoming public holidays worldwide","params":{}},{"name":"holidays_by_country","method":"GET","path":"/PublicHolidays/{year}/{countryCode}","description":"Public holidays for a year and ISO country code","params":{"year":"integer (path)","countryCode":"string (path), e.g. RO"}}]}');

-- Attach the new base skills + a builtin MCP to the market-research flow.
INSERT INTO flow_capabilities (flow_id, capability_id) VALUES
 ('aaaaaaaa-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000010'),
 ('aaaaaaaa-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000014'),
 ('aaaaaaaa-0000-0000-0000-000000000001', '33333333-0000-0000-0000-000000000010');
