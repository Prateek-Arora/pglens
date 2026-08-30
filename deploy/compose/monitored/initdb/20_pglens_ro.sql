-- The dedicated read-only role the PgLens agent logs in as (ADR-0030 — closes the DB-level read-only
-- role ADR-0020 deferred to Phase 2). Safety principle #2 is enforced HERE, at the database, not
-- only by the agent's session guard (SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY): even if
-- that guard were ever dropped, this role holds no write privilege on any table, so a write is
-- rejected with "permission denied for table …". Defense in depth — the role and the session guard
-- each block writes independently.
--
-- Least privilege: NOSUPERUSER, no CREATEDB/CREATEROLE, no RLS bypass; SELECT only, plus the two
-- capabilities PgLens genuinely needs:
--   * pg_read_all_stats — see EVERY statement in pg_stat_statements (query text included) and full
--     pg_stat_user_indexes, not just this role's own. Without it, other roles' query text is
--     redacted to "<insufficient privilege>" and idx_scan visibility is partial.
--   * hypopg's functions — PUBLIC-executable by default (a hypothetical index is session-local and
--     writes nothing to the catalog), so no grant is needed here; create / re-EXPLAIN / reset all
--     run fine inside a read-only transaction (verified — ADR-0020). The agent runs edge validation
--     as this very role.
--
-- CONNECT on the database and USAGE on schema public already come from PUBLIC's defaults, and this
-- script names no database, so it stays portable across the compose db name (pglens_demo) and the
-- Testcontainers one. Runs after 00_extensions.sql + 10_schema.sql (filename order), so the tables
-- it grants SELECT on already exist. Init scripts run once, on a fresh data volume.

CREATE ROLE pglens_ro LOGIN PASSWORD 'pglens_ro'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;

-- Full visibility into the statistics views PgLens samples.
GRANT pg_read_all_stats TO pglens_ro;

-- Read the schema and every current + future table in it (future tables created by the DB owner).
GRANT USAGE ON SCHEMA public TO pglens_ro;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO pglens_ro;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO pglens_ro;
