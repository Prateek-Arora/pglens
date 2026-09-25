package com.pglens.engine.confirm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Matching by pg_stat_statements' normalized text (ADR-0042; texts from the Step 0 spike). */
class QueryTextTest {

  @Test
  void aReplayedPreparedStatementMatchesTheApplicationsText() {
    assertThat(
            QueryText.matches(
                "SELECT b FROM t WHERE a = $1 AND id > $3 LIMIT $2",
                false,
                "PREPARE pglens_s12 AS SELECT b FROM t WHERE a = $1 AND id > $3 LIMIT $2"))
        .isTrue();
  }

  @Test
  void whitespaceDifferencesDontMatterButConstantNumberingDoes() {
    assertThat(
            QueryText.matches(
                "SELECT b\n  FROM t WHERE a = $1", false, "SELECT b FROM t WHERE a = $1;"))
        .isTrue();
    assertThat(
            QueryText.matches(
                "SELECT b FROM t WHERE a = $1 AND id > $3 LIMIT $2",
                false,
                "SELECT b FROM t WHERE a = $1 AND id > $2 LIMIT $3"))
        .isFalse();
  }

  @Test
  void aTruncatedReportTextMatchesAsAPrefix() {
    assertThat(
            QueryText.matches(
                "SELECT a, b, c FROM t WH", true, "SELECT a, b, c FROM t WHERE x = $1"))
        .isTrue();
    assertThat(
            QueryText.matches(
                "SELECT a, b, c FROM t WH", false, "SELECT a, b, c FROM t WHERE x = $1"))
        .isFalse();
  }

  @Test
  void onlyPgLensPrepareNamesAreStripped() {
    assertThat(QueryText.canonical("PREPARE app_stmt AS SELECT 1"))
        .isEqualTo("PREPARE app_stmt AS SELECT 1");
  }
}
