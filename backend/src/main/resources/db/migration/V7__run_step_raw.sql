-- Store the raw, unprocessed response of each step (tool result, LLM output, sub-agent answer, …)
-- so the UI can show it on demand. Nullable: not every step type produces a raw response.
ALTER TABLE run_steps ADD COLUMN raw TEXT;
