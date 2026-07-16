# Outpost

A slim, Sentry-SDK-compatible observability backend: error monitoring, logs, and distributed
tracing for Angular + Spring Boot apps, deployable as three containers (API server + nginx UI

- PostgreSQL).

## Run it

```bash
docker compose up -d   # builds the images on first run; UI at http://localhost:8088/outpost/
```

Health: `GET /healthz` (liveness), `GET /readyz` (readiness, checks the database).

## Repo layout

| Path      | What                                                                                                                                                                 |
| --------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `server/` | Spring Boot 4 / Java 21 backend (Gradle). Ingest API, processing, query API. Own `Dockerfile` (jar → distroless runtime).                                            |
| `ui/`     | Angular 22 frontend (pnpm). Own `Dockerfile` (build with `--base-href`, default `/outpost/` → nginx) and `nginx.conf` (serves the app, proxies the API same-origin). |
| `demo/`   | Sentry-SDK-instrumented demo shop (Angular 22 + Spring Boot 4) that feeds Outpost real errors/logs/traces — see [`demo/README.md`](demo/README.md).                  |

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

Node ≥ 24.15 required; Java toolchain (25) is auto-provisioned by Gradle.

## Configuration

All via environment variables: `OUTPOST_DB_URL`, `OUTPOST_DB_USER`, `OUTPOST_DB_PASSWORD`,
`OUTPOST_PUBLIC_URL` (base URL rendered into DSNs — a path prefix like
`https://host/outpost` is preserved, so SDK ingest can flow through the UI nginx proxy),
`OUTPOST_ADMIN_EMAIL`, `OUTPOST_ADMIN_PASSWORD` (first-run admin seed — used from Phase 1).
The UI container takes `OUTPOST_API_URL` (upstream for `/outpost/api/`, default
`http://outpost:8080`).
