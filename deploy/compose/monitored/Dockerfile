# Monitored demo Postgres for PgLens.
#
# The stock `postgres:16` image ships pg_stat_statements (it is a contrib
# module) but NOT hypopg. PgLens needs both:
#   - pg_stat_statements : the data source for the whole product. Enabled at
#     server start via `shared_preload_libraries` (set in docker-compose.yml).
#   - hypopg             : hypothetical indexes, so a recommendation can be
#     validated against the real planner without building the index.
#
# hypopg is packaged by PGDG as `postgresql-16-hypopg`. The official postgres
# image is Debian-bookworm based and already has the PGDG apt repo configured,
# so we can install it directly on top.
FROM postgres:16-bookworm

# We deliberately do not pin the hypopg patch version: the base image pins
# PG_MAJOR=16, which keeps the extension ABI-matched, and we want the latest
# packaged fix for that major.
# hadolint ignore=DL3008
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends postgresql-16-hypopg; \
    rm -rf /var/lib/apt/lists/*
