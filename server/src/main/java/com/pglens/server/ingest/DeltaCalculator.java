package com.pglens.server.ingest;

import java.util.Optional;

/**
 * Turns cumulative {@code pg_stat_statements} counters into per-interval deltas (ADR-0024). Pure
 * and DB-free, so every branch — first sample, normal delta, reset, counter regression — is
 * unit-testable with no container.
 *
 * <p>Correctness rules:
 *
 * <ul>
 *   <li><b>First sample</b> (no baseline yet): emit no delta row — the caller only establishes the
 *       {@code query_cumulative} anchor.
 *   <li><b>Reset</b>: signalled by {@code globalReset} — the per-batch {@code
 *       pg_stat_statements_info.stats_reset} advanced, so every counter restarted (a {@code
 *       pg_stat_statements_reset()}). The post-reset cumulative <i>is</i> the interval's activity,
 *       so the delta is the current counters. This is precise even for a very hot query that ran
 *       more than its whole prior history in one interval (which a "counters went down" heuristic
 *       would miss).
 *   <li><b>Normal</b>: delta = current − previous.
 *   <li><b>Defensive per-query guard</b>: a counter regression (which a global reset would already
 *       flag, but also catches a per-entry eviction/targeted reset that the global signal misses)
 *       is treated as a reset, so a delta is never negative.
 *   <li><b>Idle interval</b>: a delta with zero calls emits <b>no row</b>. An idle query still
 *       appears in {@code pg_stat_statements} every interval, so persisting its zero delta would
 *       append a meaningless row (calls_delta=0, mean NULL) to the time-series forever. The caller
 *       still advances the anchor (it upserts the cumulative unconditionally), so nothing is lost.
 * </ul>
 */
public final class DeltaCalculator {

  /** The per-interval delta to persist. {@code afterReset} is a diagnostic flag, not persisted. */
  public record Delta(
      long callsDelta,
      double totalExecTimeDeltaMs,
      long rowsDelta,
      long sharedBlksHitDelta,
      long sharedBlksReadDelta,
      boolean afterReset) {

    /** Derived interval mean; {@code null} when the query ran zero times this interval. */
    public Double meanExecTimeMs() {
      return callsDelta > 0 ? totalExecTimeDeltaMs / callsDelta : null;
    }
  }

  /**
   * Computes the interval delta for one query, or empty on the first sample (establish the anchor,
   * emit no row). {@code globalReset} is true when the monitored server's {@code
   * pg_stat_statements_info.stats_reset} advanced since the last batch.
   */
  public Optional<Delta> delta(
      CumulativeCounters previous, CumulativeCounters current, boolean globalReset) {
    if (previous == null) {
      return Optional.empty();
    }
    Delta delta =
        (globalReset || regressed(previous, current))
            ? new Delta(
                current.calls(),
                current.totalExecTimeMs(),
                current.rows(),
                current.sharedBlksHit(),
                current.sharedBlksRead(),
                true)
            : new Delta(
                current.calls() - previous.calls(),
                current.totalExecTimeMs() - previous.totalExecTimeMs(),
                current.rows() - previous.rows(),
                current.sharedBlksHit() - previous.sharedBlksHit(),
                current.sharedBlksRead() - previous.sharedBlksRead(),
                false);
    // Idle interval → no time-series row (the anchor still advances via the caller's upsert).
    return delta.callsDelta() == 0 ? Optional.empty() : Optional.of(delta);
  }

  // A counter can only grow within one stats window; a regression means a reset/eviction we weren't
  // told about globally — treat as a reset so the delta is never negative.
  private static boolean regressed(CumulativeCounters previous, CumulativeCounters current) {
    return current.calls() < previous.calls()
        || current.totalExecTimeMs() < previous.totalExecTimeMs();
  }
}
