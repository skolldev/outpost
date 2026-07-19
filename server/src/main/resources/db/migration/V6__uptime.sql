-- Uptime monitoring: admin-configured HTTP monitors, raw check results
-- (90-day retention, swept by UptimeScheduler), and incidents opened after
-- 3 consecutive failures / closed on the first success.
--
-- uptime_check stays a plain table: worst case (30 s interval) is ~2,880
-- rows/day/monitor ≈ 260k rows per monitor per 90 days — every query is a
-- (monitor_id, checked_at) index range scan, no partitioning needed.

CREATE TABLE uptime_monitor (
    id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id           bigint NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    environment          text NOT NULL,
    url                  text NOT NULL,
    interval_seconds     int NOT NULL CHECK (interval_seconds IN (30, 60, 300, 900, 3600)),
    timeout_seconds      int NOT NULL DEFAULT 10 CHECK (timeout_seconds BETWEEN 1 AND 30),
    -- Counter (not derived from uptime_check rows): atomic via UPDATE ...
    -- RETURNING, immune to retention deletes. Reset on success and on edit.
    consecutive_failures int NOT NULL DEFAULT 0,
    -- Re-armed to now() + interval after every completed check; makes the
    -- scheduler stateless across restarts and lets edits take effect by
    -- setting it to now().
    next_check_at        timestamptz NOT NULL DEFAULT now(),
    created_at           timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_uptime_monitor_due ON uptime_monitor (next_check_at);

CREATE TABLE uptime_check (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    monitor_id  bigint NOT NULL REFERENCES uptime_monitor (id) ON DELETE CASCADE,
    checked_at  timestamptz NOT NULL DEFAULT now(),
    success     boolean NOT NULL,
    status_code int,                 -- NULL on timeout / connection error
    latency_ms  int NOT NULL,
    error       text                 -- NULL on success
);

CREATE INDEX idx_uptime_check_monitor_ts ON uptime_check (monitor_id, checked_at DESC);

CREATE TABLE uptime_incident (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    monitor_id bigint NOT NULL REFERENCES uptime_monitor (id) ON DELETE CASCADE,
    opened_at  timestamptz NOT NULL DEFAULT now(),
    closed_at  timestamptz,
    last_error text
);

-- At most one open incident per monitor; doubles as the ON CONFLICT target
-- for idempotent opens.
CREATE UNIQUE INDEX uq_uptime_incident_open ON uptime_incident (monitor_id) WHERE closed_at IS NULL;
CREATE INDEX idx_uptime_incident_monitor ON uptime_incident (monitor_id, opened_at DESC);
