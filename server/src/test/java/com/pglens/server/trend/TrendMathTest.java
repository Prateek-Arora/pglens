package com.pglens.server.trend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The honesty boundaries of the derived trend numbers, tested with no database. The point of these
 * cases is not the arithmetic but the {@code null}s: a percent change against a zero prior and a
 * mean over zero calls are undefined, and PgLens reports them as absent, never as a fabricated
 * number (charter principle #1).
 */
class TrendMathTest {

  @Test
  void percentChangeIsTheRelativeMoveWhenThereIsARealPrior() {
    assertThat(TrendMath.percentChange(3000, 1000)).isCloseTo(200.0, within(1e-9)); // tripled
    assertThat(TrendMath.percentChange(500, 1000)).isCloseTo(-50.0, within(1e-9)); // halved
    assertThat(TrendMath.percentChange(1000, 1000)).isCloseTo(0.0, within(1e-9)); // unchanged
  }

  @Test
  void percentChangeIsNullWhenThereIsNoPriorLoadToDivideBy() {
    assertThat(TrendMath.percentChange(2500, 0)).isNull(); // newly active — no baseline
    assertThat(TrendMath.percentChange(0, 0)).isNull();
  }

  @Test
  void meanIsTotalOverCallsExceptWithZeroCalls() {
    assertThat(TrendMath.mean(2500, 50)).isCloseTo(50.0, within(1e-9));
    assertThat(TrendMath.mean(100, 0)).isNull(); // 0/0 is undefined, not 0
  }

  @Test
  void aWindowStartsOnTheUtcHourAtOrBeforeNowMinusItsLength() {
    Instant now = Instant.parse("2026-09-26T10:17:42Z");
    assertThat(TrendMath.windowStart(now, Duration.ofHours(24)))
        .isEqualTo(Instant.parse("2026-09-25T10:00:00Z"));
    assertThat(TrendMath.windowStart(Instant.parse("2026-09-26T10:00:00Z"), Duration.ofDays(7)))
        .isEqualTo(Instant.parse("2026-09-19T10:00:00Z")); // already on the hour: unchanged
  }
}
