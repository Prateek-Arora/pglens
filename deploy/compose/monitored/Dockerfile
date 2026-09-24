# Monitored demo Postgres for PgLens.
#
# The stock `postgres:16` image ships pg_stat_statements (it is a contrib
# module) but NOT hypopg. PgLens needs both:
#   - pg_stat_statements : the data source for the whole product. Enabled at
#     server start via `shared_preload_libraries` (set in docker-compose.yml).
#   - hypopg             : hypothetical indexes, so a recommendation can be
#     validated against the real planner without building the index.
#
# hypopg is packaged by PGDG as `postgresql-<major>-hypopg`. The official
# postgres image is Debian-bookworm based and already has the PGDG apt repo
# configured, so we can install it directly on top.
#
# PG_MAJOR defaults to 16 (the pinned dev/demo target, ADR-0011). CI's
# compatibility job rebuilds with --build-arg PG_MAJOR=17|18 to prove the engine
# on the majors real users run (ADR-0036). PgLens needs PG16+ (EXPLAIN GENERIC_PLAN).
ARG PG_MAJOR=16
FROM postgres:${PG_MAJOR}-bookworm

# Re-declare after FROM so the RUN below can see it.
ARG PG_MAJOR
# We deliberately do not pin the hypopg patch version: the base image pins
# PG_MAJOR, which keeps the extension ABI-matched, and we want the latest
# packaged fix for that major.
# hadolint ignore=DL3008
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends "postgresql-${PG_MAJOR}-hypopg"; \
    rm -rf /var/lib/apt/lists/*
