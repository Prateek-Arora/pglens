package com.pglens.engine.confirm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Reading real statements from a .sql file and from real PG16 stderr logs (Phase 2.6, ADR-0042).
 * The two fixtures were captured from PG16 with {@code log_min_duration_statement = 0} and with
 * {@code log_statement = 'all'}: psql (simple protocol) and pgjdbc 42.7 (extended protocol, an
 * unnamed and a named statement, typed and NULL parameters).
 */
class StatementSourceTest {

  @Test
  void aSqlFileKeepsItsReadsInOrder() {
    StatementSource.Parsed p =
        StatementSource.parse("q.sql", "SELECT 1;\nUPDATE t SET a = 1;\nSELECT 'x;y' FROM t;\n");
    assertThat(p.statements())
        .extracting(WorkloadStatement::sql)
        .containsExactly("SELECT 1", "SELECT 'x;y' FROM t");
    assertThat(p.statements())
        .extracting(WorkloadStatement::origin)
        .containsExactly("q.sql #1", "q.sql #3");
    assertThat(p.skippedNotRead()).isEqualTo(1);
    assertThat(p.statements()).allMatch(s -> !s.hasParameters());
  }

  @Test
  void aDurationLogYieldsEachExecutedStatementOnce() {
    StatementSource.Parsed p = StatementSource.parse("pg.log", fixture("pg16-duration.log"));
    List<WorkloadStatement> s = p.statements();

    assertThat(s)
        .extracting(WorkloadStatement::sql)
        .containsExactly(
            "SELECT count(*) FROM t WHERE a = 5",
            "SELECT id,\n       b\n  FROM t\n WHERE b = 'multi\nline' AND a = 1",
            "SELECT $$dollar ; quoted$$ AS x FROM t WHERE id = 3",
            "SELECT b FROM t WHERE a = 7 AND id > 10",
            "SELECT b FROM t\n  WHERE b = 'it''s'   -- trailing comment\n  AND a = 3",
            "SELECT b FROM t WHERE a = $1 AND id > 10 LIMIT $2",
            "SELECT id FROM t WHERE b = $1 AND a IN (1, 2, 3)",
            "SELECT id FROM t WHERE b = $1 OR a = $2",
            "SELECT id FROM t WHERE b = $1 OR a = $2");
    assertThat(s.get(5).parameters()).containsExactly("'2'", "'5'");
    assertThat(s.get(6).parameters()).containsExactly("'it''s'");
    assertThat(s.get(7).parameters()).containsExactly("NULL", "'9'");
    assertThat(s.get(8).parameters()).containsExactly("NULL", "'10'");
    // the UPDATE and the driver's SET are not reads
    assertThat(p.skippedNotRead()).isEqualTo(3);
    assertThat(s.get(0).origin()).startsWith("pg.log:");
  }

  @Test
  void aLogStatementLogYieldsTheSameStatements() {
    StatementSource.Parsed p = StatementSource.parse("pg.log", fixture("pg16-statement.log"));
    assertThat(p.statements())
        .extracting(WorkloadStatement::sql)
        .containsExactly(
            "SELECT count(*) FROM t WHERE a = 5",
            "SELECT b FROM t WHERE a = 7 AND id > 10",
            "SELECT b FROM t\n  WHERE b = 'it''s'   -- trailing comment\n  AND a = 3",
            "SELECT b FROM t WHERE a = $1 AND id > 10 LIMIT $2",
            "SELECT id FROM t WHERE b = $1 AND a IN (1, 2, 3)");
    assertThat(p.statements().get(3).parameters()).containsExactly("'2'", "'5'");
  }

  @Test
  void placeholdersWithoutLoggedValuesAreSkippedAndCounted() {
    String log =
        "2026-09-25 10:00:00 UTC [1] LOG:  execute <unnamed>: SELECT * FROM t WHERE a = $1\n"
            + "2026-09-25 10:00:00 UTC [1] LOG:  execute fetch from S_1/C_2: SELECT * FROM t\n";
    StatementSource.Parsed p = StatementSource.parse("pg.log", log);
    assertThat(p.statements()).isEmpty();
    assertThat(p.skippedNoValues()).isEqualTo(1);
  }

  @Test
  void parsesParameterLiteralsWithQuotesCommasAndNewlines() {
    assertThat(StatementSource.parameters("$1 = 'a, b', $2 = NULL, $3 = 'it''s\nok', $4 = ''"))
        .containsExactly("'a, b'", "NULL", "'it''s\nok'", "''");
    assertThatThrownBy(() -> StatementSource.parameters("$2 = '1'"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aPlainSqlFileIsNotMistakenForALog() {
    assertThat(StatementSource.looksLikeLog("SELECT 'LOG:  statement: x';")).isFalse();
  }

  private static String fixture(String name) {
    try (InputStream in = StatementSourceTest.class.getResourceAsStream("/confirm/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
