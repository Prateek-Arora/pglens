package com.pglens.server.persistence;

import com.pglens.engine.model.CatalogSnapshot;
import com.pglens.engine.model.IndexInfo;
import com.pglens.engine.model.TableActivity;
import com.pglens.engine.model.TableInfo;
import com.pglens.proto.v1.IndexStat;
import com.pglens.proto.v1.TableStat;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/**
 * Persists the monitored DB's catalog (table row-estimates + existing indexes) and reloads it as
 * the engine's {@link CatalogSnapshot} so the scheduled analysis can run the pure detector
 * server-side (ADR-0028). The catalog is small and changes slowly, so each ingest <em>replaces</em>
 * this db's rows in one transaction — no stale, dropped-index rows linger to mislead detection.
 *
 * <p>The structural catalog (table estimates + index_catalog) is <em>replaced</em> each ingest. The
 * cumulative {@code idx_scan} counters are a time-series instead — {@link #recordIndexScans}
 * appends them to {@code index_stats} so hygiene can delta them over a window (ADR-0029), and the
 * per-table read/write counters go to {@code table_stats} the same way (write load, ADR-0038).
 */
@Repository
public class CatalogRepository {

  private final JdbcTemplate jdbc;

  public CatalogRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Replaces this db's persisted catalog with {@code catalog}. Call inside the ingest transaction.
   */
  public void replaceCatalog(long dbId, com.pglens.proto.v1.CatalogSnapshot catalog) {
    jdbc.update("DELETE FROM table_catalog WHERE db_id = ?", dbId);
    jdbc.update("DELETE FROM index_catalog WHERE db_id = ?", dbId);

    for (TableStat t : catalog.getTablesList()) {
      jdbc.update(
          "INSERT INTO table_catalog (db_id, table_name, est_rows) VALUES (?, ?, ?)",
          dbId,
          lower(t.getTableName()),
          t.getEstRows());
    }
    for (IndexStat ix : catalog.getIndexesList()) {
      jdbc.update(
          con -> {
            var ps =
                con.prepareStatement(
                    "INSERT INTO index_catalog (db_id, index_name, table_name, definition, "
                        + "is_unique, is_primary, constraint_backed, columns, method, predicate) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            ps.setLong(1, dbId);
            ps.setString(2, lower(ix.getIndexName()));
            ps.setString(3, lower(ix.getTableName()));
            ps.setString(4, ix.getDefinition()); // "" until Step 6
            ps.setBoolean(5, ix.getIsUnique());
            ps.setBoolean(6, ix.getIsPrimary());
            ps.setBoolean(7, ix.getConstraintBacked());
            Array cols = con.createArrayOf("text", ix.getColumnsList().toArray());
            ps.setArray(8, cols);
            ps.setString(9, blankToBtree(ix.getMethod()));
            ps.setString(10, ix.getPredicate().isEmpty() ? null : ix.getPredicate());
            return ps;
          });
    }
  }

  /**
   * Appends this snapshot's cumulative {@code idx_scan} per index to the {@code index_stats}
   * time-series at {@code capturedAt} (the server receive time). Idempotent on the natural key so a
   * retried batch can't double-insert. Call inside the ingest transaction. Hygiene later deltas the
   * first vs. last snapshot in the window to judge "unused" (ADR-0029).
   */
  public void recordIndexScans(
      long dbId, com.pglens.proto.v1.CatalogSnapshot catalog, Instant capturedAt) {
    Timestamp at = Timestamp.from(capturedAt);
    for (IndexStat ix : catalog.getIndexesList()) {
      jdbc.update(
          "INSERT INTO index_stats (db_id, index_name, captured_at, idx_scan) VALUES (?, ?, ?, ?) "
              + "ON CONFLICT (db_id, index_name, captured_at) DO NOTHING",
          dbId,
          lower(ix.getIndexName()),
          at,
          ix.getIdxScan());
    }
  }

  /**
   * Appends this snapshot's cumulative per-table read/write counters to the {@code table_stats}
   * time-series at {@code capturedAt} (ADR-0038) — the input to the write-load window. Idempotent
   * on the natural key. Call inside the ingest transaction.
   */
  public void recordTableStats(
      long dbId, com.pglens.proto.v1.CatalogSnapshot catalog, Instant capturedAt) {
    Timestamp at = Timestamp.from(capturedAt);
    for (TableStat t : catalog.getTablesList()) {
      jdbc.update(
          "INSERT INTO table_stats (db_id, table_name, captured_at, n_tup_ins, n_tup_upd, "
              + "n_tup_del, tuples_read) VALUES (?, ?, ?, ?, ?, ?, ?) "
              + "ON CONFLICT (db_id, table_name, captured_at) DO NOTHING",
          dbId,
          lower(t.getTableName()),
          at,
          t.getNTupIns(),
          t.getNTupUpd(),
          t.getNTupDel(),
          t.getTuplesRead());
    }
  }

  /** A table's read/write activity over the persisted window (first → last snapshot). */
  public record ActivityWindow(TableActivity activity, int snapshots, Instant from, Instant to) {}

  /**
   * The read/write activity window per table for {@code dbId}, keyed by lowercased table name: the
   * last snapshot minus the first. Tables with fewer than two snapshots are absent (one point shows
   * no activity), and so is a window where any counter went backwards — a stats reset makes it
   * inconclusive, never "no writes" (same rule as index hygiene, ADR-0029).
   */
  public Map<String, ActivityWindow> activityWindows(long dbId) {
    Map<String, ActivityWindow> windows = new LinkedHashMap<>();
    jdbc.query(
        "SELECT table_name, count(*) AS snapshots, min(captured_at) AS from_at, "
            + "  max(captured_at) AS to_at, "
            + "  (array_agg(n_tup_ins ORDER BY captured_at DESC))[1] "
            + "    - (array_agg(n_tup_ins ORDER BY captured_at))[1] AS ins, "
            + "  (array_agg(n_tup_upd ORDER BY captured_at DESC))[1] "
            + "    - (array_agg(n_tup_upd ORDER BY captured_at))[1] AS upd, "
            + "  (array_agg(n_tup_del ORDER BY captured_at DESC))[1] "
            + "    - (array_agg(n_tup_del ORDER BY captured_at))[1] AS del, "
            + "  (array_agg(tuples_read ORDER BY captured_at DESC))[1] "
            + "    - (array_agg(tuples_read ORDER BY captured_at))[1] AS rd "
            + "FROM table_stats WHERE db_id = ? "
            + "GROUP BY table_name HAVING count(*) >= 2",
        (RowCallbackHandler)
            rs -> {
              long ins = rs.getLong("ins");
              long upd = rs.getLong("upd");
              long del = rs.getLong("del");
              long read = rs.getLong("rd");
              if (ins < 0 || upd < 0 || del < 0 || read < 0) {
                return; // a counter went backwards — stats reset, window inconclusive
              }
              windows.put(
                  rs.getString("table_name"),
                  new ActivityWindow(
                      new TableActivity(ins, upd, del, read),
                      rs.getInt("snapshots"),
                      rs.getTimestamp("from_at").toInstant(),
                      rs.getTimestamp("to_at").toInstant()));
            },
        dbId);
    return windows;
  }

  /** Rebuilds the engine {@link CatalogSnapshot} for {@code dbId} from persisted rows. */
  public CatalogSnapshot load(long dbId) {
    Map<String, Long> estRows = new LinkedHashMap<>();
    jdbc.query(
        "SELECT table_name, est_rows FROM table_catalog WHERE db_id = ? ORDER BY table_name",
        (RowCallbackHandler)
            rs -> estRows.put(lower(rs.getString("table_name")), rs.getLong("est_rows")),
        dbId);

    Map<String, List<IndexInfo>> indexesByTable = new LinkedHashMap<>();
    jdbc.query(
        "SELECT index_name, table_name, is_unique, is_primary, constraint_backed, definition, "
            + "columns, method, predicate "
            + "FROM index_catalog WHERE db_id = ? ORDER BY table_name, index_name",
        (RowCallbackHandler)
            rs -> {
              String table = lower(rs.getString("table_name"));
              indexesByTable
                  .computeIfAbsent(table, k -> new ArrayList<>())
                  .add(
                      new IndexInfo(
                          table,
                          rs.getString("index_name"),
                          toColumns(rs.getArray("columns")),
                          rs.getBoolean("is_unique"),
                          rs.getBoolean("is_primary"),
                          rs.getString("method"),
                          rs.getString("predicate"),
                          rs.getString("definition"),
                          rs.getBoolean("constraint_backed"),
                          // idx_scan is time-series in index_stats, not a catalog fact; hygiene
                          // reads
                          // its window separately, so the reconstructed IndexInfo carries 0 here.
                          0L));
            },
        dbId);

    Map<String, TableInfo> tables = new LinkedHashMap<>();
    estRows.forEach(
        (table, reltuples) ->
            tables.put(
                table,
                new TableInfo(table, reltuples, indexesByTable.getOrDefault(table, List.of()))));
    return new CatalogSnapshot(tables);
  }

  private static List<String> toColumns(Array array) throws java.sql.SQLException {
    if (array == null) {
      return List.of();
    }
    List<String> cols = new ArrayList<>();
    for (Object o : (Object[]) array.getArray()) {
      if (o != null) {
        cols.add(lower(o.toString()));
      }
    }
    return cols;
  }

  private static String lower(String s) {
    return s == null ? null : s.toLowerCase(Locale.ROOT);
  }

  private static String blankToBtree(String method) {
    return method == null || method.isBlank() ? "btree" : method;
  }
}
