-- PgLens "make it slow" query pack -- the Phase 1 ground-truth oracle.
--
-- Each query below is a KNOWN anti-pattern against the seeded schema. The
-- header states: the problem, the plan node that proves it, and the index
-- PgLens should recommend (and HypoPG should validate). `demo/warmup.sh`
-- replays this file so pg_stat_statements accumulates real stats.
--
-- HONESTY NOTE: query 10 is a legitimate full scan. PgLens must NOT invent an
-- index recommendation for it. Not every Seq Scan is a bug. Query 11 is the GIN
-- control: its fix is a GIN index, which HypoPG cannot simulate, so PgLens must
-- surface it as "suggested -- not planner-validated", never with a fake delta.
--
-- To inspect any plan yourself: prefix the query with EXPLAIN (ANALYZE, BUFFERS)
-- for the monitored (read-only, SELECT-only) demo DB it is always safe.


-- 1. Hot customer's order history.
--    Problem : orders.customer_id is unindexed (FK column) -> Seq Scan on orders.
--    Proof   : Seq Scan on orders.
--    Rec     : btree orders(customer_id). Also over-fetches (SELECT *).
--    Validated (HypoPG): win depends on selectivity -- ~57% for the hottest
--    customer (id 1, ~1.9% of rows), ~99% for a typical/cold customer.
SELECT * FROM orders WHERE customer_id = 1 ORDER BY created_at DESC;


-- 2. Most recent orders of a given (rare) status.
--    Problem : no index supports the status filter + created_at ordering.
--    Proof   : Seq Scan on orders + top-N heapsort.
--    Rec     : btree orders(status, created_at DESC).
SELECT id, customer_id, total_cents, created_at
FROM orders
WHERE status = 'refunded'
ORDER BY created_at DESC
LIMIT 50;


-- 3. Orders in the last 7 days.
--    Problem : orders.created_at is unindexed -> whole table scanned for a
--              tiny, recent slice.
--    Proof   : Seq Scan on orders.
--    Rec     : btree orders(created_at) -- or BRIN, since data is date-clustered.
SELECT count(*) FROM orders WHERE created_at >= now() - interval '7 days';


-- 4. Line-item rollup for one customer's orders.
--    Problem : order_items.order_id is unindexed (FK column) -> the 500k-row
--              child table is seq-scanned to satisfy the join.
--    Proof   : Seq Scan on order_items.
--    Rec     : btree order_items(order_id) [+ orders(customer_id) from #1].
--    Validated (HypoPG): MODEST (~13%) -- the hottest customer still has ~28k
--    line items, so the join stays heavy; the index enables the lookup, not a
--    small result. A less-hot customer benefits far more.
SELECT o.id, o.total_cents, count(oi.id) AS items
FROM orders o
JOIN order_items oi ON oi.order_id = o.id
WHERE o.customer_id = 1
GROUP BY o.id, o.total_cents;


-- 5. A customer's most recent activity.
--    Problem : no index on events(customer_id, occurred_at) -> Seq Scan.
--    Proof   : Seq Scan on events.
--    Rec     : btree events(customer_id, occurred_at DESC).
SELECT event_type, occurred_at
FROM events
WHERE customer_id = 1
ORDER BY occurred_at DESC
LIMIT 100;


-- 6. Count of a rare event type.
--    Problem : event_type is unindexed; 'error' is a small fraction, so an
--              index would be selective -> but we Seq Scan 300k rows.
--    Proof   : Seq Scan on events.
--    Rec     : btree events(event_type), or a partial index WHERE event_type='error'.
SELECT count(*) FROM events WHERE event_type = 'error';


-- 7. Look up a customer by email.
--    Problem : customers.email is unindexed -> Seq Scan for a single row.
--    Proof   : Seq Scan on customers.
--    Rec     : UNIQUE btree customers(email).
SELECT id, full_name, email FROM customers WHERE email = 'user12345@example.com';


-- 8. Order-item volume for a product category (HONEST NEGATIVE #2 -- wrong fix).
--    Problem : products.category is unindexed -> Seq Scan on products. But the
--              query's real cost is scanning the 500k-row order_items fact
--              table for the join; products has only 3k rows.
--    Proof   : Seq Scan on products (cheap) beside a large order_items scan.
--    Rec     : btree products(category) gives a NEGLIGIBLE HypoPG win (~0.3%) --
--              indexing the filtered column does not help when the cost is
--              elsewhere. PgLens must report the true (tiny) delta, not oversell
--              it. This is a different honesty lesson than #10.
SELECT p.category, count(*) AS n
FROM order_items oi
JOIN products p ON p.id = oi.product_id
WHERE p.category = 'electronics'
GROUP BY p.category;


-- 9. Recent orders from customers in one country (classic over-fetch + joins).
--    Problem : customers.country unindexed AND orders.customer_id unindexed;
--              plus SELECT * over-fetches every column.
--    Proof   : Seq Scan on customers and on orders.
--    Rec     : btree customers(country) + orders(customer_id); select only
--              needed columns.
--    Validated (HypoPG): MODEST (~4%) -- the indexes locate BR customers and
--    their orders, but the cross-table ORDER BY created_at DESC sort dominates.
SELECT *
FROM orders o
JOIN customers c ON c.id = o.customer_id
WHERE c.country = 'BR'
ORDER BY o.created_at DESC
LIMIT 20;


-- 10. Order totals by status (LEGITIMATE full scan -- the honesty control).
--     Problem : NONE. Aggregating every row into ~8 groups genuinely reads the
--               whole table; an index would not help.
--     Proof   : Seq Scan on orders -- and that is CORRECT.
--     Rec     : NONE. PgLens must not fabricate an index recommendation here.
--     Validated (HypoPG): a hypothetical orders(status) index leaves the plan
--     and cost UNCHANGED (0% win) -- the honest control.
SELECT status, count(*) AS n, sum(total_cents) AS revenue_cents
FROM orders
GROUP BY status;


-- 11. Events carrying a specific user-agent (jsonb containment -- GIN territory).
--     Problem : events.payload is jsonb with no GIN index -> Seq Scan applies a
--               @> containment filter across every row.
--     Proof   : Seq Scan on events, Filter: (events.payload @> $1).
--     Rec     : GIN index on events(payload) -- CREATE INDEX ... USING gin (payload).
--     HONESTY (the point of this fixture): HypoPG CANNOT simulate a GIN index, so
--     PgLens surfaces this as "suggested -- not planner-validated" and shows the
--     real pg_stat_statements time as the measured "before" -- it never fabricates
--     a cost delta for it. This is a different honesty lesson than #8 and #10.
SELECT count(*) FROM events WHERE payload @> '{"ua":"agent-7"}';
