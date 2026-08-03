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
| `ReleaseQueryPerformanceTest` | The releases page: what it reads, and what its cost scales with |
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
endpoints that keep up with it: offering trace search 20/s open-loop would bury it
under a backlog and fail the run for a reason that has nothing to do with the query.
An endpoint slower than half the driver's per-request timeout cannot be
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
| Log page 1, All time | 480 | 3 of 10 | walks an index; was 8 804 and sorted (#128) |
| Log page 1, 14-day bound | 342 | 3 of 10 | walks an index |
| …scoped to one project | 334 | 3 of 10 | walks the project-leading index |
| …scoped to one environment | 1 006 | 3 of 10 | walks the global index and filters; see finding 4 |
| Log deep page (p3) | 375 | 3 of 10 | O(page) holds |
| Logs by `trace_id` | 89 | indexed | healthy |
| Logs, 14-day bound + a 0.1 %-selective `attr=` | **3 992** | 5 of 10 | #132 — 11x the unfiltered page |
| Release list | 83 | none | healthy — counted from the rollup; was 240 299 (#130) |
| Release list, a full 200-release page | 344 | none | O(page) holds — 2.6x the 1-release page |
| Trace detail (four tables) | 943 | indexed | healthy |
| Event detail (+ 2 neighbours) | 254 | indexed | healthy |

These are steady-state numbers, and they are now *reproducibly* steady: the
seeder ends in `VACUUM ANALYZE` rather than `ANALYZE`. Before that, the first
execution of a query after seeding cost about twice its steady state — ~185
against ~78 on the list — because the first reader was setting hint bits on the
freshly bulk-loaded heap, which made a guard's number depend on whether it
happened to run first. A full scan of `issue` reads 23 blocks on this dataset.

The issue-list rows were re-measured after #126 changed both the indexes and the
seeder, the log rows after #128 added `V11`, and the release rows after #130 moved
the page's counts to the rollup. **The remaining rows — the sparkline,
users-affected, trace and event detail — were not**, so treat any of those within a
few blocks of its previous value as unverified rather than confirmed; the hint-bit
change moves cold reads much more than warm ones, and these were always quoted warm.

The log rows move by a few percent between runs (page 1 at All time was seen at 480
and at 725 on the same dataset) because they now read few enough blocks that cache
state is a material share of the total. That is why the guards assert plan shape,
and why the one buffer ceiling they keep sits at 2 500.

### Benchmark tier

2 000 003 events, 5 000 010 log records, 1 000 004 transactions, 3 000 012 spans,
4 000 issues — 11 000 029 telemetry rows, seeded in 245 s. `shared_buffers=1GB`,
`work_mem=32MB`. Latency is same-machine only; the block and temp columns are not.

**The five log rows were re-measured after #128 added `V11`, and the releases row after
#130 moved its counts to the rollup** (2026-08-04). The rest of the table was re-run in
the same pass: every row landed within ~1 % of the run before it, but the issue rows sit
~25 % below the 2026-08-02 values recorded here, which #126 and #127 landed between. They
are left as recorded rather than half-updated — re-attributing them belongs to whichever
change next measures them.

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
| Log page 1, All time | **11 ms** | 20 ms | **333** | 0 |
| Log page 1, 14d (the default) | **12 ms** | 22 ms | **342** | 0 |
| Log page 1, 14d, `project=` | **12 ms** | 22 ms | **342** | 0 |
| Log page 50, All time | **11 ms** | 21 ms | **419** | 0 |
| Log page 50, 14d, `project=` | **12 ms** | 24 ms | **358** | 0 |
| Logs, `query=` (0.1 % selective) | 109 ms | 212 ms | 92 119 | 0 |
| Logs, `attr=` (0.1 % selective) | 66 ms | 80 ms | 282 516 | 0 |
| Logs by `trace_id` | 11 ms | 17 ms | 175 | 0 |
| Trace search, page 1 | 2 097 ms | 2 134 ms | 900 534 | **224 130** |
| Trace search, page 20 | 2 098 ms | 2 122 ms | 900 382 | **224 130** |
| Trace search, `has_errors=true` | 1 401 ms | 3 186 ms | 6 358 857 | **256 683** |
| Trace detail | 17 ms | 29 ms | 1 699 | 0 |
| Releases list | **16 ms** | 26 ms | **429** | 0 |
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

1. **The releases page was the worst query in the product by an order of magnitude —
   fixed in #130.** It cost 16.5 seconds and 28.5 million blocks to annotate twenty
   rows, slower than the load driver's own request timeout, so the benchmark could
   not drive it and reported a single sample. It now costs **16 ms and 429 blocks**,
   driven at 20/s like every other healthy page — a thousandfold on latency, sixty-six
   thousandfold on logical I/O, and the weekly partitions it reads went from 13 to
   **none**.

   That zero is the finding, not the ratio. `count(DISTINCT e.issue_id)` over `event`,
   correlated to the release row and unbounded in time, is now `count(*)` over
   `issue_release_stats` — the rollup #127 built for the issue-list release filter,
   which holds exactly one row per (Issue, Release) that has ever carried an Event.
   One row per membership means the distinct count *is* a row count, so the page
   answers its question without reading a telemetry table at all, and its cost stopped
   being a function of how long events are retained. A ratio would have been true of a
   fix that merely got cheaper.

   **Reusing that rollup needed one schema change, and it is the interesting one.**
   `issue_release_stats` keys on `issue_id`, so scoping a count to one Project meant
   joining `issue` — which reads *every* Project's rows for a version string before
   discarding all but one Project's. Release versions are not unique across Projects:
   `release` is keyed `(project_id, version)`, and an install running four services off
   one tag names them all `app@1.4.0`. `V12` denormalizes `project_id` onto the rollup
   and indexes `(project_id, release)`. Its backfill is a join against `issue` — cheap
   where V10's was not, because V10 had to read every weekly partition of `event` to
   derive membership that existed nowhere else.

   **The old acceptance bound would have passed a fix that was still O(retention).**
   "Less than two full scans of `event`" is satisfied by a single `GROUP BY release`
   pass computed once per request, which removes the per-row multiplier and still reads
   every retained Event on every page load. The guard now asserts the page never reads
   `event` at all, which is the claim that holds at any dataset size, and the ticket's
   original bound is kept behind it because a regression to the correlated plan costs
   240 300 blocks against a 15 000-block scan and fails it loudly.

   **A per-row subquery over a small table is invisible until the big one is fixed, and
   that is the lesson worth keeping.** De-correlating `issue_count` alone left a full
   200-release page at 1 731 blocks against 47 for a one-release page — 37x, and every
   bit of it the two *artifact* counts, still one index probe per row over unpartitioned
   tables nobody had ever suspected. They had never been measured because `issue_count`
   was 240 000 blocks and drowned them. Grouping them the same way is what took the page
   to its current 344 against 132, a factor of 2.6. The guard's scaling assertion is what
   surfaced them: it fills the page the endpoint actually returns, and the guard dataset
   has eight releases where the endpoint returns two hundred.

   **The same guard then rejected the tidier version of the fix, which is the better
   argument for it.** Each of the three counts binds `project_id = ?` and matches the
   page with `release IN (SELECT version FROM page)` — three near-identical branches and
   the project bound four times, which reads like something to factor out. Joining `page`
   on `(project_id, version)` instead removes the repetition and binds it once. It also
   takes the constant away from the planner, which stops opening one index range per
   branch, drives the joins from `page`, and reconstructs exactly the per-output-row
   nested loop the whole change existed to delete: **3 368 blocks against 132, 25x.** The
   duplication is load-bearing, and `ReleaseController`'s javadoc now says so where the
   next person to tidy it will look.

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

4. **The global log stream read everything, every time — fixed in #128.**
   `log_record` had no index serving `("timestamp", id)` descending, so page 1
   sequentially scanned all ten partitions and sorted them, costing *more* than
   reading the table. `V11` adds `("timestamp" DESC, id DESC)` and
   `(project_id, "timestamp" DESC, id DESC)`, and page 1 fell from 8 804 blocks —
   9 010 when re-measured immediately before the fix — to 480.

   The partitioning is what makes it this cheap. `log_record` is range-partitioned
   on the same column the `ORDER BY` leads with, so Postgres uses an **ordered
   `Append`** rather than a `MergeAppend`: it walks partitions newest-first and stops
   once the page is full, executing three of ten. Cost is O(page).

   Two things the fix does *not* do, both worth knowing before quoting it.

   **All time costs about twice the 14-day default (480–725 blocks against ~340),
   and the difference is not rows read.** Both execute the same three partitions —
   the table above reports 3 of 10 for either — so the extra blocks are not a longer
   walk. They are the *planner* touching every partition it must consider before
   pruning, which `PlanFacts` counts because it sums the `Planning` node's buffers
   alongside the executed ones. That is O(retention) rather than O(rows), so it does
   not grow with the dataset, but it is why `MAX_PAGE_ONE_BLOCKS` is calibrated
   against All time. The standard 10x rule was unusable here: 10x the healthy plan is
   7 250, above the 5 043 a full scan costs, so it could not fail. The ceiling sits at
   2 500 — half the full scan — and the plan-shape assertions carry the real weight.

   **A multi-value filter cannot use any leading-column index for ordered output.**
   `project_id IN (?,?)` leaves rows ordered by `(project_id, "timestamp")`, so the
   planner correctly falls back to the global index and filters — on every index set
   tried, including ones leading with `project_id`. Only single-select benefits,
   because `IN (?)` simplifies to `= ?`. The environment filter (ADR 0009) behaves
   the same way. There is no index that serves the multi-select shape; a sort is the
   alternative and it is strictly worse.

   What is left is filter selectivity: a filtered walk of an ordered index costs
   `page / selectivity`. That is why `project_id` got an index and `environment` and
   `level` did not. A project's share of the logs shrinks without bound as an install
   adds projects, so one quiet project's stream would walk ~100 rows per row returned
   on a 100-project install; environment is low-cardinality by convention and `level` has
   six values, so both cost a bounded small constant — measured ~3x (1 006 blocks)
   and ~4x (1 322). **Neither of those constants is something the guard tier can
   assert**, because at guard scale there are two projects and three environments;
   the structural claim the guards do assert is that each shape walks the index built
   for it rather than sorting.

   **The environment half of that is an assumption, and it is the one to revisit
   first.** `level` is genuinely closed, but environments are auto-created on ingest
   by `TelemetryOrigins` and nothing caps how many accumulate. An install naming an
   environment per branch or per pod gives the environment filter exactly the
   unbounded `page / selectivity` cost that earned `project_id` its index. The
   remedy is known and priced — `(environment, "timestamp" DESC, id DESC)` took that
   shape from 826 blocks to 339 when it was tried — and was declined only because
   three environments is what the product's UI and seeder assume, not because the
   schema guarantees it.

   **At benchmark scale the fix is worth far more than at guard scale, which is the
   direction the structural argument predicted.** On 5 000 010 log records page 1
   went from 1 041 935 blocks and 274 ms to **333 blocks and 11 ms**, and page 50
   from 1 041 755 and 268 ms to **419 and 11 ms** — O(page) holding three thousand
   times better than the plan it replaced. p99 on page 1 fell from 793 ms to 20 ms.
   This is the payoff a block-count guard at guard scale could not have seen, for
   exactly the reason finding 6 gives about #126.

   **And it holds at the shapes the UI actually sends, which is the half worth
   checking.** The rows above are All time — the range picker's expensive end, not
   its default. The benchmark now drives the 14-day default and its project-scoped
   variant too, page 1 and a deep cursor *walked under those same filters*: 342, 342,
   and 358 blocks against the unfiltered 333, all within a millisecond of each other.
   The fix is not an All-time special case.

   **It also gave body-substring search two plans of near-equal estimated cost, and
   the planner now picks between them run to run.** Two full runs on the same
   dataset, differing in nothing but which plan was chosen:

   | `query=` plan | blocks | partitions | p50 |
   | --- | --: | --: | --: |
   | bitmap scan of `idx_log_body_trgm` + sort | 92 119 | 13 | 109 ms |
   | ordered walk of `idx_log_ts_id` + `ILIKE` filter | 307 690 | 3 | 89 ms |

   The first is what it always did (93 763 before the migration); the second is new.
   Note that the *cheaper* plan in blocks is the *slower* one in wall clock — the
   ordered walk trades random heap fetches for a sequential-ish read, which is a
   good trade here and a worse one as the needle gets rarer, since only the walk has
   to go further to fill a page. **This was first written up as a 3.3x regression on
   the strength of a single run; a second run showed the other plan and that claim
   was wrong.** What is true is the instability, and it is a caution about the whole
   tier: one benchmark run is a sample of the planner's choice, not a measurement of
   the query. **No guard covers this**, because #129 established body search as a
   scale-dependent question and deleted its guard.

   The same new plan is why `attr=` improved (1 041 935 → ~280–310k) without #132
   being fixed at all — the filter is still unindexed, it is just riding a cheaper
   scan.

   The write side was measured rather than assumed, because this indexes the
   highest-volume table in the product. At 2 000 010 records over 13 weekly
   partitions the two indexes cost 78 MB and 95 MB against a 651 MB heap and the
   374 MB V4's four already occupy — 90 bytes per record, taking `log_record`'s index
   storage up ~46 %. **Storage, not throughput, is the price.** The build holds a
   `ShareLock` (read from `pg_locks` during one) which does block inserts (probed
   deterministically), but only for 392 ms and 560 ms at that size.
   Ingest throughput needed the log ladder raised before it could say anything: its
   top step left the queue at depth 0, so it reported the offered rate rather than a
   capacity. Raised to 640 envelopes/s it saturates, and the ceiling is **43 481
   records/s before against 43 947 after** — a 1.1 % difference in the direction two
   extra indexes cannot produce, i.e. below what the harness resolves. See
   [`measuring-ingest.md`](measuring-ingest.md) for the caveats on a single pair.

5. **The log ordering problem masked the attribute one; #128 unmasked it.**
   `attributes->>? = ?` cannot use the GIN index — the key is a bind parameter, and
   `jsonb_ops` indexes containment rather than text extraction. While #128 was open
   this was invisible: adding a 0.1 %-selective attribute filter changed the block
   count by *nothing at all* (4 570 → 4 570 at guard scale; 1 041 935 → 1 041 935 at
   benchmark scale), because a plan that already reads everything cannot be made
   worse by an unindexable predicate.

   Now that the unfiltered page walks an index, the defect is visible at both scales
   and reads differently at each. At guard scale the filter costs **3 992 blocks
   against the unfiltered page's 342** — it still scans and sorts, because the
   planner will not walk the ordered index for a predicate it believes is this
   selective. At benchmark scale it walks the ordered index instead and costs
   **307 671 against page 1's 333**. Both are the same underlying fact — the
   predicate is applied after the read, never by an index — and either ratio is a
   far sharper signal than the parity #128 used to hide it behind. (#132)

   It also invalidated that guard's *spec*, which is the part worth flagging: the
   disabled `attributeFilterMakesTheQueryCheaper` asserts the filtered query costs a
   quarter of the unfiltered one, and a quarter of 342 is under 90 blocks — a bar a
   correct GIN lookup plus its heap fetches may not clear. The ratio was left
   unchanged rather than retuned to a number nobody has measured against a working
   implementation, with the reasoning recorded on the test.

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
