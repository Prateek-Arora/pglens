-- Recommendation lifecycle (Phase 4 Step 12, ADR-0051).
--
-- Each analysis pass now retires the recommendations the engine no longer proposes. When an index
-- that serves one has appeared on the monitored database since it was recommended, the row is kept
-- as *applied* instead of deleted, so PgLens can compare the query's measured time before and after:
--
--   applied_index : the existing index that serves it (any name — matched by table, method and key)
--   applied_at    : when PgLens first saw that index — the server receive time of the first catalog
--                   snapshot that listed it (index_stats), which is also that batch's query_stats
--                   captured_at, so "before" and "after" split exactly at one sample.
--
-- Both NULL while the recommendation is active. If the index is dropped and the engine proposes it
-- again, they are cleared.
ALTER TABLE recommendations ADD COLUMN applied_index TEXT;
ALTER TABLE recommendations ADD COLUMN applied_at TIMESTAMPTZ;

-- Rows proposed by older engine versions in a DDL format the engine no longer writes (unqualified
-- system catalogs, B27) are removed by the first pass; nothing else changes here.
