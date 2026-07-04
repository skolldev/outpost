# Outpost — Development TODO

Derived from `outpost-spec.md` (draft v0.1, 2026-07-03). Phases follow §12 milestones, plus a
Phase 0 for scaffolding the spec assumes. Section references (§) point into the spec.

Each phase is independently shippable. Exit criteria are quoted from §12.

---

## Phase 0 — Project scaffolding & walking skeleton

Goal: one runnable image + database, empty but deployable end-to-end.

### Repo & build

- [x] Spring Boot 4 / Java 21 backend project (Gradle), package structure for ingest / processing / query / auth modules (§2) — Boot 4.1.0, package `dev.outpost`
- [x] Angular 22 UI project (standalone components, signals) (§9) — from `angular-starter` template (Tailwind, Vitest, ESLint), Angular 22.0.5
- [x] Wire Angular build output into Spring Boot jar `static/` resources → single app image (§2, §11.3) — `processResources` copies `ui/dist`, SPA fallback in `SpaWebConfig`
- [x] Multi-stage Dockerfile: Angular build → jar build → distroless Java 21 runtime (§11.3)
- [x] CI: build + test + publish `outpost:{git-sha}` and `:latest` to registry (§11.3) — GHCR placeholder in `env.IMAGE`; swap for team registry. Unverified until pushed to GitHub.

### Database & config

- [x] PostgreSQL 17 + Flyway migration baseline (§7) — `V1__baseline.sql` (`setting` table)
- [x] Config via env vars: `OUTPOST_DB_URL/_USER/_PASSWORD`, `OUTPOST_PUBLIC_URL`, `OUTPOST_ADMIN_EMAIL/_PASSWORD` (§11.1)
- [x] `/healthz` (liveness) and `/readyz` (readiness, checks DB) endpoints (§11.2) — actuator health groups with `additional-path`

### Local deployment

- [x] `docker-compose.yml` per §11.1: outpost + postgres:17-alpine, healthcheck, named volume
- [x] Verify: `docker compose up -d` → UI served at `:8080`, migrations run on boot — verified incl. SPA deep link and `readyz` → 503 with DB stopped

---

## Phase 1 — Errors (P1)

> Exit: Angular + Spring demo apps report errors; browsable, grouped, env-filterable.

### Domain & storage

- [x] Flyway migrations: `project`, `project_key`, `environment`, `release`, `app_user`, `api_token`, `issue`, `issue_env_stats`, `event` (weekly range partitions on `timestamp`) (§7) — `V2__phase1_domain.sql`
- [x] Indexes: `event(project_id, timestamp)`, `event(issue_id, timestamp)`, `event(trace_id)`, `issue UNIQUE(project_id, fingerprint)` (§7)
- [x] Weekly partition auto-creation for `event` (needed before retention exists) — `PartitionManager`: startup + on-demand per batch, advisory-lock-guarded DDL

### Ingest endpoint (§4.2)

- [x] `POST /api/{project_id}/envelope/` — newline-delimited envelope parser (envelope header line, item header + payload per item)
- [x] Auth precedence: `X-Sentry-Auth` header → `sentry_key` query param → `dsn` in envelope header; validate against active keys of `{project_id}`, else `403`
- [x] Accept `Content-Type: application/x-sentry-envelope` and `text/plain`; accept `Content-Encoding: gzip` (Java SDK default)
- [x] CORS: `OPTIONS` preflight, `Access-Control-Allow-Origin: *`, allow `sentry-trace, baggage, content-type` + auth headers, no credentials
- [x] Respond `200 {"id": "<event_id>"}` after parse + buffer; `400` malformed; `413` oversize (20 MiB/envelope, 1 MiB/item)
- [x] Item-type dispatch table: handle `event`; **skip unknown types silently** (compat contract); drop `session`/`sessions`/`check_in`/`profile`/`replay_*`/`statsd`/`metric_buckets`; parse `client_report` into counters (storage/UI in P5); `attachment` ≤ 1 MiB stored with parent event (in `event.data._outpost_attachments`), else drop (§4.2 table)
- [x] `POST /api/{project_id}/security/` and `/minidump/` → `404` (§4.3)

### Ingest buffering (§6.1)

