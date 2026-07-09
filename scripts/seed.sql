-- Outpost dev seed — logical test data for clicking around the UI.
--
-- Seeds two projects (an Angular frontend + a Spring Boot backend), their DSN
-- keys, environments (dev/qa/prod), releases, a spread of grouped issues with
-- realistic events, and a searchable log stream — including trace-correlated
-- logs so "Logs around this event" works in both modes (by trace_id and by the
-- ±60 s same-project window).
--
-- Covers Phases 1–4: errors, source-map/symbolication display, logs, and
-- distributed traces (cross-service transaction/span waterfalls with pinned
-- errors and trace-correlated logs).
--
-- Idempotent: re-running wipes and recreates only the two seed projects and
-- their telemetry. Everything else (your admin user, settings) is left alone.
--
-- Run against the compose database:
--   docker compose exec -T db psql -U outpost -d outpost < scripts/seed.sql
-- or against a local bootRun DB (published on :5432):
--   psql "postgresql://outpost:outpost@localhost:5432/outpost" -f scripts/seed.sql

\set ON_ERROR_STOP on

-- Match PartitionManager's UTC Monday-week boundaries so partition names/bounds
-- line up exactly with anything the app already created (CREATE ... IF NOT EXISTS).
SET TIME ZONE 'UTC';

BEGIN;

-- ---------------------------------------------------------------------------
-- Weekly partitions for the range-partitioned tables (event, log_record, txn, span).
-- Covers ~10 weeks back (a few issues have events older than the default 14 d
-- window, to exercise the time-range picker) through next week.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    tbl  text;
    wk   date;
    i    int;
BEGIN
    FOREACH tbl IN ARRAY ARRAY['event', 'log_record', 'txn', 'span'] LOOP
        FOR i IN -10..1 LOOP
            wk := (date_trunc('week', now() + make_interval(weeks => i)))::date;  -- Monday, UTC
            EXECUTE format(
                'CREATE TABLE IF NOT EXISTS %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)',
                tbl || '_p' || to_char(wk, 'YYYYMMDD'), tbl, wk, wk + 7);
        END LOOP;
    END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- Helper: seed one issue plus a spread of events, then derive counters and
-- per-environment stats from the rows that actually landed (always consistent).
-- Created in pg_temp so it vanishes with the session.
-- ---------------------------------------------------------------------------
CREATE FUNCTION pg_temp.seed_issue(
    p_project     bigint,
    p_fingerprint text,
    p_title       text,
    p_culprit     text,
    p_extype      text,
    p_message     text,
    p_level       text,
    p_status      text,
    p_release     text,
    p_data        jsonb,
    p_symb        text,      -- symbolication_status: NULL, 'partial', 'missing_sourcemap'
    p_count       int,
    p_days        numeric,   -- events spread over the last p_days (biased recent)
    p_envs        text[],    -- environment weighting, e.g. {prod,prod,prod,qa,dev}
    p_traced      boolean,   -- attach a trace_id (enables trace-based log correlation)
    p_log_project bigint     -- where correlated logs go (NULL to skip)
) RETURNS void AS $$
DECLARE
    v_issue  bigint;
    v_ts     timestamptz;
    v_env    text;
    v_user   text;
    v_trace  text;
    v_span   text;
    v_op     text;
    v_data   jsonb;
    i        int;
BEGIN
    INSERT INTO issue (project_id, fingerprint, title, culprit, level, status, first_seen, last_seen, event_count)
    VALUES (p_project, p_fingerprint, p_title, p_culprit, p_level, p_status, now(), now(), 0)
    RETURNING id INTO v_issue;

    v_op := CASE WHEN p_log_project IS NOT NULL AND p_log_project <> p_project
                 THEN 'http.client' ELSE 'http.server' END;

    FOR i IN 1..p_count LOOP
        -- power() biases the distribution toward "now" so last_seen looks recent.
        v_ts   := now() - make_interval(secs => (p_days * 86400 * power(random(), 1.6))::int);
        v_env  := p_envs[1 + floor(random() * array_length(p_envs, 1))::int];
        v_user := 'u-' || (1000 + floor(random() * 45)::int);
        v_trace := CASE WHEN p_traced THEN md5(random()::text || i::text) END;
        v_span  := CASE WHEN p_traced THEN substr(md5(random()::text), 1, 16) END;

        v_data := p_data;
        v_data := jsonb_set(v_data, '{environment}', to_jsonb(v_env));
        v_data := jsonb_set(v_data, '{release}', to_jsonb(p_release));
        v_data := jsonb_set(v_data, '{level}', to_jsonb(p_level));
        v_data := jsonb_set(v_data, '{user}', jsonb_build_object(
            'id', v_user, 'email', v_user || '@example.com',
            'ip_address', '203.0.113.' || (1 + floor(random() * 253))::int));
        IF p_traced THEN
            v_data := jsonb_set(v_data, '{contexts,trace}', jsonb_build_object(
                'trace_id', v_trace, 'span_id', v_span, 'op', v_op));
        END IF;

        INSERT INTO event (id, project_id, issue_id, environment, release, "timestamp", trace_id,
                           level, message, exception_type, user_ident, data, raw, symbolication_status)
        VALUES (gen_random_uuid(), p_project, v_issue, v_env, p_release, v_ts, v_trace,
                p_level, p_message, p_extype, v_user, v_data, NULL, p_symb);

        -- Trace-correlated log trail (shows under "Logs around this event").
        IF p_traced AND p_log_project IS NOT NULL THEN
            INSERT INTO log_record (id, project_id, environment, "timestamp", trace_id, span_id,
                                    level, severity_number, body, attributes, release)
            VALUES
              (gen_random_uuid(), p_log_project, v_env, v_ts - interval '40 ms', v_trace,
               substr(md5(random()::text), 1, 16), 'info', 9,
               'Handling request for trace ' || substr(v_trace, 1, 8),
               jsonb_build_object('sentry.environment', v_env, 'sentry.release', p_release,
                                  'http.method', 'POST'), p_release),
              (gen_random_uuid(), p_log_project, v_env, v_ts - interval '15 ms', v_trace,
               substr(md5(random()::text), 1, 16), 'warn', 13,
               'Downstream latency high (312ms)',
               jsonb_build_object('sentry.environment', v_env, 'sentry.release', p_release,
                                  'latency_ms', 312), p_release),
              (gen_random_uuid(), p_log_project, v_env, v_ts, v_trace,
               substr(md5(random()::text), 1, 16), 'error', 17,
               p_extype || ': ' || p_message,
               jsonb_build_object('sentry.environment', v_env, 'sentry.release', p_release,
                                  'exception.type', p_extype), p_release);
        END IF;
    END LOOP;

    INSERT INTO issue_env_stats (issue_id, environment, event_count, last_seen)
    SELECT v_issue, environment, count(*), max("timestamp")
    FROM event WHERE issue_id = v_issue GROUP BY environment;

    UPDATE issue SET
        event_count = (SELECT count(*)     FROM event WHERE issue_id = v_issue),
        first_seen  = (SELECT min("timestamp") FROM event WHERE issue_id = v_issue),
        last_seen   = (SELECT max("timestamp") FROM event WHERE issue_id = v_issue)
    WHERE id = v_issue;
