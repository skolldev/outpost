-- Phase 4: tracing. A Sentry `transaction` envelope item is the root span of a
-- trace: its identity lives in contexts.trace, its children in spans[]. We store
-- one txn row per transaction (root span + transaction-level metadata) and one
-- span row per spans[] entry — the root is NOT duplicated as a span row. Both
-- tables are range-partitioned by week on start_ts, created by PartitionManager.
--
-- A trace is implicit: just a trace_id shared across txn/span/event/log_record.
-- There is no trace table — trace search queries txn roots directly and the
-- trace detail view fans out by trace_id across all four tables at query time
-- (no ingest-time linking, §6.4).

CREATE TABLE txn (
    id             uuid NOT NULL,
    project_id     bigint NOT NULL,
    environment    text NOT NULL,
    release        text,
    trace_id       text NOT NULL,
    span_id        text NOT NULL,             -- the transaction's own (root) span id
    parent_span_id text,                      -- set when this txn continues an upstream trace
    name           text NOT NULL,             -- e.g. "GET /api/orders/{id}"
    op             text,                       -- e.g. "http.server", "pageload", "navigation"
    start_ts       timestamptz NOT NULL,
    end_ts         timestamptz NOT NULL,
    duration_ms    double precision NOT NULL,
    status         text,                       -- e.g. "ok", "internal_error"
    data           jsonb NOT NULL DEFAULT '{}'::jsonb,   -- full transaction payload (contexts, tags, measurements)
    PRIMARY KEY (id, start_ts)
) PARTITION BY RANGE (start_ts);

CREATE INDEX idx_txn_trace ON txn (trace_id);
CREATE INDEX idx_txn_project_start ON txn (project_id, start_ts);

CREATE TABLE span (
    id             uuid NOT NULL,
    txn_id         uuid NOT NULL,             -- owning transaction (no FK: partitioned parent)
    project_id     bigint NOT NULL,
    trace_id       text NOT NULL,
    span_id        text NOT NULL,
    parent_span_id text,
    op             text,
    description    text,
    start_ts       timestamptz NOT NULL,
    end_ts         timestamptz NOT NULL,
    duration_ms    double precision NOT NULL,
    status         text,
    data           jsonb NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (id, start_ts)
) PARTITION BY RANGE (start_ts);

CREATE INDEX idx_span_trace ON span (trace_id);
CREATE INDEX idx_span_txn ON span (txn_id);
