# Outpost

A slim, Sentry-SDK-compatible observability backend: error monitoring, logs, and distributed
tracing for Angular + Spring Boot apps, deployable as two containers (app + PostgreSQL).
See [`outpost-spec.md`](outpost-spec.md) for the full spec and [`todo.md`](todo.md) for progress.

## Run it

```bash
docker compose up -d   # builds the image on first run; UI at http://localhost:8080
```

Health: `GET /healthz` (liveness), `GET /readyz` (readiness, checks the database).

## Repo layout

| Path | What |
|---|---|
| `server/` | Spring Boot 4 / Java 21 backend (Gradle). Ingest API, processing, query API. |
| `ui/` | Angular 22 frontend (pnpm). Packaged into the server jar as static resources. |
| `Dockerfile` | Multi-stage: UI build → jar build → distroless Java 21 runtime. |

## Development

Backend (needs a local Postgres — `docker compose up -d db` and the defaults connect to it):

```bash
cd server && ./gradlew bootRun    # http://localhost:8080
cd server && ./gradlew test      # integration tests use Testcontainers (needs Docker)
```

Frontend (dev server proxies `/api` to `:8080`):

```bash
cd ui && pnpm install && pnpm start    # http://localhost:4200
```

Node ≥ 24.15 required (see `.nvmrc`); Java toolchain (21) is auto-provisioned by Gradle.

## Configuration

All via environment variables: `OUTPOST_DB_URL`, `OUTPOST_DB_USER`, `OUTPOST_DB_PASSWORD`,
`OUTPOST_PUBLIC_URL` (DSN host shown in the UI), `OUTPOST_ADMIN_EMAIL`, `OUTPOST_ADMIN_PASSWORD`
(first-run admin seed — used from Phase 1).
