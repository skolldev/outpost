# Measuring ingest

How to find out what Outpost's ingest path can actually take, and how to read
the answer. Ingest is the one surface where load is not under our control: SDKs
send what they send, and the only lever we have is shedding.

## The shape of the thing being measured

```
POST /api/{project}/envelope/
  → stream wire body to an ephemeral spool file
  → stream-parse the spool
  → validate Project Key        (bounded in-memory cache; one DB query on miss)
  → IngestQueue.offer()         ← ArrayBlockingQueue of spool references (50 000)
      full? → 429 + Retry-After + X-Sentry-Rate-Limits
  → 200
                                  ⋮  asynchronous
  ingest-worker-N               ← outpost.ingest.workers (2)
  → nextBatch(max-batch 500, linger 1 s)
  → stream-parse each spool
  → pipeline.process per item
  → store.store per signal      ← per-project advisory lock, then the inserts
  → remove successfully digested spools

  ingest-spool-reaper           ← outpost.ingest.spool-sweep-interval (5 min)
  → remove spool files with no live queue entry, untouched for spool-max-age (1 h)
```

The accept side and the drain side fail in completely different ways, and
conflating them is the main way to get a wrong answer:

- **Accept side** is a bounded spool write, a streaming parse, a cached Project Key lookup, and an enqueue. It will happily
  acknowledge far more than the system can store.
- **Drain side** is where the work is. `EventStore` takes
  `pg_advisory_xact_lock` for the project and then issues several individual
  round trips per event before the batch insert.

So *accepted per second is not capacity*. Stored rows per second is.

> **The buffer bounds envelope count without retaining envelope payloads.**
> `queue-capacity` is 50 000 fixed-size spool references. Parsed trees and
> attachment bytes exist only while a worker digests one envelope, so queued
> heap use no longer scales with envelope size. Disk capacity is now the
> variable-size bound; an unwritable or full spool filesystem returns 500 and
> logs the failure. Crashes and drain timeouts leave spool files with no queue
> entry behind, so a periodic sweep reclaims anything untouched for
> `spool-max-age` — watch `outpost.ingest.spool.reaped.files`, since a steady
> non-zero reap rate means envelopes are being spooled and then abandoned.

## Metrics

Every meter is registered in
`server/src/main/java/dev/outpost/ingest/IngestMetrics.java` and exposed on the
management port:

```bash
curl -s localhost:9090/actuator/prometheus | grep outpost_ingest
```

The management port defaults to `9090` (`OUTPOST_MANAGEMENT_PORT`) and is
deliberately **not** published by `docker-compose.yml` — `SecurityConfig` ends in
`permitAll`, so actuator on the public port would be world-readable. The
`/healthz` and `/readyz` probes stay on the main port and are unaffected.

| Meter | What it tells you |
| --- | --- |
| `outpost_ingest_envelopes_total{outcome}` | `accepted` / `rejected` / `forbidden` / `malformed` / `oversize` |
| `outpost_ingest_items_total{signal,outcome}` | Items reaching the buffer, `accepted` / `rejected` per signal — one envelope can carry several. The envelope-level outcomes reject before an item exists, so they never appear here |
| `outpost_ingest_queue_depth` / `_capacity` | **The leading indicator.** Depth climbs long before the first 429 |
| `outpost_ingest_queue_wait_seconds{signal}` | Dwell time in the buffer. The delay an SDK cannot see |
| `outpost_ingest_batch_size` | Envelope references per worker drain — small batches mean the linger is dominating |
| `outpost_ingest_process_seconds{signal}` | Pipeline cost |
| `outpost_ingest_store_seconds{signal}` | Persistence cost — usually where the time is |
| `outpost_ingest_dropped_total{stage}` | Items lost at `pipeline` or `store`. Should be zero |
| `outpost_ingest_spool_reaped_files_total` / `_bytes_total` | Orphaned spool files swept and disk reclaimed. Should be zero in steady state — a non-zero rate means envelopes are spooled and then abandoned |

