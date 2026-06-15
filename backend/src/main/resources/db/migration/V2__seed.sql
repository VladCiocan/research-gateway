-- Demo capabilities & flows so the gateway is populated on first boot.

INSERT INTO capabilities (id, type, name, slug, version, description, spec) VALUES
 ('11111111-0000-0000-0000-000000000001', 'skill', 'Competitor Analysis', 'competitor-analysis', 1,
  'Progressive-disclosure skill that profiles competitors and positioning.',
  '{"loads":"on-demand","resources":["frameworks/porter.md"]}'),
 ('11111111-0000-0000-0000-000000000002', 'skill', 'Sizing & TAM', 'sizing-and-tam', 1,
  'Estimates market size (TAM/SAM/SOM) from gathered evidence.',
  '{"loads":"on-demand"}'),
 ('22222222-0000-0000-0000-000000000001', 'tool', 'Web Search', 'web_search', 1,
  'Searches the public web and returns ranked results.',
  '{"input_schema":{"query":"string","limit":"integer"}}'),
 ('22222222-0000-0000-0000-000000000002', 'tool', 'Read URL', 'read_url', 1,
  'Fetches and extracts the readable content of a URL.',
  '{"input_schema":{"url":"string"}}'),
 ('33333333-0000-0000-0000-000000000001', 'function', 'Dedupe by Embedding', 'dedupe_by_embedding', 1,
  'Native backend function: removes semantically duplicate candidates.',
  '{"runtime":"backend","threshold":0.92}'),
 ('33333333-0000-0000-0000-000000000002', 'function', 'Rank by Relevance', 'rank_by_relevance', 1,
  'Native backend function: scores and ranks candidates by relevance.',
  '{"runtime":"backend"}'),
 ('44444444-0000-0000-0000-000000000001', 'mcp', 'Internal Knowledge Base', 'internal-knowledge-base', 1,
  'MCP connector to the internal document index.',
  '{"transport":"stdio","credentials":"vault://mcp/kb"}'),
 ('44444444-0000-0000-0000-000000000002', 'mcp', 'CRM Connector', 'crm-connector', 1,
  'MCP connector exposing CRM accounts and opportunities.',
  '{"transport":"http","credentials":"vault://mcp/crm"}');

INSERT INTO flows (id, slug, name, version, status, description, config) VALUES
 ('aaaaaaaa-0000-0000-0000-000000000001', 'market-research', 'Market Research', 3, 'published',
  'Sector market research with cited sources.',
  '{"models":{"orchestrator":"claude-opus-4-8","worker":"claude-haiku-4-5"},"guardrails":{"max_iterations":12,"max_cost_usd":2.5,"timeout_seconds":180,"require_citations":true},"subagents":{"max_concurrent":5,"types":["search","filter","extract"]}}'),
 ('aaaaaaaa-0000-0000-0000-000000000002', 'competitive-intel', 'Competitive Intelligence', 1, 'draft',
  'Track competitors and surface positioning signals.',
  '{"models":{"orchestrator":"claude-opus-4-8","worker":"claude-haiku-4-5"},"guardrails":{"max_iterations":8,"max_cost_usd":1.5,"timeout_seconds":120,"require_citations":true}}');

INSERT INTO flow_capabilities (flow_id, capability_id) VALUES
 ('aaaaaaaa-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000001'),
 ('aaaaaaaa-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000002'),
 ('aaaaaaaa-0000-0000-0000-000000000001', '22222222-0000-0000-0000-000000000001'),
 ('aaaaaaaa-0000-0000-0000-000000000001', '22222222-0000-0000-0000-000000000002'),
 ('aaaaaaaa-0000-0000-0000-000000000001', '33333333-0000-0000-0000-000000000001'),
 ('aaaaaaaa-0000-0000-0000-000000000001', '33333333-0000-0000-0000-000000000002'),
 ('aaaaaaaa-0000-0000-0000-000000000001', '44444444-0000-0000-0000-000000000001'),
 ('aaaaaaaa-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000001'),
 ('aaaaaaaa-0000-0000-0000-000000000002', '22222222-0000-0000-0000-000000000001'),
 ('aaaaaaaa-0000-0000-0000-000000000002', '44444444-0000-0000-0000-000000000002');
