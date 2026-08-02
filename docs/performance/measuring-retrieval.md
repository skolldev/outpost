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

**Some queries admit no honest ceiling at all, and those get none.** The rule
above has no solution when the healthy plan costs *more* than reading the table —
which is the normal case for an indexed lookup into a small one, where fifty
random heap fetches lose to a sequential read of ten blocks. The issue list is
exactly that, so it carries no ceiling and relies on plan shape instead. Adding
one anyway produces a number that cannot fire, and pointing `assertCeilingCanFail`
at some *other*, larger table to make it pass is how such a ceiling survives
review. `QueryGuard.FULL_SCAN_COLUMNS` registers only the four telemetry tables,
so the harness cannot even compute the honest comparison for `issue` — the
absence is a real limitation, not a gap someone forgot to fill, and the reason the
ceiling was deleted rather than fixed.

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

## Baseline, 2026-08-02

Both tiers measured on a 14-core laptop with Postgres 17 in Docker. **The block
counts are dataset-specific and will not reproduce at another scale. The findings
are structural and will.**

### Guard tier

40 003 events, 40 010 log records, 8 004 transactions, 24 012 spans, 200 issues,
10 weekly partitions per table. Reference full-scan costs on that dataset:
`event` 15 042 blocks, `log_record` 5 043, `span` 5 184.

