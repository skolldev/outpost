-- Outpost demo setup — the MINIMAL data the demo apps need to send real telemetry.
--
-- Creates two projects and their DSN keys, nothing else. Environments, releases,
-- issues, and the weekly telemetry partitions are all created automatically when
-- real SDK envelopes arrive, so unlike scripts/seed.sql this fabricates no data:
-- everything you see in the UI afterwards came from the demo apps.
--
-- Idempotent, but DESTRUCTIVE: re-running wipes and recreates the two demo
-- projects and any telemetry they accumulated. Your admin user, tokens, and
-- settings are untouched. The compose `demo-seed` service therefore only runs
-- this when the demo projects are absent — run it by hand to reset them.
--
-- Requires the schema to exist: Flyway migrates on Outpost startup, so this
-- cannot run as a Postgres initdb script.
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
    -- Evaluated AFTER the deletes above: true on a fresh install, and also on a
    -- re-run of a DB that only ever held the demo projects.
    pin_ids boolean := NOT EXISTS (SELECT 1 FROM project);
BEGIN
    IF pin_ids THEN
        -- Nothing else owns ids 1/2, so pin them: compose's DSN paths default to
        -- DEMO_FRONTEND_PROJECT_ID=1 / DEMO_BACKEND_PROJECT_ID=2, which makes the
        -- demo work without print-dsns.sh. Identity columns need OVERRIDING and a
        -- RESTART so real projects created later don't collide.
        INSERT INTO project (id, slug, name, platform) OVERRIDING SYSTEM VALUE
        VALUES (1, 'shop-frontend', 'Shop Frontend', 'javascript-angular') RETURNING id INTO fe;
        INSERT INTO project (id, slug, name, platform) OVERRIDING SYSTEM VALUE
        VALUES (2, 'shop-backend', 'Shop Backend', 'java-spring-boot') RETURNING id INTO be;
        ALTER TABLE project ALTER COLUMN id RESTART WITH 3;
    ELSE
        -- Other projects exist; take whatever ids Postgres assigns and let
        -- print-dsns.sh --write-env reconcile the compose DSNs.
        INSERT INTO project (slug, name, platform)
        VALUES ('shop-frontend', 'Shop Frontend', 'javascript-angular') RETURNING id INTO fe;
        INSERT INTO project (slug, name, platform)
        VALUES ('shop-backend', 'Shop Backend', 'java-spring-boot') RETURNING id INTO be;
    END IF;

    -- Fixed 32-hex public keys → stable DSNs for the demo apps.
    INSERT INTO project_key (project_id, public_key, is_active) VALUES
        (fe, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', true),
        (be, 'cccccccccccccccccccccccccccccccc', true);

    RAISE NOTICE 'shop-frontend: project id %, DSN http://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa@localhost:8080/%', fe, fe;
    RAISE NOTICE 'shop-backend:  project id %, DSN http://cccccccccccccccccccccccccccccccc@localhost:8080/%', be, be;
END $$;

COMMIT;
