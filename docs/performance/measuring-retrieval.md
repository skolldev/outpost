# Measuring retrieval

How to find out what Outpost's read path costs, and how to read the answer.
Retrieval is the mirror image of [ingest](measuring-ingest.md): load *is* under
our control here — nobody sends us page 50 of the issue list, a person asks for
it — so the question is not "how much can we take" but "what does one request
cost, and does that cost grow with the dataset".

## Two tiers, and what each one asserts

| | `./gradlew test` | `./gradlew retrievalBenchmark` |
| --- | --- | --- |
| Dataset | ~90k telemetry rows, 10 weekly partitions | millions of rows, opt-in scale |
| Measures | logical I/O and plan shape | latency percentiles over real HTTP |
| Asserts | buffer ceilings, partition pruning, no seq scan, no temp files | correctness and run validity only |
| Never asserts | anything wall-clock | anything wall-clock |
| Runs in CI | yes | no |

The split is about *what* each tier asserts, not whether it asserts anything.
**Wall-clock latency has no pass/fail threshold anywhere in this repo.** CI gates
logical I/O and plan invariants; the opt-in run gates correctness and run
validity; latency is reported in both cases for same-machine before/after
comparison only. The percentile tables in `build/reports/retrieval-benchmark/`
carry that caveat in their own header, because a percentile table without one
gets quoted as a capacity claim.

## The CI guards

`server/src/test/java/dev/outpost/query/`:

| Guard | Question |
| --- | --- |
| `IssueQueryPerformanceTest` | The issue list and its four per-page-load queries |
| `LogQueryPerformanceTest` | The log stream: ordering, trace lookup, pruning, attribute filters |
| `ReleaseQueryPerformanceTest` | The releases page's per-row aggregate |
| `TraceSearchPerformanceTest` | The 350x correlated-subquery regression, still locked out |

Each `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`s the **controller's own SQL**,
reached through `QueryPlans` — never a copy. A guard holding its own copy of a
statement keeps passing after the real query regresses, which is the one failure
mode a regression guard cannot afford. The controllers expose `build…Query`
factories returning a `SearchQuery` for exactly this reason, and execute what
those factories return.

`PlanFacts` turns one `EXPLAIN` into four machine-independent numbers: shared
blocks hit, shared blocks read, weekly partitions actually scanned, and temp-file
blocks. Those four distinguish an O(rows) plan from a pruning failure from a sort
spilling to disk from plain cold I/O — four different problems that all read as
"slow" in a latency figure.

### How a ceiling is calibrated

For a path whose current plan is healthy: **ten times its measured logical I/O**,
confirmed by `QueryGuard.assertCeilingCanFail` to sit **below** the cost of a full
scan of the same table. A ceiling above the scan cost cannot fail and is
decoration. Where 10x does not fit under the scan — the sparkline reads most of
the table by nature — the ceiling goes as high as it can while still being able to
fail, and the comment says so.

**Known-bad paths invert this: the ceiling goes at the healthy target and the
guard is `@Disabled` naming its follow-up issue.** Calibrating off a bug's plan
certifies the bug as the baseline and calls it a guard. A disabled guard asserting
the target is a machine-checkable spec for the fix, and re-enabling it is that
fix's acceptance criterion.

`TraceSearchPerformanceTest`'s 50 000 is the one constant not derived this way. It
was calibrated against a real regression — 634k blocks against a healthy 1.8k —
and re-deriving it from a formula would trade evidence for tidiness.

## Running the benchmark

```bash
cd server
./gradlew retrievalBenchmark                    # ~2M events / 5M logs / 1M txn / 3M spans
./gradlew retrievalBenchmark -Pbench.scale=0.1  # a tenth of that, ~5 min
```

Docker required. Each run writes a Markdown table and a JSON copy to
`server/build/reports/retrieval-benchmark/`.

The scale factor moves **row counts only**. Cardinalities — 10 000 distinct users,
20 releases, three environments — and the 60-day retention window stay put,
because they are what make the dataset production-*shaped*. Scaling them down
alongside the volume would produce a small dataset that is also the wrong shape,
and the run would measure a plan production never gets.

Every scenario asserts its own validity as it runs and **fails rather than
reporting the timing**: 200 status, a full page where the endpoint promises one, a
non-empty result, and — for the paginated scenarios — no row id repeated across
adjacent pages. Cursors are *walked*, page by page, exactly as a user gets deep.