- [x] Bounded in-memory queue (default 10 000 items); envelope POST pushes items, responds immediately — capacity via `outpost.ingest.queue-capacity`
- [x] Worker pool (2 threads) draining queue with JDBC batch inserts (≤ 500 rows or 1 s linger) — poison batches degrade to per-event inserts
- [x] Queue-full path: `429` + `Retry-After` + `X-Sentry-Rate-Limits: <seconds>:all:organization` (§4.2) — load-verification against real SDKs stays in P5

### Error pipeline (§6.2 — no symbolication yet)

- [x] Fingerprinting priority chain (`Fingerprinter`, unit-tested):
  - [x] 1. SDK `fingerprint` array with `{{ default }}` substitution
  - [x] 2. Exception-based: SHA-256 over ordered in-app frames `(normalized_module, function)` + exception `type`, message excluded
  - [x] 3. Message fallback: SHA-256 of message with numbers/UUIDs/hex runs replaced by placeholders
- [x] Frame normalization: strip line numbers, lambda indices (`lambda$handle$3` → `lambda$handle`), proxy/CGLIB suffixes, webpack hash fragments
- [x] Issue upsert: `INSERT ... ON CONFLICT (project_id, fingerprint)` — update `last_seen`, counters; regression rule: new event in `resolved` issue → `unresolved` (§3)
- [x] Issue title = `ExceptionType: message-prefix` from primary (last) exception
- [x] Per-environment issue stats (`issue_env_stats`)
- [x] Environment auto-upsert on first sight; missing environment → `production` (§3)
- [x] Release auto-upsert from event `release` field (§3)
- [x] Store full processed payload in `event.data` jsonb; gzipped original in `event.raw` — timestamps clamped to received-at when skewed > 30 d past / 1 h future

### AuthN/AuthZ (§10)

- [x] Local email+password accounts, argon2id hashing (Spring Security `Argon2PasswordEncoder`)
- [x] Server-signed session cookie (`HttpOnly`, `SameSite=Lax`), conventional Spring Security filter chain — stateless HMAC cookie; signing secret persisted in `setting`, so sessions survive restarts/replicas
- [x] Roles: `admin` / `member` — mutations guarded with `@PreAuthorize`
- [x] First-run bootstrap: seed admin from `OUTPOST_ADMIN_EMAIL`/`_PASSWORD`
- [x] DSN key generation (32-hex public key), disable/rotate, multiple active keys per project (§4.1)

### Query API (§8)

- [x] Common query-param layer: `project`, `environment` (multi), `from`, `to`, `release`, `query`, cursor pagination (keyset on `(sort value, id)`)
- [x] `GET /issues` — status/env/release/time filters, title search, sort by last_seen/count, 14-day sparkline counts (+ users-affected, env chips per row)
- [x] `GET /issues/{id}`, `PATCH /issues/{id}` (resolve/unresolve)
- [x] `GET /issues/{id}/events`, `GET /events/{id}` (frames, breadcrumbs, tags, contexts; prev/next event ids for the navigator)
- [x] `GET /projects` + CRUD, `GET/POST /projects/{id}/keys` (admin) — plus `GET /projects/{id}/environments` for the filter bar
- [x] `GET/POST /users` (admin)

### UI (§9 pages 1, 2, 6 — partial)

- [x] Global header: project selector + environment multi-select + time-range picker; URL-addressable filter state everywhere
- [x] Login page + session handling (401 interceptor redirects to login with returnUrl)
- [x] **Issues** page: table (level badge, title + culprit, event count, users affected, env chips, first/last seen, sparkline), status tabs, search
- [x] **Issue detail**: header with status toggle + per-env counts; event navigator (latest/newer/older); stacktrace (in-app/vendor toggle), breadcrumbs timeline, tags, user/browser/OS/request contexts
- [x] **Settings**: Projects & DSNs (create, copy DSN, revoke/rotate keys, per-SDK setup snippets per §5), Users

### Validation

- [ ] Demo apps: minimal Angular 22 + `@sentry/angular` and Spring Boot 4 + `sentry-spring-boot-4-starter`, configured per §5
- [ ] Compat test suite skeleton: real SDK demo apps fire errors at Outpost in CI (§13.1) — the gate for all later SDK bumps.
      Interim: `EnvelopeIngestIntegrationTest` runs SDK-**shaped** envelopes (both families, incl. gzip + all three auth
      paths) through ingest → pipeline → query API in CI; swap its payload source for the real demo apps.
