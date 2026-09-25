package com.pglens.server.advice;

import com.pglens.engine.hygiene.WriteLoad;
import com.pglens.engine.model.TableWriteLoad;
import com.pglens.server.persistence.CatalogRepository;
import com.pglens.server.persistence.CatalogRepository.ActivityWindow;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The per-index advice for one monitored db (ADR-0038): validated recommendations grouped by index,
 * with coverage-based redundancy and the table write-load over the persisted window. The service
 * seam the Phase-4 API will expose; today it is driven by the integration tests.
 */
@Service
public class AdviceService {

  private final JdbcTemplate jdbc;
  private final CatalogRepository catalogs;

  public AdviceService(JdbcTemplate jdbc, CatalogRepository catalogs) {
    this.jdbc = jdbc;
    this.catalogs = catalogs;
  }

  public List<IndexAdvice> advice(long dbId) {
    List<AdviceAssembler.ValidatedRow> rows =
        jdbc.query(
            "SELECT ddl, access_method, queryid, estimated_ms_saved, score_basis, relative_drop, "
                + "range_label, footprint_label, build_caution FROM recommendations "
                + "WHERE db_id = ? AND status = 'PLANNER_VALIDATED'",
            (rs, n) ->
                new AdviceAssembler.ValidatedRow(
                    rs.getString("ddl"),
                    rs.getString("access_method"),
                    rs.getLong("queryid"),
                    (Double) rs.getObject("estimated_ms_saved"),
                    rs.getString("score_basis"),
                    rs.getDouble("relative_drop"),
                    rs.getString("range_label"),
                    rs.getString("footprint_label"),
                    rs.getString("build_caution")),
            dbId);
    return AdviceAssembler.assemble(rows, writeLoad(dbId));
  }

  private Map<String, TableWriteLoad> writeLoad(long dbId) {
    Map<String, TableWriteLoad> out = new LinkedHashMap<>();
    catalogs
        .activityWindows(dbId)
        .forEach((table, w) -> out.put(table, WriteLoad.assess(table, w.activity(), phrase(w))));
    return out;
  }

  private static String phrase(ActivityWindow w) {
    double hours = Duration.between(w.from(), w.to()).toMillis() / 3_600_000.0;
    return String.format(Locale.US, "over the last %.1f h (%d snapshots)", hours, w.snapshots());
  }
}
