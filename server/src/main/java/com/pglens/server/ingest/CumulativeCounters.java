package com.pglens.server.ingest;

/**
 * A snapshot of one query's <b>cumulative</b> {@code pg_stat_statements} counters, as sampled by
 * the agent. The server deltas these against the last persisted snapshot (ADR-0024). Reset
 * detection is carried separately (the per-batch global {@code
 * pg_stat_statements_info.stats_reset}) plus a per-query monotonicity guard — PG16's pgss has no
 * per-entry {@code stats_since}.
 *
 * <p>Pure domain type — no Spring, no gRPC, no I/O — so the delta logic unit-tests without a
 * container.
 */
public record CumulativeCounters(
    long calls, double totalExecTimeMs, long rows, long sharedBlksHit, long sharedBlksRead) {}
