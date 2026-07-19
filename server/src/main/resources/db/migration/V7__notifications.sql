-- Notification Channels (issue #42, parent #41): Admin-configured webhook
-- destinations. This slice is configuration only — nothing is delivered yet.
--
-- A future notification-history table will reference notification_channel(id)
-- ON DELETE CASCADE, so deleting a channel discards its history; the identity
-- PK below is that cascade target. Low-volume table (a handful of rows), so no
-- extra indexes.
--
-- Filters are stored as arrays evaluated at publish time. An empty
-- project_filter matches all Projects; an empty environment_filter matches all
-- Environments. project_filter holds Project ids; an entry for a since-deleted
-- Project is inert (it simply never matches), so no FK is needed here — a filter
-- is not an ownership edge. triggers is the subset of the three occurrence types
-- a channel fires on; the 'test' trigger is never stored (it is fired only by
-- the future test-send action, deliverable regardless of this set).
--
-- URLs are stored and returned unmasked with no egress restriction (ADR 0006):
-- the webhook URL is itself the bearer credential, and management is Admin-only.

CREATE TABLE notification_channel (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name               text NOT NULL,
    type               text NOT NULL CHECK (type IN ('teams', 'generic_json')),
    url                text NOT NULL,
    enabled            boolean NOT NULL DEFAULT true,
    triggers           text[] NOT NULL DEFAULT '{}'
        CHECK (triggers <@ ARRAY['new_issue', 'incident_started', 'incident_resolved']),
    project_filter     bigint[] NOT NULL DEFAULT '{}',
    environment_filter text[] NOT NULL DEFAULT '{}',
    created_at         timestamptz NOT NULL DEFAULT now()
);
