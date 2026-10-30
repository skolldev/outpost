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
-- membership: EventStore inserts (issue_id, user) ON CONFLICT DO NOTHING and
-- bumps `issue.users_affected` only when that insert actually inserted. Retention
-- rebuilds both from the surviving events. Events with a null `user_ident` never
-- enter membership, matching what `count(DISTINCT …)` did.
--
-- Plain, not partitioned: membership has no timestamp to range over, and its rows
-- leave with their Issue via the cascade or with retention's rebuild.
--
-- Storage, measured on TelemetrySeeder.Scale.GUARD (40 003 events, 49 MB of `event`
-- across its partitions): 25 359 membership rows in 2.7 MB with their primary key —
-- about 5% of `event`. `user_ident` falls back to the client IP for anonymous
-- traffic, so the worst case is one row per event; forcing that on the same data
-- gives 40 003 rows in 4.4 MB (~109 bytes a row), about 9% of `event`. The hashed key
-- makes that per-row figure a ceiling however long the identifiers are.
--
-- Backfill is a full pass over `event` (every partition), aggregated once. Flyway
-- runs before the application starts serving, so this blocks startup — not ingest
-- traffic, which is not being accepted yet — for the length of that pass: one read of
-- what the old query read on every issue-list page load. An install holding years of
-- unretained events should expect the upgrade's startup to take that long.
--
-- Ingest cost: one extra statement per stored batch, which inserts the batch's
-- memberships and bumps each touched Issue once. See the PR for #131 for the
-- IngestBenchmark error ladder before and after.

ALTER TABLE issue ADD COLUMN users_affected bigint NOT NULL DEFAULT 0;

-- Keyed on md5(user_ident), not the text: `user_ident` is the SDK's user.id / email /
-- username / ip_address with no length cap, and a value past the btree entry limit
-- (~2.7 KB) would fail the insert, dropping the event on ingest and this backfill at
-- startup. The hash is a fixed 16 bytes; distinct users only merge on an md5
-- collision. Every writer (EventStore, DataRetentionService, this backfill) hashes
-- the same way.
CREATE TABLE issue_user_stats (
    issue_id        bigint NOT NULL REFERENCES issue (id) ON DELETE CASCADE,
    user_ident_hash uuid NOT NULL,
    PRIMARY KEY (issue_id, user_ident_hash)
);

-- `event` carries no FK to `issue` (it is partitioned), so the join keeps an orphaned
-- event from failing the migration on this table's FK.
INSERT INTO issue_user_stats (issue_id, user_ident_hash)
SELECT DISTINCT e.issue_id, md5(e.user_ident)::uuid
FROM event e
JOIN issue i ON i.id = e.issue_id
WHERE e.user_ident IS NOT NULL;

UPDATE issue i SET users_affected = members.n
FROM (SELECT issue_id, count(*) AS n FROM issue_user_stats GROUP BY issue_id) members
WHERE i.id = members.issue_id;
