-- Indexes for the issue list, keyed on the shapes the product actually sends.
--
-- The UI always sends a status — `ui/src/app/pages/issues/issues.ts` defaults it
-- to 'unresolved' and the Resolved tab sends 'resolved' — and always sends a time
-- range, which arrives as a `last_seen` lower bound (`ui/src/app/core/filters.ts`
-- defaults to 14d). So every real page load is `status = ? AND last_seen >= ?`,
-- ordered by (last_seen, id) or, with sort=count, by (event_count, id), and
-- optionally narrowed to a project.
--
-- status leads each index because it is the only equality predicate that is
-- always present, and because omitting it is what makes the Resolved tab
-- expensive. Measured at 40 000 issues, 5% of them resolved: with indexes on
-- (last_seen, id) alone the planner walks the index and discards 95% of what it
-- reads — 2 744 blocks for the global Resolved tab and 26 980 project-scoped,
-- against the 2 819 a full scan of `issue` costs. With status leading, the same
-- two queries cost 108 and 6.
--
-- id trails each index because the ORDER BY names it: the tiebreaker is what
-- makes the (sort, id) < (?, ?) cursor predicate a range scan rather than a
-- filter.
--
-- A request carrying no status sorts, exactly as it did before this migration.
-- The UI cannot produce one; the API can, and that path is no worse than it was.
--
-- Locking, deliberately. This is the first migration to index a table that
-- already holds data: a plain CREATE INDEX takes a SHARE lock that blocks every
-- ingest write to `issue` — one UPDATE per event, via EventStore.ISSUE_UPSERT —
-- for the length of the build. CONCURRENTLY was tried and rejected. It deadlocks
-- under Flyway by default, because it waits for every concurrent transaction to
-- drain and Flyway holds one open for its own migration lock; the escape is the
-- global `postgresql.transactional.lock=false`, and it buys a non-atomic
-- migration that can leave an INVALID index behind for an operator to find.
--
-- The blocking build is the cheaper risk here, because `issue` is deduplicated by
-- fingerprint and is small next to the telemetry tables: at a million issues each
-- build is a second or two, so all four together block writes for well under the
-- ~29 seconds the 50 000-envelope ingest queue can absorb at the ~1 700 events/s
-- measured in docs/performance/measuring-ingest.md. Under that, an upgrade costs
-- queue depth rather than shed telemetry. An install far larger than that should
-- build these by hand with CONCURRENTLY before upgrading.
--
-- Write cost: `issue` goes from three indexes to six. These index columns the
-- per-event upsert changes, so its update cannot be HOT and writes an entry to
-- all six. See docs/performance/measuring-ingest.md for what that measured.

CREATE INDEX idx_issue_status_last_seen_id ON issue (status, last_seen DESC, id DESC);
CREATE INDEX idx_issue_status_event_count_id ON issue (status, event_count DESC, id DESC);
CREATE INDEX idx_issue_project_status_last_seen_id ON issue (project_id, status, last_seen DESC, id DESC);
CREATE INDEX idx_issue_project_status_event_count_id ON issue (project_id, status, event_count DESC, id DESC);

-- Superseded by idx_issue_project_status_last_seen_id, which leads with the same
-- project_id and carries both the status and the tiebreaker the ORDER BY names.
DROP INDEX IF EXISTS idx_issue_project_last_seen;
