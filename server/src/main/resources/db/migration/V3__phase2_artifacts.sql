-- Phase 2: source-map artifact storage (§7). Bundles arrive via the
-- sentry-cli chunk-upload/assemble API; upload_chunk is a 24 h staging area.

CREATE TABLE artifact_bundle (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    checksum   text NOT NULL UNIQUE,          -- sha1 of the assembled bundle zip
    raw        bytea NOT NULL,                -- bundle zip, kept for re-processing
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE artifact_bundle_release (
    bundle_id  bigint NOT NULL REFERENCES artifact_bundle (id) ON DELETE CASCADE,
    project_id bigint NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    release    text NOT NULL,
    PRIMARY KEY (bundle_id, project_id, release)
);

CREATE INDEX idx_artifact_bundle_release_project ON artifact_bundle_release (project_id, release);

CREATE TABLE artifact (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bundle_id     bigint NOT NULL REFERENCES artifact_bundle (id) ON DELETE CASCADE,
    debug_id      text NOT NULL,               -- normalized lowercase UUID
    artifact_type text NOT NULL CHECK (artifact_type IN ('source_map', 'minified_source')),
    file_path     text NOT NULL,               -- path inside the bundle (~/main-X.js)
    headers       jsonb NOT NULL DEFAULT '{}'::jsonb,
    content       bytea NOT NULL,              -- gzipped file content
    UNIQUE (debug_id, artifact_type)
);

CREATE INDEX idx_artifact_debug_id ON artifact (debug_id);

CREATE TABLE upload_chunk (
    sha1       text PRIMARY KEY,
    content    bytea NOT NULL,                 -- uncompressed chunk bytes (verified against sha1)
    created_at timestamptz NOT NULL DEFAULT now()   -- staging TTL 24 h (cleanup in P5)
);
