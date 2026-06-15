# Research Gateway

A configurable **agentic research gateway**: compose research *flows* from reusable
capabilities (skills, tools, native functions, MCP servers), then test and observe
every run from a polished web console.

This repository ships a working MVP of the architecture described in
[`researchgatewayplan.md`](./researchgatewayplan.md):

- **Backend** — Spring Boot (Java 21) + PostgreSQL via Spring Data JPA & Flyway
- **Frontend** — Angular 18 (standalone components + signals), dark, gradient-accented UI
- **Engine** — a real orchestrator loop against a custom **vLLM** (OpenAI-compatible)
  provider with tool calling: it injects skill instructions, executes **native functions**
  and **builtin MCP REST integrations** live, and produces a full, auditable trace per run.
  With no provider enabled it falls back to a deterministic simulation so the app still demos.
- **Packaging** — the Angular app is built and served as static resources by the Spring
  Boot backend, so **everything runs from a single Docker image**, orchestrated with
  Docker Compose alongside Postgres.

## Quick start (Docker Compose)

```bash
docker compose up --build
```

Then open **http://localhost:8080**.

- UI: `http://localhost:8080`
- API: `http://localhost:8080/api/...`
- Health: `http://localhost:8080/actuator/health`

Postgres is seeded on first boot with demo capabilities and two flows
(`market-research`, `competitive-intel`).

## Try the dynamic flow endpoint

Every published flow is reachable at `POST /api/flows/{slug}/run`:

```bash
curl -X POST http://localhost:8080/api/flows/market-research/run \
  -H 'Content-Type: application/json' \
  -d '{"query": "AI developer tools market"}'
```

## Local development

Backend (needs a Postgres on `localhost:5432`, db/user/pass `research_gateway`/`research`/`research`):

```bash
cd backend
mvn spring-boot:run
```

Frontend (proxies `/api` to `localhost:8080`):

```bash
cd frontend
npm install
npm start        # http://localhost:4200
```

## UI surfaces

| Screen | Purpose |
|---|---|
| **Dashboard** | Flows, capabilities, runs, cost & token overview |
| **Flows** | List + create flows, each with a dynamic run endpoint |
| **Flow Builder** | Configure models/guardrails (JSON), attach registry capabilities, publish |
| **Capabilities** | CRUD registry for skills / tools / functions / MCP servers |
| **Playground** | Run a flow with live trace, tokens, cost and cited synthesis |
| **Runs** | Full execution history with drill-down into each trace |
| **Settings** | Manage custom vLLM providers (base URL, model, context size, sampling) + test connection |

## Capabilities

- **Skills** — instruction packages injected into the orchestrator on activation (base skills
  seeded: orchestration, concatenation, extraction, ranking/triage, synthesis-with-citations).
- **Functions** — native backend code, runnable live: `concatenate`, `dedupe_by_embedding`,
  `rank_by_relevance`, `read_url_fn`.
- **MCP servers** — two kinds: **external** (a real MCP server) and **builtin** (an in-platform
  REST integration you configure with base URL, auth header and operations — each operation
  becomes a callable tool). A live example (`public-holidays`) is seeded.
- Test any function or builtin MCP operation in isolation via `POST /api/capabilities/{id}/test`
  or the **Test live** panel in the Capability Registry.

## Connecting a vLLM provider

1. Start vLLM with its OpenAI-compatible server, e.g.
   `python -m vllm.entrypoints.openai.api_server --model <model> --port 8000`.
2. In **Settings**, point the provider's base URL at `http://host.docker.internal:8000/v1`
   (the compose file maps `host.docker.internal` to the host), set the model, context size and
   sampling, **enable** it, and hit **Test connection**.
3. Runs now execute live; the Playground shows the real tool calls and synthesis.

## Architecture notes

- The Angular production build (`frontend/dist/research-gateway-ui/browser`) is copied
  into `src/main/resources/static` during the Docker build (stage 2), so a single Spring
  Boot process serves both the SPA and the REST API. Client-side routes are forwarded to
  `index.html` by `SpaForwardController`.
- Schema is managed by Flyway (`backend/src/main/resources/db/migration`).
- The current engine is a deterministic **simulation** — no live LLM calls — so the whole
  product is functional offline. Wire a real provider into `FlowEngine` to produce live
  results.

## Configuration

| Env var | Default | Description |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/research_gateway` | JDBC URL |
| `DB_USER` | `research` | DB user |
| `DB_PASSWORD` | `research` | DB password |
| `JAVA_OPTS` | _(empty)_ | Extra JVM flags |
