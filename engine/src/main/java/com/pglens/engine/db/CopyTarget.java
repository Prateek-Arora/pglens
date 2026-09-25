package com.pglens.engine.db;

import com.pglens.engine.PgLensException;
import com.pglens.engine.model.ConnectionTarget;
import com.pglens.engine.model.TargetInfo;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;

/**
 * The scratch copy {@code pglens confirm} builds indexes on (Phase 2.6, ADR-0042) — the only
 * database PgLens ever writes to — and the guard that makes sure it <em>is</em> one. {@link #open}
 * refuses, with the fix in the message, unless:
 *
 * <ul>
 *   <li>the database carries the opt-in marker {@code pglens.scratch = 'on'}, set on the database
 *       itself ({@code ALTER DATABASE … SET}, read from {@code pg_db_role_setting}) — a {@code SET}
 *       or a connection option doesn't count. Placeholder settings aren't in {@code pg_settings},
 *       so their source can't be checked there (verified, Step 0);
 *   <li>it isn't the scanned database (host, port and name, as the scan report recorded them);
 *   <li>it's a primary (a standby can't build an index), PG16+, with pg_stat_statements loaded and
 *       installed (statements are matched by its normalized text);
 *   <li>every table to index exists and the user owns it ({@code CREATE INDEX} needs the owner).
 * </ul>
 *
 * <p>The monitored database is never touched here: {@code confirm} doesn't connect to it at all.
 */
public final class CopyTarget implements AutoCloseable {

  /** The setting that marks a database as a scratch copy. */
  public static final String MARKER = "pglens.scratch";

  static final String IDENTITY_SQL =
      "SELECT current_database(), pg_is_in_recovery(), current_setting('server_version'),"
          + " current_setting('server_version_num')::int";

  static final String MARKER_SQL =
      "SELECT EXISTS (SELECT 1 FROM pg_db_role_setting s CROSS JOIN LATERAL unnest(s.setconfig) c"
          + " WHERE s.setdatabase = (SELECT oid FROM pg_database WHERE datname = current_database())"
          + " AND s.setrole = 0 AND lower(c) = '"
          + MARKER
          + "=on')";

  static final String TABLE_SQL =
      "SELECT c.oid IS NOT NULL, coalesce(pg_has_role(c.relowner, 'USAGE'), false)"
          + " FROM (SELECT to_regclass(?) AS oid) r LEFT JOIN pg_class c ON c.oid = r.oid";

  private final Connection connection;
  private final TargetInfo info;

  private CopyTarget(Connection connection, TargetInfo info) {
    this.connection = connection;
    this.info = info;
  }

  /**
   * Connects to the copy and runs every guard. {@code scanned} is the scan report's target (to
   * refuse the scanned database itself); {@code tables} are the tables that will be indexed.
   */
  public static CopyTarget open(
      ConnectionTarget copy, TargetInfo scanned, Collection<String> tables) {
    Connection c = connect(copy);
    try {
      TargetInfo info = check(c, copy, scanned, tables);
      c.setAutoCommit(false);
      return new CopyTarget(c, info);
    } catch (SQLException e) {
      closeQuietly(c);
      throw new PgLensException("Could not check the copy: " + e.getMessage(), e);
    } catch (RuntimeException e) {
      closeQuietly(c);
      throw e;
    }
  }

  /** The copy's identity (host, port, database, server version). */
  public TargetInfo info() {
    return info;
  }

  Connection connection() {
    return connection;
  }

  /**
   * Drops indexes a previous, interrupted {@code confirm} left behind (named {@code
   * pglens_confirm_*}), and returns their names.
   */
  public List<String> dropLeftovers() {
    List<String> dropped = new ArrayList<>();
    try (Statement s = connection.createStatement()) {
      try (ResultSet rs =
          s.executeQuery(
              "SELECT format('%I.%I', n.nspname, c.relname) FROM pg_class c"
                  + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                  + " WHERE c.relkind = 'i' AND c.relname LIKE 'pglens\\_confirm\\_%'")) {
        while (rs.next()) {
          dropped.add(rs.getString(1));
        }
      }
      for (String name : dropped) {
        s.execute("DROP INDEX IF EXISTS " + name);
      }
      connection.commit();
    } catch (SQLException e) {
      rollbackQuietly();
      throw new PgLensException(
          "Could not drop leftover pglens_confirm_* indexes: " + e.getMessage());
    }
    return dropped;
  }

  void rollbackQuietly() {
    try {
      connection.rollback();
    } catch (SQLException ignored) {
      // the connection is going away or already clean
    }
  }

  @Override
  public void close() {
    closeQuietly(connection);
  }

