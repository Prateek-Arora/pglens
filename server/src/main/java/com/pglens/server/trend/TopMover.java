package com.pglens.server.trend;

/**
 * A query whose total execution time changed the most between two adjacent windows (the recent
 * window vs. the equal-length window before it — "week over week" by default). {@code deltaMs =
 * recentTotalMs - priorTotalMs}; a positive delta is a regression (getting slower).
 *
 * <p>{@code pctChange} is {@code null} when there was no prior load to divide by ({@code
 * priorTotalMs <= 0}) — a query that is new or newly-active this window. That is honest: a ratio
 * against zero is not a real number, so callers rank on the absolute {@code deltaMs} instead (see
 * {@link TrendMath#percentChange}).
 */
public record TopMover(
    long queryid,
    String normalizedText,
    double recentTotalMs,
    double priorTotalMs,
    double deltaMs,
    Double pctChange) {}
