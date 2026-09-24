package com.pglens.engine.db;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the typed-literal rewrite that lets EXPLAIN parse pg_stat_statements' normalized
 * text (ADR-0021). Pure string logic — no database.
 */
class PlanCapturerTest {

  @Test
  void rewritesIntervalLiteralParamToACast() {
    assertThat(PlanCapturer.normalizeTypedLiterals("created_at >= now() - interval $1"))
        .isEqualTo("created_at >= now() - $1::interval");
  }

  @Test
  void rewritesTimestampAndDateLiterals() {
    assertThat(PlanCapturer.normalizeTypedLiterals("ts > timestamp $1 AND d = date $2"))
        .isEqualTo("ts > $1::timestamp AND d = $2::date");
  }

  @Test
  void prefersTheMultiWordTypeOverItsPrefix() {
    assertThat(PlanCapturer.normalizeTypedLiterals("ts = timestamp with time zone $1"))
        .isEqualTo("ts = $1::timestamp with time zone");
  }

  @Test
  void leavesOrdinaryParameterizedTextUnchanged() {
    String sql = "SELECT * FROM orders WHERE customer_id = $1 AND status = $2";
    assertThat(PlanCapturer.normalizeTypedLiterals(sql)).isEqualTo(sql);
  }

  @Test
  void doesNotTouchACastThatIsAlreadyValid() {
    String sql = "created_at >= now() - $1::interval";
    assertThat(PlanCapturer.normalizeTypedLiterals(sql)).isEqualTo(sql);
  }

  @Test
  void rewritesANormalizedExtractFieldToDatePart() {
    assertThat(
            PlanCapturer.normalizeForExplain(
                "SELECT extract($1 from o_orderdate) AS y, EXTRACT( $2 FROM l.shipdate) FROM t"))
        .isEqualTo("SELECT date_part($1, o_orderdate) AS y, date_part($2, l.shipdate) FROM t");
  }

  @Test
  void typesUntypedParameterArithmeticAsNumeric() {
    assertThat(PlanCapturer.normalizeForExplain("l_discount between $4 - $5 and $6 + $7"))
        .isEqualTo("l_discount between $4::numeric - $5::numeric and $6::numeric + $7::numeric");
  }

  @Test
  void leavesTypedArithmeticAndOtherParametersAlone() {
    // After the typed-literal rewrite, `date $1 + interval $2` is already typed — untouched.
    assertThat(PlanCapturer.normalizeForExplain("d < date $1 + interval $2 AND x = $10"))
        .isEqualTo("d < $1::date + $2::interval AND x = $10");
    assertThat(PlanCapturer.normalizeForExplain("a = $1 AND b > $2"))
        .isEqualTo("a = $1 AND b > $2");
    assertThat(PlanCapturer.normalizeForExplain(null)).isNull();
  }
}