  private static TargetInfo check(
      Connection c, ConnectionTarget copy, TargetInfo scanned, Collection<String> tables)
      throws SQLException {
    String database;
    boolean standby;
    String version;
    int versionNum;
    try (Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(IDENTITY_SQL)) {
      rs.next();
      database = rs.getString(1);
      standby = rs.getBoolean(2);
      version = rs.getString(3);
      versionNum = rs.getInt(4);
    }
    String where = "%s on %s:%d".formatted(database, copy.host(), copy.port());

    if (!marked(c)) {
      throw new PgLensException(
          ("Refusing to use %s: it isn't marked as a scratch copy. pglens confirm builds real"
                  + " indexes on the database it measures, so it only runs on a copy you marked."
                  + " If this is a copy you can change, run:%n  ALTER DATABASE %s SET %s = 'on';%n"
                  + "and try again.")
              .formatted(where, quoteIdent(database), MARKER));
    }
    if (sameAs(scanned, copy.host(), copy.port(), database)) {
      throw new PgLensException(
          ("Refusing to use %s: it is the database the scan report came from. Point --copy at a"
                  + " copy (a Neon branch, an Aurora clone, a restored snapshot or pg_dump).")
              .formatted(where));
    }
    if (standby) {
      throw new PgLensException(
          "Refusing to use %s: it is a standby (read-only replica), where no index can be built."
              .formatted(where));
    }
    if (versionNum < 160000) {
      throw new PgLensException(
          "The copy runs PostgreSQL %s; pglens confirm needs PostgreSQL 16 or newer."
              .formatted(version));
    }
    requirePgStatStatements(c, where);
    requireOwnedTables(c, where, tables);
    return new TargetInfo(copy.host(), copy.port(), database, version, List.of());
  }

  private static boolean marked(Connection c) throws SQLException {
    try (Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(MARKER_SQL)) {
      return rs.next() && rs.getBoolean(1);
    }
  }

  static boolean sameAs(TargetInfo scanned, String host, int port, String database) {
    if (scanned == null || scanned.host() == null || scanned.database() == null) {
      return false;
    }
    return scanned.host().equalsIgnoreCase(host)
        && scanned.database().equals(database)
        && (scanned.port() == null || scanned.port() == port);
  }

  private static void requirePgStatStatements(Connection c, String where) throws SQLException {
    try (Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery("SELECT 1 FROM pg_extension WHERE extname = 'pg_stat_statements'")) {
      if (!rs.next()) {
        throw new PgLensException(
            ("pg_stat_statements isn't installed in %s. pglens confirm matches your statements to"
                    + " the report's queries by its normalized text. Run CREATE EXTENSION"
                    + " pg_stat_statements; there (it must also be in shared_preload_libraries).")
                .formatted(where));
      }
    }
    try (Statement s = c.createStatement()) {
      s.executeQuery("SELECT count(*) FROM pg_stat_statements").close();
    } catch (SQLException e) {
      throw new PgLensException(
          ("pg_stat_statements can't be read in %s (%s). It must be in shared_preload_libraries on"
                  + " the copy's server.")
              .formatted(where, e.getMessage()));
    }
  }

  private static void requireOwnedTables(Connection c, String where, Collection<String> tables)
      throws SQLException {
    List<String> missing = new ArrayList<>();
    List<String> notOwned = new ArrayList<>();
    for (String table : tables) {
      try (PreparedStatement ps = c.prepareStatement(TABLE_SQL)) {
        ps.setString(1, table);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          if (!rs.getBoolean(1)) {
            missing.add(table);
          } else if (!rs.getBoolean(2)) {
            notOwned.add(table);
          }
        }
      }
    }
    if (!missing.isEmpty()) {
      throw new PgLensException(
          "These tables from the scan report don't exist in %s: %s. Is it a copy of the scanned database?"
              .formatted(where, String.join(", ", missing)));
    }
    if (!notOwned.isEmpty()) {
      throw new PgLensException(
          ("CREATE INDEX needs the table's owner, and the user connected to %s doesn't own: %s."
                  + " Connect to the copy as the owner (or a member of the owning role).")
              .formatted(where, String.join(", ", notOwned)));
    }
  }

  private static Connection connect(ConnectionTarget copy) {
    Properties props = new Properties();
    if (copy.user() != null) {
      props.setProperty("user", copy.user());
    }
    if (copy.password() != null) {
      props.setProperty("password", copy.password());
    }
    props.setProperty("ApplicationName", "pglens-confirm");
    // Never switch to named server-side statements: PgLens's own PREPAREs are the only ones, so
    // DEALLOCATE ALL after each replay is safe.
    props.setProperty("prepareThreshold", "0");
    try {
      return DriverManager.getConnection(copy.jdbcUrl(), props);
    } catch (SQLException e) {
      throw new PgLensException(
          "Could not connect to the copy at %s:%d/%s: %s"
              .formatted(copy.host(), copy.port(), copy.database(), e.getMessage()),
          e);
    }
  }

  static String quoteIdent(String name) {
    return name.matches("[a-z_][a-z0-9_$]*") ? name : '"' + name.replace("\"", "\"\"") + '"';
  }

  private static void closeQuietly(Connection c) {
    try {
      c.close();
    } catch (SQLException ignored) {
      // closing anyway
    }
  }
}
