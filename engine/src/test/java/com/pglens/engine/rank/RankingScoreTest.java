package com.pglens.engine.rank;

import static org.assertj.core.api.Assertions.assertThat;

import com.pglens.engine.model.ScoreBasis;
import org.junit.jupiter.api.Test;

/** Pure tests for the ranking drop and its basis (ADR-0017; floor ranking reverted by ADR-0041). */
class RankingScoreTest {

  @Test
  void ranksByTheGenericDrop() {
    RankingScore.Drop d = RankingScore.drop(0.987);
    assertThat(d.value()).isEqualTo(0.987);
    assertThat(d.basis()).isEqualTo(ScoreBasis.GENERIC_PLAN);
  }

  @Test
  void aMissingOrNegativeDropCountsAsZero() {
    assertThat(RankingScore.drop(null).value()).isZero();
    assertThat(RankingScore.drop(-0.2).value()).isZero();
  }
}
