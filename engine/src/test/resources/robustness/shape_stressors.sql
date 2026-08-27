-- PgLens capture-robustness "shape stressor" pack.
--
-- Purpose: the demo/slow_queries.sql oracle is a small set of NEAT anti-patterns.
-- Real workloads are messier, and PgLens's fragile surface is plan CAPTURE — a
-- statement whose pg_stat_statements-normalized text cannot be EXPLAIN
-- (GENERIC_PLAN)'d is dropped SILENTLY (planCaptured=false, no finding, no
-- error). That is exactly the class of bug ADR-0021 fixed (a typed literal
-- `interval '7 days'` normalizes to `interval $1`, a syntax error to EXPLAIN, so
-- a whole class of temporal-range queries vanished with no signal).
--
-- Each statement below is a VALID SELECT against the demo schema that exercises a
-- query SHAPE the neat demo does not. PlanCaptureRobustnessTest executes each so
-- pg_stat_statements normalizes it, reads the normalized text back, and asserts
-- the capturer plans it (skip-rate ~0). A new shape that silently skips fails CI.
--
-- Format: one `;`-terminated statement per block; `--` lines are comments. Keep
-- statement lines free of inline `--` and inner `;` (the test splits on `;`).

-- IN-list -> normalizes to `= ANY (...)`; a btree should still serve it.
SELECT id FROM orders WHERE status IN ('refunded', 'cancelled', 'pending');

-- Explicit `= ANY (array)`.
SELECT id FROM orders WHERE customer_id = ANY (ARRAY[1, 2, 3, 4]);

-- Multi-column AND filter (two indexable predicates on one table).
SELECT id FROM orders WHERE customer_id = 42 AND status = 'completed';

-- OR of two single-column predicates.
SELECT id FROM orders WHERE customer_id = 42 OR status = 'refunded';

-- BETWEEN -> a normalized pair of >= / <= bounds on one column.
SELECT id FROM orders WHERE total_cents BETWEEN 1000 AND 5000;

-- Not-equal (liberal rule extracts it; HypoPG will suppress since a btree can't serve <>).
SELECT id FROM orders WHERE status <> 'completed';

-- Typed literal: interval (the ADR-0021 class -> `interval $1`).
SELECT count(*) FROM orders WHERE created_at >= now() - interval '7 days';

-- Typed literal: timestamp.
SELECT count(*) FROM events WHERE occurred_at > timestamp '2024-01-01 00:00:00';

-- Typed literal: date.
SELECT count(*) FROM events WHERE occurred_at >= date '2024-06-01';

-- Typed literal: numeric.
SELECT id FROM products WHERE price_cents > numeric '1000';

-- Function on a column (expression-index territory; extraction is a known gap -> backlog).
SELECT id FROM customers WHERE lower(email) = 'user1@example.com';

-- Cast on a column.
SELECT id FROM orders WHERE customer_id::text = '42';

-- LIKE prefix (btree with text_pattern_ops territory).
SELECT id FROM customers WHERE email LIKE 'user1%';

-- LIKE contains (trigram/GIN territory; a shape, not a validated rec).
SELECT id FROM customers WHERE email LIKE '%example%';

-- jsonb field extraction (`->>`), not containment.
SELECT count(*) FROM events WHERE payload ->> 'ua' = 'agent-7';

-- jsonb containment (`@>`) -> R7/GIN, surfaced not-planner-validated.
SELECT count(*) FROM events WHERE payload @> '{"ua":"agent-7"}';

-- Correlated EXISTS subquery.
SELECT c.id FROM customers c WHERE EXISTS (SELECT 1 FROM orders o WHERE o.customer_id = c.id);

-- IN (subquery).
SELECT id FROM orders WHERE customer_id IN (SELECT id FROM customers WHERE country = 'BR');

-- Common table expression.
WITH recent AS (SELECT id FROM orders WHERE created_at > now() - interval '1 day') SELECT count(*) FROM recent;

-- Window function.
SELECT id, row_number() OVER (PARTITION BY customer_id ORDER BY created_at DESC) FROM orders;

-- UNION of two branches.
SELECT id FROM orders WHERE status = 'pending' UNION SELECT id FROM orders WHERE status = 'shipped';

-- DISTINCT ON.
SELECT DISTINCT ON (customer_id) customer_id, created_at FROM orders ORDER BY customer_id, created_at DESC;

-- LATERAL join.
SELECT c.id, o.id FROM customers c JOIN LATERAL (SELECT id FROM orders o WHERE o.customer_id = c.id LIMIT 1) o ON true;

-- GROUP BY with HAVING.
SELECT customer_id, count(*) FROM orders GROUP BY customer_id HAVING count(*) > 5;

-- Three-table join with a filter (multi-join plan shape).
SELECT c.id, count(oi.id) FROM customers c JOIN orders o ON o.customer_id = c.id JOIN order_items oi ON oi.order_id = o.id WHERE c.country = 'BR' GROUP BY c.id;
