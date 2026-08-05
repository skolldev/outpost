-- Outpost demo backfill — 30 days of logs, purely for showing the log timeline.
--
-- The demo apps only ever produce telemetry for "now", so the timeline has
-- nothing to draw at any range wider than the session you just ran. This
-- fabricates the missing history: ~30 days of log_record rows across both demo
-- projects, shaped so the chart has something to say —
--
--   * a diurnal cycle (quiet overnight, busy around 14:00 UTC),
--   * weekends at roughly a third of weekday volume,
--   * two production releases per project, cut over mid-window,
--   * four named incidents that spike volume AND shift the level mix towards
--     error/fatal, so brushing one actually changes what the list shows.
--
-- Everything it writes carries attributes->>'seeded_by' = 'demo-logs-30d', which
-- is both the idempotency key (a re-run deletes its own rows first, and nothing
-- else) and a ready-made attribute filter to demo: attr=seeded_by=demo-logs-30d.
--
-- Requires demo/scripts/demo-setup.sql to have run (it needs the two projects),
-- and creates the weekly log_record partitions the backfill lands in — normally
-- PartitionManager's job, but it only ever creates partitions around now().
--
-- Run against the compose database:
--   docker compose exec -T db psql -U outpost -d outpost < demo/scripts/demo-logs-30d.sql
-- or against a local bootRun DB (published on :5432):
--   psql "postgresql://outpost:outpost@localhost:5432/outpost" -f demo/scripts/demo-logs-30d.sql

BEGIN;

-- Every date_trunc/partition bound below is meant in UTC, matching the bounds
-- PartitionManager writes (weekStart.atStartOfDay(UTC)). Without this the script
-- would silently produce partitions an hour off in a non-UTC session.
SET LOCAL timezone = 'UTC';

DO $$
BEGIN
    IF (SELECT count(*) FROM project WHERE slug IN ('shop-frontend','shop-backend')) <> 2 THEN
        RAISE EXCEPTION 'demo projects missing — run demo/scripts/demo-setup.sql first';
    END IF;
END $$;

-- Past weeks, back far enough to cover the whole 30-day window. IF NOT EXISTS so
-- this is safe next to the partitions the running server already made.
DO $$
DECLARE
    week date;
BEGIN
    FOR week IN
        SELECT d::date
        FROM generate_series(date_trunc('week', now() - interval '31 days'),
                             date_trunc('week', now()), interval '1 week') d
    LOOP
        EXECUTE format('CREATE TABLE IF NOT EXISTS log_record_p%s PARTITION OF log_record '
                       || 'FOR VALUES FROM (%L) TO (%L)',
                       to_char(week, 'YYYYMMDD'), week, week + 7);
    END LOOP;
END $$;

DELETE FROM log_record WHERE attributes->>'seeded_by' = 'demo-logs-30d';

-- The backfill spans environments and releases the live demo apps never emit, so
-- the facet lists have to be told about them explicitly (ingest would normally
-- upsert these in TelemetryOrigins).
INSERT INTO environment (project_id, name)
SELECT p.id, e.name
FROM project p CROSS JOIN (VALUES ('production'), ('staging')) AS e(name)
WHERE p.slug IN ('shop-frontend','shop-backend')
ON CONFLICT (project_id, name) DO NOTHING;

INSERT INTO release (project_id, version, created_at)
SELECT p.id, r.version, now() - r.age
FROM project p
JOIN (VALUES
        ('shop-backend',  'shop-backend@2.1.0',  interval '34 days'),
        ('shop-backend',  'shop-backend@2.2.0',  interval '18 days'),
        ('shop-frontend', 'shop-frontend@1.4.0', interval '34 days'),
        ('shop-frontend', 'shop-frontend@1.5.0', interval '18 days')
     ) AS r(slug, version, age) ON r.slug = p.slug
ON CONFLICT (project_id, version) DO NOTHING;

INSERT INTO log_record (id, project_id, environment, "timestamp", trace_id, span_id,
                        level, severity_number, body, attributes, release)
