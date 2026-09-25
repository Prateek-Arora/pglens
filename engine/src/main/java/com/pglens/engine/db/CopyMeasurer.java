package com.pglens.engine.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pglens.engine.confirm.StatementTiming;
import com.pglens.engine.confirm.WorkloadStatement;
import com.pglens.engine.model.AccessMethod;
import com.pglens.engine.model.IndexCandidate;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Replays the user's statements on the scratch copy and builds / drops PgLens's indexes there
 * (Phase 2.6, ADR-0042). The same method as the accuracy benchmarks ({@code
 * scripts/accuracy/measure.py}, ADR-0040), so the product and the benchmark agree: the server's own
 * settings, {@code EXPLAIN (ANALYZE, BUFFERS, TIMING OFF)}, one warm-up then the median of the
 * runs.
 *
 * <p>Every replay runs in its own transaction that starts {@code SET TRANSACTION READ ONLY} and
 * always rolls back, so a statement that tries to write (even a {@code SELECT} of a writing
 * function) is rejected by the database. The only writes are {@code CREATE INDEX} / {@code DROP
 * INDEX} of PgLens's own {@code pglens_confirm_<n>} indexes. A statement with {@code $N}
 * placeholders is replayed as {@code PREPARE pglens_sN AS …; EXECUTE pglens_sN(<logged values>)}
 * with {@code plan_cache_mode = force_custom_plan} — planned with its real values, as an unnamed
 * extended-protocol statement is — because that keeps pg_stat_statements' normalized text the same
 * as the application's (substituting the values into the text renumbers the constants; Step 0).
 */
public final class CopyMeasurer {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String IDENT = "(?:[A-Za-z_][A-Za-z0-9_$]*|\"(?:[^\"]|\"\")+\")";
  private static final Pattern TABLE = Pattern.compile(IDENT + "(?:\\." + IDENT + ")?");
  private static final Pattern COLUMN = Pattern.compile(IDENT);
  private static final String NAME_PREFIX = "pglens_confirm_";

  // Top-level rows only: with pg_stat_statements.track = all, PgLens's own EXPLAIN (VERBOSE)
  // records
  // the inner query as a nested row under the same queryid, with the EXPLAIN's text (found on the
  // demo, which runs track = all).
  static final String PGSS_TEXT_SQL =
      "SELECT query FROM pg_stat_statements WHERE queryid = ? AND toplevel"
          + " AND dbid = (SELECT oid FROM pg_database WHERE datname = current_database()) LIMIT 1";

  static final String EQUIVALENT_INDEX_SQL =
      "SELECT i.indexrelid::regclass::text FROM pg_index i"
          + " JOIN pg_class ic ON ic.oid = i.indexrelid JOIN pg_am am ON am.oid = ic.relam"
          + " WHERE i.indrelid = to_regclass(?) AND am.amname = ? AND i.indpred IS NULL"
          + " AND i.indexprs IS NULL AND (SELECT array_agg(a.attname::text ORDER BY k.ord)"
          + " FROM unnest(i.indkey) WITH ORDINALITY k(attnum, ord) JOIN pg_attribute a"
          + " ON a.attrelid = i.indrelid AND a.attnum = k.attnum WHERE k.ord <= i.indnkeyatts)"
          + " = ?::text[] LIMIT 1";

  private final CopyTarget copy;
  private final Connection c;
  private final Duration statementTimeout;
  private final Duration buildTimeout;
  private int preparedCount;

  public CopyMeasurer(CopyTarget copy, Duration statementTimeout, Duration buildTimeout) {
    this.copy = copy;
    this.c = copy.connection();
    this.statementTimeout = statementTimeout;
    this.buildTimeout = buildTimeout;
  }

  /** A statement's queryid on the copy, from {@code EXPLAIN (VERBOSE)} — plans it, runs nothing. */
  public long copyQueryId(WorkloadStatement s) throws SQLException {
    JsonNode doc = inReadOnly(s, "EXPLAIN (VERBOSE, FORMAT JSON) ");
    JsonNode id = doc.path("Query Identifier");
    if (id.isMissingNode()) {
      throw new SQLException("no Query Identifier (compute_query_id is off on the copy)", "55000");
    }
    return id.asLong();
  }

