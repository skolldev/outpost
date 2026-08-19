-- Adds `release` to the Performance leaderboard's covering index (#161), which gave the
-- view a Release filter so a change in duration can be attributed to a version.
--
-- V15 covers every column the leaderboard reads — until this one. `release` is a filter
-- applied before aggregation, not part of the Transaction Group key, and it was the only
-- predicate on the page that idx_txn_performance could not answer. Postgres will not run
-- an index-only scan when a qual names a column the index does not carry, so a single
-- Release filter took the whole query off the index and onto the heap:
--
--   shape                            list    count
--   V15, 30d                          613      491
--   V15, 30d one release            4 614    3 067   sequential scan of every in-window partition
--   this index, 30d                   713      571
--   this index, 30d one release       713      571   index-only, filtered inside the scan
--
-- Measured 2026-08-19 against TelemetrySeeder.Scale.GUARD — 8 004 transactions over nine
-- weekly partitions, where a full scan of txn costs ~2 700 blocks. The absolute numbers
-- are small, and the failure they describe is not: a Release filter fell off the index
-- entirely, which is the same failure V15's own "none" column measures at 500 004
-- transactions — 238 242 blocks against 21 113. A predicate the index cannot answer reads
-- the heap for every Transaction in the window, and a txn heap row carries the full
-- transaction payload at ~1.4 KB against this index's ~110 bytes, so the ratio widens
-- with the data rather than washing out.
--
-- The wider index costs the unfiltered leaderboard ~16% more blocks (613 -> 713): every
-- shape now walks entries carrying a release string it may not read. That is the trade,
-- and it is worth taking at 6.5x on the shape it fixes.
--
-- `release` goes in INCLUDE rather than in the key, because nothing orders or ranges by
-- it and the columns ahead of it in the key (start_ts especially) mean it could never be
-- an index *condition* anyway. In INCLUDE it is a filter evaluated inside the index-only
-- scan, which is all the query needs and keeps the key — the Transaction Group key,
-- whose order is what removes the sort (see V15) — untouched.
--
-- Cost: ~14 bytes per row on a ~96-byte entry, so the index grows by roughly a seventh.
-- txn is append-only, so this is paid once per insert and never re-checked.
--
-- Locking, as V11, V14 and V15: a plain CREATE INDEX holds a ShareLock on txn for the
-- length of the build, blocking transaction ingest until it finishes. CONCURRENTLY is
-- rejected on a partitioned table outright (SQLSTATE 0A000).
--
-- It builds under a temporary name and renames afterwards rather than dropping first, for
-- two reasons: the leaderboard keeps a usable index throughout, and an install large
-- enough to care about the lock can build the replacement by hand before upgrading, after
-- which every statement below is instant — the CREATE finds its index present, and the
-- DROP and RENAME are catalogue-only:
--
--   CREATE INDEX idx_txn_performance_v16 ON ONLY txn
--       (project_id, name, op, environment, start_ts) INCLUDE (duration_ms, release);
--   -- then, per partition:
--   CREATE INDEX CONCURRENTLY idx_txn_performance_v16_p20260817 ON txn_p20260817
--       (project_id, name, op, environment, start_ts) INCLUDE (duration_ms, release);
--   ALTER INDEX idx_txn_performance_v16 ATTACH PARTITION idx_txn_performance_v16_p20260817;

CREATE INDEX IF NOT EXISTS idx_txn_performance_v16
    ON txn (project_id, name, op, environment, start_ts) INCLUDE (duration_ms, release);

DROP INDEX IF EXISTS idx_txn_performance;

ALTER INDEX idx_txn_performance_v16 RENAME TO idx_txn_performance;
