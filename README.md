<div align="center">

<img src="docs/images/logo.png" alt="" width="88" height="88">

# Outpost

**Self-hosted error tracking, logs, tracing, and uptime monitoring -<br>powered by the Sentry SDKs you already use.**

<a href="https://github.com/skolldev/outpost/actions/workflows/ci.yml"><img src="https://github.com/skolldev/outpost/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
<a href="LICENSE"><img src="https://img.shields.io/badge/license-AGPL--3.0-blue.svg" alt="License: AGPL v3"></a>
<a href="server/build.gradle"><img src="https://img.shields.io/badge/java-25-orange.svg" alt="Java 25"></a>
<a href="ui/package.json"><img src="https://img.shields.io/badge/angular-22-dd0031.svg" alt="Angular 22"></a>

</div>

Outpost is on-premises observability for individuals and teams who want error monitoring
without Sentry's breadth or operational complexity. Point any Sentry SDK at it by changing
one DSN - no vendor account, no code changes, no telemetry leaving your network.

One container, one database. That's the whole product.

```bash
git clone https://github.com/skolldev/outpost.git && cd outpost
docker compose up -d          # UI at http://localhost:8080 - login: admin@local / change-me
```

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/screens/issues-dark.webp">
  <img src="docs/images/screens/issues-light.webp" alt="The Outpost issue list: grouped errors with severity, event and user counts, environments, and a 14-day sparkline.">
</picture>

---

## Why Outpost

- **Your SDKs already work.** Outpost speaks the Sentry envelope protocol, so
  `@sentry/angular`, `sentry-spring-boot`, and friends need nothing but a new DSN.
- **One durable dependency.** PostgreSQL. No Kafka, no Redis, no ClickHouse, no object
  store
- **Small enough to reason about.** A single Spring Boot process serving both the Angular
  app and the API, and a database - deployed as a single instance, on purpose
- **Your data stays yours.** Single-tenant, self-hosted, AGPL-licensed.

## Features

|                              |                                                                                                                                                                            |
| ---------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Error monitoring**         | Events grouped into Issues by fingerprint, with culprit detection, stack traces, breadcrumbs, request/user context, and per-event attachments. Triage state on each Issue. |
| **Logs**                     | Structured Log Records with severity, message body, and searchable attributes, correlated back to the Trace and Span that emitted them.                                    |
| **Distributed tracing**      | Transactions and Spans across services, rendered as a waterfall with span attributes, plus correlated Events and Log Records for the same Trace ID.                        |
| **Releases & symbolication** | Upload source maps with `sentry-cli`; minified stack frames are reconstructed to original source locations via Debug IDs.                                                  |
| **Uptime monitoring**        | Per-project HTTP probes with latency history; three consecutive failures open an Incident.                                                                                 |
| **Notifications**            | Microsoft Teams Adaptive Cards and a [generic JSON webhook](docs/notifications/generic-json-payload.md) for new Issues and Incident start/resolve, scoped per project.     |
| **Access control**           | Session auth with Admin and Member roles, revocable per-project DSN keys, and scoped API Tokens for automation.                                                            |
| **Data retention**           | Optional installation-wide retention window                                                                                                                                |

Cross-cutting project / environment / time-range filters apply across every view and are
kept in the URL, so any screen you're looking at is a link you can share.

## Quick start

Requires Docker. The bundled `docker-compose.yml` builds the image from source on first
run:

```bash
docker compose up -d
```

Open `http://localhost:8080` and sign in with the seeded admin credentials

### Send it some telemetry

Create a Project in `Settings -> Projects`. Outpost shows you a DSN:

```
http://<project-key>@localhost:8080/<project-id>
```

Drop that into your app's existing Sentry configuration - nothing else changes:

```ts
// Angular
Sentry.init({
  dsn: "http://<project-key>@localhost:8080/<project-id>",
  environment: "production",
  release: "my-app@1.4.0",
  tracesSampleRate: 1.0,
});
```

```yaml
# Spring Boot (application.yml)
sentry:
  dsn: http://<project-key>@localhost:8080/<project-id>
  environment: production
  release: my-api@1.4.0
  traces-sample-rate: 1.0
```

Environments and Releases are created automatically on first ingest - there is nothing to
register up front.

### Or run the demo apps

The repo ships an instrumented demo shop (Angular + Spring Boot) that generates real
errors, logs, and traces against your local instance:

```bash
docker compose --profile demo up -d --build     # demo shop at http://localhost:4300
```

See [`demo/README.md`](demo/README.md) for the project-seeding and DSN steps.

## Compatibility

Outpost implements the subset of the Sentry protocol that its features need:

