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
}
