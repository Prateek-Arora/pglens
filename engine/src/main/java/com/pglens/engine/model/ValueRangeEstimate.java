package com.pglens.engine.model;

/**
 * How a planner-validated index's estimated win varies with the real values a query can use
 * (ADR-0038). The generic plan assumes an average value; on a skewed column the heaviest common
 * value can gain far less. For each of the query's equality parameters PgLens samples the column's
 * most common values plus a typical (histogram-median) value from {@code pg_stats}, plans the query
 * with each, and reports the <b>worst</b> and <b>best</b> relative cost drop; {@code column} is the
 * {@code table.column} whose value produced the worst case. Every number is a HypoPG planner
 * estimate.
 *
 * <p>The sampled values themselves never appear here — they are real data (possibly PII) and stay
 * at the edge; only the worst value's <em>frequency</em> is carried ({@code null} when the worst
 * case was the non-MCV typical value). Pure model — no I/O.
 */
public record ValueRangeEstimate(
    String column,
    int valuesSampled,
    double worstRelativeDrop,
    Double worstValueFrequency,
    double bestRelativeDrop,
    String label) {}