END $$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- Helper: seed a standalone log stream for a project (level/body/env variety).
-- ---------------------------------------------------------------------------
CREATE FUNCTION pg_temp.seed_logs(
    p_project bigint,
    p_count   int,
    p_days    numeric,
    p_release text,
    p_bodies  text[],
    p_envs    text[]
) RETURNS void AS $$
DECLARE
    v_levels text[] := ARRAY['info','info','info','info','warn','warn','debug','trace','error'];
    v_level  text;
    v_ts     timestamptz;
    v_env    text;
    v_trace  text;
    i        int;
BEGIN
    FOR i IN 1..p_count LOOP
        v_level := v_levels[1 + floor(random() * array_length(v_levels, 1))::int];
        v_ts    := now() - make_interval(secs => (p_days * 86400 * power(random(), 1.4))::int);
        v_env   := p_envs[1 + floor(random() * array_length(p_envs, 1))::int];
        -- ~30% of logs carry a trace_id (the rest are ambient service logs).
        v_trace := CASE WHEN random() < 0.30 THEN md5(random()::text || i::text) END;

        INSERT INTO log_record (id, project_id, environment, "timestamp", trace_id, span_id,
                                level, severity_number, body, attributes, release)
        VALUES (
            gen_random_uuid(), p_project, v_env, v_ts, v_trace,
            CASE WHEN v_trace IS NOT NULL THEN substr(md5(random()::text), 1, 16) END,
            v_level,
            CASE v_level WHEN 'trace' THEN 1 WHEN 'debug' THEN 5 WHEN 'info' THEN 9
                         WHEN 'warn' THEN 13 WHEN 'error' THEN 17 ELSE 9 END,
            p_bodies[1 + floor(random() * array_length(p_bodies, 1))::int],
            jsonb_build_object(
                'sentry.environment', v_env,
                'sentry.release', p_release,
                'logger', 'com.shop',
                'thread', 'http-nio-8080-exec-' || (1 + floor(random() * 8)::int)),
            p_release);
    END LOOP;
END $$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- Helper: seed one distributed trace — a browser pageload transaction (fe) with
-- a fetch client span whose child is the backend http.server transaction (be),
-- which itself has db/cache child spans. Optionally pins an error event to the
-- backend span (linking a real issue) and drops a correlated log trail. This is
-- the cross-service waterfall the trace UI (§9.4) is built to show.
--
-- Timings are relative to p_start, in ms, so every trace is internally
-- consistent regardless of when it "happened".
-- ---------------------------------------------------------------------------
CREATE FUNCTION pg_temp.seed_trace(
    p_fe          bigint,     -- frontend project
    p_be          bigint,     -- backend project
    p_env         text,
    p_fe_release  text,
    p_be_release  text,
    p_start       timestamptz,
    p_route       text,       -- browser route, e.g. '/checkout'
    p_endpoint    text,       -- backend name, e.g. 'GET /api/checkout'
    p_fe_ms       int,        -- total pageload duration
    p_fetch_ms    int,        -- fetch client span duration (nested in fe)
    p_be_ms       int,        -- backend txn duration (nested in fetch)
    p_db_ms       int,        -- db query span duration (nested in be)
    p_db_desc     text,       -- e.g. 'SELECT * FROM orders WHERE id = ?'
    p_status      text,       -- backend txn status: 'ok' or 'internal_error'
    p_error_issue bigint,     -- issue id to pin an error to the backend span (NULL = none)
    p_extype      text,       -- exception type for the pinned error
    p_message     text        -- error message for the pinned error
) RETURNS text AS $$
DECLARE
    v_trace     text := md5(random()::text || clock_timestamp()::text);
    v_fe_span   text := substr(md5(random()::text), 1, 16);  -- fe pageload root span
    v_fetch     text := substr(md5(random()::text), 1, 16);  -- fe fetch client span
    v_be_span   text := substr(md5(random()::text), 1, 16);  -- be http.server root span
    v_db_span   text := substr(md5(random()::text), 1, 16);  -- be db query span
    v_cache     text := substr(md5(random()::text), 1, 16);  -- be cache span
    v_fe_txn    uuid := gen_random_uuid();
    v_be_txn    uuid := gen_random_uuid();
    -- offsets (ms) laid out as a realistic waterfall inside the pageload window
    v_fetch_off int := greatest(20, (p_fe_ms * 0.15)::int);   -- fetch starts a bit after pageload
    v_be_off    int := v_fetch_off + 30;                       -- server work starts after network hop
    v_db_off    int := v_be_off + greatest(10, (p_be_ms * 0.2)::int);
    v_cache_off int := v_be_off + 5;
