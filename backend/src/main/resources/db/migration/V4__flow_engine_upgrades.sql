-- Engine upgrades: higher iteration budgets, sub-agent config, more on-demand skills,
-- and a paginated builtin MCP example.

-- Raise iteration ceilings (hard-capped at 120 by the engine) and add sub-agent loop bounds.
UPDATE flows SET config = jsonb_set(config, '{guardrails,max_iterations}', '40'::jsonb)
 WHERE slug = 'market-research';
UPDATE flows SET config = jsonb_set(config, '{subagents,max_loops}', '8'::jsonb)
 WHERE slug = 'market-research';

UPDATE flows SET config = jsonb_set(config, '{guardrails,max_iterations}', '24'::jsonb)
 WHERE slug = 'competitive-intel';
UPDATE flows SET config = config || '{"subagents":{"max_concurrent":4,"max_loops":6}}'::jsonb
 WHERE slug = 'competitive-intel';

-- More on-demand skills. A flow can carry many of these; the orchestrator loads only what it needs.
INSERT INTO capabilities (id, type, name, slug, version, description, spec) VALUES
 ('11111111-0000-0000-0000-000000000020', 'skill', 'Source Triangulation', 'source-triangulation', 1,
  'Cross-check a claim across independent sources before trusting it.',
  '{"loads":"on-demand","instructions":"For each key claim, confirm it appears in at least two independent sources. Flag single-source or conflicting claims explicitly."}'),
 ('11111111-0000-0000-0000-000000000021', 'skill', 'Numeric Reconciliation', 'numeric-reconciliation', 1,
  'Reconcile conflicting figures and show the math.',
  '{"loads":"on-demand","instructions":"When sources disagree on a number, present each figure with its source, pick the most credible, and show how any derived totals were computed."}'),
 ('11111111-0000-0000-0000-000000000022', 'skill', 'Timeline Builder', 'timeline-builder', 1,
  'Assemble a dated chronology of events from the evidence.',
  '{"loads":"on-demand","instructions":"Extract dated events and order them into a clean timeline; note uncertainty where dates are approximate."}');

-- A paginated builtin MCP example (JSONPlaceholder) — demonstrates pageInfo-driven paging.
INSERT INTO capabilities (id, type, name, slug, version, description, spec) VALUES
 ('44444444-0000-0000-0000-000000000020', 'mcp', 'Demo Posts API', 'demo-posts', 1,
  'Builtin REST integration (JSONPlaceholder) demonstrating pagination.',
  '{"kind":"builtin","base_url":"https://jsonplaceholder.typicode.com","auth_header":"","operations":[{"name":"list_posts","method":"GET","path":"/posts","description":"List posts page by page","paginated":true,"params":{"_page":"integer (1-based page)","_limit":"integer (page size)"}}]}');

-- Attach the new on-demand skills + paginated MCP to the market-research flow.
INSERT INTO flow_capabilities (flow_id, capability_id) VALUES
 ('aaaaaaaa-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000020'),
 ('aaaaaaaa-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000021'),
 ('aaaaaaaa-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000022'),
 ('aaaaaaaa-0000-0000-0000-000000000001', '44444444-0000-0000-0000-000000000020');
