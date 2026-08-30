package com.pglens.server.trend;

import java.util.List;

/**
 * A single query's trend: its normalized text and the ordered series of interval samples (oldest
 * first). Answers "how has this query's mean_exec_time moved over time" — the per-query axis served
 * well by the {@code query_stats} primary key {@code (db_id, queryid, captured_at)}.
 */
public record QueryTrend(long queryid, String normalizedText, List<TrendPoint> points) {}