BEGIN
    -- Frontend pageload transaction (trace root: no parent span).
    INSERT INTO txn (id, project_id, environment, release, trace_id, span_id, parent_span_id,
                     name, op, start_ts, end_ts, duration_ms, status, data)
    VALUES (v_fe_txn, p_fe, p_env, p_fe_release, v_trace, v_fe_span, NULL,
            p_route, 'pageload', p_start, p_start + make_interval(secs => p_fe_ms / 1000.0),
            p_fe_ms, 'ok',
            jsonb_build_object(
                'contexts', jsonb_build_object('trace', jsonb_build_object(
                    'trace_id', v_trace, 'span_id', v_fe_span, 'op', 'pageload')),
                'tags', jsonb_build_object('route', p_route, 'browser', 'Chrome 126'),
                'measurements', jsonb_build_object(
                    'lcp', jsonb_build_object('value', (p_fe_ms * 0.7)::int, 'unit', 'millisecond'),
                    'fcp', jsonb_build_object('value', (p_fe_ms * 0.35)::int, 'unit', 'millisecond'))));

    -- Fetch client span (child of the pageload root) — the network call to the API.
    INSERT INTO span (id, txn_id, project_id, trace_id, span_id, parent_span_id, op, description,
                      start_ts, end_ts, duration_ms, status, data)
    VALUES (gen_random_uuid(), v_fe_txn, p_fe, v_trace, v_fetch, v_fe_span, 'http.client', p_endpoint,
            p_start + make_interval(secs => v_fetch_off / 1000.0),
            p_start + make_interval(secs => (v_fetch_off + p_fetch_ms) / 1000.0),
            p_fetch_ms, p_status,
            jsonb_build_object('http.method', split_part(p_endpoint, ' ', 1),
                               'http.url', 'https://api.shop.example.com' || split_part(p_endpoint, ' ', 2),
                               'http.status_code', CASE WHEN p_status = 'ok' THEN 200 ELSE 500 END));

    -- Backend http.server transaction — continues the trace off the fetch span.
    INSERT INTO txn (id, project_id, environment, release, trace_id, span_id, parent_span_id,
                     name, op, start_ts, end_ts, duration_ms, status, data)
    VALUES (v_be_txn, p_be, p_env, p_be_release, v_trace, v_be_span, v_fetch,
            p_endpoint, 'http.server',
            p_start + make_interval(secs => v_be_off / 1000.0),
            p_start + make_interval(secs => (v_be_off + p_be_ms) / 1000.0),
            p_be_ms, p_status,
            jsonb_build_object(
                'contexts', jsonb_build_object('trace', jsonb_build_object(
                    'trace_id', v_trace, 'span_id', v_be_span, 'op', 'http.server')),
                'tags', jsonb_build_object('handler', p_endpoint, 'server_name', 'shop-backend-7d9f'),
                'request', jsonb_build_object('method', split_part(p_endpoint, ' ', 1),
                                              'url', 'https://api.shop.example.com' || split_part(p_endpoint, ' ', 2))));

    -- Backend cache lookup span (fast, nested in the server txn).
    INSERT INTO span (id, txn_id, project_id, trace_id, span_id, parent_span_id, op, description,
                      start_ts, end_ts, duration_ms, status, data)
    VALUES (gen_random_uuid(), v_be_txn, p_be, v_trace, v_cache, v_be_span, 'cache.get',
            'redis GET session:' || substr(md5(random()::text), 1, 8),
            p_start + make_interval(secs => v_cache_off / 1000.0),
            p_start + make_interval(secs => (v_cache_off + 4) / 1000.0),
            4, 'ok', jsonb_build_object('cache.hit', true, 'db.system', 'redis'));

    -- Backend db query span (the slow part; nested in the server txn).
    INSERT INTO span (id, txn_id, project_id, trace_id, span_id, parent_span_id, op, description,
                      start_ts, end_ts, duration_ms, status, data)
    VALUES (gen_random_uuid(), v_be_txn, p_be, v_trace, v_db_span, v_be_span, 'db.sql.query', p_db_desc,
            p_start + make_interval(secs => v_db_off / 1000.0),
            p_start + make_interval(secs => (v_db_off + p_db_ms) / 1000.0),
            p_db_ms, CASE WHEN p_status = 'ok' THEN 'ok' ELSE 'internal_error' END,
            jsonb_build_object('db.system', 'postgresql', 'db.statement', p_db_desc,
                               'db.rows_affected', CASE WHEN p_status = 'ok' THEN 1 ELSE 0 END));

    -- Error event pinned to the backend server span (links to a real issue and
    -- shows in the waterfall + span detail panel). Also feeds the trace log lane.
    IF p_error_issue IS NOT NULL THEN
        INSERT INTO event (id, project_id, issue_id, environment, release, "timestamp", trace_id,
                           level, message, exception_type, user_ident, data, raw, symbolication_status)
        VALUES (gen_random_uuid(), p_be, p_error_issue, p_env, p_be_release,
                p_start + make_interval(secs => (v_db_off + p_db_ms) / 1000.0), v_trace,
                'error', p_message, p_extype, 'u-' || (1000 + floor(random() * 45)::int),
                jsonb_build_object(
                    'level', 'error',
                    'contexts', jsonb_build_object('trace', jsonb_build_object(
                        'trace_id', v_trace, 'span_id', v_be_span, 'op', 'http.server'))),
                NULL, NULL);
    END IF;

    -- Correlated log trail across the trace (frontend nav + backend handling).
    INSERT INTO log_record (id, project_id, environment, "timestamp", trace_id, span_id,
                            level, severity_number, body, attributes, release)
    VALUES
      (gen_random_uuid(), p_fe, p_env, p_start + make_interval(secs => 2 / 1000.0), v_trace, v_fe_span,
       'info', 9, 'Navigated to ' || p_route,
       jsonb_build_object('sentry.environment', p_env, 'sentry.release', p_fe_release,
                          'route', p_route), p_fe_release),
      (gen_random_uuid(), p_be, p_env, p_start + make_interval(secs => v_be_off / 1000.0), v_trace, v_be_span,
       'info', 9, 'Handling ' || p_endpoint,
       jsonb_build_object('sentry.environment', p_env, 'sentry.release', p_be_release,
                          'http.method', split_part(p_endpoint, ' ', 1)), p_be_release),
      (gen_random_uuid(), p_be, p_env,
       p_start + make_interval(secs => (v_db_off + p_db_ms) / 1000.0), v_trace, v_db_span,
       CASE WHEN p_status = 'ok' THEN 'info' ELSE 'error' END,
       CASE WHEN p_status = 'ok' THEN 9 ELSE 17 END,
       CASE WHEN p_status = 'ok'
            THEN p_db_desc || ' completed in ' || p_db_ms || 'ms'
            ELSE p_extype || ': ' || p_message END,
       jsonb_build_object('sentry.environment', p_env, 'sentry.release', p_be_release,
                          'db.system', 'postgresql'), p_be_release);

    RETURN v_trace;
