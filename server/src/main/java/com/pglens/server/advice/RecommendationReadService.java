package com.pglens.server.advice;

import com.pglens.engine.hygiene.IndexHygieneFinding;
import com.pglens.engine.model.TableWriteLoad;
import com.pglens.server.persistence.HygieneRepository;
import com.pglens.server.persistence.MonitoredDb;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The read API's recommendation views (ADR-0044): {@link AdviceService}'s per-index advice with
 * string query ids, the indexes PgLens surfaced but could not planner-check (charter #6), index
 * hygiene (the drop side), and the best indexes across every database. Each response carries the
 * "planner-validated ≠ safe" caveat and the {@code pglens confirm} command ({@link Confirm}).
 */
@Service
public class RecommendationReadService {

  /** This index's planner-validated evidence for one query. */
  public record QueryEvidence(
      String queryid,
      double estimatedMsSaved,
      String scoreBasis,
      double plannerCostDropFraction,
      String rangeLabel) {}

  /**
   * One index to consider creating. {@code estimatedMsSaved} sums the per-query estimates, each
   * validated against this exact index. Not {@code actionable} when {@code redundantWith} names a
   * wider validated index that already serves all its queries.
   */
  public record IndexRecommendation(
      String database,
      String ddl,
      String table,
      String accessMethod,
      double estimatedMsSaved,
      boolean actionable,
      String redundantWith,
      String footprintLabel,
      String buildCaution,
      TableWriteLoad writeLoad,
      List<QueryEvidence> queries) {}

  /** An index PgLens suggests but HypoPG cannot simulate (GIN/GiST): no planner estimate exists. */
  public record NotPlannerValidated(
      String ddl, String accessMethod, String reason, List<String> queryids) {}

  public record Recommendations(
      String database,
      Confirm confirm,
      List<IndexRecommendation> recommended,
      List<NotPlannerValidated> notPlannerValidated) {}

  public record AcrossDatabases(Confirm confirm, List<IndexRecommendation> recommended) {}

  public record Hygiene(String database, List<IndexHygieneFinding> findings) {}

  private final AdviceService advice;
  private final HygieneRepository hygiene;
  private final JdbcTemplate jdbc;

  public RecommendationReadService(
      AdviceService advice, HygieneRepository hygiene, JdbcTemplate jdbc) {
    this.advice = advice;
    this.hygiene = hygiene;
    this.jdbc = jdbc;
  }

  public Recommendations forDatabase(MonitoredDb db) {
    return new Recommendations(
        db.name(), Confirm.INSTANCE, recommended(db), notPlannerValidated(db.id()));
  }

  /** The {@code limit} actionable indexes with the largest estimated saving, across databases. */
  public AcrossDatabases acrossDatabases(List<MonitoredDb> dbs, int limit) {
    List<IndexRecommendation> all = new ArrayList<>();
    for (MonitoredDb db : dbs) {
      recommended(db).stream().filter(IndexRecommendation::actionable).forEach(all::add);
    }
    all.sort(Comparator.comparingDouble(IndexRecommendation::estimatedMsSaved).reversed());
    return new AcrossDatabases(Confirm.INSTANCE, all.stream().limit(limit).toList());
  }

  public Hygiene hygiene(MonitoredDb db) {
    return new Hygiene(db.name(), hygiene.loadHygiene(db.id()));
  }

  private List<IndexRecommendation> recommended(MonitoredDb db) {
    return advice.advice(db.id()).stream()
        .map(
            a ->
                new IndexRecommendation(
                    db.name(),
                    a.ddl(),
                    a.table(),
                    a.accessMethod(),
                    a.estimatedMsSaved(),
                    a.actionable(),
                    a.redundantWith(),
                    a.footprintLabel(),
                    a.buildCaution(),
                    a.writeLoad(),
                    a.queries().stream()
                        .map(
                            q ->
                                new QueryEvidence(
                                    Long.toString(q.queryId()),
                                    q.estimatedMsSaved(),
                                    q.scoreBasis(),
                                    q.relativeDrop(),
                                    q.rangeLabel()))
                        .toList()))
        .toList();
  }

  private List<NotPlannerValidated> notPlannerValidated(long dbId) {
    return jdbc.query(
        "SELECT ddl, access_method, min(reason), array_agg(queryid ORDER BY queryid) "
            + "FROM recommendations WHERE db_id = ? AND status = 'NOT_PLANNER_VALIDATED' "
            + "GROUP BY ddl, access_method ORDER BY ddl",
        (rs, n) ->
            new NotPlannerValidated(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                java.util.Arrays.stream((Long[]) rs.getArray(4).getArray())
                    .map(String::valueOf)
                    .toList()),
        dbId);
  }
}
