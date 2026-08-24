-- Register the extensions PgLens needs on the monitored database.
-- Runs once, automatically, on a fresh data volume (docker-entrypoint-initdb.d).

-- The stats source for the whole product. The shared library is loaded at
-- server start (shared_preload_libraries, see docker-compose.yml); this makes
-- the SQL-visible views/functions available in the demo database.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- Hypothetical indexes: lets PgLens ask the planner "what would this index
-- cost?" without building it. Provided by postgresql-16-hypopg (see
-- monitored/Dockerfile).
CREATE EXTENSION IF NOT EXISTS hypopg;
