package com.pglens.engine.db;

import com.pglens.engine.PgLensException;
import com.pglens.engine.model.CatalogSnapshot;
import com.pglens.engine.model.IndexInfo;
import com.pglens.engine.model.TableInfo;
import java.sql.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * Reads the table + index catalog the pure detector needs into a {@link CatalogSnapshot}. Under
 * GENERIC_PLAN there are no ANALYZE actuals, so this supplies row-count estimates ({@code
 * reltuples}) and the existing indexes as detection input — keeping the rules pure and
 * offline-testable.
 *
 * <p>Scope: ordinary user tables ({@code relkind = 'r'}) outside the system schemas.
 */
public class CatalogReader {

  private static final String TUPLES_SQL =
      DataSources.INTROSPECTION_MARKER
          + """
      SELECT c.relname AS table_name, c.reltuples::bigint AS reltuples
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE c.relkind = 'r'
        AND n.nspname NOT IN ('pg_catalog', 'information_schema')
      """;

  // Ordered key columns come from pg_get_indexdef(index, col, pretty): for a plain column it
  // returns
  // the column name, for an expression key the expression text. indkey subscripts are 0-based;
  // pg_get_indexdef's column number is 1-based, hence k + 1.
  //
  // Step 6 (hygiene) adds three columns: `definition` (the full pg_get_indexdef DDL, for display),
  // `constraint_backed_base` (TRUE when the index backs a PK/UNIQUE/EXCLUSION constraint — its OID
  // appears as some constraint's conindid), and cumulative `idx_scan` (pg_stat_user_indexes; 0 when
  // the view has no row yet). FK coverage is folded into constraint_backed afterwards, in Java.
  private static final String INDEX_SQL =
      DataSources.INTROSPECTION_MARKER
          + """
      SELECT t.relname AS table_name,
             i.relname AS index_name,
             ix.indisunique AS is_unique,
             ix.indisprimary AS is_primary,
             am.amname AS method,
             pg_get_expr(ix.indpred, ix.indrelid) AS predicate,
             pg_get_indexdef(ix.indexrelid) AS definition,
             EXISTS (SELECT 1 FROM pg_constraint con WHERE con.conindid = ix.indexrelid)
               AS constraint_backed_base,
             COALESCE(psui.idx_scan, 0) AS idx_scan,
             (SELECT array_agg(pg_get_indexdef(ix.indexrelid, k + 1, true) ORDER BY k)
                FROM generate_subscripts(ix.indkey, 1) AS k) AS columns
      FROM pg_index ix
      JOIN pg_class i ON i.oid = ix.indexrelid
      JOIN pg_class t ON t.oid = ix.indrelid
      JOIN pg_am am ON am.oid = i.relam
      JOIN pg_namespace n ON n.oid = t.relnamespace
      LEFT JOIN pg_stat_user_indexes psui ON psui.indexrelid = ix.indexrelid
      WHERE t.relkind = 'r'
        AND n.nspname NOT IN ('pg_catalog', 'information_schema')
      """;

  // Per-table FOREIGN KEY referencing-column lists (ordered). An index whose leading key columns
  // cover one of these lists speeds that FK's enforcement, so it must never be recommended for
  // removal — we fold that into constraint_backed. conkey holds the referencing attnums in FK
  // order.
  private static final String FK_SQL =
      DataSources.INTROSPECTION_MARKER
          + """
      SELECT t.relname AS table_name,
             (SELECT array_agg(a.attname ORDER BY x.ord)
                FROM unnest(con.conkey) WITH ORDINALITY AS x(attnum, ord)
                JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = x.attnum)
               AS fk_columns
      FROM pg_constraint con
      JOIN pg_class t ON t.oid = con.conrelid
      JOIN pg_namespace n ON n.oid = t.relnamespace
      WHERE con.contype = 'f'
        AND t.relkind = 'r'
        AND n.nspname NOT IN ('pg_catalog', 'information_schema')
      """;

  private final JdbcTemplate jdbc;

  public CatalogReader(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Snapshots row-count estimates and existing indexes for every user table. */
  public CatalogSnapshot read() {
    try {
      Map<String, Long> tuples = new LinkedHashMap<>();
      jdbc.query(
          TUPLES_SQL,
          (RowCallbackHandler)
              rs -> tuples.put(lower(rs.getString("table_name")), rs.getLong("reltuples")));

      Map<String, List<List<String>>> fkColumnsByTable = readForeignKeyColumns();

      Map<String, List<IndexInfo>> indexesByTable = new LinkedHashMap<>();
      jdbc.query(
          INDEX_SQL, (RowCallbackHandler) rs -> collectIndex(rs, indexesByTable, fkColumnsByTable));

      Map<String, TableInfo> tables = new LinkedHashMap<>();
      tuples.forEach(
          (table, reltuples) ->
              tables.put(
                  table,
                  new TableInfo(table, reltuples, indexesByTable.getOrDefault(table, List.of()))));
      return new CatalogSnapshot(tables);
    } catch (DataAccessException e) {
      throw new PgLensException(
          "Failed to read the table/index catalog: " + e.getMostSpecificCause().getMessage(), e);
    }
  }

  private Map<String, List<List<String>>> readForeignKeyColumns() {
    Map<String, List<List<String>>> byTable = new LinkedHashMap<>();
    jdbc.query(
        FK_SQL,
        (RowCallbackHandler)
            rs -> {
              String table = lower(rs.getString("table_name"));
              List<String> cols = toColumnList(rs.getArray("fk_columns"));
              if (!cols.isEmpty()) {
                byTable.computeIfAbsent(table, k -> new ArrayList<>()).add(cols);
              }
            });
    return byTable;
  }

  private static void collectIndex(
      java.sql.ResultSet rs,
      Map<String, List<IndexInfo>> byTable,
      Map<String, List<List<String>>> fkColumnsByTable)
      throws java.sql.SQLException {
    String table = lower(rs.getString("table_name"));
    List<String> cols = toColumnList(rs.getArray("columns"));
    boolean constraintBacked =
        rs.getBoolean("constraint_backed_base")
            || coversAnyForeignKey(cols, fkColumnsByTable.get(table));
    byTable
        .computeIfAbsent(table, k -> new ArrayList<>())
        .add(
            new IndexInfo(
                table,
                rs.getString("index_name"),
                cols,
                rs.getBoolean("is_unique"),
                rs.getBoolean("is_primary"),
                rs.getString("method"),
                rs.getString("predicate"),
                rs.getString("definition"),
                constraintBacked,
                rs.getLong("idx_scan")));
  }

  /** True if the index's key columns start with (cover) any of the table's FK column lists. */
  private static boolean coversAnyForeignKey(
      List<String> indexColumns, List<List<String>> foreignKeys) {
    if (foreignKeys == null) {
      return false;
    }
    for (List<String> fk : foreignKeys) {
      if (indexColumns.size() >= fk.size() && indexColumns.subList(0, fk.size()).equals(fk)) {
        return true;
      }
    }
    return false;
  }

  private static List<String> toColumnList(Array array) throws java.sql.SQLException {
    List<String> cols = new ArrayList<>();
    if (array != null) {
      for (Object o : (Object[]) array.getArray()) {
        if (o != null) {
          cols.add(lower(o.toString()));
        }
      }
    }
    return cols;
  }

  private static String lower(String s) {
    return s == null ? null : s.toLowerCase(Locale.ROOT);
  }
}
