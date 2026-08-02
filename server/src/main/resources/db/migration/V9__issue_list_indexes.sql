-- Indexes for the issue list's two keyset orderings.
--
-- The list pages by (last_seen, id) descending, or by (event_count, id) when
-- sort=count, each optionally narrowed to a project. Only (project_id, last_seen
-- DESC) existed, so neither ordering was index-supported for the global list:
-- every page sorted the whole table, including page 1. That is the one cost
-- keyset pagination exists to avoid.
--
-- Each index names id as the trailing column because the ORDER BY does: the
-- tiebreaker is what makes the (sort, id) < (?, ?) cursor predicate a range scan
-- rather than a filter.
--
-- See docs/performance/measuring-retrieval.md, finding 6, for the measurements.

CREATE INDEX idx_issue_last_seen_id ON issue (last_seen DESC, id DESC);
CREATE INDEX idx_issue_event_count_id ON issue (event_count DESC, id DESC);
CREATE INDEX idx_issue_project_last_seen_id ON issue (project_id, last_seen DESC, id DESC);
CREATE INDEX idx_issue_project_event_count_id ON issue (project_id, event_count DESC, id DESC);

-- Superseded by idx_issue_project_last_seen_id: same leading columns, plus the
-- tiebreaker. Dropping it keeps the write cost of an issue row — updated on every
-- ingest batch that touches it — at four index maintenances rather than five.
DROP INDEX idx_issue_project_last_seen;
