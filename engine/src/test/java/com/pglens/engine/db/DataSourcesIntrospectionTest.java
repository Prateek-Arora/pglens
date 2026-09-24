package com.pglens.engine.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The introspection marker must sit inside the statement, not before it: PG18's pg_stat_statements
 * drops a leading comment from the stored text, which would put PgLens's own queries back on its
 * leaderboard (ADR-0036).
 */
class DataSourcesIntrospectionTest {

  @Test
  void placesTheMarkerAfterTheLeadingKeyword() {
    assertThat(DataSources.introspection("SELECT count(*) FROM pg_class"))
        .isEqualTo("SELECT /*pglens:introspection*/ count(*) FROM pg_class");
  }

  @Test
  void keepsLeadingWhitespaceFromTextBlocks() {
    assertThat(DataSources.introspection("\n  SELECT 1"))
        .isEqualTo("\n  SELECT /*pglens:introspection*/ 1");
  }

  @Test
  void rejectsSqlThatDoesNotStartWithAKeyword() {
    assertThatThrownBy(() -> DataSources.introspection("/* x */ SELECT 1"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
