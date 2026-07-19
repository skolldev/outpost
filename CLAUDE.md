# Outpost

Self-hosted error tracking / observability (Sentry-compatible ingest): issues,
logs, traces, releases, uptime monitoring.

## Layout

- `server/` — Spring Boot 4 / Java 25 backend (see `server/CLAUDE.md`)
- `ui/` — Angular 22 frontend (see `ui/CLAUDE.md`)
- `demo/` — sample apps that generate telemetry for local testing; **not part
  of the product** — don't model conventions on it
- `docker-compose.yml` — local Postgres
- `scripts/` — dev helper scripts

## Verify

- Backend: `cd server && ./gradlew test` (Testcontainers — Docker must be
  running). `./gradlew compileJava` for a quick compile check.
- Frontend typecheck/build: `cd ui && npx tsc --noEmit -p tsconfig.app.json`
  (+ `-p tsconfig.spec.json`) and `npx ng build --configuration development`.
- Frontend tests run **only through Wallaby** — CLI runners fail in this repo
  (details in `ui/CLAUDE.md`).

## Wire contract

All API JSON is **snake_case**: the backend serializes Java records via a
global Jackson `SNAKE_CASE` strategy, and `ui/src/app/core/models.ts` mirrors
the JSON field names verbatim. When adding an endpoint, the record DTO on the
server and the interface in `models.ts` must line up field for field.