| Endpoint                                                        | Status                                                                                               |
| --------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `POST /api/{project}/envelope/`                                 | Errors, transactions, spans, logs, attachments. Client reports are accepted and counted, not stored. |
| `POST /api/0/.../chunk-upload/`, `.../artifactbundle/assemble/` | `sentry-cli` source map upload                                                                       |
| `POST /api/{project}/security/`, `/minidump/`                   | Not implemented (`404`)                                                                              |

"Sentry-compatible" means SDK ingest and `sentry-cli` artifact upload work. It is **not** a
drop-in replacement for the Sentry API or feature set.

## Deployment

Outpost runs as two containers: the app - one Spring Boot process serving the UI, the
browser API and SDK ingest on port 8080 - and Postgres. Image: `outpost/outpost`.

Two constraints are worth knowing before you deploy:

- **Single instance by design.** The app does not coordinate across replicas, so a rolling
  update is not available: a deploy is stop-then-start, and telemetry sent during that
  window is dropped unless the SDK retries. Keep `replicas: 1` and a `Recreate` strategy.
- **Shutdown needs up to 60 seconds.** Accepted envelopes are drained on SIGTERM. Docker's
  default 10-second stop timeout kills that mid-drain, so `stop_grace_period` (Compose) or
  `terminationGracePeriodSeconds` (Kubernetes) must be raised.

Served at the host root only. Put it on its own subdomain rather than a URL sub-path.

### Docker Compose

Self-contained - nothing to clone or build:

```yaml
# compose.yml
services:
  outpost:
    image: outpost/outpost:latest
    restart: unless-stopped
    ports: ["8080:8080"]
    environment:
      OUTPOST_DB_URL: jdbc:postgresql://db:5432/outpost
      OUTPOST_DB_USER: outpost
      OUTPOST_DB_PASSWORD: <strong-password>
      OUTPOST_PUBLIC_URL: https://outpost.example.com
      OUTPOST_ADMIN_EMAIL: you@example.com
      OUTPOST_ADMIN_PASSWORD: <strong-password>
    stop_grace_period: 60s
    depends_on: { db: { condition: service_healthy } }

  db:
    image: postgres:17-alpine
    restart: unless-stopped
    environment:
      {
        POSTGRES_DB: outpost,
        POSTGRES_USER: outpost,
        POSTGRES_PASSWORD: <strong-password>,
      }
    volumes: ["outpost-pg:/var/lib/postgresql/data"]
    healthcheck: { test: ["CMD-SHELL", "pg_isready -U outpost"], interval: 5s }

volumes: { outpost-pg: {} }
```

Put a TLS-terminating reverse proxy (Caddy, Traefik, nginx) in front of port 8080 and point
`OUTPOST_PUBLIC_URL` at the URL it serves - that value is rendered into the DSNs shown in
the UI and into notification deep links. If that proxy buffers responses, exempt
`/api/internal/logs`: it is a Server-Sent Events stream and buffering stalls the live log
tail.

The `outpost-pg` named volume holds the database - back that up. Envelope spooling is a
bounded-capacity mechanism, not durable storage: Outpost drains acknowledged telemetry on
graceful shutdown, but a crash or an exceeded drain timeout can still lose it.

### Kubernetes

There are no manifests in the repo; the example below is the minimal shape. Provision
Postgres however you normally do (managed database or an operator such as CloudNativePG).

```yaml
apiVersion: apps/v1
kind: Deployment
metadata: { name: outpost }
spec:
  replicas: 1 # single-instance by design - do not scale up
  strategy: { type: Recreate } # avoid two instances running during rollout
  selector: { matchLabels: { app: outpost } }
  template:
    metadata: { labels: { app: outpost } }
    spec:
      terminationGracePeriodSeconds: 60 # the ingest drain needs it; the 30s default truncates
      containers:
        - name: outpost
          image: outpost/outpost:latest
          ports: [{ containerPort: 8080 }]
          env:
            - {
                name: OUTPOST_DB_URL,
                value: "jdbc:postgresql://<postgres-host>:5432/outpost",
              }
            - { name: OUTPOST_DB_USER, value: outpost }
            - { name: OUTPOST_PUBLIC_URL, value: "https://outpost.example.com" }
            - { name: OUTPOST_ADMIN_EMAIL, value: "you@example.com" }
            - name: OUTPOST_DB_PASSWORD
              valueFrom: { secretKeyRef: { name: outpost, key: db-password } }
            - name: OUTPOST_ADMIN_PASSWORD
              valueFrom:
                { secretKeyRef: { name: outpost, key: admin-password } }
          livenessProbe: { httpGet: { path: /healthz, port: 8080 } }
          readinessProbe: { httpGet: { path: /readyz, port: 8080 } }
```

Add to that a `Secret` named `outpost` holding `db-password` and `admin-password`, a
`Service` on 8080, and an Ingress routing `/` to it with TLS via your usual mechanism. If
the ingress controller buffers proxied responses, disable it for `/api/internal/logs` so
the live log tail streams.

## Configuration

All configuration is environment variables.

