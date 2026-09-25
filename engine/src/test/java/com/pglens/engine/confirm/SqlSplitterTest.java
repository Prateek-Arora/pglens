package com.pglens.engine.confirm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Statement splitting that respects quoting and comments (Phase 2.6, ADR-0042). */
class SqlSplitterTest {

  @Test
  void splitsAtTopLevelSemicolonsOnly() {
    String sql =
        """
        SELECT 'a;b' FROM t;  -- a comment; with a semicolon
        SELECT "we;ird" FROM t WHERE x = $$dollar ; body$$;
        /* block; /* nested; */ still comment */ SELECT E'it\\'s;' ;
        SELECT $tag$ ; $tag$ , $1
        """;
    assertThat(SqlSplitter.split(sql))
        .containsExactly(
            "SELECT 'a;b' FROM t",
            "-- a comment; with a semicolon\nSELECT \"we;ird\" FROM t WHERE x = $$dollar ; body$$",
            "/* block; /* nested; */ still comment */ SELECT E'it\\'s;'",
            "SELECT $tag$ ; $tag$ , $1");
  }

  @Test
  void dropsBlankAndCommentOnlyPieces() {
    assertThat(SqlSplitter.split(" ; -- nothing\n ; /* x */ ;SELECT 1"))
        .containsExactly("SELECT 1");
  }

  @Test
  void doubledQuotesStayInsideTheLiteral() {
    assertThat(SqlSplitter.split("SELECT 'it''s; fine'; SELECT 2"))
        .containsExactly("SELECT 'it''s; fine'", "SELECT 2");
  }

  @Test
  void keepsOnlyStatementsThatRead() {
    assertThat(SqlSplitter.isRead("select 1")).isTrue();
    assertThat(SqlSplitter.isRead("  /* c */ -- x\n (SELECT 1) UNION (SELECT 2)")).isTrue();
    assertThat(SqlSplitter.isRead("WITH x AS (SELECT 1) SELECT * FROM x")).isTrue();
    assertThat(SqlSplitter.isRead("VALUES (1)")).isTrue();
    assertThat(SqlSplitter.isRead("TABLE t")).isTrue();
    assertThat(SqlSplitter.isRead("UPDATE t SET x = 1")).isFalse();
    assertThat(SqlSplitter.isRead("EXPLAIN SELECT 1")).isFalse();
    assertThat(SqlSplitter.isRead("SET work_mem = '1GB'")).isFalse();
    assertThat(SqlSplitter.isRead("-- only a comment")).isFalse();
  }
}
