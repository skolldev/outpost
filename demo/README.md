# Outpost Demo Shop

A shop-themed demo pair — Angular 22 frontend (`demo/frontend`) + Spring Boot 4 backend
(`demo/backend`) — instrumented with the **official Sentry SDKs** (`@sentry/angular`,
`io.sentry:sentry-spring-boot-4-starter`) and pointed at Outpost. It produces real errors,
structured logs, and distributed traces so every Outpost feature can be tested against
genuine SDK traffic instead of fabricated seed data.

Unlike `scripts/seed.sql` (which fabricates telemetry for UI browsing), the demo only needs
`demo/scripts/demo-setup.sql`: two projects + DSN keys. Everything you then see in Outpost
was produced end-to-end by the SDKs.

## Quick start (docker compose)

```bash
# 1. (recommended) Symbolication: start the base stack, log in to
#    http://localhost:8080 → Settings → API tokens → create one
#    (artifacts:write) and export it. The demo-frontend container then
#    self-uploads its source maps at startup.
docker compose up -d
export SENTRY_AUTH_TOKEN=outpost_...

# 2. Demo apps — the demo-seed service creates the projects + DSN keys first
docker compose --profile demo up -d --build
```

Open **http://localhost:4300**, sign in as a persona, click scenario buttons — then watch
**http://localhost:8080** (Issues / Logs / Traces / Releases).

### How the projects get created

The `demo-seed` service (demo profile, one-shot) applies `demo/scripts/demo-setup.sql`
before the demo apps start; they `depends_on` it with `service_completed_successfully`.
It cannot be a Postgres initdb script — Flyway creates the schema when *Outpost* boots,
so demo-seed waits for the `project` table to appear first.

- On a database with **no projects yet**, the SQL pins the demo project ids to **1 and 2**,
  which is what compose's DSN paths default to. Nothing else to do.
- If other projects already exist, ids are identity-assigned and you must reconcile them:
  `demo/scripts/print-dsns.sh --write-env`, then re-run step 2.

demo-seed is **non-destructive on re-runs**: if both demo projects already exist it leaves
them and their telemetry alone, so `docker compose --profile demo up` never wipes a session
you were in the middle of. Either way it logs the resolved DSNs
(`docker compose logs demo-seed`). To deliberately reset the demo projects:

```bash
docker compose exec -T db psql -U outpost -d outpost < demo/scripts/demo-setup.sql
```

> **Wrong project id = silent 401 drops.** Ingest validates the (project id, key) pair and
> never the DSN host. If nothing arrives, compare the DSN project id in
> `docker compose config` against `docker compose logs demo-seed`.

### 30 days of log history (for the log timeline)

The demo apps only emit telemetry for *now*, so the Logs timeline has nothing to draw at any
range wider than the session you just ran. `demo/scripts/demo-logs-30d.sql` backfills ~60k
`log_record` rows over the past 30 days — the one place in the demo where data is fabricated
rather than ingested. Run it by hand (it is **not** wired into compose):

```bash
docker compose exec -T db psql -U outpost -d outpost < demo/scripts/demo-logs-30d.sql
```

What it puts on the chart: a diurnal cycle peaking at 14:00 UTC, weekends at ~⅓ of weekday
volume, `production` + `staging` for both projects, one release cutover per project, and four
named incidents that spike volume *and* swing the level mix — a 2½h DB pool exhaustion, a
45-minute payment-gateway outage, a two-day cache stampede (warn-heavy, no error spike), and a
frontend chunk-load failure after a CDN purge. Brush any of the tall bars and the list below
narrows to that story.

Every row carries `seeded_by=demo-logs-30d` in its attributes, so `attr=seeded_by=demo-logs-30d`
selects exactly the backfill (and incident rows also carry `attr=incident=<label>`). Re-running
is idempotent — it deletes only its own rows. To remove it without touching live telemetry:

```bash
docker compose exec -T db psql -U outpost -d outpost \
  -c "DELETE FROM log_record WHERE attributes->>'seeded_by' = 'demo-logs-30d'"
```

## Dev-server mode (fast iteration)

```bash
# backend on :8081 (needs Outpost on :8080 and its db)
cd demo/backend && DEMO_SENTRY_DSN="http://ccc...c@localhost:8080/<backend-id>" ./gradlew bootRun

# frontend on :4300 — set the DSN/project id in public/config.json first
cd demo/frontend && pnpm install && pnpm start
```

Dev mode sends everything except symbolicated stacktraces (`ng serve` bundles carry no
debug ids — Outpost shows the `missing_sourcemap` status, which is itself worth testing).
For the full symbolication path:

```bash
cd demo/frontend
export SENTRY_AUTH_TOKEN=outpost_...
pnpm demo:prod   # build → inject debug ids → upload maps → serve the SAME dist on :4300
```

Never rebuild without re-uploading: debug ids change per build, and serving a rebuilt dist
with stale uploads yields unsymbolicated frames again.

## What each scenario exercises

