# Demo data & the slow-query oracle

This directory is PgLens's reusable test fixture: a realistic-but-skewed dataset
that reliably produces bad query plans, plus the documented pack of queries that
exercise them. It is the **ground-truth oracle** every later phase tests against.

## Files

| File | What it is |
|---|---|
| `seed.sql` | Reproducible generator (`setseed`) for ~1M rows of **skewed** e-commerce data. Idempotent; `make reseed` wipes and reloads. |
| `slow_queries.sql` | ~10 queries, each headed by its expected problem, the plan node that proves it, and the index PgLens should recommend. |
| `warmup.sh` | Replays `slow_queries.sql` N times so `pg_stat_statements` accumulates real stats (runs inside the container). |

The schema itself is created by `deploy/compose/monitored/initdb/10_schema.sql`
(every table has its primary key and foreign keys, but the **secondary indexes
are deliberately omitted**).

## Why the data is skewed

Uniform-random data lets the planner pick "reasonable" plans and hides the seq
scans we want to detect. `seed.sql` introduces real skew — hot customers
(power-law over `customer_id`), recent-heavy timestamps, lopsided `status`, and
a rare `event_type` — so the anti-patterns are genuine.

## Verified ground truth (HypoPG cost deltas)

Every recommendation in `slow_queries.sql` was checked against the real planner
with HypoPG — a hypothetical index created and `EXPLAIN`ed **in the same
session** (hypothetical indexes are session-local; create + explain must share
one connection). Planner total-cost, measured on `postgres:16-bookworm` with the
`setseed(0.42)` dataset:

| # | Recommended index | Cost before → after | Verdict |
|---|---|---|---|
| 1 | `orders(customer_id)` | 4187 → 1806 (hot) / 3942 → 46 (cold) | win — selectivity-dependent |
| 2 | `orders(status, created_at)` | 4437 → 13 | strong win (~99.7%) |
| 3 | `orders(created_at)` | 4570 → 935 | win (~80%, index-only scan) |
| 4 | `order_items(order_id)` [+ #1] | 10608 → 9237 | modest (~13%) |
| 5 | `events(customer_id, occurred_at)` | 6449 → 384 | strong win (~94%) |
| 6 | `events(event_type)` | 6481 → 1262 | win (~80%) |
| 7 | `customers(email)` | 473 → 8 | strong win (~98%) |
| 8 | `products(category)` | 6971 → 6951 | **negligible (~0.3%)** — cost is elsewhere |
| 9 | `customers(country)` [+ #1] | 4760 → 4574 | modest (~4%) |
| 10 | *(none)* | 4530 → 4530 | **no win (0%)** — legitimate full scan |

Two of these are honest negatives for *different* reasons — #8 (indexing the
filtered column doesn't help when a large fact-table scan dominates) and #10 (a
whole-table aggregate genuinely reads everything). PgLens must report those true
deltas, not oversell them.

## The honesty control

`slow_queries.sql` query #10 (`GROUP BY status`) is a **legitimate** full scan:
aggregating every row genuinely reads the whole table, and no index would help.
PgLens must **not** invent a recommendation for it. Not every Seq Scan is a bug —
that distinction is the whole point of the product.

## Usage

```bash
make up && make seed && make warmup
make psql-monitored     # then: EXPLAIN (ANALYZE, BUFFERS) SELECT ... ;
```
