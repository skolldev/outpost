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
INSERT INTO issue_release_stats (issue_id, release, event_count, last_seen)
SELECT issue_id, release, count(*), max("timestamp")
FROM event
WHERE release IS NOT NULL
GROUP BY issue_id, release;
