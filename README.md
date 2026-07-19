# Outpost

A slim, Sentry-SDK-compatible observability backend: error monitoring, logs, and distributed
tracing for Angular + Spring Boot apps, deployable as three containers (API server + nginx UI
+ PostgreSQL).

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

Outpost is three containers: API server, nginx UI (which serves the app under `/outpost/`
and proxies both the browser API and SDK ingest to the server, so only the UI needs to be
exposed), and Postgres. The backend assumes a **single instance** — don't scale the API
server horizontally.

### Docker Compose

On the target host, clone the repo and override the environment for your setup — the base
`docker-compose.yml` hardcodes dev values, so add a `docker-compose.override.yml`:

```yaml
# docker-compose.override.yml — production overrides (git-ignored, per host)
services:
  outpost:
    ports: []                                        # drop the direct 8080 exposure
    environment:
      OUTPOST_PUBLIC_URL: https://outpost.example.com/outpost
      OUTPOST_DB_PASSWORD: <strong-password>
      OUTPOST_ADMIN_EMAIL: you@example.com
      OUTPOST_ADMIN_PASSWORD: <strong-password>
  db:
    ports: []                                        # don't publish Postgres
    environment:
      POSTGRES_PASSWORD: <same-strong-password>
```

```bash
docker compose up -d --build     # builds server + UI images, starts all three services
```

Put a TLS-terminating reverse proxy (Caddy, Traefik, nginx) in front of port 8088.
Postgres data lives in the `outpost-pg` named volume — back that up.

### Kubernetes

There are no manifests in the repo; the example below is the minimal shape. First build
and push the two images to a registry your cluster can pull from:

```bash
docker compose build
docker tag outpost/outpost-server:latest registry.example.com/outpost-server:latest
docker tag outpost/outpost-ui:latest registry.example.com/outpost-ui:latest
docker push registry.example.com/outpost-server:latest
docker push registry.example.com/outpost-ui:latest
```

Provision Postgres however you normally do (managed database or an operator such as
CloudNativePG), then deploy:

```yaml
apiVersion: v1
kind: Secret
metadata: { name: outpost }
stringData:
  db-password: <strong-password>
  admin-password: <strong-password>
---
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
          image: registry.example.com/outpost-server:latest
          ports: [{ containerPort: 8080 }]
          env:
            - { name: OUTPOST_DB_URL, value: "jdbc:postgresql://<postgres-host>:5432/outpost" }
            - { name: OUTPOST_DB_USER, value: outpost }
            - name: OUTPOST_DB_PASSWORD
              valueFrom: { secretKeyRef: { name: outpost, key: db-password } }
            - { name: OUTPOST_PUBLIC_URL, value: "https://outpost.example.com/outpost" }
            - { name: OUTPOST_ADMIN_EMAIL, value: "you@example.com" }
            - name: OUTPOST_ADMIN_PASSWORD
              valueFrom: { secretKeyRef: { name: outpost, key: admin-password } }
          livenessProbe: { httpGet: { path: /healthz, port: 8080 } }
          readinessProbe: { httpGet: { path: /readyz, port: 8080 } }
---
apiVersion: v1
kind: Service
metadata: { name: outpost-server }
spec:
  selector: { app: outpost-server }
  ports: [{ port: 8080 }]
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
          image: registry.example.com/outpost-ui:latest
          ports: [{ containerPort: 80 }]
          env:
            - { name: OUTPOST_API_URL, value: "http://outpost-server:8080" }
          readinessProbe: { httpGet: { path: /nginx-healthz, port: 80 } }
---
apiVersion: v1
kind: Service
metadata: { name: outpost-ui }
spec:
  selector: { app: outpost-ui }
  ports: [{ port: 80 }]
---
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata: { name: outpost }
spec:
  rules:
    - host: outpost.example.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend: { service: { name: outpost-ui, port: { number: 80 } } }
  # add TLS via your usual mechanism (e.g. cert-manager)
```

Only the UI is exposed via the Ingress — SDK ingest and the browser API both flow through
its nginx proxy to `outpost-server`, matching the `OUTPOST_PUBLIC_URL` path prefix. The
app is served at `https://outpost.example.com/outpost/`.

## Configuration

All via environment variables: `OUTPOST_DB_URL`, `OUTPOST_DB_USER`, `OUTPOST_DB_PASSWORD`,
`OUTPOST_PUBLIC_URL` (base URL rendered into DSNs — a path prefix like
`https://host/outpost` is preserved, so SDK ingest can flow through the UI nginx proxy),
`OUTPOST_ADMIN_EMAIL`, `OUTPOST_ADMIN_PASSWORD` (first-run admin seed — used from Phase 1).
The UI container takes `OUTPOST_API_URL` (upstream for `/outpost/api/`, default
`http://outpost:8080`).
