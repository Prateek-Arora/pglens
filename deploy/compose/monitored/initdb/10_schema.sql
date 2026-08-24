-- PgLens demo schema: a small e-commerce model (customers, products, orders,
-- order_items, events). Created once on a fresh volume; data is loaded
-- separately by `make seed` (demo/seed.sql).
--
-- GROUND TRUTH FOR PHASE 1:
-- Every table has its PRIMARY KEY (auto-indexed) and realistic FOREIGN KEYs,
-- but the SECONDARY indexes that obvious queries need are DELIBERATELY OMITTED.
-- A foreign key does NOT create an index on the child column -- that gap is one
-- of the most common real-world Postgres anti-patterns, and PgLens must detect
-- it. Each "MISSING INDEX" note below is a recommendation Phase 1 should
-- produce and HypoPG should validate. See demo/slow_queries.sql for the queries
-- that exercise them.

CREATE TABLE customers (
    id         serial PRIMARY KEY,
    full_name  text        NOT NULL,
    email      text        NOT NULL,       -- MISSING INDEX: equality lookups by email seq-scan (should be UNIQUE btree)
    country    text        NOT NULL,       -- MISSING INDEX: filter by country seq-scans
    segment    text        NOT NULL,       -- skewed: mostly 'standard'
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE products (
    id          serial PRIMARY KEY,
    sku         text NOT NULL,
    name        text NOT NULL,
    category    text NOT NULL,             -- MISSING INDEX: filter by category seq-scans
    price_cents integer NOT NULL
);

CREATE TABLE orders (
    id          serial PRIMARY KEY,
    customer_id integer     NOT NULL REFERENCES customers (id),  -- MISSING INDEX: FK column unindexed -> joins/filters seq-scan
    status      text        NOT NULL,      -- skewed: mostly 'completed'
    total_cents integer     NOT NULL,
    created_at  timestamptz NOT NULL
    -- MISSING INDEX: orders(created_at)          -> date-range / ORDER BY seq-scans (BRIN candidate: data is date-clustered)
    -- MISSING INDEX: orders(status, created_at)  -> "recent rows of status X" seq-scans + top-N sort
);

CREATE TABLE order_items (
    id               serial PRIMARY KEY,
    order_id         integer NOT NULL REFERENCES orders (id),    -- MISSING INDEX: FK column unindexed -> join to orders seq-scans 500k rows
    product_id       integer NOT NULL REFERENCES products (id),  -- MISSING INDEX: FK column unindexed
    quantity         integer NOT NULL,
    unit_price_cents integer NOT NULL
);

CREATE TABLE events (
    id          serial PRIMARY KEY,
    customer_id integer     NOT NULL REFERENCES customers (id),  -- MISSING INDEX: events(customer_id, occurred_at) -> per-customer timeline seq-scans
    event_type  text        NOT NULL,      -- MISSING INDEX: filter by rare event_type seq-scans (partial-index candidate)
    occurred_at timestamptz NOT NULL,
    payload     jsonb       NOT NULL       -- future GIN example (jsonb containment) -- NOT planner-validated by HypoPG
);