END $$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- Clean slate for the seed projects (events aren't FK-cascaded — delete first).
-- ---------------------------------------------------------------------------
DELETE FROM event      WHERE project_id IN (SELECT id FROM project WHERE slug IN ('shop-frontend','shop-backend'));
DELETE FROM log_record WHERE project_id IN (SELECT id FROM project WHERE slug IN ('shop-frontend','shop-backend'));
DELETE FROM span       WHERE project_id IN (SELECT id FROM project WHERE slug IN ('shop-frontend','shop-backend'));
DELETE FROM txn        WHERE project_id IN (SELECT id FROM project WHERE slug IN ('shop-frontend','shop-backend'));
DELETE FROM project    WHERE slug IN ('shop-frontend','shop-backend');  -- cascades keys/env/release/issue/env_stats
DELETE FROM api_token  WHERE name = 'ci-pipeline (seed)';

-- ---------------------------------------------------------------------------
-- Projects, DSN keys, environments, releases.
-- Fixed 32-hex public keys → stable DSNs (usable to send real events too).
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    fe bigint;  -- shop-frontend
    be bigint;  -- shop-backend
    env text;
    -- issue ids (looked up by fingerprint) for pinning trace errors to real issues
    iss_npe   bigint;
    iss_order bigint;
    v_ep      text;
    v_route   text;
    v_dbq     text;
    v_err     boolean;
    v_env     text;
    n         int;
BEGIN
    INSERT INTO project (slug, name, platform)
    VALUES ('shop-frontend', 'Shop Frontend', 'javascript-angular') RETURNING id INTO fe;
    INSERT INTO project (slug, name, platform)
    VALUES ('shop-backend', 'Shop Backend', 'java-spring-boot') RETURNING id INTO be;

    INSERT INTO project_key (project_id, public_key, is_active) VALUES
        (fe, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', true),
        (fe, 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', false),   -- a rotated-out key, for the UI
        (be, 'cccccccccccccccccccccccccccccccc', true);

    FOREACH env IN ARRAY ARRAY['dev','qa','prod'] LOOP
        INSERT INTO environment (project_id, name) VALUES (fe, env), (be, env);
    END LOOP;

    INSERT INTO release (project_id, version, created_at) VALUES
        (fe, 'shop-frontend@1.4.2', now() - interval '3 days'),
        (fe, 'shop-frontend@1.4.1', now() - interval '18 days'),
        (be, 'shop-backend@2.1.0',  now() - interval '4 days'),
        (be, 'shop-backend@2.0.5',  now() - interval '21 days');

    -- ---- Frontend issues (symbolicated TS stacktraces with source context) ----

    -- 1) High-volume checkout crash. Not traced → exercises the ±60 s log window.
    PERFORM pg_temp.seed_issue(fe,
        'fe-checkout-total-undefined',
        'TypeError: Cannot read properties of undefined (reading ''total'')',
        'src/app/checkout/checkout.service.ts in CheckoutService.summary',
        'TypeError', 'Cannot read properties of undefined (reading ''total'')',
        'error', 'unresolved', 'shop-frontend@1.4.2',
        $json${
          "platform":"javascript",
          "sdk":{"name":"sentry.javascript.angular","version":"10.3.0"},
          "exception":{"values":[{
            "type":"TypeError",
            "value":"Cannot read properties of undefined (reading 'total')",
            "stacktrace":{"frames":[
              {"filename":"src/app/app.component.ts","abs_path":"app:///src/app/app.component.ts","function":"AppComponent.ngOnInit","lineno":34,"colno":9,"in_app":true,"context_line":"    this.summary = this.checkout.summary();","pre_context":["  ngOnInit(): void {","    this.user = this.auth.currentUser();"],"post_context":["  }",""]},
              {"filename":"src/app/checkout/checkout.service.ts","abs_path":"app:///src/app/checkout/checkout.service.ts","function":"CheckoutService.summary","lineno":52,"colno":21,"in_app":true,"context_line":"    return this.cart.total + this.shipping;","pre_context":["  summary(): number {","    const cart = this.cartFor(this.user.id);"],"post_context":["  }",""]}
            ]}
          }]},
          "breadcrumbs":{"values":[
            {"timestamp":1751558400,"category":"navigation","type":"navigation","level":"info","message":"Navigated to /checkout","data":{"from":"/cart","to":"/checkout"}},
            {"timestamp":1751558405,"category":"ui.click","type":"user","level":"info","message":"Clicked button.checkout-submit"},
            {"timestamp":1751558406,"category":"xhr","type":"http","level":"info","message":"GET /api/cart [200]","data":{"status_code":200}}
          ]},
          "tags":{"page":"checkout","browser":"Chrome 126","route":"/checkout"},
          "contexts":{"browser":{"name":"Chrome","version":"126.0.6478.127"},"os":{"name":"macOS","version":"14.5"}},
          "request":{"url":"https://shop.example.com/checkout","method":"GET","headers":{"User-Agent":"Mozilla/5.0"}}
        }$json$::jsonb,
        NULL, 46, 13, ARRAY['prod','prod','prod','qa','dev'], false, NULL);

    -- 2) HTTP 500 from the API. Traced → correlated backend logs appear.
    PERFORM pg_temp.seed_issue(fe,
        'fe-http-500-orders',
        'HttpErrorResponse: Http failure response for /api/orders: 500',
        'src/app/core/api.ts in Api.post',
        'HttpErrorResponse', 'Http failure response for https://api.shop.example.com/api/orders: 500 Internal Server Error',
        'error', 'unresolved', 'shop-frontend@1.4.2',
        $json${
          "platform":"javascript",
          "sdk":{"name":"sentry.javascript.angular","version":"10.3.0"},
          "exception":{"values":[{
            "type":"HttpErrorResponse",
            "value":"Http failure response for https://api.shop.example.com/api/orders: 500 Internal Server Error",
            "stacktrace":{"frames":[
              {"filename":"src/app/checkout/checkout.component.ts","abs_path":"app:///src/app/checkout/checkout.component.ts","function":"CheckoutComponent.placeOrder","lineno":71,"colno":15,"in_app":true,"context_line":"    const order = await this.api.post('/api/orders', payload);","pre_context":["  async placeOrder(): Promise<void> {","    const payload = this.buildPayload();"],"post_context":["    this.router.navigate(['/thanks']);","  }"]},
              {"filename":"src/app/core/api.ts","abs_path":"app:///src/app/core/api.ts","function":"Api.post","lineno":23,"colno":27,"in_app":true,"context_line":"    return firstValueFrom(this.http.post<T>(url, body));","pre_context":["  post<T>(path: string, body: unknown): Promise<T> {","    const url = this.base + path;"],"post_context":["  }",""]}
            ]}
          }]},
          "breadcrumbs":{"values":[
            {"timestamp":1751558500,"category":"ui.click","type":"user","level":"info","message":"Clicked button.place-order"},
            {"timestamp":1751558501,"category":"xhr","type":"http","level":"error","message":"POST /api/orders [500]","data":{"status_code":500}}
          ]},
          "tags":{"page":"checkout","browser":"Firefox 127","route":"/checkout"},
          "contexts":{"browser":{"name":"Firefox","version":"127.0"},"os":{"name":"Windows","version":"11"}},
          "request":{"url":"https://shop.example.com/checkout","method":"GET"}
        }$json$::jsonb,
        NULL, 30, 9, ARRAY['prod','prod','qa'], true, be);  -- correlated logs land in the backend

    -- 3) Dev/QA-only change-detection error.
    PERFORM pg_temp.seed_issue(fe,
        'fe-expression-changed',
        'Error: ExpressionChangedAfterItHasBeenCheckedError',
        'src/app/products/product-list.component.ts in ProductListComponent.ngAfterViewInit',
        'Error', 'ExpressionChangedAfterItHasBeenCheckedError: Previous value: ''loading: true''. Current value: ''loading: false''.',
        'error', 'unresolved', 'shop-frontend@1.4.1',
        $json${
          "platform":"javascript",
          "sdk":{"name":"sentry.javascript.angular","version":"10.3.0"},
          "exception":{"values":[{
            "type":"Error",
            "value":"ExpressionChangedAfterItHasBeenCheckedError: Previous value: 'loading: true'. Current value: 'loading: false'.",
            "stacktrace":{"frames":[
              {"filename":"src/app/products/product-list.component.ts","abs_path":"app:///src/app/products/product-list.component.ts","function":"ProductListComponent.ngAfterViewInit","lineno":58,"colno":11,"in_app":true,"context_line":"    this.loading = false;","pre_context":["  ngAfterViewInit(): void {","    this.load();"],"post_context":["  }",""]}
            ]}
          }]},
          "breadcrumbs":{"values":[
            {"timestamp":1751558600,"category":"navigation","type":"navigation","level":"info","message":"Navigated to /products"}
          ]},
          "tags":{"page":"products","browser":"Chrome 126","route":"/products"},
          "contexts":{"browser":{"name":"Chrome","version":"126.0"},"os":{"name":"Linux","version":"Ubuntu 24.04"}},
          "request":{"url":"https://shop.example.com/products","method":"GET"}
        }$json$::jsonb,
        NULL, 12, 14, ARRAY['dev','dev','qa','qa','prod'], false, NULL);

    -- 4) Partial symbolication → shows the missing-source-map warning banner.
    PERFORM pg_temp.seed_issue(fe,
        'fe-vendor-partial-symb',
        'TypeError: t is not a function',
        'vendor.js in <anonymous>',
        'TypeError', 't is not a function',
        'error', 'unresolved', 'shop-frontend@1.4.2',
        $json${
          "platform":"javascript",
          "sdk":{"name":"sentry.javascript.angular","version":"10.3.0"},
          "_outpost_symbolication":{"status":"partial","missing":[
            {"debug_id":"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d","abs_path":"https://shop.example.com/vendor-8f2a1c.js"}
          ]},
          "exception":{"values":[{
            "type":"TypeError",
            "value":"t is not a function",
            "stacktrace":{"frames":[
              {"filename":"src/app/analytics/tracker.ts","abs_path":"app:///src/app/analytics/tracker.ts","function":"Tracker.emit","lineno":19,"colno":7,"in_app":true,"context_line":"    this.provider.track(name, props);","pre_context":["  emit(name: string, props: object): void {",""],"post_context":["  }",""]},
              {"filename":"vendor-8f2a1c.js","abs_path":"https://shop.example.com/vendor-8f2a1c.js","function":"<anonymous>","lineno":1,"colno":48213,"in_app":false}
            ]}
          }]},
          "tags":{"page":"home","browser":"Safari 17","route":"/"},
          "contexts":{"browser":{"name":"Safari","version":"17.5"},"os":{"name":"iOS","version":"17.5"}},
          "request":{"url":"https://shop.example.com/","method":"GET"}
        }$json$::jsonb,
        'partial', 8, 6, ARRAY['prod','prod','qa'], false, NULL);

    -- ---- Backend issues (JVM stacktraces — already symbolic, no context lines) ----

    -- 5) NullPointerException in the cart flow (high volume, prod-heavy).
    PERFORM pg_temp.seed_issue(be,
        'be-npe-cart',
        'java.lang.NullPointerException: Cannot invoke Cart.getItems() because cart is null',
        'com.shop.cart.CartService in summarize',
        'java.lang.NullPointerException', 'Cannot invoke com.shop.model.Cart.getItems() because "cart" is null',
        'error', 'unresolved', 'shop-backend@2.1.0',
        $json${
          "platform":"java",
          "sdk":{"name":"sentry.java.spring-boot.jakarta","version":"8.44.0"},
          "exception":{"values":[{
            "type":"java.lang.NullPointerException",
            "value":"Cannot invoke com.shop.model.Cart.getItems() because \"cart\" is null",
            "module":"com.shop.cart",
            "stacktrace":{"frames":[
              {"module":"org.springframework.web.servlet.DispatcherServlet","function":"doDispatch","filename":"DispatcherServlet.java","lineno":1089,"in_app":false},
              {"module":"com.shop.cart.CartController","function":"view","filename":"CartController.java","lineno":41,"in_app":true},
              {"module":"com.shop.cart.CartService","function":"summarize","filename":"CartService.java","lineno":73,"in_app":true}
            ]}
          }]},
          "tags":{"handler":"CartController#view","server_name":"shop-backend-7d9f","transaction":"GET /api/cart"},
          "contexts":{"runtime":{"name":"OpenJDK Runtime Environment","version":"21.0.3+9"},"os":{"name":"Linux","version":"6.1.0"}},
          "request":{"url":"https://api.shop.example.com/api/cart","method":"GET"}
        }$json$::jsonb,
        NULL, 40, 14, ARRAY['prod','prod','prod','qa'], true, be);

    -- 6) DataIntegrityViolationException placing orders.
    PERFORM pg_temp.seed_issue(be,
        'be-data-integrity-orders',
        'org.springframework.dao.DataIntegrityViolationException: could not execute statement',
        'com.shop.order.OrderService in placeOrder',
        'org.springframework.dao.DataIntegrityViolationException', 'could not execute statement [ERROR: duplicate key value violates unique constraint "orders_idempotency_key_key"]',
        'error', 'unresolved', 'shop-backend@2.1.0',
        $json${
          "platform":"java",
          "sdk":{"name":"sentry.java.spring-boot.jakarta","version":"8.44.0"},
          "exception":{"values":[{
            "type":"org.springframework.dao.DataIntegrityViolationException",
            "value":"could not execute statement [ERROR: duplicate key value violates unique constraint \"orders_idempotency_key_key\"]",
            "module":"org.springframework.dao",
            "stacktrace":{"frames":[
              {"module":"com.shop.order.OrderController","function":"create","filename":"OrderController.java","lineno":55,"in_app":true},
              {"module":"com.shop.order.OrderService","function":"placeOrder","filename":"OrderService.java","lineno":88,"in_app":true},
              {"module":"org.hibernate.engine.jdbc.spi.SqlExceptionHelper","function":"convert","filename":"SqlExceptionHelper.java","lineno":92,"in_app":false}
            ]}
          }]},
          "tags":{"handler":"OrderController#create","server_name":"shop-backend-7d9f","transaction":"POST /api/orders"},
          "contexts":{"runtime":{"name":"OpenJDK Runtime Environment","version":"21.0.3+9"},"os":{"name":"Linux","version":"6.1.0"}},
          "request":{"url":"https://api.shop.example.com/api/orders","method":"POST"}
        }$json$::jsonb,
        NULL, 24, 12, ARRAY['prod','prod','qa','dev'], true, be);

    -- 7) Resolved issue (populates the Resolved tab); some events older than 14 d.
    PERFORM pg_temp.seed_issue(be,
        'be-query-timeout',
        'org.springframework.dao.QueryTimeoutException: JDBC statement timed out',
        'com.shop.catalog.CatalogRepository in search',
        'org.springframework.dao.QueryTimeoutException', 'JDBC statement timed out after 3000ms',
        'error', 'resolved', 'shop-backend@2.0.5',
        $json${
          "platform":"java",
          "sdk":{"name":"sentry.java.spring-boot.jakarta","version":"8.44.0"},
          "exception":{"values":[{
            "type":"org.springframework.dao.QueryTimeoutException",
            "value":"JDBC statement timed out after 3000ms",
            "module":"org.springframework.dao",
            "stacktrace":{"frames":[
              {"module":"com.shop.catalog.CatalogController","function":"search","filename":"CatalogController.java","lineno":33,"in_app":true},
              {"module":"com.shop.catalog.CatalogRepository","function":"search","filename":"CatalogRepository.java","lineno":120,"in_app":true}
            ]}
          }]},
          "tags":{"handler":"CatalogController#search","server_name":"shop-backend-5b2","transaction":"GET /api/catalog/search"},
          "contexts":{"runtime":{"name":"OpenJDK Runtime Environment","version":"21.0.3+9"},"os":{"name":"Linux","version":"6.1.0"}},
          "request":{"url":"https://api.shop.example.com/api/catalog/search?q=shoes","method":"GET"}
        }$json$::jsonb,
        NULL, 9, 22, ARRAY['qa','qa','prod'], false, NULL);

    -- 8) Low-volume warning from a scheduled job (older; mostly outside 14 d).
    PERFORM pg_temp.seed_issue(be,
        'be-scheduled-illegalstate',
        'java.lang.IllegalStateException: cleanup job already running',
        'com.shop.jobs.CartCleanupJob in run',
        'java.lang.IllegalStateException', 'cleanup job already running',
        'warning', 'unresolved', 'shop-backend@2.0.5',
        $json${
          "platform":"java",
          "sdk":{"name":"sentry.java.spring-boot.jakarta","version":"8.44.0"},
          "exception":{"values":[{
            "type":"java.lang.IllegalStateException",
            "value":"cleanup job already running",
            "module":"com.shop.jobs",
            "stacktrace":{"frames":[
              {"module":"com.shop.jobs.CartCleanupJob","function":"run","filename":"CartCleanupJob.java","lineno":29,"in_app":true}
            ]}
          }]},
          "tags":{"server_name":"shop-backend-5b2","transaction":"cart-cleanup"},
          "contexts":{"runtime":{"name":"OpenJDK Runtime Environment","version":"21.0.3+9"},"os":{"name":"Linux","version":"6.1.0"}}
        }$json$::jsonb,
        NULL, 6, 26, ARRAY['prod','qa'], false, NULL);

    -- ---- Ambient log streams (Logs page: search, level filters, live window) ----
    PERFORM pg_temp.seed_logs(fe, 160, 14, 'shop-frontend@1.4.2',
        ARRAY[
          'User session initialized',
          'Fetching cart contents',
          'Cart updated: 3 items',
          'Navigated to /products',
          'XHR GET /api/products [200] in 84ms',
          'Feature flag new-checkout enabled',
          'Slow resource main.js loaded in 1.2s',
          'Form validation failed for field email',
          'Retrying failed request /api/cart',
          'Service worker activated'],
        ARRAY['prod','prod','qa','dev']);

    PERFORM pg_temp.seed_logs(be, 220, 14, 'shop-backend@2.1.0',
        ARRAY[
          'Started ShopApplication in 4.21 seconds',
          'Handling GET /api/products',
          'Persisted order id=4711',
          'JDBC pool: 3 active, 7 idle',
          'Cache miss for key catalog:page:1',
          'Scheduled job cart-cleanup completed, removed 12 carts',
          'Slow query 1200ms on table orders',
          'Rejected request: rate limit exceeded for 203.0.113.42',
          'Retrying transaction after deadlock',
          'Sent confirmation email to customer u-1012'],
        ARRAY['prod','prod','prod','qa','dev']);

    -- ---- Distributed traces (§9.4: cross-service waterfalls) ----
    -- Resolve a few issue ids so trace errors deep-link to real issues.
    SELECT id INTO iss_npe   FROM issue WHERE project_id = be AND fingerprint = 'be-npe-cart';
    SELECT id INTO iss_order FROM issue WHERE project_id = be AND fingerprint = 'be-data-integrity-orders';

    -- A few hand-tuned, "interesting" traces that always land (stable to click on):

    -- 1) Healthy checkout pageload → API → Postgres. No errors; nice nested bars.
    PERFORM pg_temp.seed_trace(fe, be, 'prod', 'shop-frontend@1.4.2', 'shop-backend@2.1.0',
        now() - interval '8 minutes', '/checkout', 'GET /api/checkout',
        820, 540, 430, 260, 'SELECT * FROM cart WHERE user_id = ?', 'ok',
        NULL, NULL, NULL);

    -- 2) Slow products listing — big db span dominates the waterfall.
    PERFORM pg_temp.seed_trace(fe, be, 'prod', 'shop-frontend@1.4.2', 'shop-backend@2.1.0',
        now() - interval '26 minutes', '/products', 'GET /api/products',
        2100, 1850, 1720, 1400, 'SELECT * FROM product ORDER BY rank LIMIT 50', 'ok',
        NULL, NULL, NULL);

    -- 3) Failing order placement — error pinned to the backend span, links to a
    --    real issue; the db span and log lane both go red. The G3 payoff trace.
    PERFORM pg_temp.seed_trace(fe, be, 'prod', 'shop-frontend@1.4.2', 'shop-backend@2.1.0',
        now() - interval '4 minutes', '/checkout', 'POST /api/orders',
        1240, 980, 870, 610, 'INSERT INTO orders (idempotency_key, ...) VALUES (?, ...)',
        'internal_error', iss_order,
        'org.springframework.dao.DataIntegrityViolationException',
        'could not execute statement [ERROR: duplicate key value violates unique constraint "orders_idempotency_key_key"]');

    -- 4) Cart view hitting the NPE — another pinned error, different issue/service path.
    PERFORM pg_temp.seed_trace(fe, be, 'qa', 'shop-frontend@1.4.2', 'shop-backend@2.1.0',
        now() - interval '52 minutes', '/cart', 'GET /api/cart',
        690, 470, 380, 90, 'SELECT * FROM cart_item WHERE cart_id = ?',
        'internal_error', iss_npe,
        'java.lang.NullPointerException',
        'Cannot invoke com.shop.model.Cart.getItems() because "cart" is null');

    -- A spread of generated traces so the list pages, the duration filter has
    -- range to bite on, and "errors only" has both matches and non-matches.
    FOR n IN 1..40 LOOP
        v_env := (ARRAY['prod','prod','prod','qa','dev'])[1 + floor(random() * 5)::int];
        CASE 1 + floor(random() * 4)::int
            WHEN 1 THEN v_route := '/checkout'; v_ep := 'GET /api/checkout';
                        v_dbq := 'SELECT * FROM cart WHERE user_id = ?';
            WHEN 2 THEN v_route := '/products';  v_ep := 'GET /api/products';
                        v_dbq := 'SELECT * FROM product ORDER BY rank LIMIT 50';
            WHEN 3 THEN v_route := '/orders';    v_ep := 'POST /api/orders';
                        v_dbq := 'INSERT INTO orders (idempotency_key, ...) VALUES (?, ...)';
            ELSE        v_route := '/cart';       v_ep := 'GET /api/cart';
                        v_dbq := 'SELECT * FROM cart_item WHERE cart_id = ?';
        END CASE;
        -- ~20% error, biased to prod, spread over the last ~3 days.
        v_err := random() < 0.20;
        PERFORM pg_temp.seed_trace(fe, be, v_env, 'shop-frontend@1.4.2', 'shop-backend@2.1.0',
            now() - make_interval(secs => (3 * 86400 * power(random(), 1.5))::int),
            v_route, v_ep,
            300 + floor(random() * 2400)::int,   -- fe pageload
            200 + floor(random() * 2000)::int,   -- fetch
            120 + floor(random() * 1600)::int,   -- backend
            40  + floor(random() * 1200)::int,   -- db
            v_dbq,
            CASE WHEN v_err THEN 'internal_error' ELSE 'ok' END,
            CASE WHEN v_err THEN iss_npe ELSE NULL END,
            CASE WHEN v_err THEN 'java.lang.NullPointerException' END,
            CASE WHEN v_err THEN 'Cannot invoke com.shop.model.Cart.getItems() because "cart" is null' END);
    END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- A couple of extra accounts + an API token so the Settings pages aren't empty.
