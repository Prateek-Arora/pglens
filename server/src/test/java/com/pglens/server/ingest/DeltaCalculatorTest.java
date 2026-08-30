package com.pglens.server.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.pglens.server.ingest.DeltaCalculator.Delta;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the delta engine (ADR-0024) — no DB, no Spring. Covers the branches that
 * corrupt every trend if wrong: first sample, normal delta, global reset, counter-regression guard,
 * and the derived-mean rule.
 */
class DeltaCalculatorTest {

  private final DeltaCalculator calc = new DeltaCalculator();

  private static CumulativeCounters counters(long calls, double totalMs, long rows) {
    return new CumulativeCounters(calls, totalMs, rows, 0L, 0L);
  }

  @Test
  void firstSampleEmitsNoDeltaRow() {
    Optional<Delta> delta = calc.delta(null, counters(100, 500.0, 1000), false);
    assertThat(delta).isEmpty();
  }

  @Test
  void normalDeltaSubtractsTheBaseline() {
    Delta delta =
        calc.delta(counters(100, 500.0, 1000), counters(150, 800.0, 1600), false).orElseThrow();

    assertThat(delta.afterReset()).isFalse();
    assertThat(delta.callsDelta()).isEqualTo(50);
    assertThat(delta.totalExecTimeDeltaMs()).isCloseTo(300.0, within(1e-9));
    assertThat(delta.rowsDelta()).isEqualTo(600);
  }

  @Test
  void derivedMeanIsTotalOverCalls() {
    Delta delta = calc.delta(counters(100, 500.0, 0), counters(150, 800.0, 0), false).orElseThrow();
    assertThat(delta.meanExecTimeMs()).isCloseTo(300.0 / 50, within(1e-9));
  }

  @Test
  void idleIntervalEmitsNoRow() {
    // Same counters as last sample → zero calls this interval → no query_stats row (F2). An idle
    // query still shows up in pg_stat_statements, so persisting its zero delta would append a
    // meaningless row every interval; the anchor still advances via the caller's upsert.
    Optional<Delta> delta =
        calc.delta(counters(100, 500.0, 1000), counters(100, 500.0, 1000), false);
    assertThat(delta).isEmpty();
  }

  @Test
  void derivedMeanIsNullForZeroCalls() {
    // The record's own rule (independent of whether such a row is ever persisted): 0/0 is NULL,
    // never a fabricated 0.0 (charter #1).
    assertThat(new Delta(0, 0.0, 0, 0, 0, false).meanExecTimeMs()).isNull();
  }

  @Test
  void globalResetMakesTheDeltaTheCurrentCumulative() {
    // pg_stat_statements_reset() happened between samples: counters restarted and accrued to
    // (30, 120). The interval delta is the post-reset activity — precise even if a hot query ran
    // MORE than its prior total (here it happens to be less, but the flag doesn't rely on that).
    Delta delta =
        calc.delta(counters(150, 800.0, 1600), counters(30, 120.0, 400), true).orElseThrow();

    assertThat(delta.afterReset()).isTrue();
    assertThat(delta.callsDelta()).isEqualTo(30);
    assertThat(delta.totalExecTimeDeltaMs()).isCloseTo(120.0, within(1e-9));
    assertThat(delta.rowsDelta()).isEqualTo(400);
  }

  @Test
  void resetAfterHeavyLoadIsStillCorrectWithTheFlag() {
    // The case a "counters went down" heuristic misses: a very hot query that, post-reset, ran more
    // than its whole prior history in one interval. The global-reset flag handles it precisely.
    Delta delta =
        calc.delta(counters(1_000, 5_000.0, 2_000), counters(1_500, 9_000.0, 3_000), true)
            .orElseThrow();

    assertThat(delta.afterReset()).isTrue();
    assertThat(delta.callsDelta()).isEqualTo(1_500); // the whole post-reset cumulative, not 500
  }

  @Test
  void counterRegressionWithoutAGlobalResetFlagIsStillTreatedAsReset() {
    // Defensive per-query guard: catches a per-entry eviction/targeted reset the global signal
    // misses. Counters went backwards with no global reset -> treat as reset, never a negative
    // delta.
    Delta delta =
        calc.delta(counters(150, 800.0, 1600), counters(40, 200.0, 300), false).orElseThrow();

    assertThat(delta.afterReset()).isTrue();
    assertThat(delta.callsDelta()).isEqualTo(40);
  }
}