| Query | Blocks | Partitions read | Verdict |
| --- | --: | --: | --- |
| Issue list, default tab | 78 | — | walks an index; was 30 and sorted (#126) |
| Issue list, default tab, `sort=count` | 16 | — | walks an index |
| Issue list, Resolved tab | 30 | — | scans at guard scale by choice; see finding 6 |
| Issue list, deep cursor | 30 | — | O(page) holds |
| Issue list, `environment=` | 85 | — | healthy — answered from the rollup |
| Issue list, `release=` | 217 | — | healthy — answered from the rollup; was 2 087 (#127) |
| Sparkline (14-day bound) | 9 470 | 5 of 10 | prunes correctly |
| Users affected (unbounded) | **20 041** | **10 of 10** | #131 |
| Log page 1 | **8 804** | **10 of 10** | #128 — costs more than a full scan |
| Logs by `trace_id` | 79 | indexed | healthy |
| Logs, 14-day bound | 4 570 | 5 of 10 | prunes correctly |
| …plus a 0.1 %-selective `attr=` | **4 570** | 5 of 10 | #132 — the filter saves nothing at all |
| Release list | **240 299** | **10 of 10** | #130 — 16x a full scan of `event` |
| Trace detail (four tables) | 943 | indexed | healthy |
| Event detail (+ 2 neighbours) | 254 | indexed | healthy |

These are steady-state numbers, and they are now *reproducibly* steady: the
seeder ends in `VACUUM ANALYZE` rather than `ANALYZE`. Before that, the first
execution of a query after seeding cost about twice its steady state — ~185
against ~78 on the list — because the first reader was setting hint bits on the
freshly bulk-loaded heap, which made a guard's number depend on whether it
happened to run first. A full scan of `issue` reads 23 blocks on this dataset.

The issue-list rows were re-measured after #126 changed both the indexes and the
seeder. **The rows below them were not**, so treat any of those within a few
blocks of its previous value as unverified rather than confirmed; the hint-bit
change moves cold reads much more than warm ones, and these were always quoted
warm.

### Benchmark tier

2 000 003 events, 5 000 010 log records, 1 000 004 transactions, 3 000 012 spans,
4 000 issues — 11 000 029 telemetry rows, seeded in 245 s. `shared_buffers=1GB`,
`work_mem=32MB`. Latency is same-machine only; the block and temp columns are not.

The issue rows carry the plan facts of **all four** statements a page load issues,
and each filtered row is paired with the aggregates for *its own* page of issues,
not for an unfiltered one.

| Scenario | p50 | p99 | Blocks | Temp |
| --- | --: | --: | --: | --: |
| Issue list, page 1 (all four queries) | 975 ms | 1 251 ms | 944 107 | 0 |
| Issue list, `sort=count` | 1 270 ms | 1 315 ms | 1 222 641 | 0 |
| Issue list, `query=` (substring) | 962 ms | 1 028 ms | 944 021 | 0 |
| Issue list, `release=` | 1 039 ms | 1 066 ms | 1 448 863 | 0 |
| Issue list, `environment=` | 936 ms | 1 044 ms | 944 811 | 0 |
| Issue list, `project=` | 611 ms | 820 ms | 731 930 | 0 |
| Issue list, page 50 | **36 ms** | 47 ms | 84 231 | 0 |
| Log page 1 | 274 ms | 793 ms | 1 041 935 | 0 |
| Logs, `query=` (0.1 % selective) | 116 ms | 121 ms | 93 763 | 0 |
| Logs, `attr=` (0.1 % selective) | 331 ms | 963 ms | 1 041 935 | 0 |
| Logs by `trace_id` | 10 ms | 21 ms | 157 | 0 |
| Log page 50 | 268 ms | 787 ms | 1 041 755 | 0 |
| Trace search, page 1 | 2 097 ms | 2 134 ms | 900 534 | **224 130** |
| Trace search, page 20 | 2 098 ms | 2 122 ms | 900 382 | **224 130** |
| Trace search, `has_errors=true` | 1 401 ms | 3 186 ms | 6 358 857 | **256 683** |
| Trace detail | 17 ms | 29 ms | 1 699 | 0 |
| **Releases list** | **14 895 ms** | single sample | **28 495 399** | 0 |
| Uptime overview | 30 ms | 37 ms | — | — |
| Event detail + neighbours | 15 ms | 26 ms | 298 | 0 |

Issue-list saturation ladder, same dataset:

| Offered | p50 | p99 | non-200 |
| --: | --: | --: | --: |
| 1/s | 783 ms | 816 ms | 0 |
| 2/s | 785 ms | 833 ms | 0 |
| 4/s | 895 ms | 970 ms | 0 |
| 8/s | 1 022 ms | 1 258 ms | 0 |
| **16/s** | **7 025 ms** | **14 783 ms** | 0 |

### Findings

1. **The releases page is the worst query in the product, by an order of
   magnitude.** 14.9 seconds and 28 million blocks to annotate twenty rows —
   slower than the load driver's own request timeout, so the benchmark reports it
   as a single sample rather than driving it. It is structurally identical to the
   trace-search regression already fixed and guarded: a correlated aggregate over a
   partitioned telemetry table, run once per output row, with no time bound. It
   gets linearly worse with both release count and event volume. (#130)

2. **The issue list's cost is its aggregates, not its list.** The list query is
   558 blocks. The page is 944 107, because the sparkline and the unbounded
   users-affected count run over `event` for all fifty issues on every load — the
   environment rollup, the fourth statement, adds a few hundred. Page 50
   costs 36 ms against page 1's 975 ms — the *deep* page is twenty times cheaper,
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
   guard scale; 1 041 935 → 1 041 935 at benchmark scale). The contrast with
   `query=` on the same run — 1 041 935 down to 93 763 — is what makes it clear
   which of the two is broken. (#132)

6. **Neither issue-list sort order had an index — fixed in #126.** `(last_seen, id)`
   and `(event_count, id)` both fell back to a full sort of `issue` on every page,
   so `KeysetPage`'s O(page) promise rested on an index that was not there. `V9`
   adds four indexes and drops the `(project_id, last_seen)` one they supersede.

   The fix is the clearest argument in this file for asserting plan shape over
   block counts. The default tab now costs *more* at guard scale — 78 blocks
   against 30 — because fifty random heap fetches into a ten-block table lose to
   simply reading the ten blocks. What changed is that the cost is bounded by page
   size instead of table size, and that only pays off at a scale guard data does
   not reach. **A block-count guard would have called this fix a regression.**

   It also moved something nobody was aiming at: the `release=` filter fell from
   11 852 blocks to ~2 500, because an ordered outer scan lets the `EXISTS`
   semi-join stop once the page is full rather than testing every issue. That is a
   4x improvement to a query this change was not about, but it was still #127 — the
   `EXISTS` remained unbounded and `event(release)` still had no index. Issue #127
   subsequently moved that lookup to `issue_release_stats`; it now costs 217 blocks
   and does not touch `event`.

   **The first version of this fix indexed a query the product never sends, and it
   is the mistake most worth keeping written down.** The guards called
   `buildIssueQuery` with `status=null, from=null`, so that is the shape they
   measured and the shape the indexes were built for. But the UI always sends both:
   `ui/src/app/pages/issues/issues.ts` defaults `status` to `unresolved`, and
   `ui/src/app/core/filters.ts` defaults the range to 14d, which arrives as a
   `last_seen` bound. The range is harmless — a range start on the same index. The
   status is not, and none of the original four indexes contained it. Measured at
   40 000 issues with 5% resolved:

   | shape | `(last_seen, id)` indexes | `(status, last_seen, id)` indexes |
   | --- | --: | --: |
   | Unresolved tab, global | 129 | 140 |
   | Unresolved tab, `project=` | 196 | 188 |
   | Resolved tab, global | 2 744 | **108** |
   | Resolved tab, `project=` | **26 980** | **6** |

   A full scan of `issue` on that dataset is 2 819 blocks, so the Resolved tab
   project-scoped was running at *ten times the cost of reading the whole table* —
   the planner walking an index and discarding 95% of what it read. Leading with
   `status` costs nothing on the default tab and fixes the other one outright, at
   the same four indexes. A request with no status still sorts; the UI cannot
   produce one, and that path is no worse than before.

   The lesson generalizes past this bug: **a guard is only as honest as the
   parameters it passes.** `issueListSortIsIndexSupported` was green throughout,
   because it asked about a request nobody makes.

   The second thing worth recording is that naming the index matters. An earlier
   guard asserted only that no `Sort` ran, and it passed with the project-scoped
   indexes dropped — Postgres walks a global index and applies `project_id` as a
   filter, which is ordered, sort-free, and precisely the plan those indexes exist
   to avoid. "Some index was used" cannot fail when a redundant index is added;
   only "*this* index was used" can. `everyIssueListShapeWalksItsOwnIndex` names
   the index it expects for each of the four shapes, which is what
   `PlanFacts.indexesUsed` was added for.

   Two mechanics behind that guard. It prices out the **sort**, not the scan:
   disabling sequential scans alone just moves Postgres onto a bitmap scan of the
   `(project_id, fingerprint)` unique index, which returns rows in heap order and
   sorts them anyway. And it asserts the named index *and* the absence of a `Sort`,
   since a bitmap scan of the right index would satisfy the first alone. What it
   deliberately does not assert is that these are the plans chosen today: at guard
   scale three of the four shapes rightly scan and quicksort fifty rows, because
   200 issues live in ten blocks. *Whether the crossover has been passed* is a
   question about dataset size, and **no tier asserts it** — the benchmark measures
   `project=` and reports its blocks, but asserts only correctness and run
   validity. Saying it "belongs to the benchmark tier" would overstate what is
   there; what exists is a measurement, not a guard.

   Finally, the list query has **no buffer ceiling**, and cannot honestly have one.
   The rule above requires a ceiling below the cost of reading the same table; the
   list reads only `issue`, which a full scan covers in ~23 blocks at guard scale,
   while the healthy indexed plan costs ~78. Any ceiling that clears the healthy
   plan is already above the scan. The one that used to be here validated itself
   against `event` — a table this query never touches — so it passed vacuously, and
   at 940 it sat 41x above the whole table, unable to catch the 30-block sorting
   plan it was nominally guarding. It was deleted rather than retuned.

7. **The issue-list knee is between 8 and 16 requests/s.** p50 drifts from ~785 ms
   to 1 022 ms across 1/s through 8/s, then jumps to 7 025 ms at 16/s — the same
   shape as the previous run, one step steeper. Each request issues four queries against
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