Watch **queue depth and queue wait together**. A rising wait under a flat accept
rate means the drain side is the constraint, which is the distinction a 429 alone
cannot make: by the time a 429 appears, the buffer has been full for a while.

## Running the benchmark

```bash
cd server && ./gradlew ingestBenchmark      # Docker must be running
```

(The sibling task is `retrievalBenchmark`; both are excluded from `test` by the
`benchmark` tag and separated from each other by an `ingest` / `retrieval` tag.)

It is excluded from `./gradlew test` and therefore from CI, because throughput on
a shared machine is not something you can put a threshold on without flaking. The
pass/fail half of the question — that a full buffer sheds correctly, with the
headers the SDKs read — lives in `IngestBackpressureIntegrationTest`, which
**does** run in CI and contains no wall clock at all.

Each run writes a Markdown table and a JSON copy to
`server/build/reports/ingest-benchmark/`. The Markdown is meant to be pasted into
a PR body.

Scenarios, in `server/src/test/java/dev/outpost/bench/IngestBenchmark.java`:

| Scenario | Question |
| --- | --- |
| `errorEnvelopeStepLoad` | Sustained error events per second |
| `logEnvelopeStepLoad` | Log records per second (100 per envelope, as the SDKs batch them) |
| `transactionEnvelopeStepLoad` | Transactions per second, each fanning out into spans |
| `singleVersusMultiProjectThroughput` | Does throughput scale with projects? Isolates the per-project advisory lock |
| `burstBeyondQueueCapacity` | Does an overload shed cleanly instead of falling over? Uses a driver-safe 3 200/s and extends duration to offer three queue capacities |

The report distinguishes the target `offered/s`, the actual scheduler pace,
and the rate the driver dispatched after its own in-flight limit. Any non-zero
driver `failed` or `shed` count invalidates a server-capacity comparison and
fails the embedded benchmark. Queue wait is an exact per-step average; an
in-process percentile cannot be reset or subtracted safely between plateaus. It
includes the verified drain, so every accepted envelope in the step contributes.

### The allocation columns

`alloc MB` and `alloc KB/env` come from `AllocationProbe`, which reads the
per-thread allocation counters (`ThreadMXBean.getThreadAllocatedBytes`) for
Tomcat's `http-nio-*` threads and the `ingest-worker-*` threads. Request threads
are sampled as soon as every HTTP response has arrived; worker threads remain in
the window until the queue has fully drained. This is the column to quote in a
PR that claims to have removed a copy — `stored/s` will barely move for one,
because on a 2 GB heap allocation is not yet the constraint.

Three properties it is worth knowing about:

- **GC-independent.** The counters are monotonic and count every byte handed
  out, whether it survived a millisecond or the whole run. A before/after is
  therefore not measuring where the collector happened to be on its sawtooth,
  which is what `totalMemory() - freeMemory()` deltas measure.
- **Server-side only.** The in-JVM driver generates envelopes and runs an HTTP
  client in the same process. It dispatches on virtual threads, whose allocation
  is charged to their `ForkJoinPool` carriers, so the thread-name filter excludes
  the driver by construction rather than by luck.
- **Charged to the step that caused it.** The window closes once the queue has
  drained, not when the offered load stops. Past the knee most of a plateau's
  store work happens after its last request, and closing the window earlier would
  bill that work to the following step — making the overloaded step look cheap
  and its successor look expensive. The accept-side checkpoint also prevents
  idle Tomcat threads from timing out during a long drain and taking their
  allocation counters with them.

`alloc KB/env` divides by 200s **and** 429s, because shedding happens after the
parse: a rejected envelope has already paid for one. The unit is the envelope, so
a log envelope carrying 100 records is legitimately ~100× an error envelope.
Compare within a scenario, not across.

Expect the figure to *drift down* on the saturated steps of a ladder — 116 KB at
200/s against 89 KB at 3 200/s on the error ladder. Nothing got cheaper: a shed
envelope pays for the parse and never for the store, so a step that is mostly
429s has a cheaper mix. Compare steps with similar 429 shares, and prefer the
unsaturated steps when quoting a before/after.

