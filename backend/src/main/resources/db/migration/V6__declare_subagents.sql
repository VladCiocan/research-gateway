-- Declare named, skill-defined sub-agents on the market-research flow.
-- Each sub-agent is defined by a skill and assigned its own subset of the flow's capabilities;
-- the orchestrator activates them by name (see FlowEngine spawn_subagents). All referenced slugs
-- are capabilities already attached to the market-research flow.

UPDATE flows SET config = jsonb_set(config, '{subagents,agents}', '[
  {
    "name": "evidence-gatherer",
    "skill": "source-triangulation",
    "when": "gather and cross-check evidence for a specific sub-question",
    "capabilities": ["demo-posts", "concatenate"]
  },
  {
    "name": "market-sizer",
    "skill": "sizing-and-tam",
    "when": "estimate market size (TAM/SAM/SOM) from gathered evidence",
    "capabilities": ["numeric-reconciliation", "concatenate"]
  },
  {
    "name": "competitor-profiler",
    "skill": "competitor-analysis",
    "when": "profile competitors and their positioning",
    "capabilities": ["demo-posts"]
  }
]'::jsonb)
WHERE slug = 'market-research';
