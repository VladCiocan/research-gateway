# Research Gateway

A configurable **agentic research gateway**: compose research *flows* from reusable
capabilities (skills, tools, native functions, MCP servers), then test and observe
every run from a polished web console.

This repository ships a working MVP of the architecture described in
[`researchgatewayplan.md`](./researchgatewayplan.md):

- **Backend** — Spring Boot (Java 21) + PostgreSQL via Spring Data JPA & Flyway
- **Frontend** — Angular 18 (standalone components + signals), dark, gradient-accented UI
- **Engine** — a simulated orchestrator–worker loop (plan → subagents → triage → synthesis)
  that produces a full, auditable trace per run
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
| **Settings** | Providers, MCP connectors, API keys, deployment info |

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