A `—` in these columns means the probe's thread-name filter matched nothing — if
Tomcat is ever switched to virtual threads, for instance. It says so on stdout
rather than reporting a confident zero.

### Against a real deployment

The in-JVM run shares a machine with the server, so its numbers are a floor —
reproducible enough to compare a before against an after, not a capacity
statement. For an absolute number, drive a real instance:

```bash
./gradlew ingestBenchmark \
  -Pbench.target=http://host:8080 \
  -Pbench.projectId=1 \
  -Pbench.key=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
```

That enables `RemoteIngestBenchmark` and skips the embedded one. It reports
client-side figures only; scrape `/actuator/prometheus` on the instance during
the run for the queue columns.

## Writing a benchmark that doesn't lie

Six traps, all of which this harness avoids deliberately — worth knowing if you
extend it.

1. **Closed-loop drivers hide saturation.** N threads looping send-then-wait
   throttle themselves the instant the server slows, so offered load silently
   tracks served load and the knee never appears. `LoadDriver` is open-loop: it
   offers at a fixed rate regardless of how fast answers come back.
2. **Coordinated omission.** Timing from the actual send rather than the intended
   one makes a stalled server look fast, because the driver stopped asking during
   the stall. Latencies here are measured from the instant each request was *due*.
3. **Reused `event_id`s.** The event insert is `ON CONFLICT DO NOTHING`, so
   repeating an id measures a no-op insert. `EnvelopeFactory` issues a fresh one
   every time.
4. **One fingerprint for everything.** Events sharing a fingerprint all contend on
   a single `issue` row *inside* the per-project advisory lock — a degenerate hot
   spot, not production shape. `EnvelopeFactory` spreads over 50 by default; pass
   `1` when you want the worst case on purpose.
5. **The driver can saturate before the server.** An instantaneous spike—or a
   burst rate scaled with queue capacity—can exhaust the driver's in-flight cap,
   the accept backlog, or ephemeral ports before the queue fills. The burst keeps
   a known-good fixed rate and scales duration instead. Driver failures and
   driver-side shedding are reported separately and make the local run fail.
6. **An empty buffer is not an idle ingest path.** Workers remove whole batches
   before persisting them. Clearing fixtures when `queue.size()` reaches zero can
   delete a Project underneath an in-flight batch and contaminate every later
   scenario. Scenario boundaries wait for the outstanding count, which includes
   both buffered and in-flight envelopes, and timeouts fail rather than warn.

## What the first run found

Baseline from 2026-07-29 on a 14-core laptop, 2 GB heap, Postgres in Docker,
the then-current defaults (`queue-capacity` 10 000 parsed items, `workers` 2, `max-batch` 500,
`linger-millis` 1000). **The absolute numbers are machine-specific and will not
reproduce elsewhere. The five findings are structural and will.**

| Signal | Sustained | First 429 at |
| --- | --- | --- |
| Errors | ~700 events/s | 1 600 envelopes/s offered |
| Transactions (5 spans) | ≥1 600 txn/s — never saturated | not reached |
| Logs (100 records/envelope) | ~2 400 records/s, peaking at 40 envelopes/s | not reached before collapse |

1. **The per-project advisory lock is the error-path ceiling.** At an identical
   3 200/s offered against the same two workers, one project stored 480 events/s
   and four projects stored 1 015 — a 2.1× gain from nothing but spreading the
   load. 2.1× is the worker count: with a single project the two workers
   serialize on `pg_advisory_xact_lock` and do the work of one. Raising
   `outpost.ingest.workers` does nothing for a single-project install.

2. **Errors cost ~2× a transaction.** `TransactionStore` has no advisory lock and
   no issue upsert; `EventStore` has both, plus an `issue_env_stats` upsert per
   event. Grouping is the expensive part of error ingest, not the insert.

