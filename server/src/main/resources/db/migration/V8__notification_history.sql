-- Notification history (issue #43, parent #41): one row per Notification —
-- one message to one channel about one trigger occurrence. Records the outcome
-- of best-effort delivery (ADR 0005): a row is written 'pending' before the
-- send and moved to 'sent' or 'failed' by the async delivery worker. Rows left
-- 'pending' by a shutdown mid-send simply go stale; there is no redelivery.
--
-- 'suppressed' (rate cap, #47) is reserved in the CHECK now so that later
-- slice adds no migration. channel_id is FK ON DELETE CASCADE: deleting a
-- channel discards its history, the cascade the #42 channel table anticipated.
--
-- Low-volume relative to telemetry and pruned to ~30 days by the retention
-- scheduler (#47), so it stays a plain table. The (channel_id, created_at)
-- index serves both per-channel history listing (#44) and the retention sweep.

CREATE TABLE notification_history (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    channel_id    bigint NOT NULL REFERENCES notification_channel (id) ON DELETE CASCADE,
    trigger_type  text NOT NULL
        CHECK (trigger_type IN ('new_issue', 'incident_started', 'incident_resolved', 'test')),
    status        text NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'sent', 'failed', 'suppressed')),
    summary       text NOT NULL,
    error_detail  text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX notification_history_channel_created_idx
    ON notification_history (channel_id, created_at DESC);
