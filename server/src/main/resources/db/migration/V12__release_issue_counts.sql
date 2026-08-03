-- The Releases page annotates each Release with the number of distinct Issues
-- carrying a retained Event on it. That is the membership question
-- `issue_release_stats` already answers, one row per (Issue, Release) with a
-- retained Event, so the page can count rollup rows instead of aggregating
-- `event` once per Release row with no time bound (#130).
--
-- What the rollup was missing is a Project-scoped way in. It keys on issue_id,
-- so scoping a count to one Project meant joining `issue` — which reads every
-- Project's rows for a Release version and then discards all but one Project's.
-- Release versions are not unique across Projects (`release` is keyed
-- (project_id, version), and every install names its releases the same handful
-- of ways), so that is real work proportional to the whole installation on a
-- page that asked about one Project. Denormalizing project_id keeps a Project's
-- page proportional to that Project.
--
-- The column is derivable from issue_id, and duplicating it is the deliberate
-- part: the alternative is an index on `issue` walked once per rollup row, on
-- the read path, forever.
--
-- No foreign key, deliberately, and this is the write side of the trade. The
-- row already cascades from `issue`, which cascades from `project`, so an FK
-- here would delete nothing the existing one does not. What it would add is a
-- referential check on every insert of EventStore's per-event rollup upsert —
-- the hot path docs/performance/measuring-ingest.md measures, on a value this
-- system writes from the Issue it just upserted rather than accepts from a
-- caller. The primary key already admits one row per (Issue, Release), and the
-- retention rebuild re-derives project_id from `issue`, so a drifted value has
-- no way in.
ALTER TABLE issue_release_stats ADD COLUMN project_id bigint;

-- Structurally cheaper than V10's backfill, which is a claim about what this
-- reads rather than a measured time: the rollup and `issue`, both low-volume,
-- where V10 had to read every weekly partition of `event` to derive membership
-- that existed nowhere else. It is still a full rewrite of the rollup under an
-- ACCESS EXCLUSIVE lock taken by the ALTERs around it, and no upgrade of a
-- populated install has been timed — an operator with a large rollup should
-- treat the duration as unknown rather than as small.
UPDATE issue_release_stats stats SET project_id = i.project_id FROM issue i WHERE i.id = stats.issue_id;

ALTER TABLE issue_release_stats ALTER COLUMN project_id SET NOT NULL;

-- The Releases page's whole access path: one range per (Project, Release) on
-- the page, counted in one pass. The primary key (issue_id, release) cannot
-- serve it — it leads with the column the page does not know.
--
-- Its write cost is per new (Issue, Release) pair, not per Event, which is the
-- reason it is affordable on a table an ingest worker upserts into for every
-- Event it stores. EventStore's upsert inserts a row the first time an Issue is
-- seen on a Release and updates counters on every Event after that; the update
-- leaves both indexed columns alone, so it adds no index entry. A deploy that
-- surfaces 500 Issues writes 500 entries, once — against the millions of Events
-- those Issues go on to collect.
CREATE INDEX idx_issue_release_stats_project_release ON issue_release_stats (project_id, release);
