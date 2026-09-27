package com.pglens.engine.db;

import com.pglens.engine.PgLensException;
import com.pglens.engine.model.AccessGap;
import com.pglens.engine.model.CatalogSnapshot;
import com.pglens.engine.model.IndexInfo;
import com.pglens.engine.model.SqlIdent;
import com.pglens.engine.model.TableActivity;
import com.pglens.engine.model.TableInfo;
import java.sql.Array;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * Reads the table + index catalog the pure detector needs into a {@link CatalogSnapshot}. Under
 * GENERIC_PLAN there are no ANALYZE actuals, so this supplies row-count estimates ({@code
 * reltuples}) and the existing indexes as detection input — keeping the rules pure and
 * offline-testable. It also carries each table's cumulative read/write counters ({@link
 * TableActivity}, from {@code pg_stat_user_tables}) for the write-load note (ADR-0038).
 *
 * <p>Scope: ordinary and partitioned user tables outside the system schemas, each named by its
 * identity ({@link SqlIdent#table}: schema-qualified unless {@code public}, quoted as needed;
 * ADR-0049). Column names are raw (unquoted, case preserved). A partition carries its tree's root;
 * the root gets its leaves' row estimates and activity summed.
 */
public class CatalogReader {

  private static final String TUPLES_SQL =
      DataSources.introspection(
          """
      SELECT n.nspname AS schema_name, c.relname AS table_name, c.relkind AS kind,
             c.reltuples::bigint AS reltuples,
             COALESCE(st.n_tup_ins, 0) AS n_tup_ins,
             COALESCE(st.n_tup_upd, 0) AS n_tup_upd,
             COALESCE(st.n_tup_del, 0) AS n_tup_del,
             COALESCE(st.seq_tup_read, 0) + COALESCE(st.idx_tup_fetch, 0) AS tuples_read,
             rn.nspname AS root_schema, rc.relname AS root_name
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
      LEFT JOIN pg_stat_user_tables st ON st.relid = c.oid
      LEFT JOIN pg_class rc ON c.relispartition AND rc.oid = pg_partition_root(c.oid)
      LEFT JOIN pg_namespace rn ON rn.oid = rc.relnamespace
      WHERE c.relkind IN ('r', 'p')
        AND n.nspname NOT IN ('pg_catalog', 'information_schema')
      """);

  // Ordered key columns come from pg_get_indexdef(index, col, pretty): for a plain column it
  // returns
  // the column name, for an expression key the expression text. indkey subscripts are 0-based;
  // pg_get_indexdef's column number is 1-based, hence k + 1.
  //
  // Step 6 (hygiene) adds three columns: `definition` (the full pg_get_indexdef DDL, for display),
  // `constraint_backed_base` (TRUE when the index backs a PK/UNIQUE/EXCLUSION constraint — its OID
  // appears as some constraint's conindid), and cumulative `idx_scan` (pg_stat_user_indexes; 0 when
  // the view has no row yet). FK coverage is folded into constraint_backed afterwards, in Java. An
  // index attached to a partitioned index can't be dropped on its own, so it counts as backed too;
  // the partitioned index itself (relkind 'I') is left out: it is never scanned (idx_scan stays 0),
  // and the rules look at the partitions it cascades to.
  private static final String INDEX_SQL =
      DataSources.introspection(
          """
      SELECT n.nspname AS schema_name,
             t.relname AS table_name,
             i.relname AS index_name,
             ix.indisunique AS is_unique,
             ix.indisprimary AS is_primary,
             am.amname AS method,
             pg_get_expr(ix.indpred, ix.indrelid) AS predicate,
             pg_get_indexdef(ix.indexrelid) AS definition,
             EXISTS (SELECT 1 FROM pg_constraint con WHERE con.conindid = ix.indexrelid)
               OR EXISTS (SELECT 1 FROM pg_inherits inh WHERE inh.inhrelid = ix.indexrelid)
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
      """);

  // Per-table FOREIGN KEY referencing-column lists (ordered). An index whose leading key columns
  // cover one of these lists speeds that FK's enforcement, so it must never be recommended for
  // removal — we fold that into constraint_backed. conkey holds the referencing attnums in FK
  // order.
  private static final String FK_SQL =
      DataSources.introspection(
          """
      SELECT n.nspname AS schema_name,
             t.relname AS table_name,
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
      """);

  // Per schema: its user tables, how many this role can't SELECT, and whether it has USAGE.
  private static final String ACCESS_SQL =
      DataSources.introspection(
          """
      SELECT n.nspname AS schema_name, count(*) AS tables,
             count(*) FILTER (WHERE NOT has_table_privilege(c.oid, 'SELECT')) AS no_select,
             has_schema_privilege(n.oid, 'USAGE') AS usage
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE c.relkind IN ('r', 'p') AND NOT c.relispartition
        AND n.nspname NOT IN ('pg_catalog', 'information_schema')
        AND n.nspname NOT LIKE 'pg\\_%'
      GROUP BY n.nspname, n.oid
      ORDER BY n.nspname
      """);

  private final JdbcTemplate jdbc;

  public CatalogReader(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * When this database's statistics were last reset ({@code pg_stat_database.stats_reset}) — the
   * start of the window the cumulative {@link TableActivity} counters cover. Empty if never reset.
   */
  public Optional<Instant> databaseStatsReset() {
    try {
      OffsetDateTime reset =
          jdbc.queryForObject(
              DataSources.introspection(
                  "SELECT stats_reset FROM pg_stat_database WHERE datname = current_database()"),
              OffsetDateTime.class);
      return Optional.ofNullable(reset).map(OffsetDateTime::toInstant);
    } catch (DataAccessException e) {
      return Optional.empty();
    }
  }

  /**
   * The schemas whose tables this role can't read (no {@code USAGE} on the schema, or no {@code
   * SELECT} on some table), so their queries can't be planned. Empty when the check itself can't
   * run — it only ever adds advice.
   */
  public List<AccessGap> accessGaps() {
    try {
      return jdbc
          .query(
              ACCESS_SQL,
              (rs, n) -> {
                boolean usage = rs.getBoolean("usage");
                int tables = rs.getInt("tables");
                return new AccessGap(
                    rs.getString("schema_name"),
                    usage ? rs.getInt("no_select") : tables,
                    tables,
                    usage);
              })
          .stream()
          .filter(g -> g.unreadable() > 0)
          .toList();
    } catch (DataAccessException unavailable) {
      return List.of();
    }
  }

  /** The role PgLens is connected as. */
  public String currentRole() {
    return jdbc.queryForObject(DataSources.introspection("SELECT current_user"), String.class);
  }

  /** Snapshots row-count estimates and existing indexes for every user table. */
  public CatalogSnapshot read() {
    try {
      Map<String, Long> tuples = new LinkedHashMap<>();
      Map<String, TableActivity> activity = new LinkedHashMap<>();
      Map<String, String> roots = new LinkedHashMap<>();
      List<String> leaves = new ArrayList<>();
      jdbc.query(
          TUPLES_SQL,
          (RowCallbackHandler)
              rs -> {
                String table =
                    SqlIdent.table(rs.getString("schema_name"), rs.getString("table_name"));
                boolean partitioned = "p".equals(rs.getString("kind"));
                tuples.put(table, partitioned ? 0L : rs.getLong("reltuples"));
                activity.put(
                    table,
                    partitioned
                        ? new TableActivity(0, 0, 0, 0)
                        : new TableActivity(
                            rs.getLong("n_tup_ins"),
                            rs.getLong("n_tup_upd"),
                            rs.getLong("n_tup_del"),
                            rs.getLong("tuples_read")));
                String rootName = rs.getString("root_name");
                if (rootName != null) {
                  roots.put(table, SqlIdent.table(rs.getString("root_schema"), rootName));
                  if (!partitioned) {
                    leaves.add(table);
                  }
                }
              });
      // A partitioned root holds no rows itself: give it its leaf partitions' totals.
      for (String leaf : leaves) {
        String root = roots.get(leaf);
        if (tuples.containsKey(root)) {
          tuples.merge(root, Math.max(0L, tuples.get(leaf)), Long::sum);
          activity.put(root, sum(activity.get(root), activity.get(leaf)));
        }
      }

      Map<String, List<List<String>>> fkColumnsByTable = readForeignKeyColumns();

      Map<String, List<IndexInfo>> indexesByTable = new LinkedHashMap<>();
      jdbc.query(
          INDEX_SQL, (RowCallbackHandler) rs -> collectIndex(rs, indexesByTable, fkColumnsByTable));

      Map<String, TableInfo> tables = new LinkedHashMap<>();
      tuples.forEach(
          (table, reltuples) ->
              tables.put(
                  table,
                  new TableInfo(
                      table,
                      reltuples,
                      indexesByTable.getOrDefault(table, List.of()),
                      activity.get(table),
                      roots.get(table))));
      return new CatalogSnapshot(tables);
    } catch (DataAccessException e) {
      throw new PgLensException(
          "Failed to read the table/index catalog: " + e.getMostSpecificCause().getMessage(), e);
    }
  }

  private static TableActivity sum(TableActivity a, TableActivity b) {
    return new TableActivity(
        a.inserted() + b.inserted(),
        a.updated() + b.updated(),
        a.deleted() + b.deleted(),
        a.tuplesRead() + b.tuplesRead());
  }

  private Map<String, List<List<String>>> readForeignKeyColumns() {
    Map<String, List<List<String>>> byTable = new LinkedHashMap<>();
    jdbc.query(
        FK_SQL,
        (RowCallbackHandler)
            rs -> {
              String table =
                  SqlIdent.table(rs.getString("schema_name"), rs.getString("table_name"));
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
    String schema = rs.getString("schema_name");
    String table = SqlIdent.table(schema, rs.getString("table_name"));
    List<String> cols = toColumnList(rs.getArray("columns"));
    boolean constraintBacked =
        rs.getBoolean("constraint_backed_base")
            || coversAnyForeignKey(cols, fkColumnsByTable.get(table));
    byTable
        .computeIfAbsent(table, k -> new ArrayList<>())
        .add(
            new IndexInfo(
                table,
                SqlIdent.table(schema, rs.getString("index_name")),
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

  /**
   * Key columns as raw names: a plain column (which {@code pg_get_indexdef} prints quoted when it
   * needs to be) is unquoted, an expression key is kept as its text.
   */
  private static List<String> toColumnList(Array array) throws java.sql.SQLException {
    List<String> cols = new ArrayList<>();
    if (array != null) {
      for (Object o : (Object[]) array.getArray()) {
        if (o != null) {
          String key = o.toString();
          cols.add(SqlIdent.isIdentifier(key) ? SqlIdent.unquote(key) : key);
        }
      }
    }
    return cols;
  }
}
