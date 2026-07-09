-- Phase 0 baseline. Domain tables (project, issue, event, ...) arrive with Phase 1.

CREATE TABLE setting (
    key        text PRIMARY KEY,
    value      text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
