-- Phase 3: logs. log_record is range-partitioned by week on "timestamp" like
-- event; partitions are created by PartitionManager. Body substring search
-- (ILIKE) is served by a pg_trgm GIN index — pg_trgm is a trusted extension,
-- so the (non-superuser) database owner may create it.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE log_record (
    id              uuid NOT NULL,
    project_id      bigint NOT NULL,
    environment     text NOT NULL,
    "timestamp"     timestamptz NOT NULL,
    trace_id        text,
    span_id         text,
    level           text NOT NULL,
    severity_number int,
    body            text NOT NULL,
    attributes      jsonb NOT NULL DEFAULT '{}'::jsonb,
    release         text,
    PRIMARY KEY (id, "timestamp")
) PARTITION BY RANGE ("timestamp");

CREATE INDEX idx_log_project_env_ts ON log_record (project_id, environment, "timestamp");
CREATE INDEX idx_log_trace ON log_record (trace_id);
CREATE INDEX idx_log_attributes ON log_record USING gin (attributes);
CREATE INDEX idx_log_body_trgm ON log_record USING gin (body gin_trgm_ops);
