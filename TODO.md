# TODO

Actionable findings from a code-level comparison against
[Bugsink](https://github.com/bugsink/bugsink) (2026-07-29). Only items worth
acting on are listed; the comparison also covered surfaces we lead on (logs,
tracing, uptime, ingest-time symbolication) which need no work.

**Reading this as a ticket source.** Each item carries a **Readiness** line
mapping to `docs/agents/triage-labels.md`. Items 1–4 and 6–9 are issue-shaped.
Item 5 is PRD-shaped, not a single issue. The **Roadmap** section at the bottom
is explicitly **not** for filing — those are product decisions that would sit
open forever as issues.

P0–P2 here is a severity axis; the tracker's labels are a readiness axis. They
are orthogonal, so both are stated per item.

Claims are sourced to code in one repo or the other and have been verified
against it, except where marked *unverified*.

---

## P0 — correctness and safety bugs

### 1. Duplicate `event_id` inflates issue counters

**Readiness:** `ready-for-agent`

`EventStore.storeAll` bumps `issue.event_count` and `issue_env_stats.event_count`
via the upsert **before** the event insert, which is `ON CONFLICT DO NOTHING`.

- `server/src/main/java/dev/outpost/pipeline/EventStore.java:51` (`event_count = issue.event_count + 1`)
- `server/src/main/java/dev/outpost/pipeline/EventStore.java:60` (`ON CONFLICT DO NOTHING`)

A duplicate `event_id` — which Sentry SDKs produce on network retry — increments
both counters while storing no row.

**Repro:** POST the same envelope (identical `event_id`) twice to
`/api/{projectId}/envelope/`, then query the issue.

- Expected: `issue.event_count = 1`, `issue_env_stats.event_count = 1`, one `event` row.
- Actual: both counters at 2, one `event` row.

**Acceptance:**
- Aggregates increment only for events that actually inserted (e.g. `RETURNING id`
  on the event insert, aggregating the survivors).
- Integration test: duplicate envelope leaves both counters at 1.
- Batch path covered too — a batch containing one new and one duplicate event
  increments by exactly 1.

Bugsink detects this explicitly and raises rather than absorbing it
(`ingest/views.py`, the `event_created` branch).

### 2. Gzip bomb: envelope size cap is applied post-decompression

**Readiness:** `ready-for-agent`

`EnvelopeController.readBody` wraps the stream in `GZIPInputStream` and *then*
reads up to `MAX_ENVELOPE_BYTES + 1` into a heap array.

- `server/src/main/java/dev/outpost/ingest/EnvelopeController.java:140` (gzip wrap)
- `server/src/main/java/dev/outpost/ingest/EnvelopeController.java:146` (`readNBytes`)

A ~20 KB compressed body inflates to 20 MB of heap. This is reachable
**unauthenticated**: `readBody` runs before `authenticator.isValidKey`, so a
caller with no valid DSN key can force the allocation. A handful of concurrent
requests is an OOM.

**Repro:** POST a gzip body of a few KB that inflates past `MAX_ENVELOPE_BYTES`,
with an absent or invalid `X-Sentry-Auth`. The full inflated size is allocated
before the 403.

**Acceptance:**
- A configurable compressed-size limit rejects with 413 based on bytes read
  *off the wire*, before decompression can exceed it.
- The limit also applies to uncompressed senders, so an uncompressed body is
  held to the smaller cap (Bugsink nests `MAX_ENVELOPE_COMPRESSED_SIZE` around
  `MAX_ENVELOPE_SIZE` for exactly this).
- Test with a zip-bomb fixture asserting bounded allocation and a 413.
- The check runs before key validation, so it stays cheap for unauthenticated
  callers.

### 3. Ingest allocates ~6 copies of every event

**Readiness:** `ready-for-human` (perf refactor, wants judgement on where to stop)

Per event we hold: the raw body `byte[]`, a per-item `Arrays.copyOfRange`, the
Jackson tree, a `deepCopy()`, a second `deepCopy()` for the attachment path, the
gzip `byte[]`, and a JSON `String` for the JSONB bind.

- `server/src/main/java/dev/outpost/ingest/EnvelopeController.java`
- `server/src/main/java/dev/outpost/pipeline/ErrorPipeline.java`

**Acceptance:**
- No full-tree `deepCopy()` on the attachment path.
- The JSONB bind does not round-trip through a `String`.
- Allocation per event measurably reduced under a repeatable ingest benchmark
  (state the before/after in the PR). The benchmark now exists:
  `cd server && ./gradlew ingestBenchmark` writes a pasteable table to
  `server/build/reports/ingest-benchmark/`. See `docs/performance/measuring-ingest.md`.

Split from #2 deliberately: that one is a security fix on a deadline, this is a
refactor. Land #2 first; several of these copies also disappear with #8.

### 4. Ingest buffer is dropped on *graceful* shutdown, and stops too early

**Readiness:** `ready-for-agent`

`IngestWorkers.stop()` sets `running = false`, interrupts, joins — it never
drains the queue.

- `server/src/main/java/dev/outpost/ingest/IngestWorkers.java:110`

Up to `queue-capacity` (default 10 000) items are lost on SIGTERM.

`IngestWorkers` also does not override `getPhase()`, so it sits at
`SmartLifecycle.DEFAULT_PHASE`. Verified phases (decompiled from
`spring-boot-web-server-4.1.0`), highest stops first:

| Component | Phase |
| --- | --- |
| `IngestWorkers` (unset → `DEFAULT_PHASE`) | 2147483647 |
| `WebServerGracefulShutdownLifecycle` | 2147482623 |
| `WebServerStartStopLifecycle` (connector stop) | 2147481599 |

So the workers stop before the connector does, and the endpoint keeps accepting,
200-ing, and queueing into a pool with no workers left.

**Also:** `server.shutdown` is unset in `application.yaml`, so it defaults to
`immediate`. That keeps the accept-into-a-dead-pool window short today, but it
also means a drain has almost no room to run. The fix should set
`server.shutdown: graceful` with a `spring.lifecycle.timeout-per-shutdown-phase`,
or the drain is decorative.

**Repro:** POST envelopes continuously, send SIGTERM mid-stream, compare stored
rows against 200s returned before shutdown began.

**Acceptance:**
- `getPhase()` returns a value strictly below 2147481599 so the workers stop
  *after* the connector. (Bonus: lower phase also starts them *before* the
  connector, so workers are ready before traffic arrives.)
- `stop()` drains the queue, bounded by a configurable timeout, before returning.
- `server.shutdown: graceful` set, with an explicit per-phase timeout.
- Test: every envelope that received a 200 before SIGTERM is stored, or the
  drain timeout is hit and the residual count is logged.

**Why this is the priority:** deploys and restarts are effectively all real-world
loss events; hard crashes are the rare tail. ADR 0002 accepts losing telemetry
"if the server stops" — as written we lose it on every routine deploy, a weaker
claim than the ADR makes. This makes the ADR true rather than overturning it.

**Out of scope:** crash durability. See "Decided against".

---

## P1 — architectural debt that gets more expensive with every install

### 5. Grouping has no migration path — **PRD, not an issue**

**Readiness:** needs a PRD before it can be triaged into issues

`Fingerprinter` writes a bare SHA-256 into `issue.fingerprint`, unique per
`(project_id, fingerprint)`.

- `server/src/main/java/dev/outpost/pipeline/Fingerprinter.java`
- `server/src/main/resources/db/migration/V2__phase1_domain.sql:56` (`issue`)

The first time we touch `normalizeMessage`, the in-app frame rule, or anything
else in the default fingerprint, every open issue in every deployed installation
forks in two. There is no way to ship a grouping improvement without that.

**What Bugsink does**, worth copying closely:

- Project carries `grouping_mechanism`, `previous_grouping_mechanism`,
  `grouping_mechanism_upgraded_at` (`projects/models.py:130-141`).
- A separate `Grouping` table: many groupings → one `Issue`.
- During a 30-day transition window a new event tries the current mechanism's
  key, falls back to the previous mechanism's key, and on a hit **creates a new
  Grouping row pointing at the existing Issue** (`ingest/views.py`,
  `get_grouping_path_for_event`).
- Upgrading is opt-in per project, from project settings.

Second payoff: the Grouping/Issue split makes manual issue merging and splitting
possible, which the current single-column design forecloses.

**Why this is not one ticket:** it spans a schema migration, a pipeline change,
transition-window logic with its own edge cases, and project settings UI. Write
the PRD first; the issues fall out of it.

**Cheap now, very expensive later.** Do it before the grouping rules need a
change, not after.

### 6. No per-project rate limiting

**Readiness:** `ready-for-human` (needs a call on default thresholds)

We only shed load when the shared in-memory queue is full, at which point we 429
*every* project — as the header admits:

- `server/src/main/java/dev/outpost/ingest/EnvelopeController.java` (`X-Sentry-Rate-Limits: 30:all:organization`)

One runaway client degrades every other project on the instance.

**The trick worth stealing** — `ingest/event_counter.py`, `check_for_thresholds`:

- The expensive aggregate only reruns every `(threshold − current_count)` events,
  because the quota provably cannot be crossed sooner than that
  (`next_quota_check`).
- When a threshold trips, it computes the exact instant the rolling window drops
  back under the line (`below_threshold_from`) and stores it, so every subsequent
  request is a timestamp comparison, not a query.
- Counting is `max(digest_order) − min(digest_order) + 1`, not `COUNT(*)`.

Bugsink applies this at installation and project scope over 5-minute / hourly /
monthly windows, and checks at ingest *and* digest (a backlog makes the
ingest-side decision stale).

**Acceptance:**
- Per-project limits enforced, with `X-Sentry-Rate-Limits` scoped to the project
  rather than `organization`.
- A project over its limit does not affect ingest for other projects (test with
  two projects, one saturated).
- The aggregate query is amortized: assert bounded query count across N events
  in a test, not one query per event.
- Thresholds configurable, with documented defaults.

### 7. Attachments live in the event JSONB

**Readiness:** `ready-for-human` (needs a storage-shape decision)

`ErrorPipeline.withAttachments` base64-encodes attachment bytes into
`_outpost_attachments` inside the event `data` column.

- `server/src/main/java/dev/outpost/pipeline/ErrorPipeline.java`

~1.37× size inflation, TOASTed, and re-read in full on every event view.
`EnvelopeParser.MAX_ITEM_BYTES` (1 MiB) also drops larger attachments silently —
no log, no client signal. *(Sentry's own default limit is larger, but the exact
figure is unverified; pick ours on our own merits.)*

**Acceptance:**
- Attachment bytes no longer stored in `event.data`.
- The event detail view fetches attachments on demand, not as part of the event row.
- Oversize attachments are rejected or truncated *observably* (logged, and
  surfaced on the event) rather than silently dropped.
- Events written under the old embedded format still render.

### 8. Spool envelope bytes to disk; keep only a pointer in the queue

**Readiness:** `ready-for-agent` (after #4 lands)

`IngestQueue` holds fully-parsed `IngestItem`s — Jackson trees plus raw
attachment arrays — entirely in heap.

- `server/src/main/java/dev/outpost/ingest/IngestQueue.java`
- `server/src/main/java/dev/outpost/ingest/IngestItem.java`

**What Bugsink does:** the queue row (`snappea/models.py` `Task`) carries only
`task_name/args/kwargs`; the payload is spooled to a file and the task holds the
filename (`ingest/filestore.py`, `get_filename_for_event_id`). The queue lives in
a *separate* database (`snappea/dbrouters.py`) so queue writes never contend with
digest writes.

**Sell this on capacity and heap, not durability.**

- Buffer depth stops being bounded by heap, so bursts stop turning into 429s.
- Composes with #2 and #3: we stop holding whole decompressed envelopes in
  memory, and several copies disappear.
- Makes crash replay a *pure addition* later (scan the spool dir at startup) if
  we ever change our mind, with no redesign.

**Costs, honestly:** ingest currently does zero disk I/O; this adds a write per
envelope plus a new failure surface — disk full, permissions, orphan cleanup.
Bugsink needed a vacuum command for exactly that and shipped a fix for stale
spool files as recently as 2.5.0. Budget the reaper up front.

**Acceptance:**
- Queued items hold a spool-file reference, not parsed payloads; per-item heap
  is bounded and independent of envelope size.
- Queue capacity raised, with the new default documented.
- A reaper removes spool files with no live queue entry after a configurable age;
  covered by a test.
- Spool write failures (disk full, permission denied) degrade to a 500 with a
  clear log, not a silent drop.
- The #4 drain still holds with spooling in place.

**Sequencing:** land #4 first. The drain is what makes a deeper buffer safe; a
bigger buffer without a drain just means more to lose.

**Note:** ADR 0002 describes ingestion as using "a bounded in-memory queue."
That wording needs updating when this lands — the decision (best-effort, no
broker) is unchanged, the mechanism isn't.

---

## P2 — support and operability affordances

### 9. Auth failures don't say what we saw

**Readiness:** `ready-for-agent`

On a bad key we return `{"detail": "invalid or inactive DSN key"}`
(`IngestAuthenticator` / `EnvelopeController`).

Bugsink returns the **reconstructed DSN as the server understood it**:
`Project not found or key incorrect: http://<key>@host/42`
(`ingest/views.py`, `get_project`). That one string resolves the most common
"my SDK isn't reporting" ticket without a round trip.

**Acceptance:**
- 403 body includes the DSN as parsed from the request (project id + key as
  received), built from `OUTPOST_PUBLIC_URL`.
- Response does not distinguish "no such project" from "wrong key" (Bugsink's
  reasoning: the user thinks in DSNs, and it avoids a constant-time-compare
  requirement).
- Key validation is cached with invalidation on key mutation — currently a DB
  round trip per envelope (`IngestAuthenticator.isValidKey`).

### 10. No envelope capture for debugging

**Readiness:** `ready-for-agent` (cheap once #8 lands)

Bugsink has `KEEP_ENVELOPES`: store raw envelope bytes on ingest, downloadable
by a superuser (`ingest/models.py` `StoreEnvelope`, `ingest/views.py`
`download_envelope`). Off by default, and stored even on the error paths (bad
DSN, over quota) because those are the interesting ones.

Without it, diagnosing "the SDK sends something we mishandle" means asking the
user for a tcpdump.

**Acceptance:**
- Off by default; a bounded retention (count or age) when enabled.
- Captures on the error paths too, including auth failure.
- Admin-only download of the raw bytes.

After #8 the bytes are already on disk, so this becomes a retention flag rather
than a new write path — worth sequencing behind it.

### 11. No event validation on ingest

**Readiness:** `ready-for-human` (needs a call on strict vs warn default)

Malformed-but-parseable events land in JSONB and fail later at render time, in a
different process, with no attribution to the sending SDK.

Bugsink validates against the Sentry event JSON schema with a
`none`/`warn`/`strict` switch — precompiled `fastjsonschema` on the hot path,
falling back to `jsonschema` **only on failure** to produce a readable message
(`ingest/views.py`, `validate_event_data`). That fallback is the good part: fast
when valid, informative when not.

### 12. Client reports are counted and thrown away

**Readiness:** `ready-for-human` (decide: build or delete)

`ClientReportCounters` accumulates into an in-memory map that is never persisted
and never surfaced ("Phase 5").

- `server/src/main/java/dev/outpost/ingest/ClientReportCounters.java`

Either persist and expose them (what the SDKs dropped client-side is useful
signal) or delete the component.

---

## Roadmap — **do not file as issues**

Product decisions, not work items. As issues they would sit open indefinitely.
Promote individually to a PRD if and when picked up.

- **Tags and search.** Issue search is `title ILIKE ? OR culprit ILIKE ?`
  (`IssueController.java:95`). Bugsink has a full tag model with auto-deduced
  tags (browser/OS/device via UA parsing), per-issue value distributions, a
  `mostly_unique` heuristic that hides non-facetable keys, and a `key:value` +
  free-text query language (`tags/search.py`). Largest single functional gap.
- **Issue lifecycle depth.** We have `unresolved | resolved`
  (`V2__phase1_domain.sql:63`). Bugsink has mute/snooze with volume-based unmute
  conditions ("wake me if >100/hour"), resolve-by-release, resolve-in-next-
  release, release-aware regression detection, a `TurningPoint` audit trail, and
  issue comments.
- **Retention by event count.** Ours is an installation-wide time window.
  Bugsink also caps stored events per project and evicts by a relevance score
  (`events/retention.py`), keeping the retained set representative across ages
  instead of collapsing to "newest N".
- **Email.** No email subsystem at all — no invitations, no password reset, no
  email alerts. Constrains multi-user onboarding more than the missing alert
  channel does.
- **Documented public API.** `/api/internal/**` is UI-facing and undocumented;
  only `/api/0/**` (artifact upload) is token-scoped. Bugsink ships DRF +
  OpenAPI over issues/events/projects/releases/teams.
- **Ingest surfaces we 404:** minidumps / native crashes, CSP reports
  (`/security/`). Bugsink routes both through the same digest pipeline, so they
  inherit grouping, quota and retention for free — worth copying that framing.
- **Markdown export of an issue + stacktrace** for LLM consumption
  (`issues/markdown_issue.py`). Small, and a real differentiator.

---

## Decided against

Recorded so it doesn't get re-litigated.

**A durable message broker (Kafka, Redis, RabbitMQ).** Violates ADR 0003
(PostgreSQL as sole durable dependency) and solves a problem we don't have at
our target volume.

**A Postgres-backed task table (`FOR UPDATE SKIP LOCKED` + `LISTEN/NOTIFY`).**
The standard no-broker pattern, and it works — but it writes every event to
Postgres twice, on exactly the path where our batch-insert design wins.
`SKIP LOCKED` exists to coordinate multiple consumers; ADR 0001 makes us
single-instance, so that coordination is unused. Bugsink deliberately put its
queue in a *separate* database for the same contention reason.

**Crash durability / at-least-once ingest.** Not a goal. Worth recording that
Bugsink does not provide it either, despite appearances: `snappea/foreman.py:375`
deletes the task row **before** running it — *"delete-before-run is the
implementation of our at-most-once guarantee"* — so a task in flight when the
process dies is lost, and orphaned spool files are vacuumed rather than replayed.
Its shipped `docker-compose-sample.yaml` volumes only the Postgres data dir, so
the SQLite queue and spool are discarded on container replacement anyway.

The real difference was never the guarantee — it was the size of the loss window
(their in-flight task count vs. our entire 10 000-item buffer) and the fact that
they drain on shutdown. Items #4 and #8 close both without changing the guarantee
we offer.
