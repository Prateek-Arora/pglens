package com.pglens.server.trend;

import java.time.Instant;

/**
 * One interval on a query's trend line — the persisted delta for a single {@code query_stats} row
 * (ADR-0024). {@code meanExecTimeMs} is the derived per-interval mean ({@code total_delta /
 * calls_delta}); it is {@code null} for an interval with no calls (0/0 is undefined, never
 * fabricated).
 */
public record TrendPoint(
    Instant capturedAt, Double meanExecTimeMs, long callsDelta, double totalExecTimeDeltaMs) {}
