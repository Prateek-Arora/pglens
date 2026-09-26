package com.pglens.explain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FormatsTest {

  @Test
  void durationsPickAReadableUnit() {
    assertThat(Formats.duration(13.2)).isEqualTo("13.2 ms");
    assertThat(Formats.duration(999.94)).isEqualTo("999.9 ms");
    assertThat(Formats.duration(16_411.4)).isEqualTo("16.4 s");
    assertThat(Formats.duration(153_257.9)).isEqualTo("153.3 s (about 2.6 min)");
    assertThat(Formats.duration(5_000_000)).isEqualTo("5,000.0 s (about 1.4 h)");
  }

  @Test
  void countsCostsAndPercents() {
    assertThat(Formats.count(14_830_778)).isEqualTo("14,830,778");
    assertThat(Formats.cost(9_760.6)).isEqualTo("9,761");
    assertThat(Formats.percent(0.5829349654634011)).isEqualTo("58.3%");
  }
}
