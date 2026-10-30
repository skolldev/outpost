-- Maintains the issue list's "Users" figure as a rollup instead of scanning `event` (#131).
--
-- The list used to compute it on every page load as
-- `count(DISTINCT user_ident) FROM event WHERE issue_id IN (…page…)`, with no time
-- bound, so it read every weekly partition ever created: 20 045 blocks over 10 of 10
-- partitions on the guard dataset, against 9 428 over 5 for the time-bounded
-- sparkline beside it. Every other number on an Issue row is maintained on ingest and
-- reconciled by retention (`issue.event_count`, `issue_env_stats`,
-- `issue_release_stats`); this brings users-affected into the same pattern.
--
-- Distinct users are not additive — one user on several days counts once — so a
-- counter alone cannot be maintained incrementally. `issue_user_stats` holds the
-- membership: EventStore inserts (issue_id, user_ident) ON CONFLICT DO NOTHING and
-- bumps `issue.users_affected` only when that insert actually inserted. Retention
-- rebuilds both from the surviving events. Events with a null `user_ident` never
-- enter membership, matching what `count(DISTINCT …)` did.
--
-- Plain, not partitioned: membership has no timestamp to range over, and its rows
-- leave with their Issue via the cascade or with retention's rebuild.
--
-- Storage: two narrow columns per distinct (Issue, user). `user_ident` falls back to
-- the client IP for anonymous traffic, so the worst case approaches one row per
-- event — still ~60 bytes a row (tuple header, bigint, short text, plus its primary
-- key entry) against `event`'s jsonb payload and gzipped raw bytea.
--
-- Backfill is a full pass over `event` (every partition), aggregated once. Flyway
-- runs before the application starts serving, so this blocks startup — not ingest
-- traffic, which is not being accepted yet — for the length of that pass. On the
-- guard dataset it is a sequential read of what the old per-page-load query already
-- read; an install with years of unretained events should expect a startup measured
-- in that scan.

ALTER TABLE issue ADD COLUMN users_affected bigint NOT NULL DEFAULT 0;

CREATE TABLE issue_user_stats (
    issue_id   bigint NOT NULL REFERENCES issue (id) ON DELETE CASCADE,
    user_ident text NOT NULL,
    PRIMARY KEY (issue_id, user_ident)
);

-- `event` carries no FK to `issue` (it is partitioned), so the join keeps an orphaned
-- event from failing the migration on this table's FK.
INSERT INTO issue_user_stats (issue_id, user_ident)
SELECT DISTINCT e.issue_id, e.user_ident
FROM event e
JOIN issue i ON i.id = e.issue_id
WHERE e.user_ident IS NOT NULL;

UPDATE issue i SET users_affected = members.n
FROM (SELECT issue_id, count(*) AS n FROM issue_user_stats GROUP BY issue_id) members
WHERE i.id = members.issue_id;
