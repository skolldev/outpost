-- Phase 1 domain: projects, keys, environments, releases, users, tokens, issues, events.
-- event is range-partitioned by week on "timestamp"; partitions are created by
-- PartitionManager (app-side) at startup and on demand during ingest.

CREATE TABLE project (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    slug       text NOT NULL UNIQUE,
    name       text NOT NULL,
    platform   text,                          -- e.g. javascript-angular | java-spring-boot
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE project_key (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id bigint NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    public_key text NOT NULL UNIQUE,          -- 32-hex DSN public key
    is_active  boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_project_key_project ON project_key (project_id);

CREATE TABLE environment (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id bigint NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    name       text NOT NULL,
    UNIQUE (project_id, name)
);

CREATE TABLE release (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id bigint NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    version    text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (project_id, version)
);

CREATE TABLE app_user (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         text NOT NULL,
    password_hash text NOT NULL,
    role          text NOT NULL CHECK (role IN ('admin', 'member')),
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_app_user_email ON app_user (lower(email));

CREATE TABLE api_token (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       text NOT NULL,
    token_hash text NOT NULL UNIQUE,
    scopes     text[] NOT NULL DEFAULT '{}',
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE issue (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id  bigint NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    fingerprint text NOT NULL,
    title       text NOT NULL,
    culprit     text,
    level       text NOT NULL DEFAULT 'error',
    status      text NOT NULL DEFAULT 'unresolved' CHECK (status IN ('unresolved', 'resolved')),
    first_seen  timestamptz NOT NULL,
    last_seen   timestamptz NOT NULL,
    event_count bigint NOT NULL DEFAULT 0,
    UNIQUE (project_id, fingerprint)
);

CREATE INDEX idx_issue_project_last_seen ON issue (project_id, last_seen DESC);

CREATE TABLE issue_env_stats (
    issue_id    bigint NOT NULL REFERENCES issue (id) ON DELETE CASCADE,
    environment text NOT NULL,
    event_count bigint NOT NULL DEFAULT 0,
    last_seen   timestamptz NOT NULL,
    PRIMARY KEY (issue_id, environment)
);

CREATE TABLE event (
    id                   uuid NOT NULL,
    project_id           bigint NOT NULL,
    issue_id             bigint NOT NULL,
    environment          text NOT NULL,
    release              text,
    "timestamp"          timestamptz NOT NULL,
    trace_id             text,
    level                text,
    message              text,
    exception_type       text,
    user_ident           text,               -- id/email/ip from the user context, for "users affected"
    data                 jsonb NOT NULL,     -- full processed payload
    raw                  bytea,              -- gzipped original payload (kept while symbolication may rerun)
    symbolication_status text,
    PRIMARY KEY (id, "timestamp")
) PARTITION BY RANGE ("timestamp");

CREATE INDEX idx_event_project_ts ON event (project_id, "timestamp");
CREATE INDEX idx_event_issue_ts ON event (issue_id, "timestamp");
CREATE INDEX idx_event_trace ON event (trace_id);