3. **`max-batch` counts items, not records.** A log item holds up to 100 records,
   so a 500-item batch is 50 000 records in one transaction — each with its own
   environment upsert before the batch insert. Log throughput therefore *degrades*
   past 40 envelopes/s instead of plateauing, and at 160/s a single batch did not
   finish inside the 15 s measurement window at all (0 rows stored).

4. **One error event costs ~110 KB of allocation.** Measured on the error ladder
   (2026-07-30, `alloc KB/env`): 116 KB at 200/s, holding within a few percent up
   to the knee. The envelope on the wire is under a kilobyte, so the ingest path
   allocates orders of magnitude more than the telemetry it is storing — which is
   what `TODO.md` #3 predicts from the copy count: the raw body, a per-item
   `copyOfRange`, the Jackson tree, two `deepCopy()`s, the gzip array and a JSON
   `String` for the JSONB bind. It is the number to beat when that item is picked
   up.

5. **Backpressure is late but correct.** Queue depth was pinned at 10 000 and
   queue wait p99 had reached ~8 s before the first 429 — an SDK gets no signal
   at all until the buffer is completely full, by which point accepted telemetry
   is arriving eight seconds stale. Accept latency stayed at 1–3 ms throughout,
   so the endpoint gives no hint of the backlog behind it. When shedding did
   engage it was clean: zero 5xx, readiness green, and all 16 925 acknowledged
   envelopes stored.

## Index maintenance on `issue`, measured 2026-08-02

`EventStore.ISSUE_UPSERT` runs **once per event**, not once per batch, and it
bumps `event_count` and `last_seen` every time. Both are indexed, so the update
can never be HOT: each one writes a new heap tuple and an entry into *every*
index on `issue`, including the ones on columns it did not touch. The index count
is therefore a direct multiplier on the error path — the same path finding 2
above already names as the reason errors cost ~2x a transaction.

#126 took that count from three to six. That is a doubling of per-event index
maintenance on the hottest write in the system, so it was measured rather than
argued about — six paired runs of the saturated single-project error ladder,
alternating before/after on one machine:

| | stored/s, 6 measurements | mean | spread |
| --- | --- | --: | --: |
| 3 indexes | 1707, 1729, 1770, 1779, 1806, 1824 | 1769 | 6.6% |
| 6 indexes | 1671, 1673, 1727, 1735, 1767, 1822 | 1733 | 8.7% |

**−2.1% of means, against a 7–9% spread, with the distributions overlapping and
the sign flipping between adjacent pairs.** The 4-project variant moved −0.2%.
`alloc KB/env` was unchanged to three digits, which is the expected shape: index
maintenance is Postgres-side work and does not touch JVM allocation.

The honest reading is that the cost is below what this harness resolves on this
machine — **not** that it is zero. The noise floor is ~7%, so anything smaller is
invisible here. Two caveats on generalizing it: `EnvelopeFactory` spreads over 50
fingerprints, so 50 issue rows absorb the batch, and an install with far more
distinct issues per batch touches proportionally more index pages; and this
ladder saturates on the per-project advisory lock (finding 1), which may mask a
storage-side cost that would show on a lock-free path.

Worth knowing before adding a seventh index: **nothing in CI will catch it.** The
ingest benchmark is excluded from `test` by the `benchmark` tag, so a future index
on `issue` gets measured only if someone chooses to.

## Related

- [`measuring-retrieval.md`](measuring-retrieval.md) — the read path, measured on
  the same two-tier split: CI guards that assert logical I/O and plan shape, and
  an opt-in `retrievalBenchmark` that reports latency. It reuses `LoadDriver` and
  the report plumbing here verbatim.
- `docs/adr/0002-best-effort-ingestion.md` — why the buffer is bounded and in
  memory, and why 429 is the shedding mechanism.
- `docs/adr/0001-single-instance-deployment.md` — why in-process percentiles are
  accurate here; there is nothing to aggregate across.
- `TODO.md` items #3, #6 and #8 — the ingest work this harness exists to justify
  and verify.