WITH
-- Midnight UTC today: every incident and deploy below is expressed as an offset
-- from it, so the story lands on whole hours whenever the script is run.
anchor AS (
    SELECT date_trunc('day', now()) AS day0, date_trunc('hour', now()) AS now_h
),
-- One row per (project, environment) firehose. base_rate is logs/minute at the
-- daily peak on a weekday; err_share/warn_share are the level mix outside of
-- incidents. Staging is deliberately thin and noisy-in-debug rather than a
-- scaled-down copy of production.
stream(slug, environment, service, base_rate, err_share, warn_share) AS (VALUES
    ('shop-backend',  'production', 'shop-backend',  2.0,  0.035, 0.10),
    ('shop-backend',  'staging',    'shop-backend',  0.30, 0.060, 0.16),
    ('shop-frontend', 'production', 'shop-frontend', 1.2,  0.025, 0.09),
    ('shop-frontend', 'staging',    'shop-frontend', 0.18, 0.050, 0.14)
),
-- Named windows where volume and the level mix both move. label doubles as the
-- body-template pool for the error/fatal/warn rows inside the window, so an
-- incident reads as one story in the list rather than as louder background.
incident(slug, environment, label, starts_at, ends_at, rate_mult, err_share, warn_share) AS (
    SELECT i.slug, i.environment, i.label,
           a.day0 - i.days_ago + i.from_h, a.day0 - i.days_ago + i.to_h,
           i.rate_mult, i.err_share, i.warn_share
    FROM anchor a
    JOIN (VALUES
            ('shop-backend',  'production', 'db-pool-exhaustion',
                interval '22 days', interval '9 hours',  interval '11 hours 30 minutes', 7.0, 0.55, 0.25),
            ('shop-backend',  'production', 'cache-stampede',
                interval '6 days',  interval '7 hours',  interval '54 hours',            1.6, 0.09, 0.45),
            -- Off-peak and short: the multipliers are large because the diurnal
            -- factor is against them, and a spike only reads at a wide bucket
            -- width if it beats the daytime baseline it is drawn next to.
            ('shop-backend',  'production', 'payment-gateway-timeouts',
                interval '9 days',  interval '20 hours 15 minutes', interval '22 hours 30 minutes', 12.0, 0.65, 0.20),
            ('shop-frontend', 'production', 'chunk-load-failures',
                interval '3 days',  interval '13 hours', interval '15 hours', 12.0, 0.45, 0.25)
         ) AS i(slug, environment, label, days_ago, from_h, to_h, rate_mult, err_share, warn_share) ON true
),
minute AS (
    SELECT g AS ts,
           (extract(hour FROM g) + extract(minute FROM g) / 60.0)::float AS hod,
           extract(isodow FROM g) AS dow
    FROM anchor a, generate_series(a.now_h - interval '30 days', a.now_h, interval '1 minute') g
),
-- Expected rows for this stream in this minute, and the level mix to draw from.
slot AS (
    SELECT s.slug, s.environment, s.service, m.ts, i.label,
           s.base_rate
             -- Daily cycle: ~12% of peak at 02:00, peak at 14:00 UTC. The
             -- exponent sharpens the peak so a 1h bucket looks like traffic
             -- rather than a sine wave.
             * (0.12 + 0.88 * power((1 + cos(2 * pi() * (m.hod - 14) / 24)) / 2, 2.2))
             * (CASE WHEN m.dow >= 6 THEN 0.35 ELSE 1.0 END)
             * coalesce(i.rate_mult, 1.0) AS rate,
           coalesce(i.err_share,  s.err_share)  AS err_share,
           coalesce(i.warn_share, s.warn_share) AS warn_share
    FROM stream s
    CROSS JOIN minute m
    LEFT JOIN incident i
           ON i.slug = s.slug AND i.environment = s.environment
          AND m.ts >= i.starts_at AND m.ts < i.ends_at
),
-- Stochastic rounding: a rate of 0.3 emits a row in ~30% of minutes instead of
-- rounding away to a permanently empty stream.
draw AS MATERIALIZED (
    SELECT slot.*, floor(rate + random())::int AS n FROM slot WHERE rate > 0
),
-- One row per log line, with its dice already rolled: rl picks the level, rt
-- decides whether the line belongs to a traced request, rq groups consecutive
-- lines into the same trace.
line AS (
    SELECT d.slug, d.environment, d.service, d.label, d.err_share, d.warn_share,
           d.ts + (random() * interval '1 minute') AS ts,
           random() AS rl, random() AS rt, floor(random() * 6)::int AS rq
    FROM draw d CROSS JOIN generate_series(1, d.n)
),
levelled AS (
    SELECT l.*,
           CASE
               WHEN l.rl < l.err_share
                   THEN CASE WHEN random() < 0.05 THEN 'fatal' ELSE 'error' END
               WHEN l.rl < l.err_share + l.warn_share THEN 'warn'
               WHEN l.rl < l.err_share + l.warn_share + 0.42 THEN 'info'
               WHEN l.rl < l.err_share + l.warn_share + 0.72 THEN 'debug'
               ELSE 'trace'
           END AS level
    FROM line l
),
-- Body pools. Keyed by project slug for ordinary traffic and by incident label
-- for the error/fatal/warn lines inside a window. {n}/{ms}/{id} are filled per
-- row so no two lines read identically.
template(pool, level, body) AS (VALUES
    ('shop-backend','info', 'GET /api/products?page={n} 200 in {ms}ms'),
    ('shop-backend','info', 'POST /api/cart/{id}/items 201 in {ms}ms'),
    ('shop-backend','info', 'checkout session {id} created — {n} items'),
    ('shop-backend','info', 'order {id} confirmed for customer {n}'),
    ('shop-backend','info', 'payment authorized for order {id} in {ms}ms'),
    ('shop-backend','info', 'inventory reserved for SKU-{n}'),
    ('shop-backend','info', 'user {n} signed in'),
    ('shop-backend','debug','cache lookup products:page:{n} → hit in {ms}ms'),
    ('shop-backend','debug','connection acquired from pool (active {n}/20)'),
    ('shop-backend','debug','resolved shipping zone for postcode {id}'),
    ('shop-backend','debug','serialized cart {id} in {ms}ms'),
    ('shop-backend','debug','flushing write-behind buffer, {n} entries'),
    ('shop-backend','trace','SELECT id, sku, price FROM product WHERE category_id = {n} → {n} rows in {ms}ms'),
    ('shop-backend','trace','entering PricingService#quote(cart={id})'),
    ('shop-backend','trace','http client → payments-gw POST /v2/charges'),
    ('shop-backend','trace','span checkout.authorize started'),
    ('shop-backend','warn', 'slow query {ms}ms: SELECT ... FROM order_item WHERE order_id = {n}'),
    ('shop-backend','warn', 'retrying payments-gw call (attempt {n}/3)'),
    ('shop-backend','warn', 'cart {id} abandoned after {n} minutes'),
    ('shop-backend','warn', 'stale inventory read for SKU-{n}, falling back to source'),
    ('shop-backend','error','failed to persist order {id}: order_item_order_id_fkey violated'),
    ('shop-backend','error','payments-gw returned 502 for charge {id}'),
    ('shop-backend','error','unhandled NullPointerException in POST /api/checkout'),
    ('shop-backend','error','inventory service timed out after {ms}ms for SKU-{n}'),
    ('shop-backend','fatal','OutOfMemoryError: Java heap space — writing heap dump'),
    ('shop-backend','fatal','database unreachable, stopping ingest worker {n}'),

    ('shop-frontend','info', 'route change → /products/{n} rendered in {ms}ms'),
    ('shop-frontend','info', 'added SKU-{n} to cart'),
    ('shop-frontend','info', 'checkout step {n} completed'),
    ('shop-frontend','info', 'session restored for visitor {id}'),
    ('shop-frontend','info', 'LCP {ms}ms on /products'),
    ('shop-frontend','debug','hydrated {n} product cards'),
    ('shop-frontend','debug','service worker cache hit for /assets/main-{id}.js'),
    ('shop-frontend','debug','prefetching route /checkout'),
    ('shop-frontend','debug','analytics batch flushed ({n} events)'),
    ('shop-frontend','trace','fetch GET /api/products?page={n} → 200 in {ms}ms'),
    ('shop-frontend','trace','signal recompute cartTotal ({n} dependencies)'),
    ('shop-frontend','trace','zone task scheduled: {id}'),
    ('shop-frontend','warn', 'image /assets/hero-{n}.webp took {ms}ms to load'),
    ('shop-frontend','warn', 'retrying failed call to /api/cart ({n}/3)'),
    ('shop-frontend','warn', 'deprecated localStorage key cart_v1 read'),
    ('shop-frontend','warn', 'long task blocked the main thread for {ms}ms'),
    ('shop-frontend','error','TypeError: Cannot read properties of undefined (reading ''price'')'),
    ('shop-frontend','error','ChunkLoadError: Loading chunk {n} failed'),
    ('shop-frontend','error','POST /api/checkout failed with 500 after {ms}ms'),
    ('shop-frontend','error','unhandled promise rejection in CartEffects'),
    ('shop-frontend','fatal','app bootstrap failed — NG0403 no provider for CartStore'),

    ('db-pool-exhaustion','warn', 'HikariPool-1 connection wait {ms}ms exceeds threshold'),
    ('db-pool-exhaustion','warn', 'HikariPool-1 thread starvation detected (housekeeper delta {ms}ms)'),
    ('db-pool-exhaustion','error','HikariPool-1 connection is not available, request timed out after {ms}ms'),
    ('db-pool-exhaustion','error','could not obtain JDBC connection for order {id}'),
    ('db-pool-exhaustion','error','transaction rolled back: connection closed mid-statement'),
    ('db-pool-exhaustion','fatal','HikariPool-1 exhausted — 20/20 active, {n} threads awaiting'),

    ('cache-stampede','warn', 'redis miss rate {n}% over the last 5m'),
    ('cache-stampede','warn', 'recomputing products:page:{n} — stampede guard held {ms}ms'),
    ('cache-stampede','error','redis GET products:page:{n} timed out after {ms}ms'),
    ('cache-stampede','fatal','cache node redis-{n} evicted, failing over'),

    ('payment-gateway-timeouts','warn', 'payments-gw latency {ms}ms above SLO, retry {n}/3'),
    ('payment-gateway-timeouts','warn', 'falling back to the offline authorization queue'),
    ('payment-gateway-timeouts','error','payments-gw POST /v2/charges timed out after {ms}ms (order {id})'),
    ('payment-gateway-timeouts','error','charge {id} left PENDING — gateway unreachable'),
    ('payment-gateway-timeouts','error','circuit breaker OPEN for payments-gw'),
    ('payment-gateway-timeouts','fatal','payment reconciliation halted — {n} charges in an unknown state'),

    ('chunk-load-failures','warn', 'retrying chunk load checkout-{id} ({n}/3)'),
    ('chunk-load-failures','warn', 'falling back to the server-rendered checkout'),
    ('chunk-load-failures','error','ChunkLoadError: chunk checkout-{id} missing from the CDN'),
    ('chunk-load-failures','error','GET /assets/main-{id}.js failed with 404 after cache purge'),
    ('chunk-load-failures','fatal','app bootstrap failed after chunk {id} returned 404')
),
pool AS (
    SELECT pool, level, array_agg(body) AS bodies FROM template GROUP BY pool, level
)
SELECT
    gen_random_uuid(),
    p.id,
    v.environment,
    v.ts,
    -- ~55% of lines belong to a request: several lines a minute share a trace so
    -- the list's "filter by this trace" chip lands on something.
    CASE WHEN v.rt < 0.55 THEN md5(v.service || v.environment || v.rq::text || date_trunc('minute', v.ts)::text) END,
    CASE WHEN v.rt < 0.55 THEN substr(md5(random()::text), 1, 16) END,
    v.level,
    CASE v.level WHEN 'trace' THEN 1 WHEN 'debug' THEN 5 WHEN 'info' THEN 9
                 WHEN 'warn' THEN 13 WHEN 'error' THEN 17 ELSE 21 END,
    replace(replace(replace(pool.bodies[1 + floor(random() * array_length(pool.bodies, 1))::int],
        '{ms}', (5 + floor(random() * 2400))::text),
        '{n}',  (1 + floor(random() * 900))::text),
        '{id}', substr(md5(random()::text), 1, 8)),
    jsonb_build_object(
        'seeded_by', 'demo-logs-30d',
        'service', v.service,
        'host', v.service || '-' || (1 + floor(random() * 3))::int,
        'region', (ARRAY['eu-west-1','us-east-1'])[1 + floor(random() * 2)::int],
        'duration_ms', floor(random() * 1800)
    )
    || CASE WHEN v.label IS NOT NULL THEN jsonb_build_object('incident', v.label) ELSE '{}'::jsonb END
    || CASE WHEN v.level IN ('error','fatal')
            THEN jsonb_build_object('http.status_code', (ARRAY[500,502,503,504])[1 + floor(random() * 4)::int])
            ELSE jsonb_build_object('http.status_code', (ARRAY[200,200,200,201,204,304,404])[1 + floor(random() * 7)::int])
       END,
    -- One deploy per project inside the window: staging picks the new version up
    -- six days before production does.
    v.slug || '@' || CASE
        WHEN v.ts >= a.day0 - (CASE WHEN v.environment = 'staging' THEN interval '18 days' ELSE interval '12 days' END)
            THEN CASE WHEN v.slug = 'shop-backend' THEN '2.2.0' ELSE '1.5.0' END
        ELSE CASE WHEN v.slug = 'shop-backend' THEN '2.1.0' ELSE '1.4.0' END
    END
FROM levelled v
CROSS JOIN anchor a
JOIN project p ON p.slug = v.slug
JOIN pool ON pool.pool = CASE
        WHEN v.label IS NOT NULL AND v.level IN ('warn','error','fatal') THEN v.label
        ELSE v.slug
    END
   AND pool.level = v.level;

COMMIT;

\echo
\echo 'Backfilled logs by project and level:'
SELECT p.slug, l.environment, l.level, count(*)
FROM log_record l JOIN project p ON p.id = l.project_id
WHERE l.attributes->>'seeded_by' = 'demo-logs-30d'
GROUP BY 1, 2, 3
ORDER BY 1, 2, 3;

\echo 'Window:'
SELECT min("timestamp") AS oldest, max("timestamp") AS newest, count(*) AS rows
FROM log_record WHERE attributes->>'seeded_by' = 'demo-logs-30d';
