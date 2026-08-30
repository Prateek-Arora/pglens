package com.pglens.server.trend;

import java.time.Instant;

/**
 * A query that appeared for the first time in the recent window — no {@code query_stats} row exists
 * for it before the window — and whose total time in the window crosses the "slow" floor. This is
 * the honest definition of "new": genuinely absent from the persisted history, not merely quiet in
 * the prior window. {@code meanMs} is the window mean ({@code recentTotalMs / recentCalls}), {@code
 * null} if there were no calls.
 */
public record NewSlowQuery(
    long queryid,
    String normalizedText,
    Instant firstSeen,
    double recentTotalMs,
    long recentCalls,
    Double meanMs) {}
