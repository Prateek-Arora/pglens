package com.pglens.engine.db;

import com.pglens.engine.model.ConnectionTarget;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Builds the single physical connection PgLens uses for a whole scan.
 *
 * <p>One connection matters because HypoPG hypothetical indexes are session-local (see memory:
 * hypopg-session-local) — create-index, re-EXPLAIN, and reset must share it. The connection is
 * tagged {@code application_name=pglens} so it is identifiable on the monitored server.
 */
public final class DataSources {

  /**
   * Comment that tags PgLens's own catalog-introspection queries. pg_stat_statements records it, so
   * the ranker's hygiene filter drops these from the leaderboard (ADR-0014, ADR-0021) — PgLens must
   * never rank its own queries. Apply it with {@link #introspection(String)}, never by prepending.
   */
  public static final String INTROSPECTION_MARKER = "/*pglens:introspection*/";

  private static final Pattern LEADING_KEYWORD = Pattern.compile("^\\s*[A-Za-z]+");

  /**
   * Tags an introspection query with {@link #INTROSPECTION_MARKER}, placed <em>after</em> its
   * leading keyword ({@code SELECT <marker> ...}). PG18's pg_stat_statements strips a leading
   * comment from the stored query text but keeps inner ones, so a prefixed marker silently stops
   * working there (verified on PG17 vs PG18; ADR-0036).
   */
  public static String introspection(String sql) {
    Matcher keyword = LEADING_KEYWORD.matcher(sql);
    if (!keyword.find()) {
      throw new IllegalArgumentException("Introspection SQL must start with a keyword: " + sql);
    }
    return sql.substring(0, keyword.end())
        + " "
        + INTROSPECTION_MARKER
        + sql.substring(keyword.end());
  }

  static final String READ_ONLY = "SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY";
  static final String STATEMENT_TIMEOUT = "SET statement_timeout = '30s'";
  static final String LOCK_TIMEOUT = "SET lock_timeout = '5s'";

  private static final String APPLICATION_NAME = "pglens";
  // pgjdbc's extended protocol treats the literal $1 in EXPLAIN (GENERIC_PLAN) <text> as a bind
  // parameter and fails ("bind message supplies 0 parameters"); simple mode sends $N as text.
  private static final String QUERY_MODE = "simple";

  private DataSources() {}

  /**
   * Enforces read-only at the database (charter safe-by-default; ADR-0020) plus statement/lock
   * timeouts on PgLens's single scan connection. Read-only makes the target itself reject any write
   * — HypoPG's create/re-EXPLAIN/reset still work under it. Session-level, so it sticks for the
   * whole scan. Throws {@link org.springframework.dao.DataAccessException} if the target rejects
   * it.
   */
  public static void applySessionGuards(JdbcTemplate jdbc) {
    jdbc.execute(READ_ONLY);
    jdbc.execute(STATEMENT_TIMEOUT);
    jdbc.execute(LOCK_TIMEOUT);
  }

  /** A reusable single-connection {@link javax.sql.DataSource} for the given target. */
  public static SingleConnectionDataSource forScan(ConnectionTarget target) {
    SingleConnectionDataSource ds =
        new SingleConnectionDataSource(
            target.jdbcUrl(), target.user(), target.password(), /* suppressClose= */ true);
    Properties props = new Properties();
    props.setProperty("ApplicationName", APPLICATION_NAME);
    props.setProperty("preferQueryMode", QUERY_MODE);
    ds.setConnectionProperties(props);
    ds.setAutoCommit(true);
    return ds;
  }
}
