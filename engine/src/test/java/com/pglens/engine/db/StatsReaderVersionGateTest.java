package com.pglens.engine.db;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pglens.engine.PgLensException;
import org.junit.jupiter.api.Test;

/** The PG16+ gate (ADR-0036): a pre-16 target is refused up front with an actionable message. */
class StatsReaderVersionGateTest {

  @Test
  void refusesPreSixteenWithTheReason() {
    assertThatThrownBy(() -> StatsReader.requireSupportedVersion(150008, "15.8"))
        .isInstanceOf(PgLensException.class)
        .hasMessageContaining("PostgreSQL 16 or newer")
        .hasMessageContaining("15.8")
        .hasMessageContaining("GENERIC_PLAN");
  }

  @Test
  void acceptsSixteenAndNewer() {
    assertThatCode(() -> StatsReader.requireSupportedVersion(160000, "16.0"))
        .doesNotThrowAnyException();
    assertThatCode(() -> StatsReader.requireSupportedVersion(180003, "18.3"))
        .doesNotThrowAnyException();
  }
}