## Writing a retrieval benchmark that doesn't lie

Seven traps. The harness avoids all of them deliberately; each one produces
*fast, wrong* numbers, which is the dangerous direction.

1. **Uniform events per issue.** Real telemetry is heavily skewed — a few issues
   own most events — and a uniform spread makes every issue equally cheap, which
   is exactly the case the expensive aggregates handle well. `TelemetrySeeder`
   draws issues as `floor(issues · random()³)`.
2. **Page 1 as the only page.** Page 1 of a keyset-paginated list is the one page
   a broken index can still serve quickly. The benchmark walks to page 50; the
   guards walk to page 3 and compare the ratio, which is the part that survives a
   change of dataset scale.
3. **A dataset that fits in one partition.** Every pruning assertion passes, for
   the wrong reason, forever. Guard scale is small but spans 42 days —
   `TelemetrySeederIntegrationTest` asserts that every telemetry table really has
   rows in six or more distinct weeks.
4. **`DELETE` between fixtures.** Deleted rows keep their pages until a `VACUUM`,
   so a sequential scan of an "empty" table still reads every block the old data
   occupied — and the next test's buffer counts silently carry the previous
   test's dataset. `TelemetrySeeder.clear()` truncates. This one bit us: it is why
   `TraceSearchPerformanceTest` clears through the seeder rather than its own
   `DELETE` list.
5. **No `ANALYZE`.** Without statistics the planner is blind and every plan
   measured is one nobody will ever get in production.
6. **A `CROSS JOIN LATERAL` with no outer reference.** It is not lateral: Postgres
   evaluates it once and the join replicates that single row. An entire seeded
   dataset can land on one issue at one timestamp and still look seeded. The
   seeder computes per-row draws in a derived table behind an `OFFSET 0` fence,
   and the tests in `TelemetrySeederIntegrationTest` exist because this failed
   silently the first time.
7. **A cursor that stops advancing.** A benchmark measuring page 1 fifty times is
   fast, worthless, and looks like a good result. `PageWalk` compares adjacent
   pages' row ids and the run fails on an overlap.

A benchmark whose selectivity is a lie is the eighth trap, and it is subtle in
both directions: a body-search needle matching every row makes the planner choose
a scan on its own, and an attribute value matching half the table tells you
nothing about whether an index could have been used. Both seeded filters are
~0.1% selective on purpose.

## Baseline, 2026-08-01

Guard dataset — 40 003 events, 40 010 log records, 8 004 transactions, 24 012
spans, 200 issues, 10 weekly partitions per table, on a 14-core laptop with
Postgres in Docker. **The block counts are dataset-specific and will not
reproduce at another scale. The findings are structural and will.**

Reference costs on that dataset: a full scan of `event` is 15 045 blocks, of
`log_record` 4 653.

