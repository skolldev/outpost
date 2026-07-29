# Outpost

A slim, Sentry-SDK-compatible observability backend: error monitoring, logs, and distributed
tracing for Angular + Spring Boot apps, deployable as three containers (API server + nginx UI
+ PostgreSQL).

## Run it

```bash
docker compose up -d   # builds the images on first run; UI at http://localhost:8088
```

Health: `GET /healthz` (liveness), `GET /readyz` (readiness, checks the database).

## Repo layout

| Path      | What                                                                                                                                                                 |
| --------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `server/` | Spring Boot 4 / Java 21 backend (Gradle). Ingest API, processing, query API. Own `Dockerfile` (jar → distroless runtime).                                            |
| `ui/`     | Angular 22 frontend (pnpm). Own `Dockerfile` (Angular build → nginx) and `nginx.conf` (serves the app, proxies the API same-origin).                                |
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

### Restarting the backend container after code changes

The compose stack runs pre-built images, so a plain `docker compose restart` will **not**
pick up code changes — rebuild the image and recreate the container:

```bash
docker compose up -d --build outpost    # rebuild server image + recreate the container
docker compose logs -f outpost          # watch it come back up
```

Same idea for the UI: `docker compose up -d --build ui`. (For fast iteration, prefer
`./gradlew bootRun` / `pnpm start` against `docker compose up -d db` instead of rebuilding
containers each time.)

## Deployment

Outpost is three containers: the API server, the nginx UI (which serves the app and proxies
both the browser API and SDK ingest to the server, so only the UI needs to be exposed), and
Postgres. The backend assumes a **single instance** — don't scale the API server
horizontally.

Images: `outpost/outpost-server` and `outpost/outpost-ui`.

### Docker Compose

Self-contained — nothing to clone or build:

```yaml
# compose.yml
services:
  outpost:
    image: outpost/outpost-server:latest
    restart: unless-stopped
    environment:
      OUTPOST_DB_URL: jdbc:postgresql://db:5432/outpost
      OUTPOST_DB_USER: outpost
      OUTPOST_DB_PASSWORD: <strong-password>
      OUTPOST_PUBLIC_URL: https://outpost.example.com
      OUTPOST_ADMIN_EMAIL: you@example.com
      OUTPOST_ADMIN_PASSWORD: <strong-password>
    depends_on: { db: { condition: service_healthy } }

  ui:
    image: outpost/outpost-ui:latest
    restart: unless-stopped
    ports: ["8088:80"]
    environment: { OUTPOST_API_URL: "http://outpost:8080" }
    depends_on: [outpost]

  db:
    image: postgres:17-alpine
    restart: unless-stopped
    environment: { POSTGRES_DB: outpost, POSTGRES_USER: outpost, POSTGRES_PASSWORD: <strong-password> }
    volumes: ["outpost-pg:/var/lib/postgresql/data"]
    healthcheck: { test: ["CMD-SHELL", "pg_isready -U outpost"], interval: 5s }

volumes: { outpost-pg: {} }
```

```bash
docker compose up -d
```

Put a TLS-terminating reverse proxy (Caddy, Traefik, nginx) in front of port 8088 and point
`OUTPOST_PUBLIC_URL` at the URL it serves. Postgres data lives in the `outpost-pg` named
volume — back that up.

### Kubernetes

There are no manifests in the repo; the example below is the minimal shape. Provision
Postgres however you normally do (managed database or an operator such as CloudNativePG).

```yaml
apiVersion: apps/v1
kind: Deployment
metadata: { name: outpost-server }
spec:
  replicas: 1                    # single-instance by design — do not scale up
  strategy: { type: Recreate }   # avoid two instances running during rollout
  selector: { matchLabels: { app: outpost-server } }
  template:
    metadata: { labels: { app: outpost-server } }
    spec:
      containers:
        - name: server
          image: outpost/outpost-server:latest
          ports: [{ containerPort: 8080 }]
          env:
            - { name: OUTPOST_DB_URL, value: "jdbc:postgresql://<postgres-host>:5432/outpost" }
            - { name: OUTPOST_DB_USER, value: outpost }
            - { name: OUTPOST_PUBLIC_URL, value: "https://outpost.example.com" }
            - { name: OUTPOST_ADMIN_EMAIL, value: "you@example.com" }
            - name: OUTPOST_DB_PASSWORD
              valueFrom: { secretKeyRef: { name: outpost, key: db-password } }
            - name: OUTPOST_ADMIN_PASSWORD
              valueFrom: { secretKeyRef: { name: outpost, key: admin-password } }
          livenessProbe: { httpGet: { path: /healthz, port: 8080 } }
          readinessProbe: { httpGet: { path: /readyz, port: 8080 } }
---
apiVersion: apps/v1
kind: Deployment
metadata: { name: outpost-ui }
spec:
  replicas: 1
  selector: { matchLabels: { app: outpost-ui } }
  template:
    metadata: { labels: { app: outpost-ui } }
    spec:
      containers:
        - name: ui
          image: outpost/outpost-ui:latest
          ports: [{ containerPort: 80 }]
          env: [{ name: OUTPOST_API_URL, value: "http://outpost-server:8080" }]
          readinessProbe: { httpGet: { path: /nginx-healthz, port: 80 } }
```

Add to that a `Secret` named `outpost` holding `db-password` and `admin-password`, a
`Service` per Deployment (`outpost-server` on 8080, `outpost-ui` on 80), and an Ingress
routing `/` to the `outpost-ui` service with TLS via your usual mechanism. Only the UI is
exposed — SDK ingest and the browser API both reach the server through its nginx proxy.

## Configuration

All configuration is environment variables.

Server:

| Variable | Default | What |
| -------- | ------- | ---- |
| `OUTPOST_DB_URL` | `jdbc:postgresql://localhost:5432/outpost` | Postgres JDBC URL |
| `OUTPOST_DB_USER` | `outpost` | database user |
| `OUTPOST_DB_PASSWORD` | `outpost` | database password |
| `OUTPOST_PUBLIC_URL` | `http://localhost:8080` | public base URL of this instance — rendered into the DSNs shown in the UI and into notification deep links |
| `OUTPOST_ADMIN_EMAIL` | — | first-run admin seed |
| `OUTPOST_ADMIN_PASSWORD` | — | first-run admin seed |

UI:

| Variable | Default | What |
| -------- | ------- | ---- |
| `OUTPOST_API_URL` | `http://outpost:8080` | upstream the nginx proxy forwards `/api/` to |

Health endpoints: `GET /healthz` (server liveness), `GET /readyz` (server readiness, checks
the database), `GET /nginx-healthz` (UI container).
