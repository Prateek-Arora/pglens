-- Real-schema hardening (Phase 4 Step 11, ADR-0049).
--
-- query_texts.plan_error: why a query has no captured plan, as the agent saw it — most usefully
-- "the PgLens role may not read a table this query uses (permission denied for schema app)", which
-- a GRANT fixes (the agent then re-sends the plan). NULL when the plan was captured, and for rows
-- written by an older agent.
--
-- table_catalog.partition_root: for a partition, its partition tree's root table — where an index
-- for it belongs (an index on the parent covers every partition). NULL for any other table.
--
-- Table and index names are now identities: schema-qualified unless public, quoted as Postgres
-- would quote them, case preserved. A lower-case table in public keeps the name it had, so
-- existing history carries over unchanged.
ALTER TABLE query_texts ADD COLUMN plan_error TEXT;
ALTER TABLE table_catalog ADD COLUMN partition_root TEXT;
