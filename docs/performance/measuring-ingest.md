# Measuring ingest

How to find out what Outpost's ingest path can actually take, and how to read
the answer. Ingest is the one surface where load is not under our control: SDKs
send what they send, and the only lever we have is shedding.

## The shape of the thing being measured

```
POST /api/{project}/envelope/
  → parse envelope
  → validate DSN key            (one DB query, uncached)
  → IngestQueue.offer()         ← ArrayBlockingQueue, outpost.ingest.queue-capacity (10 000)
      full? → 429 + Retry-After + X-Sentry-Rate-Limits
  → 200
                                  ⋮  asynchronous
  ingest-worker-N               ← outpost.ingest.workers (2)
  → nextBatch(max-batch 500, linger 1 s)
  → pipeline.process per item
  → store.store per signal      ← per-project advisory lock, then the inserts
```

The accept side and the drain side fail in completely different ways, and
conflating them is the main way to get a wrong answer:

- **Accept side** is cheap — a parse, one query, an enqueue. It will happily
  acknowledge far more than the system can store.
- **Drain side** is where the work is. `EventStore` takes
  `pg_advisory_xact_lock` for the project and then issues several individual
  round trips per event before the batch insert.

So *accepted per second is not capacity*. Stored rows per second is.

> **The buffer bounds item count, not memory.** `queue-capacity` is 10 000
> *items*, and a `LogBatch` item holds up to 100 log records as a parsed Jackson
> tree. A log-heavy workload therefore exhausts heap long before it fills the
> buffer: on a 512 MB heap the first run of this benchmark died at queue depth
> **1 461 of 10 000**, having issued zero 429s. Backpressure never got a chance to
> engage. That is `TODO.md` #8's argument, with a number attached — and the reason
> `ingestBenchmark` sets a 2 GB heap rather than inheriting Gradle's default.

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
| `outpost_ingest_items_total{signal,outcome}` | The same split per signal, since one envelope can carry several items |
| `outpost_ingest_queue_depth` / `_capacity` | **The leading indicator.** Depth climbs long before the first 429 |
| `outpost_ingest_queue_wait_seconds{signal}` | Dwell time in the buffer. The delay an SDK cannot see |
| `outpost_ingest_batch_size` | Items per worker drain — small batches mean the linger is dominating |
| `outpost_ingest_process_seconds{signal}` | Pipeline cost |
| `outpost_ingest_store_seconds{signal}` | Persistence cost — usually where the time is |
| `outpost_ingest_dropped_total{stage}` | Items lost at `pipeline` or `store`. Should be zero |

Watch **queue depth and queue wait together**. A rising wait under a flat accept
rate means the drain side is the constraint, which is the distinction a 429 alone
cannot make: by the time a 429 appears, the buffer has been full for a while.

## Running the benchmark

```bash
cd server && ./gradlew ingestBenchmark      # Docker must be running
```

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
| `burstBeyondQueueCapacity` | Does an overload shed cleanly instead of falling over? |

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

Four traps, all of which this harness avoids deliberately — worth knowing if you
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

A fifth, learned the hard way: an instantaneous spike of tens of thousands of
requests never reaches the ingest buffer at all. It exhausts the accept backlog
first, so the run measures the socket layer and reports zero 429s while the queue
sat half empty. Pace overload tests over seconds.

## What the first run found

Baseline from 2026-07-29 on a 14-core laptop, 2 GB heap, Postgres in Docker,
shipped defaults (`queue-capacity` 10 000, `workers` 2, `max-batch` 500,
`linger-millis` 1000). **The absolute numbers are machine-specific and will not
reproduce elsewhere. The four findings are structural and will.**

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

4. **Backpressure is late but correct.** Queue depth was pinned at 10 000 and
   queue wait p99 had reached ~8 s before the first 429 — an SDK gets no signal
   at all until the buffer is completely full, by which point accepted telemetry
   is arriving eight seconds stale. Accept latency stayed at 1–3 ms throughout,
   so the endpoint gives no hint of the backlog behind it. When shedding did
   engage it was clean: zero 5xx, readiness green, and all 16 925 acknowledged
   envelopes stored.

## Related

- `docs/adr/0002-best-effort-ingestion.md` — why the buffer is bounded and in
  memory, and why 429 is the shedding mechanism.
- `docs/adr/0001-single-instance-deployment.md` — why in-process percentiles are
  accurate here; there is nothing to aggregate across.
- `TODO.md` items #3, #6 and #8 — the ingest work this harness exists to justify
  and verify.
