package com.pglens.engine.model;

/**
 * One row from {@code pg_stat_statements}: the normalized statement text plus its aggregated stats,
 * already filtered to top-level statements in the target database. Times are milliseconds.
 *
 * <p>Pure model type — no Spring, no I/O.
 */
public record StatementStat(
    long queryId,
    String query,
    boolean truncated,
    long calls,
    double totalExecTimeMs,
    double meanExecTimeMs,
    long rows,
    long sharedBlksHit,
    long sharedBlksRead) {}
