package com.pglens.server.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.pglens.server.impact.ImpactMath.BeforeAfter;
import com.pglens.server.impact.ImpactMath.Status;
import org.junit.jupiter.api.Test;

class ImpactMathTest {

  @Test
  void aMeasuredChangeIsTheChangeInMeanTimePerCall() {
    // The fresh-clone demo run: 25 calls at 7.0 ms before, 40 at 3.8 ms after.
    BeforeAfter m = new BeforeAfter(25, 175.0, 40, 152.0);
    assertThat(m.status()).isEqualTo(Status.MEASURED);
    assertThat(m.meanBefore()).isEqualTo(7.0);
    assertThat(m.meanAfter()).isEqualTo(3.8);
    assertThat(m.change()).isCloseTo(-0.457, within(0.001));
  }

  @Test
  void nothingIsReportedUntilBothSidesHaveEnoughCalls() {
    BeforeAfter measuring = new BeforeAfter(25, 175.0, 9, 30.0);
    assertThat(measuring.status()).isEqualTo(Status.MEASURING);
    assertThat(measuring.change()).isNull();
    assertThat(measuring.meanAfter()).isNotNull();

    BeforeAfter noBaseline = new BeforeAfter(3, 21.0, 40, 152.0);
    assertThat(noBaseline.status()).isEqualTo(Status.NO_BASELINE);
    assertThat(noBaseline.change()).isNull();
  }

  @Test
  void noCallsMeansNoMeanNotZero() {
    BeforeAfter none = new BeforeAfter(0, 0, 0, 0);
    assertThat(none.meanBefore()).isNull();
    assertThat(none.meanAfter()).isNull();
    assertThat(none.status()).isEqualTo(Status.NO_BASELINE);
  }

  @Test
  void aSlowerQueryIsAPositiveChange() {
    assertThat(new BeforeAfter(20, 100.0, 20, 150.0).change()).isCloseTo(0.5, within(1e-9));
  }
}
