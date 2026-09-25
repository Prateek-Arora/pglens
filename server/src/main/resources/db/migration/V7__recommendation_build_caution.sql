-- V7 (ADR-0041, backlog B17). A validated B-tree whose key column could hold a value too wide for a
-- B-tree entry (~2.7 kB) may fail on CREATE INDEX, and HypoPG can't detect that. The edge validator
-- now reports a caution from catalog facts only (column types + the table's TOAST size); NULL means
-- no caution. Also: from this version every score_basis is 'GENERIC_PLAN' (ADR-0041) — the value
-- range stays as evidence; older 'VALUE_RANGE_FLOOR' rows are rescored when revalidated.
ALTER TABLE recommendations ADD COLUMN build_caution TEXT;
