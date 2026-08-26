package com.pglens.engine.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RankByTest {

  @Test
  void mapsCliValues() {
    assertThat(RankBy.fromCli("total")).isEqualTo(RankBy.TOTAL_TIME);
    assertThat(RankBy.fromCli("mean")).isEqualTo(RankBy.MEAN_TIME);
    assertThat(RankBy.fromCli("calls")).isEqualTo(RankBy.CALLS);
  }

  @Test
  void isCaseInsensitiveAndTrims() {
    assertThat(RankBy.fromCli("  TOTAL ")).isEqualTo(RankBy.TOTAL_TIME);
  }

  @Test
  void defaultsToTotalForNull() {
    assertThat(RankBy.fromCli(null)).isEqualTo(RankBy.TOTAL_TIME);
  }

  @Test
  void rejectsUnknownValue() {
    assertThatThrownBy(() -> RankBy.fromCli("bogus")).isInstanceOf(IllegalArgumentException.class);
  }
}
