package com.pglens.server.persistence;

import com.pglens.engine.hygiene.IndexHygieneFinding;
import com.pglens.engine.hygiene.IndexHygieneFinding.Kind;
import com.pglens.engine.hygiene.IndexScanWindow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads the {@code idx_scan} history the index-hygiene rule needs (ADR-0029) and persists its
 * findings. The scan window is the honest basis for "unused": how much an index's cumulative
 * counter grew between the first and last snapshot PgLens has recorded for it.
 */
@Repository
public class HygieneRepository {

  private final JdbcTemplate jdbc;

  public HygieneRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Builds the scan-growth window per index for {@code dbId}, keyed by lowercased index name. Only
   * indexes with at least two snapshots are included (one point can't show growth), and a window
   * whose counter went backwards is dropped (a stats reset — it can't be judged, so the index is
   * never called "unused"). {@code scanDelta == 0} over the window ⇒ unused.
   */
  public Map<String, IndexScanWindow> scanWindows(long dbId) {
    Map<String, IndexScanWindow> windows = new LinkedHashMap<>();
    jdbc.query(
        "SELECT index_name, "
            + "  (array_agg(idx_scan ORDER BY captured_at))[1]      AS first_scan, "
            + "  (array_agg(idx_scan ORDER BY captured_at DESC))[1] AS last_scan, "
            + "  min(captured_at) AS from_at, "
            + "  max(captured_at) AS to_at, "
            + "  count(*)         AS snapshots "
            + "FROM index_stats WHERE db_id = ? "
            + "GROUP BY index_name HAVING count(*) >= 2",
        rs -> {
          long first = rs.getLong("first_scan");
          long last = rs.getLong("last_scan");
          long delta = last - first;
          if (delta < 0) {
            return; // counter reset within the window — inconclusive, skip
          }
          windows.put(
              rs.getString("index_name"),
              new IndexScanWindow(
                  delta,
                  rs.getInt("snapshots"),
                  rs.getTimestamp("from_at").toInstant(),
                  rs.getTimestamp("to_at").toInstant()));
        },
        dbId);
    return windows;
  }

  /**
   * Replaces this db's hygiene findings with {@code findings} (derived state, recomputed each pass
   * — so a since-fixed index stops appearing). Call inside the analysis transaction.
   */
  public void replaceHygiene(long dbId, List<IndexHygieneFinding> findings) {
    jdbc.update("DELETE FROM index_hygiene WHERE db_id = ?", dbId);
    for (IndexHygieneFinding f : findings) {
      jdbc.update(
          "INSERT INTO index_hygiene "
              + "(db_id, index_name, table_name, kind, related_index, definition, reason) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?)",
          dbId,
          f.indexName(),
          f.table(),
          f.kind().name(),
          f.relatedIndex(),
          f.definition(),
          f.reason());
    }
  }

  /** Loads this db's persisted hygiene findings (for trends / the API / tests). */
  public List<IndexHygieneFinding> loadHygiene(long dbId) {
    return jdbc.query(
        "SELECT index_name, table_name, kind, related_index, definition, reason "
            + "FROM index_hygiene WHERE db_id = ? ORDER BY table_name, index_name",
        (rs, n) ->
            new IndexHygieneFinding(
                rs.getString("table_name"),
                rs.getString("index_name"),
                rs.getString("definition"),
                Kind.valueOf(rs.getString("kind")),
                rs.getString("related_index"),
                rs.getString("reason")),
        dbId);
  }
}
