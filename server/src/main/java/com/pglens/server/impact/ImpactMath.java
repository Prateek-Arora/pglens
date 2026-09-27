package com.pglens.server.impact;

import com.pglens.server.trend.TrendMath;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Before/after arithmetic for an applied index (ADR-0051): the query's measured mean time per call
 * before PgLens first saw the index against the mean since. Pure — inputs are summed {@code
 * pg_stat_statements} deltas, and nothing is reported until both sides have enough calls.
 */
public final class ImpactMath {

  private ImpactMath() {}

  /** Each side of the comparison looks at most this far from the moment the index appeared. */
  public static final Duration WINDOW = Duration.ofDays(7);

  /** Calls needed on each side before a change is reported. */
  public static final long MIN_CALLS = 10;

  /** Whether a before/after change can be reported yet. */
  public enum Status {
    /** Enough calls on both sides: the change is reported. */
    MEASURED,
    /** Enough calls before, not yet after. */
    MEASURING,
    /** Too few calls before the index appeared (or that history is gone): nothing to compare. */
    NO_BASELINE
  }

  /** Summed measured activity on each side of the moment the index appeared. */
  public record BeforeAfter(long callsBefore, double msBefore, long callsAfter, double msAfter) {

    public @Nullable Double meanBefore() {
      return TrendMath.mean(msBefore, callsBefore);
    }

    public @Nullable Double meanAfter() {
      return TrendMath.mean(msAfter, callsAfter);
    }

    public Status status() {
      if (callsBefore < MIN_CALLS) {
        return Status.NO_BASELINE;
      }
      return callsAfter < MIN_CALLS ? Status.MEASURING : Status.MEASURED;
    }

    /**
     * {@code (after − before) / before} of the mean time per call — negative is faster — or null
     * until {@link Status#MEASURED}.
     */
    public @Nullable Double change() {
      Double before = meanBefore();
      Double after = meanAfter();
      if (status() != Status.MEASURED || before == null || after == null || before <= 0) {
        return null;
      }
      return (after - before) / before;
    }
  }
}
