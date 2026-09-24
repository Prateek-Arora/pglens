package com.pglens.engine.db;

import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Samples real values of one column from {@code pg_stats} for the value-range estimate (ADR-0038):
 * the {@value #MCV_SAMPLES} most common values (heaviest first, with their frequencies) plus the
 * histogram's median bound (a typical non-MCV value, frequency {@code null}).
 *
 * <p>Values are returned already quoted as SQL literals <b>by the server</b> ({@code
 * quote_literal}) — never concatenated in Java — so a hostile value can't break out of its literal.
 * They are real data and stay here at the edge: callers use them only to plan, never to report
 * (ADR-0038). Reading {@code pg_stats} needs only {@code SELECT} on the table, which the read-only
 * role has.
 */
public class ValueSampler {

  static final int MCV_SAMPLES = 3;

  /** One sampled value as a server-quoted literal, and its MCV frequency (null if typical). */
  public record SampledValue(String literal, Double frequency) {}

  // to_regclass resolves the table exactly as the query does (search_path), so the stats row is
  // the right schema's. Both halves are marked introspection so PgLens never ranks this read.
  private static final String SAMPLE_SQL =
      DataSources.introspection(
          """
          SELECT lit, freq FROM (
            SELECT quote_literal(m.v) AS lit, m.f::float8 AS freq
            FROM pg_stats s
            CROSS JOIN LATERAL unnest(s.most_common_vals::text::text[], s.most_common_freqs)
              AS m(v, f)
            WHERE s.tablename = ? AND s.attname = ?
              AND s.schemaname = (SELECT n.nspname FROM pg_class c
                                  JOIN pg_namespace n ON n.oid = c.relnamespace
                                  WHERE c.oid = to_regclass(?))
            ORDER BY m.f DESC
            LIMIT %d
          ) mcv
          UNION ALL
          SELECT quote_literal(h[(array_length(h, 1) + 1) / 2]), NULL::float8
          FROM (
            SELECT s.histogram_bounds::text::text[] AS h
            FROM pg_stats s
            WHERE s.tablename = ? AND s.attname = ?
              AND s.schemaname = (SELECT n.nspname FROM pg_class c
                                  JOIN pg_namespace n ON n.oid = c.relnamespace
                                  WHERE c.oid = to_regclass(?))
          ) hist
          WHERE h IS NOT NULL
          """
              .formatted(MCV_SAMPLES));

  private final JdbcTemplate jdbc;

  public ValueSampler(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Up to {@value #MCV_SAMPLES} MCVs + one typical value for {@code table.column}; empty when the
   * column has no statistics (never analyzed) or they can't be read.
   */
  public List<SampledValue> sample(String table, String column) {
    try {
      return jdbc.query(
          SAMPLE_SQL,
          (rs, n) ->
              new SampledValue(rs.getString("lit"), (Double) rs.getObject("freq", Double.class)),
          table,
          column,
          table,
          table,
          column,
          table);
    } catch (DataAccessException unreadable) {
      return List.of();
    }
  }
}
