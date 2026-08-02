-- Release filtering asks whether an Issue has ever had an Event for one Release.
-- Answer that from a low-volume rollup, as environment filtering already does,
-- instead of probing every weekly event partition once per candidate Issue.

CREATE TABLE issue_release_stats (
    issue_id    bigint NOT NULL REFERENCES issue (id) ON DELETE CASCADE,
    release     text NOT NULL,
    event_count bigint NOT NULL DEFAULT 0,
    last_seen   timestamptz NOT NULL,
    PRIMARY KEY (issue_id, release)
);

-- Preserve release-filter results for Events ingested before this migration.
--
-- Cost, deliberately, in the register V9 set. This reads every weekly partition
-- of `event` end to end — the largest table in the system, and the only migration
-- so far to scan it. At the ~15 000 blocks a 40 000-event dataset costs (see
-- docs/performance/measuring-retrieval.md), an install holding tens of millions of
-- events is a multi-minute aggregate, and Flyway runs it before the app serves.
--
-- It is not batched and must not be: the release filter reads this table as the
-- whole truth, so a partially-filled rollup does not answer slowly, it answers
-- *wrong* — Issues silently missing from a filtered list, with nothing to
-- indicate it. A long startup is the cheaper failure. An operator upgrading an
-- install that large should expect it, and can run this INSERT by hand against a
-- pre-created table before the upgrade window if the downtime is not acceptable.
--
-- ACCESS SHARE on `event` only, so nothing here blocks concurrent writes; the
-- cost is wall clock before first serve, not lock contention.
--
-- Blank Releases are skipped as well as null ones, matching the guard in
-- EventStore: a blank one can only have come from an SDK sending "release":"",
-- and IssueController rejects a blank filter, so no query could ever match it.
INSERT INTO issue_release_stats (issue_id, release, event_count, last_seen)
SELECT issue_id, release, count(*), max("timestamp")
FROM event
WHERE release IS NOT NULL AND btrim(release) <> ''
GROUP BY issue_id, release;
