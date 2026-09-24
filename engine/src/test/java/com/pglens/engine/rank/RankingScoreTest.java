package com.pglens.engine.rank;

import static org.assertj.core.api.Assertions.assertThat;

import com.pglens.engine.model.ScoreBasis;
import org.junit.jupiter.api.Test;

/** Pure tests for the ranking drop and its basis (ADR-0017 amended by ADR-0038). */
class RankingScoreTest {

  @Test
  void usesTheWorstCaseAsAFloorWhenPresent() {
    RankingScore.Drop d = RankingScore.drop(0.987, 0.567);
    assertThat(d.value()).isEqualTo(0.567);
    assertThat(d.basis()).isEqualTo(ScoreBasis.VALUE_RANGE_FLOOR);
  }

  @Test
  void neverRanksAboveTheGenericFigure() {
    // A sampled value can beat the generic plan; the floor is still capped by it.
    assertThat(RankingScore.drop(0.6, 0.9).value()).isEqualTo(0.6);
  }

  @Test
  void fallsBackToGenericAndClampsAtZero() {
    RankingScore.Drop d = RankingScore.drop(0.4, null);
    assertThat(d.value()).isEqualTo(0.4);
    assertThat(d.basis()).isEqualTo(ScoreBasis.GENERIC_PLAN);
    assertThat(RankingScore.drop(null, null).value()).isZero();
    assertThat(RankingScore.drop(0.5, -0.2).value()).isZero();
  }
}