-- NOTE: these users cannot log in — password_hash is a placeholder (argon2id is
-- computed by the app, not reproducible in SQL). They exist for the Users list.
-- The API token likewise shows in the list but cannot be used (hash preimage
-- unknown). Your bootstrapped admin (OUTPOST_ADMIN_EMAIL) is untouched.
-- ---------------------------------------------------------------------------
INSERT INTO app_user (email, password_hash, role) VALUES
    ('dev@shop.example.com',  '$argon2id$v=19$m=16384,t=2,p=1$c2VlZHNlZWRzZWVk$0000000000000000000000000000000000000000000', 'member'),
    ('lead@shop.example.com', '$argon2id$v=19$m=16384,t=2,p=1$c2VlZHNlZWRzZWVk$1111111111111111111111111111111111111111111', 'admin')
ON CONFLICT DO NOTHING;

INSERT INTO api_token (name, token_hash, scopes)
VALUES ('ci-pipeline (seed)', encode(sha256(random()::text::bytea), 'hex'), ARRAY['artifacts:write']);

COMMIT;

-- ---------------------------------------------------------------------------
-- Summary of what landed.
-- ---------------------------------------------------------------------------
SELECT p.slug,
       (SELECT count(*) FROM issue i WHERE i.project_id = p.id)               AS issues,
       (SELECT count(*) FROM event e WHERE e.project_id = p.id)               AS events,
       (SELECT count(*) FROM log_record l WHERE l.project_id = p.id)          AS logs,
       (SELECT count(*) FROM txn t WHERE t.project_id = p.id)                 AS txns,
       (SELECT count(*) FROM span s WHERE s.project_id = p.id)                AS spans,
       (SELECT count(*) FROM release r WHERE r.project_id = p.id)             AS releases
FROM project p
WHERE p.slug IN ('shop-frontend','shop-backend')
ORDER BY p.slug;