  /**
   * pg_stat_statements' normalized text for {@code queryId} on the copy. If the copy hasn't seen
   * the query yet, runs {@code s} once for real (read-only, rows streamed and discarded) so that it
   * records it — {@code EXPLAIN ANALYZE} alone isn't recorded as the inner query (Step 0).
   */
  public Optional<String> normalizedText(long queryId, WorkloadStatement s) throws SQLException {
    Optional<String> known = pgssText(queryId);
    if (known.isPresent()) {
      return known;
    }
    runOnce(s);
    return pgssText(queryId);
  }

  /** Warm-up, then the median warm execution time of {@code runs} runs; or why it failed. */
  public StatementTiming time(WorkloadStatement s, int runs) {
    try {
      executionMs(s); // warm-up
      List<Double> ms = new ArrayList<>(runs);
      for (int i = 0; i < runs; i++) {
        ms.add(executionMs(s));
      }
      Collections.sort(ms);
      return StatementTiming.measured(
          runs % 2 == 1 ? ms.get(runs / 2) : (ms.get(runs / 2 - 1) + ms.get(runs / 2)) / 2);
    } catch (SQLException e) {
      return failure(e);
    }
  }

  /** An existing index on the copy with exactly this key and access method, if any. */
  public Optional<String> equivalentIndex(IndexCandidate candidate) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(EQUIVALENT_INDEX_SQL)) {
      ps.setString(1, candidate.table());
      ps.setString(2, candidate.accessMethod().sqlUsing());
      Array cols = c.createArrayOf("text", candidate.columns().toArray());
      ps.setArray(3, cols);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
      }
    } finally {
      c.rollback();
    }
  }

  /** A built index: its qualified name, how long {@code CREATE INDEX} took, and its size. */
  public record Built(String name, long buildMs, long bytes) {}

  /**
   * Builds the candidate for real as {@code pglens_confirm_<rank>}. Throws with the database's
   * error message (without detail — a detail can quote a row) when it can't be built.
   */
  public Built build(IndexCandidate candidate, int rank) throws SQLException {
    requireIdentifiers(candidate);
    String name = NAME_PREFIX + rank;
    String using =
        candidate.accessMethod() == AccessMethod.BTREE
            ? ""
            : "USING " + candidate.accessMethod().sqlUsing() + " ";
    String ddl =
        "CREATE INDEX %s ON %s %s(%s)"
            .formatted(name, candidate.table(), using, String.join(", ", candidate.columns()));
    long start = System.nanoTime();
    try (Statement st = c.createStatement()) {
      st.execute("SET LOCAL statement_timeout = " + buildTimeout.toMillis());
      st.execute("SET LOCAL lock_timeout = '30s'");
      st.execute(ddl);
      c.commit();
    } catch (SQLException e) {
      c.rollback();
      throw e;
    }
    long buildMs = (System.nanoTime() - start) / 1_000_000;
    String qualified = qualifiedIndexName(candidate.table(), name);
    long bytes = 0;
    try (PreparedStatement ps = c.prepareStatement("SELECT pg_relation_size(to_regclass(?))")) {
      ps.setString(1, qualified);
      try (ResultSet rs = ps.executeQuery()) {
        bytes = rs.next() ? rs.getLong(1) : 0;
      }
    } finally {
      c.rollback();
    }
    return new Built(qualified, buildMs, bytes);
  }

  /** Drops an index {@link #build} created. */
  public void drop(Built built) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.execute("DROP INDEX IF EXISTS " + built.name());
      c.commit();
    } catch (SQLException e) {
      c.rollback();
      throw e;
    }
  }

  // ---------------------------------------------------------------------------------------------

  private double executionMs(WorkloadStatement s) throws SQLException {
    JsonNode doc = inReadOnly(s, "EXPLAIN (ANALYZE, BUFFERS, TIMING OFF, FORMAT JSON) ");
    return doc.path("Execution Time").asDouble();
  }

  // Runs "<explain> <statement>" (or "<explain> EXECUTE …" after a PREPARE) in a read-only
  // transaction that always rolls back, and returns the plan document.
  private JsonNode inReadOnly(WorkloadStatement s, String explain) throws SQLException {
    String prepared = null;
    try (Statement st = c.createStatement()) {
      begin(st);
      String sql;
      if (s.hasParameters()) {
        prepared = prepare(st, s);
        sql = "EXECUTE " + prepared + "(" + String.join(", ", s.parameters()) + ")";
      } else {
        sql = s.sql();
      }
      try (ResultSet rs = st.executeQuery(explain + sql)) {
        rs.next();
        return JSON.readTree(rs.getString(1)).get(0);
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        throw new SQLException("unreadable EXPLAIN output", "XX000", e);
      }
    } finally {
      end(prepared);
    }
  }

  // Executes the statement itself once (rows streamed in batches and discarded).
  private void runOnce(WorkloadStatement s) throws SQLException {
    String prepared = null;
    try (Statement st = c.createStatement()) {
      begin(st);
      st.setFetchSize(1000);
      String sql = s.sql();
      if (s.hasParameters()) {
        prepared = prepare(st, s);
        sql = "EXECUTE " + prepared + "(" + String.join(", ", s.parameters()) + ")";
      }
      if (st.execute(sql)) {
        try (ResultSet rs = st.getResultSet()) {
          while (rs.next()) {
            // discard
          }
        }
      }
    } finally {
      end(prepared);
    }
  }

  private void begin(Statement st) throws SQLException {
    st.execute("SET TRANSACTION READ ONLY"); // first statement: this transaction can't write
    st.execute("SET LOCAL statement_timeout = " + statementTimeout.toMillis());
    st.execute("SET LOCAL plan_cache_mode = force_custom_plan");
  }

  private String prepare(Statement st, WorkloadStatement s) throws SQLException {
    String name = "pglens_s" + (++preparedCount);
    st.execute("PREPARE " + name + " AS " + s.sql());
    return name;
  }

  // Always roll back; a PREPARE outlives its transaction, so deallocate it afterwards.
  private void end(String prepared) throws SQLException {
    c.rollback();
    if (prepared != null) {
      try (Statement st = c.createStatement()) {
        st.execute("DEALLOCATE ALL");
      } finally {
        c.rollback();
      }
    }
  }

  private Optional<String> pgssText(long queryId) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(PGSS_TEXT_SQL)) {
      ps.setLong(1, queryId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
      }
    } finally {
      c.rollback();
    }
  }

  private String qualifiedIndexName(String table, String index) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT format('%I.%I', n.nspname, ?::text) FROM pg_class c"
                + " JOIN pg_namespace n ON n.oid = c.relnamespace WHERE c.oid = to_regclass(?)")) {
      ps.setString(1, index);
      ps.setString(2, table);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : index;
      }
    } finally {
      c.rollback();
    }
  }

  // The DDL is built from the report file's table/column names: accept identifiers only, so a
  // crafted report can't smuggle SQL into the copy.
  static void requireIdentifiers(IndexCandidate candidate) throws SQLException {
    boolean ok =
        TABLE.matcher(candidate.table()).matches()
            && !candidate.columns().isEmpty()
            && candidate.columns().stream().allMatch(col -> COLUMN.matcher(col).matches());
    if (!ok) {
      throw new SQLException(
          "the report's table or column names aren't plain identifiers; not building it", "42602");
    }
  }

  static StatementTiming failure(SQLException e) {
    String state = e.getSQLState() == null ? "?" : e.getSQLState();
    if (state.equals("57014")) {
      return StatementTiming.failed(StatementTiming.Failure.TIMEOUT, state);
    }
    if (state.equals("25006")) {
      return StatementTiming.failed(StatementTiming.Failure.WRITE_REJECTED, state);
    }
    return StatementTiming.failed(StatementTiming.Failure.ERROR, state);
  }

  /** The copy this measurer writes to. */
  public CopyTarget copy() {
    return copy;
  }
}
