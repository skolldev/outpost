# Measuring retrieval

How to find out what Outpost's read path costs, and how to read the answer.
Retrieval is the mirror image of [ingest](measuring-ingest.md): load *is* under
our control here — nobody sends us page 50 of the issue list, a person asks for
it — so the question is not "how much can we take" but "what does one request
cost, and does that cost grow with the dataset".

## Two tiers, and what each one asserts

| | `./gradlew test` | `./gradlew retrievalBenchmark` |
| --- | --- | --- |
| Dataset | ~112k telemetry rows, 10 weekly partitions | millions of rows, opt-in scale |
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

Two notes on reading them. Buffer counts are summed across every plan node, and
Postgres reports them cumulatively up the tree, so the total double-counts a
child's blocks in each ancestor — it is a comparable *index* of logical I/O, not a
block count, and it is deliberately the same quantity the text-format regex it
replaced produced, so `TraceSearchPerformanceTest`'s ceiling keeps its meaning.
And only *executed* nodes count as scanned: a partition eliminated by runtime
pruning still appears in the plan, and counting it would make every pruning
assertion vacuous.

The `uptime/overview` row in the benchmark report has no plan columns. That
endpoint issues four unrelated statements and none of them was extracted behind
the seam, so the harness reports a gap rather than a number — `—` in the table
means "not measured", never "read nothing".

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
./gradlew retrievalBenchmark                    # 2M events / 5M logs / 1M txn / 3M spans, ~12 min
./gradlew retrievalBenchmark -Pbench.scale=0.1  # a tenth of that, ~5 min
```

Docker required. Each run writes a Markdown table and a JSON copy to
`server/build/reports/retrieval-benchmark/`.

The scale factor moves **row counts only**. Cardinalities — 10 000 distinct users,
4 000 issues, 20 releases, three environments — and the 60-day retention window
stay put, because they are what make the dataset production-*shaped*. Two of them
matter more than the rest: `users` is the divisor of the suspect
`count(DISTINCT user_ident)`, so scaling it down would make a smoke run understate
exactly the thing it exists to look at, and `issues` decides how many pages deep
the deep-pagination scenario can go before it runs out.

Every scenario asserts its own validity as it runs and **fails rather than
reporting the timing**: 200 status, a full page where the endpoint promises one, a
non-empty result, and — for the paginated scenarios — no row id repeated across
adjacent pages. Cursors are *walked*, page by page, exactly as a user gets deep.

Each scenario's offered rate comes from its own measured latency rather than a
fixed number, held at a small constant concurrency. A fixed rate is only safe for
endpoints that keep up with it: offering the releases page 20/s open-loop would
bury it under a backlog and fail the run for a reason that has nothing to do with
the query. An endpoint slower than half the driver's per-request timeout cannot be
driven at all, and is measured once and reported as a single sample — losing the
whole report to say "this endpoint is slow" would be a poor trade for a report
that was going to say exactly that.

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
   `TelemetrySeederTest` asserts that every telemetry table really has
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
   and the tests in `TelemetrySeederTest` exist because this failed
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

Both tiers measured on a 14-core laptop with Postgres 17 in Docker. **The block
counts are dataset-specific and will not reproduce at another scale. The findings
are structural and will.**

### Guard tier

40 003 events, 40 010 log records, 8 004 transactions, 24 012 spans, 200 issues,
10 weekly partitions per table. Reference full-scan costs on that dataset:
`event` 15 042 blocks, `log_record` 5 043.

| Query | Blocks | Partitions read | Verdict |
| --- | --: | --: | --- |
| Issue list, page 1 | 30 | — | cheap, but sorts (#126) |
| Issue list, deep cursor | 30 | — | O(page) holds |
| Issue list, `environment=` | 133 | — | healthy — answered from the rollup |
| Issue list, `release=` | **11 852** | 8 of 10 | #127 |
| Sparkline (14-day bound) | 9 470 | 5 of 10 | prunes correctly |
| Users affected (unbounded) | **20 041** | **10 of 10** | #131 |
| Log page 1 | **8 804** | **10 of 10** | #128 — costs more than a full scan |
| Logs by `trace_id` | 79 | indexed | healthy |
| Logs, 14-day bound | 4 570 | 5 of 10 | prunes correctly |
| …plus a 0.1 %-selective `attr=` | **4 570** | 5 of 10 | #132 — the filter saves nothing at all |
| Release list | **240 299** | **10 of 10** | #130 — 16x a full scan of `event` |
| Trace detail (four tables) | 745 | indexed | healthy |
| Event detail (+ 2 neighbours) | 202 | indexed | healthy |

### Benchmark tier

2 000 003 events, 5 000 010 log records, 1 000 004 transactions, 3 000 012 spans,
4 000 issues — 11 000 029 telemetry rows, seeded in 232 s. `shared_buffers=1GB`,
`work_mem=32MB`. Latency is same-machine only; the block and temp columns are not.

| Scenario | p50 | p99 | Blocks | Temp |
| --- | --: | --: | --: | --: |
| Issue list, page 1 (all four queries) | 719 ms | 1 091 ms | 785 735 | 0 |
| Issue list, `sort=count` | 1 221 ms | 1 245 ms | 1 217 780 | 0 |
| Issue list, `release=` | 803 ms | 1 467 ms | 1 304 082 | 0 |
| Issue list, `environment=` | 756 ms | 910 ms | 786 449 | 0 |
| Issue list, page 50 | **36 ms** | 42 ms | 94 292 | 0 |
| Log page 1 | 246 ms | 726 ms | 1 041 950 | 0 |
| Logs, `query=` (0.1 % selective) | 120 ms | 125 ms | 92 287 | 0 |
| Logs, `attr=` (0.1 % selective) | 321 ms | 908 ms | 1 041 950 | 0 |
| Logs by `trace_id` | 9 ms | 16 ms | 157 | 0 |
| Log page 50 | 313 ms | 887 ms | 1 041 770 | 0 |
| Trace search, page 1 | 2 060 ms | 2 090 ms | 900 222 | **224 130** |
| Trace search, page 20 | 2 059 ms | 2 080 ms | 900 190 | **224 130** |
| Trace search, `has_errors=true` | 1 323 ms | 3 055 ms | 6 174 598 | **256 641** |
| Trace detail | 15 ms | 24 ms | 1 731 | 0 |
| **Releases list** | **13 489 ms** | single sample | **28 496 329** | 0 |
| Uptime overview | 29 ms | 36 ms | — | — |
| Event detail + neighbours | 13 ms | 26 ms | 290 | 0 |

Issue-list saturation ladder, same dataset:

| Offered | p50 | p99 | non-200 |
| --: | --: | --: | --: |
| 1/s | 636 ms | 666 ms | 0 |
| 2/s | 600 ms | 628 ms | 0 |
| 4/s | 660 ms | 696 ms | 0 |
| 8/s | 700 ms | 903 ms | 0 |
| **16/s** | **3 856 ms** | **12 176 ms** | 0 |

### Findings

1. **The releases page is the worst query in the product, by an order of
   magnitude.** 13.5 seconds and 28 million blocks to annotate twenty rows —
   slower than the load driver's own request timeout, so the benchmark reports it
   as a single sample rather than driving it. It is structurally identical to the
   trace-search regression already fixed and guarded: a correlated aggregate over a
   partitioned telemetry table, run once per output row, with no time bound. It
   gets linearly worse with both release count and event volume. (#130)

2. **The issue list's cost is its aggregates, not its list.** The list query is
   558 blocks. The page is 785 735, because the sparkline and the unbounded
   users-affected count run over `event` for all fifty issues on every load. Page 50
   costs 36 ms against page 1's 719 ms — the *deep* page is twenty times cheaper,
   because the skew puts the busiest issues on page 1. Fixing the unbounded count
   (#131) is worth more here than anything done to the list. It also means a naive
   page-1-versus-page-N latency comparison is confounded on this endpoint, which is
   why the guards compare the list query in isolation.

3. **Trace search sorts 1.75 GB to disk on every page.** `DISTINCT ON (trace_id)`
   has to order every transaction in range before the `LIMIT` applies, and at a
   million transactions that does not fit in `work_mem`. Pagination itself is
   healthy — page 20 costs what page 1 costs, to within noise — the constant is just
   enormous. This is the finding the `temp` column exists for: it is invisible in
   the guard tier, where 8 004 transactions sort comfortably in memory. (#133)

4. **The global log stream reads everything, every time.** `log_record` has no
   index serving `("timestamp", id)` descending, so page 1 sequentially scans all
   ten partitions and sorts them — costing *more* than reading the table, because it
   reads it and then sorts it. Time-bounding helps only by pruning partitions;
   within the window it still scans. (#128)

5. **The log ordering problem masks the attribute one.** `attributes->>? = ?`
   cannot use the GIN index — the key is a bind parameter, and `jsonb_ops` indexes
   containment rather than text extraction. At both scales, adding a 0.1 %-selective
   attribute filter changes the block count by *nothing at all* (4 570 → 4 570 at
   guard scale; 1 041 950 → 1 041 950 at benchmark scale). The contrast with
   `query=` on the same run — 1 041 950 down to 92 287 — is what makes it clear
   which of the two is broken. (#132)

6. **Neither issue-list sort order has an index.** `(last_seen, id)` and
   `(event_count, id)` both fall back to a full sort of `issue` on every page.
   `KeysetPage`'s O(page) promise rests on an index that is not there. It does not
   hurt yet — `issue` is small next to `event` — and it will, which is why the guard
   asserts the plan shape rather than a block count guard scale keeps small. (#126)

7. **The issue-list knee is between 8 and 16 requests/s.** p50 holds at ~700 ms up
   to 8/s and jumps to 3 856 ms at 16/s. Each request issues four queries against
   Spring Boot's default ten-connection Hikari pool, so ~8 concurrent page loads
   saturates it — and the driver shares the machine with the server, so which of the
   pool and the CPU binds first is the next experiment rather than a conclusion.
   Note that the wall moves with finding 2: a page load that stopped scanning
   `event` twice would raise this ceiling without touching the pool.

**One candidate finding was retracted.** Body-substring search looked broken at
guard scale — a 0.1 %-selective needle saved only 13 % — and is not: 40 000 rows is
small enough that Postgres correctly prefers a scan to `idx_log_body_trgm`, and at
5 000 000 it uses the index for an 11x saving. The issue was closed and the guard
deleted rather than left `@Disabled`, because a disabled guard is a spec for a fix
and there was nothing to fix. It is a good illustration of why the two tiers exist:
the guard tier is for plan invariants that hold at any size, and a question whose
answer depends on dataset size belongs in the benchmark.

## Related

- [`measuring-ingest.md`](measuring-ingest.md) — the write path, and the
  benchmark this one is modelled on.
- `docs/adr/0001-single-instance-deployment.md` — why in-process percentiles are
  accurate here; there is nothing to aggregate across.