**Server**

| Variable                                          | Default                                    | What                                                                                                       |
| ------------------------------------------------- | ------------------------------------------ | ---------------------------------------------------------------------------------------------------------- |
| `OUTPOST_DB_URL`                                  | `jdbc:postgresql://localhost:5432/outpost` | Postgres JDBC URL                                                                                          |
| `OUTPOST_DB_USER`                                 | `outpost`                                  | database user                                                                                              |
| `OUTPOST_DB_PASSWORD`                             | `outpost`                                  | database password                                                                                          |
| `OUTPOST_PUBLIC_URL`                              | `http://localhost:8080`                    | public base URL of this instance - rendered into the DSNs shown in the UI and into notification deep links |
| `OUTPOST_ADMIN_EMAIL`                             | -                                          | first-run admin seed                                                                                       |
| `OUTPOST_ADMIN_PASSWORD`                          | -                                          | first-run admin seed                                                                                       |
| `OUTPOST_INGEST_MAX_ENVELOPE_WIRE_BYTES`          | `4194304` (4 MiB)                          | maximum request bytes read before decoding; uncompressed requests use the smaller of both envelope limits   |
| `OUTPOST_INGEST_MAX_ENVELOPE_DECOMPRESSED_BYTES`  | `20971520` (20 MiB)                        | maximum decoded envelope bytes; uncompressed requests use the smaller of both envelope limits               |
| `OUTPOST_INGEST_SPOOL_DIRECTORY`                  | `${java.io.tmpdir}/outpost-ingest`         | ephemeral directory for accepted envelope bodies while they await digest                                    |
| `OUTPOST_INGEST_QUEUE_CAPACITY`                   | `50000`                                    | maximum envelope spool references buffered before SDK backpressure                                           |
| `OUTPOST_INGEST_SHUTDOWN_TIMEOUT`                 | `25s`                                      | maximum time to drain acknowledged telemetry during graceful shutdown                                      |
| `OUTPOST_INGEST_SPOOL_MAX_AGE`                    | `1h`                                       | how long a spool file sits untouched before the sweep reaps it; must exceed the shutdown timeout           |
| `OUTPOST_INGEST_SPOOL_SWEEP_INTERVAL`             | `5m`                                       | how often the spool directory is swept for orphaned files                                                  |
| `OUTPOST_SHUTDOWN_PHASE_TIMEOUT`                  | `30s`                                      | maximum time Spring allows each graceful-shutdown lifecycle phase                                           |

**Health endpoints:** `GET /healthz` (liveness) and `GET /readyz` (readiness, checks the
database), both on 8080. Metrics are on the separate management port 9090
(`/actuator/prometheus`), which is deliberately not published.

## Development

| Path        | What                                                                                                                         |
| ----------- | ---------------------------------------------------------------------------------------------------------------------------- |
| `server/`   | Spring Boot 4 / Java 25 backend (Gradle). Ingest API, processing pipeline, query API. Plain JDBC, Flyway migrations, no ORM. |
| `ui/`       | Angular 22 frontend (pnpm). Standalone components, signals, Tailwind v4, spartan/ui.                                         |
| `demo/`     | Sentry-instrumented demo shop that feeds Outpost real telemetry. Not part of the product.                                    |
| `docs/adr/` | Architecture decision records.                                                                                               |

Start Postgres, then run each side natively for fast iteration:

```bash
docker compose up -d db

cd server && ./gradlew bootRun          # API on :8080
cd ui && pnpm install && pnpm start     # dev server on :4200, proxies /api to :8080
```

Node ≥ 24.15 is required; the Java 25 toolchain is auto-provisioned by Gradle.

**Tests**

```bash
cd server && ./gradlew test    # integration tests via Testcontainers (Docker required)
cd ui && pnpm test
```

The dev loop is unaffected by the single image: `ng serve` proxies `/api` to a natively
run server, exactly as before.

**Rebuilding the container.** The compose stack runs a pre-built image, so `docker compose
restart` will _not_ pick up code changes - rebuild and recreate:

```bash
docker compose up -d --build outpost
```

That builds the UI and the server in one pass, so a change to either rebuilds the jar.

## Documentation

- [Architecture decision records](docs/adr/) - why single-instance, why Postgres only, why
  best-effort ingestion
- [Domain glossary](CONTEXT.md) - the vocabulary used throughout the code and UI
- [Notification payloads](docs/notifications/) - generic JSON and Teams Adaptive Card formats

## Contributing

Issues and pull requests are welcome. CI runs lint, format check, build, and tests for both
`ui/` and `server/` - please make sure those pass locally first. Conventions for each side
live in `server/CLAUDE.md` and `ui/CLAUDE.md`.

## License

[GNU AGPL v3](LICENSE). Outpost is not affiliated with or endorsed by Sentry; "Sentry" is a
trademark of Functional Software, Inc.