| Query | Shared blocks | Partitions read | Verdict |
| --- | --: | --: | --- |
| Issue list, page 1 | 30 | — | healthy, but sorts (#126) |
| Issue list, deep cursor | 30 | — | O(page) holds |
| Issue list, `environment=` | 132 | — | healthy — answered from the rollup |
| Issue list, `release=` | **12 527** | 7 of 10 | #127 |
| Sparkline (14-day bound) | 9 428 | 5 of 10 | prunes correctly |
| Users affected (unbounded) | **20 045** | **10 of 10** | #131 |
| Log page 1 | **8 142** | **10 of 10** | #128 — costs more than a full scan |
| Logs by `trace_id` | 77 | indexed | healthy |
| Logs, 14-day bound | 4 244 | 3 of 10 | prunes correctly |
| Logs, `attr=` 0.1% selective | 6 173 | 10 of 10 | #132 — the filter saves nothing |
| Release list | **240 368** | **10 of 10** | #130 — 16x a full scan of `event` |
| Trace detail (four tables) | 572 | indexed | healthy |
| Event detail | 42 | indexed | healthy |

Benchmark dataset at `-Pbench.scale=0.1` — 400 030 events, 500 050 log records,
200 004 transactions, 600 012 spans, 400 issues, 1.1M telemetry rows total,
`shared_buffers=1GB`:

| Scenario | p50 | p99 | Shared blocks |
| --- | --: | --: | --: |
| Issue list, page 1 | 68 ms | 92 ms | 142 |
| Issue list, page 8 (deep) | 26 ms | 33 ms | 57 |
| Issue list, `release=` | 71 ms | 82 ms | 47 406 |
| Log page 1 | 33 ms | 41 ms | 104 460 |
| Logs, `query=` (0.1% selective) | 24 ms | 27 ms | 10 301 |
| Logs, `attr=` (0.1% selective) | 37 ms | 42 ms | 104 460 |
| Logs by `trace_id` | 9 ms | 13 ms | 156 |
| Trace search, page 1 | 174 ms | 182 ms | 97 382 |
| Trace search, page 20 | 178 ms | 191 ms | 97 378 |
| Trace detail | 15 ms | 31 ms | 1 355 |
| **Releases list** | **3 097 ms** | **5 379 ms** | **2 944 686** |
| Uptime overview | 30 ms | 37 ms | — |
| Event detail + neighbours | 13 ms | 22 ms | 52 |

### Findings

1. **The releases page is the worst query in the product.** Three seconds at a
   tenth of the target dataset, 2.9M blocks, and it gets linearly worse with both
   release count and event volume. Structurally identical to the trace-search
   regression already fixed and guarded: a correlated aggregate over a partitioned
   telemetry table, run once per output row, with no time bound. (#130)

2. **The issue list's cost is its aggregates, not its list.** Page 1 costs 68 ms
   while page 8 costs 26 ms — the *deep* page is cheaper, because the skew puts
   the busiest issues on page 1 and the two per-page aggregates over `event`
   dominate. The list query itself is 30–142 blocks either way. Fixing the
   unbounded users-affected count (#131) is worth more here than anything done to
   the list. Note that this also means a naive page-1-versus-page-N latency
   comparison is confounded on this endpoint; the guards compare the list query in
   isolation for that reason.

3. **The global log stream reads everything, every time.** `log_record` has no
   index serving `("timestamp", id)` descending, so page 1 sequentially scans all
   ten partitions and sorts them — costing *more* than reading the table, because
   it reads it and then sorts it. Time-bounding helps only by pruning partitions;
   within the window it still scans. (#128)

4. **The log ordering problem masks the attribute one.** `attributes->>? = ?`
   cannot use the GIN index — the key is a bind parameter, and `jsonb_ops` indexes
   containment rather than text extraction — but you cannot see that while the
   plan already reads every row for the sort. At benchmark scale a 0.1%-selective
   attribute filter costs 104 460 blocks against an unfiltered 104 460: exactly
   nothing saved. The guard for it is written as a differential assertion for this
   reason. (#132)

5. **Neither issue-list sort order has an index.** `(last_seen, id)` and
   `(event_count, id)` both fall back to a full sort of `issue` on every page.
   `KeysetPage`'s O(page) promise rests on an index that is not there. It does not
   hurt yet — `issue` is small next to `event` — and it will, which is why the
   guard asserts the plan shape rather than a block count. (#126)

6. **Trace search is O(all transactions) but honestly O(page) in depth.** Page 1
   and page 20 cost the same 97k blocks and the same 175 ms, so pagination is
   working; the constant is high because `DISTINCT ON (trace_id)` scans every
   transaction before the limit applies. Not filed: it is a design property of
   representing traces by their root transaction, and the fix is a `trace` table
   rather than an index.

7. **The issue-list knee is between 100 and 200 requests/s.** p50 goes from 68 ms
   at 100/s to 2 121 ms at 200/s. Each issue-list request issues four queries
   against Spring Boot's default ten-connection Hikari pool, and the driver shares
   the machine with the server, so which of the pool and the CPU is the wall is
   the next experiment rather than a conclusion.

**One finding was retracted.** Body-substring search looked broken at guard scale
— a 0.1%-selective needle saved nothing — and is not: 40 000 rows is small enough
that Postgres correctly prefers a scan to `idx_log_body_trgm`, and at 500 000 it
uses the index for a 10x saving. The issue was closed and the guard deleted rather
than left `@Disabled`, because a disabled guard is a spec for a fix and there was
nothing to fix. It is a good illustration of why the two tiers exist: the guard
tier is for plan invariants that hold at any size, and a question whose answer
depends on dataset size belongs in the benchmark.

## Related

- [`measuring-ingest.md`](measuring-ingest.md) — the write path, and the
  benchmark this one is modelled on.
- `docs/adr/0001-single-instance-deployment.md` — why in-process percentiles are
  accurate here; there is nothing to aggregate across.
