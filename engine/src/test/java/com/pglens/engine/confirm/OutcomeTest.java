package com.pglens.engine.confirm;

import static org.assertj.core.api.Assertions.assertThat;

import com.pglens.engine.confirm.StatementTiming.Failure;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verdicts from measured timings (ADR-0042; thresholds from the benchmarks, ADR-0040). */
class OutcomeTest {

  private static Outcome.Pair pair(double before, double after) {
    return new Outcome.Pair(StatementTiming.measured(before), StatementTiming.measured(after));
  }

  @Test
  void thresholds() {
    assertThat(Verdict.of(0.15)).isEqualTo(Verdict.FASTER);
    assertThat(Verdict.of(0.149)).isEqualTo(Verdict.NO_REAL_EFFECT);
    assertThat(Verdict.of(-0.049)).isEqualTo(Verdict.NO_REAL_EFFECT);
    assertThat(Verdict.of(-0.05)).isEqualTo(Verdict.SLOWER);
  }

  @Test
  void theDropIsOverTheSumOfTimesNotAnAverageOfRatios() {
    // 100 → 10 and 1 → 5: most of the time is saved, although one statement got 5x slower.
    Outcome o = Outcome.of(List.of(pair(100, 10), pair(1, 5)), 300_000);
    assertThat(o.drop()).isCloseTo(1 - 15.0 / 101, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(o.verdict()).isEqualTo(Verdict.FASTER);
    assertThat(o.statements()).isEqualTo(2);
  }

  @Test
  void theJob10cCaseIsSlower() {
    // JOB 10c with cast_info (movie_id): 930.98 ms → 7,276.7 ms (ADR-0040).
    Outcome o = Outcome.of(List.of(pair(930.98, 7276.7)), 300_000);
    assertThat(o.verdict()).isEqualTo(Verdict.SLOWER);
    assertThat(o.drop()).isLessThan(-5);
  }

  @Test
  void aTimeoutAfterTheIndexCountsAsALowerBound() {
    Outcome slower =
        Outcome.of(
            List.of(
                new Outcome.Pair(
                    StatementTiming.measured(1_000),
                    StatementTiming.failed(Failure.TIMEOUT, "57014"))),
            300_000);
    assertThat(slower.verdict()).isEqualTo(Verdict.SLOWER);
    assertThat(slower.afterAtLeast()).isTrue();
    assertThat(slower.afterMs()).isEqualTo(300_000);

    // A lower bound can't prove "faster": 2 min saved elsewhere, but one statement timed out.
    Outcome unclear =
        Outcome.of(
            List.of(
                pair(500_000, 1_000),
                new Outcome.Pair(
                    StatementTiming.measured(1_000),
                    StatementTiming.failed(Failure.TIMEOUT, "57014"))),
            300_000);
    assertThat(unclear.verdict()).isEqualTo(Verdict.NOT_MEASURED);
  }

  @Test
  void failedBaselinesAreLeftOutAndNothingLeftIsNotMeasured() {
    Outcome o =
        Outcome.of(
            List.of(
                new Outcome.Pair(
                    StatementTiming.failed(Failure.WRITE_REJECTED, "25006"),
                    StatementTiming.measured(1))),
            300_000);
    assertThat(o.verdict()).isEqualTo(Verdict.NOT_MEASURED);
    assertThat(o.failed()).isEqualTo(1);
    assertThat(o.reason()).contains("tried to write");
    assertThat(Outcome.of(List.of(), 1).verdict()).isEqualTo(Verdict.NOT_MEASURED);
  }

  @Test
  void aFailureDescriptionNeverCarriesTheErrorText() {
    assertThat(StatementTiming.failed(Failure.ERROR, "22P02").describe())
        .isEqualTo("failed (SQLSTATE 22P02)");
  }
}
