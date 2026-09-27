package com.pglens.engine.db;

import com.pglens.engine.model.ConnectionTarget;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
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

  /**
   * The guards, as connection <em>startup</em> options: read-only (charter safe-by-default;
   * ADR-0020) plus statement/lock timeouts. They are set when the backend starts, never with {@code
   * SET}: through a transaction-mode pooler a session {@code SET} would stay on a shared server
   * connection and make the application's own transactions read-only (measured on PgBouncer 1.24,
   * ADR-0053). A pooler instead rejects or drops these options, and {@link #verifySessionGuards}
   * refuses the connection.
   */
  static final String GUARD_OPTIONS =
      "-c default_transaction_read_only=on -c statement_timeout=30s -c lock_timeout=5s";

  private static final String APPLICATION_NAME = "pglens";
  // pgjdbc's extended protocol treats the literal $1 in EXPLAIN (GENERIC_PLAN) <text> as a bind
  // parameter and fails ("bind message supplies 0 parameters"); simple mode sends $N as text.
  private static final String QUERY_MODE = "simple";

  private DataSources() {}

  /**
   * Checks that PgLens's connection to a monitored database is guarded: read-only, and one server
   * session across statements (HypoPG's hypothetical indexes live in the session). Call it first,
   * and again whenever the connection may have been re-opened; it only reads. Throws a {@link
   * org.springframework.dao.DataAccessException} that says what to do when the connection goes
   * through a transaction-mode pooler (PgBouncer, Supabase port 6543, a Neon {@code -pooler} host)
   * or the guards are missing.
   */
  public static void verifySessionGuards(JdbcTemplate jdbc) {
    Map<String, Object> first;
    Map<String, Object> second;
    try {
      first = jdbc.queryForMap(SESSION_CHECK);
      second = jdbc.queryForMap(SESSION_CHECK);
    } catch (DataAccessException e) {
      String cause = String.valueOf(e.getMostSpecificCause().getMessage());
      if (cause.contains("unsupported startup parameter")) {
        throw new DataAccessResourceFailureException(POOLER_MESSAGE + " (" + cause + ")");
      }
      throw e;
    }
    boolean readOnly = "on".equals(first.get("ro")) && "on".equals(second.get("ro"));
    boolean oneSession = Objects.equals(first.get("pid"), second.get("pid"));
    if (!readOnly || !oneSession) {
      throw new DataAccessResourceFailureException(POOLER_MESSAGE);
    }
  }

  static final String POOLER_MESSAGE =
      "PgLens's read-only guard isn't in effect on this connection — it looks like a connection "
          + "pooler in transaction mode (PgBouncer, Supabase port 6543, a Neon '-pooler' host). "
          + "Connect PgLens directly to Postgres (or through a session-mode pool): its read-only "
          + "guard and HypoPG's hypothetical indexes live in one database session";

  private static final String SESSION_CHECK =
      introspection(
          "SELECT pg_backend_pid() AS pid, current_setting('transaction_read_only') AS ro");

  /**
   * PgLens's guarded connection to a monitored database: {@link #forScan} plus the read-only and
   * timeout {@link #GUARD_OPTIONS}. Every production read of a monitored database uses this.
   */
  public static SingleConnectionDataSource guarded(ConnectionTarget target) {
    SingleConnectionDataSource ds = forScan(target);
    Properties props = new Properties();
    props.putAll(connectionProperties());
    props.setProperty("options", GUARD_OPTIONS);
    ds.setConnectionProperties(props);
    return ds;
  }

  /**
   * A reusable single-connection {@link javax.sql.DataSource} for the given target, with no guards
   * — for setting up test databases. Monitored databases are read with {@link #guarded}.
   */
  public static SingleConnectionDataSource forScan(ConnectionTarget target) {
    SingleConnectionDataSource ds =
        new SingleConnectionDataSource(
            target.jdbcUrl(), target.user(), target.password(), /* suppressClose= */ true);
    ds.setConnectionProperties(connectionProperties());
    ds.setAutoCommit(true);
    return ds;
  }

  private static Properties connectionProperties() {
    Properties props = new Properties();
    props.setProperty("ApplicationName", APPLICATION_NAME);
    props.setProperty("preferQueryMode", QUERY_MODE);
    return props;
  }
}
