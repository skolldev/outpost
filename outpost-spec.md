# Outpost — a slim, Sentry-SDK-compatible observability backend

> Codename **Outpost** (a sentry stands at an outpost — rename at will).
>
> Status: **draft spec v0.1**, 2026-07-03.

A self-hosted alternative to Sentry's self-hosted install, answering one question well:
**"why did this error happen?"** Error monitoring, logs, and distributed tracing for exactly
two client stacks — **Angular 22** and **Spring Boot 4** — using the **unmodified official
Sentry SDKs**. Deployable as two containers (app + PostgreSQL).

Sentry self-hosted needs ~20 containers (Kafka, ClickHouse, Redis, Snuba, Relay, Symbolicator,
workers, cron jobs…). At our scale (apps with ≤ 20 concurrent users, a handful of environments)
that is two orders of magnitude more infrastructure than the problem requires. Prior art proves
the slim approach works: [Bugsink](https://www.bugsink.com/) and
[GlitchTip](https://glitchtip.com/) both ingest from unmodified Sentry SDKs with a fraction of
the moving parts. Outpost differs from those by adding **logs + tracing** (GlitchTip/Bugsink are
errors-first) while cutting everything else.

---

## 1. Goals and non-goals

### Goals

| # | Feature | Definition of done |
|---|---------|--------------------|
| G1 | Error monitoring | Errors from `@sentry/angular` and `sentry-spring-boot-4-starter` are ingested, grouped into issues, browsable/filterable. No alerting. |
| G2 | Logs | Structured logs via Sentry Logs protocol; searchable, filterable, correlated to traces and errors via `trace_id`. |
| G3 | Distributed tracing | A trace started in the browser continues through Spring Boot via `sentry-trace`/`baggage` headers; single waterfall view across both services. |
| G4 | Source maps | Angular prod stack traces are symbolicated server-side using debug-ID artifact bundles uploaded by unmodified `sentry-cli`. |
| G5 | Multi-environment | The same app reports from `dev`, `qa`, `prod` (etc.) into one project; every list/detail view filters by environment. Environments are auto-registered on first sight. |
| G6 | Trivial deployment | `docker compose up -d` locally; a Deployment + Service + Ingress + Postgres on Kubernetes. Prebuilt images pushed to a registry, matching the team's existing workflow. |

### Non-goals (explicitly out of scope)

- Metrics, performance monitoring (apdex, web vitals, slow-query detection), uptime monitoring,
  cron monitoring, session replay, user feedback, profiling.
- Alerting/notifications of any kind (email, Slack, webhooks).
- SDKs other than `@sentry/angular` and `sentry-spring-boot-4`. Other Sentry SDKs will *mostly*
  work (the protocol is shared) but are never tested against.
- Horizontal ingest scaling, Kafka-class throughput, multi-region. Design ceiling: ~50 events/s
  sustained — orders of magnitude above expected load.
- Organizations/teams. Single-tenant: one instance, one implicit org, N projects, ≤ 20 users.

### Hard constraint

**Zero client-side lock-in.** Consumer apps configure only a DSN. If Outpost dies, point the DSN
at real Sentry/GlitchTip and everything keeps working. This means the ingest surface must be
wire-compatible with the Sentry protocol subset the two SDKs actually use — Outpost adapts to
the SDKs, never the reverse.

---

## 2. System architecture

```
┌───────────────┐   envelopes (HTTPS)   ┌──────────────────────────────┐
│ Angular app   │──────────────────────▶│  Outpost (single container)  │
│ @sentry/      │                       │                              │
│  angular      │   sentry-trace +      │  Spring Boot 4 app:          │
└───────┬───────┘   baggage headers     │   • Ingest API (Sentry-      │
        │ user requests                 │     compatible)              │
        ▼                               │   • Processing pipeline      │
┌───────────────┐   envelopes (HTTPS)   │     (grouping, symbolication)│
│ Spring Boot   │──────────────────────▶│   • Query API (internal)     │
│ sentry-spring-│                       │   • Angular 22 UI (static,   │
│  boot-4       │                       │     served from the jar)     │
└───────────────┘                       └──────────────┬───────────────┘
                                                       │ JDBC
┌───────────────┐  chunk-upload +                      ▼
│ CI pipeline   │  artifactbundle/assemble   ┌──────────────────┐
│ (sentry-cli)  │───────────────────────────▶│   PostgreSQL 17  │
└───────────────┘                            │  (only stateful  │
                                             │   component)     │
                                             └──────────────────┘
```

**Stack decision (settled):** backend Spring Boot 4 / Java 21, UI Angular 22, storage a single
PostgreSQL. The Outpost UI is built by the Angular CLI and packaged into the Spring Boot jar as
static resources → **one app image**. Rationale: matches team expertise; one image + one database
is the simplest possible k8s story; and Outpost dogfoods itself — a second Outpost instance (or
the same one, carefully) monitors Outpost using the same two SDKs it supports.

**No queue, no cache, no object store.** Ingestion is handled by an in-process bounded buffer
with batch inserts (§6.1). Artifact bundles live in Postgres `bytea` (a few MB per release —
trivial at this scale). Everything is recoverable from Postgres alone; backup = `pg_dump`.

**Concurrency model:** single app replica by default. The in-process ingest buffer means a hard
kill can lose ≤ ~2 s of buffered telemetry — acceptable for observability data (Sentry's own SDKs
drop events under backpressure too). Nothing else is replica-hostile (no sticky sessions; auth is
a signed cookie), so 2+ replicas work if ever needed; only the ingest buffer's at-most-once
semantics apply per-replica.

---

## 3. Domain model

```
Project 1──* ProjectKey (DSN)
Project 1──* Environment          (auto-created, e.g. dev/qa/prod)
Project 1──* Release              (auto-created from event `release` field)
Project 1──* Issue 1──* Event     (error events, grouped by fingerprint)
Project 1──* LogRecord
Project 1──* Transaction 1──* Span
Trace (trace_id, implicit) ──* Transaction / LogRecord / Event   (cross-project!)
Release 1──* ArtifactBundle 1──* Artifact (file + sourcemap, keyed by debug_id)
User, ApiToken (for sentry-cli), Setting
```

Key semantics:

- **Project** = one deployable service. Standard setup per app: two projects, e.g. `shop-frontend`
  and `shop-backend`. A **trace is cross-project**: the trace view joins by `trace_id` across all
  projects.
- **Environment** is a dimension on every signal (event, log, transaction), taken from the SDK's
  `environment` field. It is *not* a separate project — same DSN for dev/qa/prod; the SDK config
  supplies the environment name. Unknown environment → `production` (Sentry default).
- **Issue** = group of similar error events (fingerprint, §6.2), with first_seen/last_seen,
  event count, per-environment counts, status (`unresolved` | `resolved`). Regression rule: a new
  event in a `resolved` issue flips it back to `unresolved`.
- **Release** = string from the SDK (`release: "shop-frontend@1.4.2"`). Used for filtering and
  for legacy source-map fallback; debug-ID lookup doesn't need it but the association is kept.

---

## 4. Ingest API (Sentry wire-compatible surface)

This is the compatibility contract. Everything here must match what the two SDK families and
`sentry-cli` actually send. Reference: [Sentry envelope protocol](https://develop.sentry.dev/sdk/data-model/envelopes/),
[Sentry logs protocol](https://develop.sentry.dev/sdk/telemetry/logs/).

### 4.1 DSN

Standard Sentry DSN format, generated per project key:

```
https://<public_key>@outpost.example.com/<project_id>
```

`public_key` = 32-hex random. No secret key (modern SDKs don't send one). Keys can be disabled
(revocation) and rotated (multiple active keys per project).

### 4.2 `POST /api/{project_id}/envelope/`

The only telemetry ingest endpoint — both SDKs send *everything* (errors, transactions, logs,
client reports) here as envelopes.

- **Auth**, accepted in this order: `X-Sentry-Auth` header (`sentry_key=...`), `sentry_key` query
  parameter, or `dsn` field in the envelope header. Validate key ∈ active keys of `{project_id}`;
  reject with `403` otherwise.
- **Body**: newline-delimited envelope — one JSON envelope-header line, then per item: one JSON
  item-header line (`type` required, `length` recommended) + payload. Accept
  `Content-Type: application/x-sentry-envelope` and `text/plain`. Accept `Content-Encoding: gzip`
  (the Java SDK compresses by default).
- **CORS**: the browser SDK calls this cross-origin. Respond to `OPTIONS` preflight; send
  `Access-Control-Allow-Origin: *`, allow headers `sentry-trace, baggage, content-type` and the
  `X-Sentry-Auth` variants. No credentials.
- **Response**: `200 {"id": "<event_id>"}` immediately after parsing + buffering (processing is
  async, §6.1). Malformed envelope → `400`. Oversize → `413` (limits: 20 MiB/envelope,
  1 MiB/item — tighter than Sentry's, generous for our SDKs).
- **Backpressure**: if the ingest buffer is full, respond
  `429` + `Retry-After` + `X-Sentry-Rate-Limits: <seconds>:all:organization` — both SDKs honor
  this and pause sending.

**Item-type handling table** (unknown types must be skipped silently, never rejected — this is
what keeps future SDK versions from breaking ingest):

| Item `type` | Sent by | Handling |
|---|---|---|
| `event` | both SDKs | Error pipeline: parse → symbolicate (JS) → fingerprint → issue upsert + event insert (§6) |
| `transaction` | both SDKs | Tracing pipeline: insert transaction + spans (§6.4) |
| `log` | both SDKs (`enableLogs`) | Log pipeline: unpack batch (content type `application/vnd.sentry.items.log+json`, `item_count` header), insert rows (§6.3) |
| `attachment` | optional SDK feature | Store up to 1 MiB with parent event; else drop |
| `client_report` | both SDKs | Parse, increment `discarded_events` counters (surfaced on a health page), don't store raw |
| `session`, `sessions` | JS SDK (release health) | **Drop silently** (release health is out of scope) |
| `check_in`, `profile`, `replay_event`, `replay_recording`, `statsd`, `metric_buckets` | n/a for our config | Drop silently |
| `span` | future span-streaming SDKs | Drop in v1; revisit when Sentry SDKs default to span-first envelopes (§13) |

### 4.3 `POST /api/{project_id}/security/` and `/api/{project_id}/minidump/`

Not implemented — return `404`. (CSP reports and native crashes are out of scope.)

### 4.4 Source-map upload API (sentry-cli compatibility)

`sentry-cli sourcemaps inject && sentry-cli sourcemaps upload` (≥ 2.17, debug-ID flow) must work
unmodified against `SENTRY_URL=https://outpost.example.com`. That flow uses three endpoints,
authenticated with `Authorization: Bearer <ApiToken>` (Outpost API token, created in the UI):

1. **`GET /api/0/organizations/{org}/chunk-upload/`** — capability discovery. Return our upload
   parameters; `accept: ["artifact_bundles"]` steers the CLI to the debug-ID flow:

   ```json
   {
     "url": "https://outpost.example.com/api/0/organizations/outpost/chunk-upload/",
     "chunkSize": 8388608, "chunksPerRequest": 64, "maxRequestSize": 33554432,
     "concurrency": 4, "hashAlgorithm": "sha1",
     "accept": ["artifact_bundles"], "compression": ["gzip"]
   }
   ```

2. **`POST /api/0/organizations/{org}/chunk-upload/`** — multipart upload of gzip-compressed
   chunks, each part named by its SHA-1. Store chunks in a staging table; dedupe by hash.

3. **`POST /api/0/organizations/{org}/artifactbundle/assemble/`** — body
   `{"checksum", "chunks": [...], "projects": [...]}`. Assemble chunks into the bundle zip,
   verify checksum, respond `{"state":"created","missingChunks":[]}` (list missing hashes with
   `state: "not_found"` so the CLI re-uploads). Assembly is synchronous — bundles are small; the
   CLI polls the same endpoint and accepts an immediately-final state.

   The zip contains `manifest.json` mapping paths to `{debug-id, type: source_map | minified_source, headers}`.
   On assemble: unpack, and for each **(debug_id, type)** insert an `artifact` row with the file
   content (`bytea`, gzipped). Keep the raw bundle for re-processing; dedupe by bundle checksum.

   `{org}` is accepted but ignored (single tenant). Also accept the legacy per-project assemble
   path `/api/0/projects/{org}/{project}/artifactbundle/assemble/` used by some CLI versions.

**Not implemented:** legacy release-file upload (`/releases/{version}/files/`), debug-symbol
(`dif`) upload. `sentry-cli releases new/finalize` endpoints (`POST/PUT /api/0/organizations/{org}/releases/`)
are implemented as trivial upserts to the `release` table so existing CI recipes don't break.

### 4.5 Tunnel endpoint (optional, v1.1)

`POST /tunnel` accepting an envelope whose project/key are read from the envelope's `dsn` header —
lets the Angular app proxy events through its own domain past ad-blockers (SDK `tunnel` option).
Same pipeline, different auth entry. Low priority: self-hosted domains are rarely blocklisted.

---

## 5. What the SDKs send (tested contract)

The compatibility test matrix pins these exact versions and upgrades them deliberately:

### Angular 22 — `@sentry/angular` (v10+)

```ts
Sentry.init({
  dsn: "https://<key>@outpost.example.com/1",
  environment: "prod",                            // ← G5: dev | qa | prod
  release: "shop-frontend@" + APP_VERSION,
  sendDefaultPii: true,
  tracesSampleRate: 1.0,                          // full tracing; volume is tiny
  integrations: [Sentry.browserTracingIntegration()],
  tracePropagationTargets: ["https://api.shop.example.com"],
  enableLogs: true,
});
```

Emits: `event` items (errors with stacktrace + `debug_meta.images[]` carrying debug IDs,
breadcrumbs, tags, user, request context), `transaction` items (pageload/navigation with
browser spans), `log` items (batched, ≤ 100/envelope), `client_report`s, `session`s (dropped).
Propagates `sentry-trace` + `baggage` headers on fetch/XHR to `tracePropagationTargets`.

Build-time (CI, after `ng build`):

```bash
sentry-cli sourcemaps inject ./dist/shop-frontend/browser
sentry-cli sourcemaps upload --release "shop-frontend@$VERSION" ./dist/shop-frontend/browser
# SENTRY_URL=https://outpost.example.com  SENTRY_AUTH_TOKEN=<outpost api token>  SENTRY_ORG=outpost  SENTRY_PROJECT=shop-frontend
```

`ng build` keeps `"sourceMap": {"scripts": true, "hidden": true}` so maps exist but aren't
referenced publicly; **do not ship `.map` files to the web server** — they go only to Outpost.

### Spring Boot 4 — `io.sentry:sentry-spring-boot-4-starter` (v8.44+)

```yaml
sentry:
  dsn: https://<key>@outpost.example.com/2
  environment: prod
  release: shop-backend@${APP_VERSION}
  traces-sample-rate: 1.0
  send-default-pii: true
  logs.enabled: true          # Sentry Logs
```

Plus `sentry-logback` so Logback records become breadcrumbs (≥ INFO) / log records. Emits:
`event` items (JVM stacktraces — already symbolic, **no server-side symbolication needed**),
`transaction` items (one per HTTP request via the auto-instrumented filter, spans for JDBC,
RestClient/WebClient, etc.), `log` items. **Continues traces** from incoming `sentry-trace` +
`baggage` headers — this is what makes G3 work end-to-end with zero custom code.

---

## 6. Processing pipeline

### 6.1 Ingest buffering

Envelope POST → parse + auth + split into items → push items onto a bounded in-memory queue
(default 10 000 items) → respond `200`. A small worker pool (2 threads) drains the queue and
writes **batched inserts** (JDBC batch, ≤ 500 rows or 1 s linger). Queue full → `429` (§4.2).
This is the entire "message queue" — no Kafka, no Redis. At the design ceiling (50 events/s)
Postgres yawns.

### 6.2 Error events: symbolication → fingerprint → issue

**Symbolication (JS events only), at ingest, synchronous within the worker:**

1. For each stacktrace frame with `abs_path`/`lineno`/`colno`, find its debug ID via the event's
   `debug_meta.images[]` (`type: "sourcemap"`, matching `code_file` to frame `abs_path`).
2. Look up `artifact` by `(debug_id, type=source_map)`; on miss, fall back to release+URL
   matching; on total miss, keep the minified frame and flag the event
   `symbolication: "missing_sourcemap"` (UI shows an actionable warning listing missing debug IDs).
3. Apply the source map (Java library candidate: `com.atlassian.sourcemap`, else a VLQ decoder
   is ~200 LoC): rewrite `filename/function/lineno/colno`, attach `pre_context`/`context_line`/
   `post_context` from the embedded `sourcesContent`, mark `in_app` frames (heuristic: mapped
   path under `webpack://shop-frontend/src/`; strip node_modules).
4. Store **both** raw and symbolicated stacktraces (re-symbolication after a late source-map
   upload is a background job triggered by artifact-bundle assembly — it re-processes flagged
   events of the matching release from the raw payload).

**Fingerprinting (grouping), in priority order:**

1. SDK-provided `fingerprint` array, if present (honor `{{ default }}` substitution).
2. Exception-based: SHA-256 over the ordered list of in-app frames'
   `(normalized_module, function)` + exception `type` — **message excluded** (messages carry
   variable data). Normalization strips line numbers, lambda indices (`lambda$handle$3` →
   `lambda$handle`), proxy/CGLIB suffixes, and webpack hash fragments.
3. Message-based fallback (no stacktrace): SHA-256 of the message with numbers/UUIDs/hex runs
   replaced by placeholders.

Then `INSERT ... ON CONFLICT` upsert the issue by `(project_id, fingerprint)`: update
`last_seen`, counters, `status = 'unresolved'` on regression; insert the event row. Issue title =
`ExceptionType: message-prefix` from the primary (last) exception.

### 6.3 Logs

Unpack the `log` item batch → one row each: `(project_id, environment, timestamp, trace_id,
span_id, level, severity_number, body, attributes jsonb, release, server/browser context)`.
No grouping — logs are an append-only searchable stream.

### 6.4 Transactions & spans

`transaction` item → one `txn` row (trace_id, span_id, parent_span_id from `contexts.trace`,
name like `GET /api/orders/{id}`, op, start/end, status, environment, release) + one `span` row
per entry in `spans[]`. Also extract the flat span list needed for the waterfall — no
reconstruction logic at query time beyond ordering by `start_timestamp` and nesting by
`parent_span_id`.

**Errors and logs attach to traces** purely via `trace_id` (present on all three when a trace is
active) — correlation is a query-time join, no ingest-time linking.

---

## 7. Storage (PostgreSQL 17)

Single database, migrations via Flyway. Sketch (not exhaustive DDL):

```sql
project(id, slug, name, platform, created_at)
project_key(id, project_id, public_key, is_active, created_at)
environment(id, project_id, name)                      -- auto-upsert on ingest
release(id, project_id, version, created_at)
app_user(id, email, password_hash, role, created_at)   -- role: admin | member
api_token(id, name, token_hash, scopes, created_at)    -- for sentry-cli / CI

issue(id, project_id, fingerprint, title, culprit, level, status,
      first_seen, last_seen, event_count, UNIQUE(project_id, fingerprint))
issue_env_stats(issue_id, environment, event_count, last_seen)

event(id uuid, project_id, issue_id, environment, release, timestamp,
      trace_id, level, message, exception_type,
      data jsonb,          -- full processed event payload (contexts, breadcrumbs, tags, frames)
      raw bytea NULL,      -- gzipped original payload, kept only while symbolication may rerun
      symbolication_status)
  PARTITION BY RANGE (timestamp);   -- weekly partitions

log_record(id, project_id, environment, timestamp, trace_id, span_id, level,
           severity_number, body, attributes jsonb, release)
  PARTITION BY RANGE (timestamp);

txn(id, project_id, environment, trace_id, span_id, parent_span_id, name, op,
    start_ts, end_ts, duration_ms, status, release, data jsonb)
  PARTITION BY RANGE (start_ts);
span(id, txn_id, trace_id, span_id, parent_span_id, op, description,
     start_ts, end_ts, status, data jsonb)
  PARTITION BY RANGE (start_ts);

artifact_bundle(id, checksum, raw bytea, created_at)
artifact_bundle_release(bundle_id, project_id, release)
artifact(id, bundle_id, debug_id, artifact_type,       -- source_map | minified_source
         file_path, headers jsonb, content bytea,       -- gzipped
         UNIQUE(debug_id, artifact_type))
upload_chunk(sha1 PRIMARY KEY, content bytea, created_at)   -- staging, TTL 24 h
```

**Indexes that matter:** `event(project_id, timestamp)`, `event(issue_id, timestamp)`,
`event(trace_id)`, `log_record(project_id, environment, timestamp)`, `log_record(trace_id)`,
GIN on `log_record.attributes`, `txn(trace_id)`, `txn(project_id, start_ts)`,
`artifact(debug_id)`. Log body search: `pg_trgm` GIN index on `body` for fast `ILIKE`
substring search (full websearch syntax is a non-goal).

**Retention = partition dropping.** Nightly job drops partitions older than the configured
retention (defaults: errors 90 d, logs 30 d, traces 30 d; per-signal setting). Issues outlive
their events (counters remain; detail page says "events expired"). `event.raw` is cleared after
30 d or once symbolication is confirmed complete. `upload_chunk` staging rows expire after 24 h.

**Volume estimate** at ≤ 20 users/app, a few apps, `tracesSampleRate: 1.0`: ~10⁴–10⁵ rows/day
worst case → single-digit GB working set at full retention. No ClickHouse required — by a wide
margin.

---

## 8. Query API (internal, for the Outpost UI)

`/api/internal/**`, session-cookie auth, plain REST + JSON. Never exposed to SDKs; free to change.
Common query params everywhere: `project`, `environment` (multi), `from`, `to`, `release`,
`query` (text), cursor pagination.

| Endpoint | Purpose |
|---|---|
| `GET /issues` | Issue list: filter by status/env/release/time, text search on title, sort by last_seen/count. Includes 14-day sparkline counts. |
| `GET /issues/{id}` · `PATCH /issues/{id}` | Detail + status change (resolve/unresolve). |
| `GET /issues/{id}/events` · `GET /events/{id}` | Event list within an issue; full event (frames, breadcrumbs, tags, contexts, linked trace/logs presence flags). |
| `GET /logs` | Log stream: filters (level, env, project, trace_id, attribute key/value, body substring), keyset-paginated descending; `?live=true` upgrades to SSE tail. |
| `GET /traces/{trace_id}` | Everything with that trace_id across all projects: transactions + spans (waterfall-ready), error events, log records — one payload. |
| `GET /traces` | Trace search: by root transaction name, project, env, duration range, "has errors". |
| `GET /projects` + CRUD, `GET/POST /projects/{id}/keys` | Project + DSN management (admin). |
| `GET /releases`, `GET /releases/{v}/artifacts` | Release list, uploaded-bundle inspection (debugging "why isn't my stack trace symbolicated"). |
| `GET/POST /users`, `/tokens`, `/settings` | Admin: users, sentry-cli tokens (shown once), retention settings. |
| `GET /stats` | Ingest health: accepted/dropped per project/type per day (from client_report counters + server counters). |

---

## 9. Frontend (Outpost UI) spec

Angular 22, standalone components, signals; packaged into the backend jar. Global header bar:
**project selector + environment multi-select + time-range picker** — these three pin the context
for every page (G5: env filtering is ubiquitous, not per-page).

### Pages

1. **Issues** (home) — table: level badge, title + culprit, event count, users-affected count,
   env chips, first/last seen, 14-day sparkline. Filters: status tabs (unresolved/resolved),
   search. Row → Issue detail.
2. **Issue detail** — header (title, status toggle, counts per environment); event navigator
   (latest/oldest/prev/next); per event: **symbolicated stacktrace** with expandable source
   context and in-app/vendor toggle, breadcrumbs timeline, tags, user/browser/OS/request
   contexts, and two correlation buttons: **"View trace"** (if `trace_id`) and **"Logs around
   this event"** (logs filtered to trace_id, or ±60 s same-service window when no trace).
   Symbolication warning banner with missing debug IDs when applicable.
3. **Logs** — Kibana-lite: level/project/env/attribute filters + body substring search, virtual-
   scrolled table, expandable rows (all attributes, copy JSON), **Live tail** toggle (SSE),
   trace_id links into the trace view.
4. **Traces** — search list (root name, duration, span count, error badge) → **Trace waterfall**:
   nested spans across *both* services color-coded by project, error events pinned as markers on
   their spans, collapsible log lane underneath, span detail side panel (op, description, timing,
   data). This page is the G3 payoff: browser `pageload` → fetch span → backend request txn →
   JDBC spans in one view.
5. **Releases** — versions per project, artifact bundles received (file count, debug IDs),
   first-seen, issue counts. Primarily a source-map debugging aid.
6. **Settings** (admin) — Projects & DSNs (create, copy DSN, revoke/rotate keys, per-SDK setup
   snippets exactly as in §5), Users, API tokens, Retention, ingest Stats.

**No dashboards, no saved searches, no custom queries in v1.** URL-addressable filter state
everywhere (shareable links) is the poor man's saved search.

---

## 10. AuthN/AuthZ

- **UI/Query API**: local email+password accounts (argon2id), server-signed session cookie
  (`HttpOnly`, `SameSite=Lax`). No SSO/OIDC in v1 (≤ 20 users) — leave the door open by keeping
  Spring Security's filter chain conventional. Roles: `admin` (settings, users, projects),
  `member` (read + issue triage). First-run bootstrap: `OUTPOST_ADMIN_EMAIL`/`_PASSWORD` env vars
  seed the initial admin.
- **Ingest**: DSN public key per project (§4.1). It's a browser-visible identifier, not a secret —
  revocation + the 429 backpressure path are the abuse story, same as Sentry's.
- **sentry-cli / CI**: opaque bearer `ApiToken`s (hashed at rest, scope `artifacts:write`),
  created in Settings, shown once.

---

## 11. Deployment

### 11.1 Docker Compose (local dogfooding — G6/req 3)

```yaml
# docker-compose.yml — the whole product
services:
  outpost:
    image: registry.example.com/outpost/outpost:latest
    ports: ["8080:8080"]
    environment:
      OUTPOST_DB_URL: jdbc:postgresql://db:5432/outpost
      OUTPOST_DB_USER: outpost
      OUTPOST_DB_PASSWORD: outpost
      OUTPOST_PUBLIC_URL: http://localhost:8080     # DSN host shown in UI
      OUTPOST_ADMIN_EMAIL: admin@local
      OUTPOST_ADMIN_PASSWORD: change-me
    depends_on:
      db: { condition: service_healthy }
  db:
    image: postgres:17-alpine
    environment: { POSTGRES_DB: outpost, POSTGRES_USER: outpost, POSTGRES_PASSWORD: outpost }
    volumes: [ "outpost-pg:/var/lib/postgresql/data" ]
    healthcheck: { test: ["CMD-EXEC", "pg_isready -U outpost"], interval: 5s }
volumes: { outpost-pg: {} }
```

`docker compose up -d` → UI at `:8080`, migrations run on boot, admin seeded. Two containers.

### 11.2 Kubernetes (req 2)

Ship plain manifests via **Kustomize** (base + overlays), matching the team's existing
"prebuilt image pushed to registry" flow; a Helm chart is a later nicety, not a requirement.

- **Deployment** (1 replica, `strategy: Recreate` to keep single-writer buffer semantics
  simple), resources ~`512Mi–1Gi / 250m–1000m`; liveness `/healthz`, readiness `/readyz`
  (checks DB). Config via env from a `Secret`.
- **Service** (ClusterIP) + **Ingress**: one hostname, e.g. `outpost.internal.example.com`,
  TLS via existing cert-manager. Ingress body-size limit ≥ 25 MiB
  (`nginx.ingress.kubernetes.io/proxy-body-size`) for envelope + chunk uploads.
- **PostgreSQL**: whatever the cluster already standardizes on — CloudNativePG or a managed
  instance. Outpost only needs a JDBC URL; it does not manage its database.
- **Jobs**: none. Retention/cleanup run as in-process scheduled tasks (`@Scheduled` +
  ShedLock-style Postgres advisory lock, so a second replica never double-runs them).
- Consumer apps need nothing new: their existing images get three env-injected values
  (DSN, environment name, release), which they already know how to do.

### 11.3 App image

Single multi-stage Dockerfile: Angular UI build → jar build (UI into `static/`) → distroless
Java 21 runtime. CI publishes `outpost:{git-sha}` + `:latest` to the registry.

---

## 12. Milestones

| Phase | Scope | Exit criterion |
|---|---|---|
| **P1 — Errors** | Envelope endpoint (event items only), fingerprinting, issues + events, UI pages 1–2 (no symbolication), projects/DSNs/auth, compose setup | Angular + Spring demo apps report errors; browsable, grouped, env-filterable |
| **P2 — Source maps** | chunk-upload + assemble API, artifact storage, ingest-time symbolication + re-symbolication job, releases page | Unmodified `sentry-cli sourcemaps upload` in CI → prod Angular stack traces show original TS source |
| **P3 — Logs** | `log` item pipeline, logs page with live tail, event↔log correlation | `Sentry.logger` (both SDKs) + Logback records searchable; "logs around this event" works |
| **P4 — Tracing** | `transaction` pipeline, trace API, waterfall UI, cross-signal links | One user click in Angular renders a waterfall spanning browser → Spring Boot → JDBC with pinned errors + logs |
| **P5 — Hardening** | Retention jobs, backpressure/429, client-report stats page, k8s manifests, dogfood deployment | Outpost monitors Outpost in the cluster for two weeks without intervention |

Each phase is independently shippable; P1 alone already replaces "grep the pod logs".

## 13. Risks & open questions

1. **SDK protocol drift** is the existential risk of the whole approach. Mitigations: skip-don't-
   reject unknown item types (§4.2); pin SDK versions in consumer apps; a compat test suite that
   runs real `@sentry/angular` + `sentry-spring-boot-4` demo apps against Outpost in CI and is
   the gate for SDK version bumps. Watch item: Sentry's span-first ("span streaming") migration —
   when SDKs start defaulting to `span` envelope items instead of `transaction`, P4's pipeline
   grows a second parser (data model already fits: spans are the storage primitive).
2. **Java source-map library**: validate `com.atlassian.sourcemap` (age, index-map support)
   against Angular CLI output in P2 week 1; the fallback (own VLQ decoder + section maps) is
   small but must be planned, not discovered.
3. **JSONB bloat**: full event payloads in `data` are the storage hog. Acceptable at our volume;
   if it grows, gzip into `bytea` (like `raw`) and keep only indexed columns queryable — decide
   only if a real problem appears.
4. **PII**: `sendDefaultPii: true` puts user emails/IPs in an internal tool. Confirm that's
   acceptable for prod data, or default the snippets to `false` and scrub IPs at ingest
   (config flag, default TBD).
5. Naming: "Outpost" is a placeholder; check for collisions before publishing anything.