| Trigger (scenario panel) | What it sends | Check in Outpost |
| --- | --- | --- |
| 💥 Unhandled frontend error | TypeError via Angular ErrorHandler, breadcrumbs, user | Issues → symbolicated TS stack + source context |
| 🧾 Handled error + attachment | `captureException` + tags + `cart.json` attachment | Issues detail → tags, attachment |
| 🧬 Custom fingerprint ×3 | 3 different messages, one `setFingerprint` | Issues → single issue, count 3 |
| 🔌 Failed fetch | fetch to dead port → captured TypeError | Issues + errored `http.client` span |
| 🔥 Backend 500 | `GET /api/boom` with propagated trace headers | shop-backend Issues; error pinned in the cross-service trace |
| 🐢 Slow request (2 s) | backend `cache.rebuild` span + db span | Traces → duration filter, waterfall |
| 🐇 N+1 queries | 18 orders lazily loading items | Traces → waterfall with ~19 sequential `db.query` spans |
| 🧾 Backend handled + attachment | backend `captureException` + `cart-snapshot.json` + fingerprint | shop-backend Issues detail |
| 🪵 Frontend log burst (100) | `Sentry.logger.*` + console integration | Logs → live tail, level filter |
| 🪵 Backend log burst (200) | SLF4J → Sentry logs inside the request span | Logs → search, trace-correlated ("logs around this event") |
| 🌊 Backpressure (5000 logs) | oversized burst | ingest 429 + SDK client reports; Outpost must stay healthy |
| Checkout page → Place order | browser → `POST /api/checkout` → db spans → `GET /api/shipping/quote` | Traces → one trace, 2 services, 3 transactions + correlated logs |
| Navigating Catalog ↔ Checkout | `pageload` / `navigation` transactions | Traces |
| Sign in as persona | `Sentry.setUser` + `X-Demo-User` header → backend `Sentry.setUser` | same user on events of BOTH projects |
| *(ambient)* traffic generator | logs every ~20 s, real self-HTTP txns every ~45 s, an error every ~4 min | live tail moves by itself; disable: `DEMO_TRAFFIC=false` |

Releases (`shop-frontend@1.5.0` with artifact bundle, `shop-backend@2.2.0`) and the
environment (default `dev`, switch via `DEMO_SENTRY_ENVIRONMENT`) appear under /releases
and in every filter.

## Configuration

| Variable | Used by | Default | Meaning |
| --- | --- | --- | --- |
| `DEMO_FRONTEND_PROJECT_ID` / `DEMO_BACKEND_PROJECT_ID` | compose | `1` / `2` | project ids in the DSN paths; the defaults are what `demo-seed` pins on a fresh DB — override via `print-dsns.sh --write-env` otherwise |
| `DEMO_SENTRY_DSN` | both apps | localhost DSNs | full DSN override |
| `DEMO_SENTRY_ENVIRONMENT` | both apps | `dev` | environment tag (`dev`/`qa`/`prod`/…) |
| `DEMO_SENTRY_RELEASE` | both apps | `shop-*@x.y.z` | release tag |
| `DEMO_TRAFFIC` | backend | `true` | ambient traffic generator on/off |
| `DEMO_SELF_URL` | backend | `http://localhost:8081` | base URL the backend calls itself on (checkout → shipping quote) |
| `DEMO_CORS_ORIGINS` | backend | `http://localhost:4300` | allowed browser origins (must allow `sentry-trace`/`baggage` headers) |
| `DEMO_API_BASE` | frontend | `http://localhost:8081` | backend URL the browser calls |
| `SENTRY_AUTH_TOKEN` | frontend container / upload script | — | Outpost API token (`artifacts:write`) for source map upload |
| `OUTPOST_INTERNAL_URL` | frontend container | `http://outpost:8080` | in-network Outpost URL for the self-upload |

Frontend dev mode reads `public/config.json` instead of env vars (the docker entrypoint
templates the same file from the `DEMO_*` variables).

## Networking notes

- Outpost's ingest validates only project id + key, **never the DSN host** — that's why the
  containerized backend can use `http://…@outpost:8080/<id>` while the browser SDK uses
  `http://…@localhost:8080/<id>`. No `OUTPOST_PUBLIC_URL` change needed.
- Distributed tracing across the browser → backend hop requires the backend CORS config to
  allow the `sentry-trace` and `baggage` request headers (already done in `CorsConfig`).

## What this demo deliberately does NOT cover

Outpost accepts-but-drops these envelope item types, so the SDK features are pointless to
enable here: **session replay, profiling, cron check-ins, release-health sessions**, plus
the unimplemented **CSP/security-report and minidump endpoints**.

## Troubleshooting

- **Nothing arrives at all** → project id mismatch (silent 401s). Check
  `docker compose logs demo-seed` for the real ids, then
  `demo/scripts/print-dsns.sh --write-env` and `docker compose --profile demo up -d`.
- **Frontend stacks minified / `missing_sourcemap`** → no maps uploaded for the *served*
  build. Compose: set `SENTRY_AUTH_TOKEN` and recreate demo-frontend. Dev: `pnpm demo:prod`.
- **No cross-service traces** → browser blocked the preflight; check `DEMO_CORS_ORIGINS`
  matches the frontend origin exactly.
- **429s during bursts** → intended (ingest backpressure). The SDKs buffer/drop and send
  client reports; Outpost counts them.
- **Ambient noise while debugging** → `DEMO_TRAFFIC=false docker compose --profile demo up -d`.
