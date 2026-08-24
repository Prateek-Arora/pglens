-- PgLens demo data generator.
--
-- Loads SKEWED, high-row-count data so the planner genuinely chooses the bad
-- plans we want to detect (uniform-random data can hide seq scans behind
-- "reasonable" plans). Skew introduced here:
--   * hot customers   -- a power-law over customer_id (customer 1 is hottest)
--   * recent-heavy    -- created_at / occurred_at cluster near now()
--   * lopsided status -- orders are mostly 'completed'
--   * rare event_type -- 'error' events are a small fraction
--
-- Reproducible: setseed() fixes the RNG, so the same data (hence the same plan
-- shapes) comes out every run. Idempotent: skips if already seeded -- use
-- `make reseed` to wipe and reload.
--
-- Row counts kept modest (~1M total) so a cold `make seed` finishes in seconds
-- while still forcing non-trivial plans. Callers pass -v ON_ERROR_STOP=1.

DO $$
DECLARE
    existing bigint;
BEGIN
    SELECT count(*) INTO existing FROM orders;
    IF existing > 0 THEN
        RAISE NOTICE 'orders already has % rows; skipping seed (run `make reseed` to force).', existing;
        RETURN;
    END IF;

    PERFORM setseed(0.42);

    RAISE NOTICE 'Seeding 20000 customers...';
    INSERT INTO customers (full_name, email, country, segment, created_at)
    SELECT
        'Customer ' || g,
        'user' || g || '@example.com',
        (ARRAY['US','US','US','US','GB','DE','IN','BR','CA','AU'])[1 + floor(random() * 10)::int],
        (ARRAY['standard','standard','standard','premium','vip'])[1 + floor(random() * 5)::int],
        now() - (random() * interval '900 days')
    FROM generate_series(1, 20000) AS g;

    RAISE NOTICE 'Seeding 3000 products...';
    INSERT INTO products (sku, name, category, price_cents)
    SELECT
        'SKU-' || lpad(g::text, 6, '0'),
        'Product ' || g,
        (ARRAY['electronics','books','home','toys','grocery','clothing'])[1 + floor(random() * 6)::int],
        (100 + floor(random() * 50000))::int
    FROM generate_series(1, 3000) AS g;

    RAISE NOTICE 'Seeding 200000 orders (hot customers, recent-heavy, lopsided status)...';
    INSERT INTO orders (customer_id, status, total_cents, created_at)
    SELECT
        1 + floor(power(random(), 2.5) * 19999)::int,
        (ARRAY['completed','completed','completed','completed','shipped','pending','cancelled','refunded'])[1 + floor(random() * 8)::int],
        (500 + floor(random() * 200000))::int,
        now() - (power(random(), 2) * interval '365 days')
    FROM generate_series(1, 200000) AS g;

    RAISE NOTICE 'Seeding 500000 order_items...';
    INSERT INTO order_items (order_id, product_id, quantity, unit_price_cents)
    SELECT
        1 + floor(random() * 200000)::int,
        1 + floor(random() * 3000)::int,
        1 + floor(random() * 5)::int,
        (100 + floor(random() * 50000))::int
    FROM generate_series(1, 500000) AS g;

    RAISE NOTICE 'Seeding 300000 events (rare error type, recent-heavy)...';
    INSERT INTO events (customer_id, event_type, occurred_at, payload)
    SELECT
        1 + floor(power(random(), 1.5) * 19999)::int,
        (ARRAY['page_view','page_view','page_view','add_to_cart','checkout','login','logout','error'])[1 + floor(random() * 8)::int],
        now() - (power(random(), 2) * interval '180 days'),
        jsonb_build_object(
            'ip', '10.0.' || floor(random() * 255)::int || '.' || floor(random() * 255)::int,
            'ua', 'agent-' || floor(random() * 50)::int
        )
    FROM generate_series(1, 300000) AS g;

    RAISE NOTICE 'Seed complete.';
END
$$;

-- Refresh planner statistics so EXPLAIN reflects the freshly loaded data.
ANALYZE;