- [x] Exit check: errors from both SDK payload shapes ingested, grouped into issues, env-filterable (verified in CI test
      and live against `bootRun` + compose db) — re-verify with real SDKs once demo apps exist

---

## Phase 2 — Source maps (P2)

> Exit: unmodified `sentry-cli sourcemaps upload` in CI → prod Angular stack traces show original TS source.

### Risk spike (do first — §13.2)

- [x] Week 1: validate `com.atlassian.sourcemap` against real Angular CLI output (incl. index maps); if unfit, plan own VLQ decoder (~200 LoC) + section-map support — **unfit**: no index-map support, published only to Atlassian's own Maven repo (not Central), drags in gson. Wrote own `SourceMapConsumer` (~200 LoC, zero deps, standard + index maps); validated against 600+ lookups on a real `ng build --source-map` output, expected values from Node's built-in `node:module` SourceMap **and** a second spec-faithful decoder (Node's parser has a name-carry-over quirk on 4-field segments; ours follows the spec). Fixtures + generator in `server/src/test/resources/sourcemap/`
- [x] Learned along the way: Angular 22 (esbuild) emits plain `src/…` source paths (no `webpack://` prefix — in_app heuristic handles both); sentry-cli bundles are zips behind an 8-byte `SYSB` header (stream unzip must skip it)

### Storage

- [x] Migrations: `artifact_bundle`, `artifact_bundle_release`, `artifact` (UNIQUE `(debug_id, artifact_type)`), `upload_chunk` (§7) — `V3__phase2_artifacts.sql`
- [x] Index: `artifact(debug_id)`

### Upload API — sentry-cli compat (§4.4)

- [x] Bearer `ApiToken` auth: opaque tokens, hashed at rest (SHA-256 — tokens are 192-bit random), scope `artifacts:write` (§10) — `ApiTokenService` + filter in `SecurityConfig`
- [x] `GET /api/0/organizations/{org}/chunk-upload/` — capability discovery response steering CLI to debug-ID flow (`accept: ["artifact_bundles"]`)
- [x] `POST .../chunk-upload/` — multipart gzip chunks named by SHA-1 (`file` + `file_gzip` part names), staging table, dedupe by hash
- [x] `POST .../artifactbundle/assemble/` — synchronous assembly: verify checksum, report `missingChunks` / `not_found`, unpack zip, parse `manifest.json`, insert `artifact` per `(debug_id, type)`, keep raw bundle, dedupe by bundle checksum — responds final `ok` (not spec-sketch `created`: only ok/error are terminal for `--wait`); staged chunks deleted after use
- [x] Accept legacy per-project assemble path `/api/0/projects/{org}/{project}/artifactbundle/assemble/`; `{org}` accepted but ignored
- [x] `POST/PUT /api/0/organizations/{org}/releases/` as trivial upserts to `release` (keep CI recipes working)
- [x] Legacy release-file upload + `dif` upload: explicitly not implemented

### Symbolication pipeline (§6.2)

- [x] Ingest-time, synchronous in worker, JS events only (`Symbolicator`, runs before fingerprinting):
  - [x] Resolve frame debug ID via `debug_meta.images[]` (`code_file` ↔ frame `abs_path`)
  - [x] Lookup `artifact` by `(debug_id, source_map)` (LRU-cached, invalidated on new bundles); fallback to release+URL matching; total miss → keep minified frame, flag `missing_sourcemap` (+ `partial` when only some frames mapped; missing debug IDs stored in `event.data._outpost_symbolication` for the UI)
  - [x] Apply map: rewrite `filename/function/lineno/colno`; attach `pre_context`/`context_line`/`post_context` from `sourcesContent`; `in_app` heuristic (`webpack://<project>/src/` **and** esbuild's plain `src/…`, strip node_modules)
- [x] Store both raw and symbolicated stacktraces (`raw_stacktrace` per exception, like Sentry)
- [x] Re-symbolication background job: triggered by bundle assembly (after commit), re-processes flagged events of matching release from raw payload — updates frames + status in place; intentionally does **not** re-group (event keeps its issue)

### UI

- [x] Issue detail: symbolicated stacktrace with expandable source context (newest in-app frame auto-expanded); warning banner listing missing debug IDs (§9.2)
- [x] **Releases** page: versions per project, bundles received (file count, debug IDs), first-seen, issue counts (§9.5)
- [x] Query API: `GET /releases`, `GET /releases/{v}/artifacts` (§8)
- [x] Settings: API tokens (create, shown once, revoke; CI snippet) (§8, §10)

### Validation

- [ ] Demo Angular app CI: `ng build` with `"sourceMap": {"scripts": true, "hidden": true}` → `sentry-cli sourcemaps inject` + `upload` against Outpost (§5) — demo apps still pending from Phase 1; interim validation below used Outpost's own UI build
- [x] `SourceMapUploadIntegrationTest` (CI): wire-exact CLI simulation — token auth, chunk upload, assemble (incl. missing-chunk retry, idempotent re-assemble, legacy path), symbolicated ingest, late-upload re-symbolication, releases API
- [x] Exit check: prod Angular stack trace shows original TS source with context lines — verified **live** with unmodified `sentry-cli 3.6.0` (`npx @sentry/cli`) against `bootRun` + compose db: inject + upload of the real `ui/` production build, then a minified event symbolicated to `src/app/core/api.ts:23:27` with context lines; re-verify from a real demo-app CI once demo apps exist

---

## Phase 3 — Logs (P3)

> Exit: `Sentry.logger` (both SDKs) + Logback records searchable; "logs around this event" works.

### Storage

- [x] Migration: `log_record` (range-partitioned on `timestamp`) (§7) — `V4__phase3_logs.sql`; weekly partitions via `PartitionManager`, generalized to cover both `event` and `log_record`
- [x] Indexes: `log_record(project_id, environment, timestamp)`, `log_record(trace_id)`, GIN on `attributes`, `pg_trgm` GIN on `body` for `ILIKE` substring search — `pg_trgm` is a trusted extension, created in the migration

### Pipeline (§6.3)

- [x] Handle `log` envelope items: unpack batch (`application/vnd.sentry.items.log+json`, `item_count` header) — `IngestItem` is now sealed (`ErrorEvent` | `LogBatch`), same queue/workers/backpressure
- [x] Insert one row per record: project, environment, timestamp, trace_id, span_id, level, severity_number, body, attributes jsonb, release — append-only, no grouping. Typed attributes (`{"k":{"value":v,"type":t}}`) flattened to plain `{"k":v}`; environment/release/span_id extracted from `sentry.environment` / `sentry.release` / `sentry.trace.parent_span_id` attributes; env+release auto-upserted; timestamps clamped like events

### Query API & UI

- [x] `GET /logs` — filters: level, env, project, trace_id, attribute key/value, body substring; keyset pagination descending (§8) — attribute filter is `attr=key=value` (bare `attr=key` matches presence)
- [x] `?live=true` → SSE tail — `LogTail` broadcaster fed by `LogStore`, same filters applied in-process, 25 s keepalive comments, emitters completed on shutdown (SmartLifecycle, ahead of Tomcat's graceful-shutdown wait)
- [x] **Logs** page (Kibana-lite): filters + substring search, expandable rows (all attributes, copy JSON), Live-tail toggle, trace_id links (§9.3) — trace_id links filter the logs page for now (trace view lands in P4). **Deviation:** load-more keyset pagination + live buffer capped at 500 rows instead of a virtual-scrolled table (no CDK dep; revisit if row counts hurt)
- [x] Issue detail: **"Logs around this event"** — logs by trace_id, or ±60 s same-project window when no trace (§9.2) — collapsible section, lazy-loaded, plus "Open in Logs" link when traced

### Validation

- [ ] Demo apps: `enableLogs: true` (Angular) + `logs.enabled: true` and `sentry-logback` (Spring) (§5) — still pending from Phase 1
- [x] Exit check: logs from both SDKs searchable; event↔log correlation works both with and without trace_id — interim, like P1: `LogIngestIntegrationTest` runs SDK-**shaped** log batches through ingest → query API in CI (all filters, both correlation modes, SSE tail); re-verify with real SDKs once demo apps exist

---

## Phase 4 — Tracing (P4)

> Exit: one user click in Angular renders a waterfall spanning browser → Spring Boot → JDBC with pinned errors + logs.

### Storage

- [ ] Migrations: `txn` and `span` (range-partitioned on `start_ts`) (§7)
- [ ] Indexes: `txn(trace_id)`, `txn(project_id, start_ts)`

### Pipeline (§6.4)

- [ ] Handle `transaction` items: one `txn` row (trace_id, span_id, parent_span_id from `contexts.trace`, name, op, start/end, duration, status, environment, release) + one `span` row per `spans[]` entry
- [ ] No ingest-time linking — errors/logs correlate to traces via `trace_id` at query time

### Query API & UI

- [ ] `GET /traces/{trace_id}` — cross-project join: transactions + spans (waterfall-ready), error events, log records in one payload (§8)
- [ ] `GET /traces` — search by root transaction name, project, env, duration range, "has errors" (§8)
- [ ] **Traces** page: search list (root name, duration, span count, error badge) (§9.4)
- [ ] **Trace waterfall**: nested spans across both services color-coded by project (order by `start_timestamp`, nest by `parent_span_id`), error events pinned as span markers, collapsible log lane, span detail side panel (§9.4)
- [ ] Issue detail: **"View trace"** button when event has `trace_id` (§9.2)

### Validation

- [ ] Demo apps: `tracesSampleRate: 1.0`, `browserTracingIntegration`, `tracePropagationTargets` (Angular); auto-instrumented filter continuing `sentry-trace`/`baggage` (Spring) (§5)
- [ ] Exit check (G3): browser `pageload` → fetch span → backend request txn → JDBC spans in a single waterfall, zero custom SDK code

---

## Phase 5 — Hardening (P5)

> Exit: Outpost monitors Outpost in the cluster for two weeks without intervention.

### Retention & cleanup (§7)

- [ ] Nightly partition-drop job per signal (defaults: errors 90 d, logs 30 d, traces 30 d); configurable per-signal
- [ ] Issues outlive events: counters remain; detail page shows "events expired"
- [ ] Clear `event.raw` after 30 d or once symbolication confirmed complete
- [ ] Expire `upload_chunk` staging rows after 24 h
- [ ] Schedule via `@Scheduled` + Postgres-advisory-lock guard (ShedLock-style) so a second replica never double-runs (§11.2)
- [ ] Settings UI: retention configuration (§9.6)

### Backpressure & stats

- [ ] Load-verify the full `429` + `Retry-After` + `X-Sentry-Rate-Limits` path against both SDKs (§4.2, §6.1)
- [ ] Persist `client_report` `discarded_events` counters + server-side accepted/dropped counters (§4.2)
- [ ] `GET /stats` — accepted/dropped per project/type per day (§8); ingest Stats page in Settings (§9.6)

### Kubernetes (§11.2)

- [ ] Kustomize base + overlays: Deployment (1 replica, `strategy: Recreate`, resources ~512Mi–1Gi / 250m–1000m, liveness/readiness probes, env from Secret)
- [ ] Service (ClusterIP) + Ingress: TLS via cert-manager, `proxy-body-size` ≥ 25 MiB
- [ ] Document Postgres expectation (CloudNativePG or managed; Outpost only needs a JDBC URL)

### Dogfooding & release readiness

- [ ] Instrument Outpost with its own two SDKs (`@sentry/angular` on the UI, `sentry-spring-boot-4-starter` on the backend) pointed at a second instance or itself (§2)
- [ ] Deploy to cluster; run two weeks without intervention (exit criterion)
- [ ] Compat test suite in CI as the gate for SDK version bumps (§13.1)
- [ ] Resolve open questions before publishing (§13): PII stance for `sendDefaultPii` snippets / IP scrubbing flag; watch span-streaming migration; check "Outpost" name for collisions

---

## Deferred / v1.1+

- [ ] `POST /tunnel` endpoint (envelope auth via `dsn` header, ad-blocker bypass) (§4.5)
- [ ] `span` envelope items (span-streaming SDKs) — second parser onto existing span storage (§13.1)
- [ ] Helm chart (§11.2)
- [ ] SSO/OIDC (§10)
- [ ] JSONB compression for `event.data` — only if storage becomes a real problem (§13.3)

## Explicit non-goals — do not build (§1)

Metrics / performance monitoring / uptime / cron monitoring / session replay / user feedback /
profiling; alerting of any kind; SDKs beyond the two supported; horizontal ingest scaling;
organizations/teams.
