-- Outpost demo setup — the MINIMAL data the demo apps need to send real telemetry.
--
-- Creates two projects and their DSN keys, nothing else. Environments, releases,
-- issues, and the weekly telemetry partitions are all created automatically when
-- real SDK envelopes arrive, so unlike scripts/seed.sql this fabricates no data:
-- everything you see in the UI afterwards came from the demo apps.
--
-- Idempotent: re-running wipes and recreates only the two demo projects and any
-- telemetry they accumulated. Your admin user, tokens, and settings are untouched.
--
-- Run against the compose database:
--   docker compose exec -T db psql -U outpost -d outpost < demo/scripts/demo-setup.sql
-- or against a local bootRun DB (published on :5432):
--   psql "postgresql://outpost:outpost@localhost:5432/outpost" -f demo/scripts/demo-setup.sql

BEGIN;

-- Telemetry tables aren't FK-cascaded from project — delete explicitly first.
DELETE FROM event      WHERE project_id IN (SELECT id FROM project WHERE slug IN ('shop-frontend','shop-backend'));
DELETE FROM log_record WHERE project_id IN (SELECT id FROM project WHERE slug IN ('shop-frontend','shop-backend'));
DELETE FROM span       WHERE project_id IN (SELECT id FROM project WHERE slug IN ('shop-frontend','shop-backend'));
DELETE FROM txn        WHERE project_id IN (SELECT id FROM project WHERE slug IN ('shop-frontend','shop-backend'));
DELETE FROM project    WHERE slug IN ('shop-frontend','shop-backend');  -- cascades keys/env/release/issue/env_stats

DO $$
DECLARE
    fe bigint;
    be bigint;
BEGIN
    INSERT INTO project (slug, name, platform)
    VALUES ('shop-frontend', 'Shop Frontend', 'javascript-angular') RETURNING id INTO fe;
    INSERT INTO project (slug, name, platform)
    VALUES ('shop-backend', 'Shop Backend', 'java-spring-boot') RETURNING id INTO be;

    -- Fixed 32-hex public keys → stable DSNs for the demo apps.
    INSERT INTO project_key (project_id, public_key, is_active) VALUES
        (fe, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', true),
        (be, 'cccccccccccccccccccccccccccccccc', true);

    RAISE NOTICE 'shop-frontend: project id %, DSN http://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa@localhost:8080/%', fe, fe;
    RAISE NOTICE 'shop-backend:  project id %, DSN http://cccccccccccccccccccccccccccccccc@localhost:8080/%', be, be;
END $$;

COMMIT;
