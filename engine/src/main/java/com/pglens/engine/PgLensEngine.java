package com.pglens.engine;

import com.pglens.engine.candidate.IndexCandidateGenerator;
import com.pglens.engine.db.CatalogReader;
import com.pglens.engine.db.DataSources;
import com.pglens.engine.db.HypoPGValidator;
import com.pglens.engine.db.PlanCapturer;
import com.pglens.engine.db.StatsReader;
import com.pglens.engine.detect.AntiPatternDetector;
import com.pglens.engine.model.CatalogSnapshot;
import com.pglens.engine.model.ConnectionTarget;
import com.pglens.engine.model.Finding;
import com.pglens.engine.model.IndexCandidate;
import com.pglens.engine.model.PlanNode;
import com.pglens.engine.model.PlanSummary;
import com.pglens.engine.model.QueryReport;
import com.pglens.engine.model.RankBy;
import com.pglens.engine.model.RankedRecommendation;
import com.pglens.engine.model.Recommendation;
import com.pglens.engine.model.ScanReport;
import com.pglens.engine.model.StatementStat;
import com.pglens.engine.model.TargetInfo;
import com.pglens.engine.parse.PlanParser;
import com.pglens.engine.rank.Recommender;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Orchestrating facade for the PgLens analysis engine. Holds the single scan-scoped connection and
 * wires the I/O readers/validator to the pure analysis core: rank ({@link StatsReader}) → capture
 * ({@link PlanCapturer}) → parse ({@link PlanParser}) → detect ({@link AntiPatternDetector}) →
 * propose ({@link IndexCandidateGenerator}) → HypoPG-validate ({@link HypoPGValidator}) →
 * cross-query rank ({@link Recommender}). Construct one per scan, and close it.
 *
 * <p>Everything runs over one physical connection (HypoPG is session-local) under a session that is
 * enforced <b>read-only</b> at the database (writes are rejected, not merely discouraged) with
 * statement/lock timeouts — see {@link DataSources#applySessionGuards}. No index is ever created on
 * the target.
 */
public final class PgLensEngine implements AutoCloseable {

  private final ConnectionTarget target;
  private final SingleConnectionDataSource dataSource;
  private final JdbcTemplate jdbc;

  private final StatsReader statsReader;
  private final CatalogReader catalogReader;
  private final PlanCapturer planCapturer;
  private final PlanParser planParser;
  private final AntiPatternDetector detector;
  private final IndexCandidateGenerator candidateGenerator;
  private final HypoPGValidator validator;
  private final Recommender recommender;

  private PgLensEngine(ConnectionTarget target, SingleConnectionDataSource dataSource) {
    this.target = target;
    this.dataSource = dataSource;
    this.jdbc = new JdbcTemplate(dataSource);
    applySessionGuards();
    this.statsReader = new StatsReader(jdbc);
    this.catalogReader = new CatalogReader(jdbc);
    this.planCapturer = new PlanCapturer(jdbc);
    this.planParser = new PlanParser();
    this.detector = new AntiPatternDetector();
    this.candidateGenerator = new IndexCandidateGenerator();
    this.validator = new HypoPGValidator(jdbc);
    this.recommender = new Recommender();
  }

  /** Opens a scan-scoped, single-connection engine against the given target. */
  public static PgLensEngine connect(ConnectionTarget target) {
    return new PgLensEngine(target, DataSources.forScan(target));
  }

  /** The top {@code limit} slow statements, ranked and hygiene-filtered. */
  public List<StatementStat> topStatements(RankBy rankBy, int limit, long minCalls) {
    return statsReader.topStatements(rankBy, limit, minCalls);
  }

  /** Row-count estimates and existing indexes for the target's user tables. */
  public CatalogSnapshot readCatalog() {
    return catalogReader.read();
  }

  /**
   * The full scan: rank the slow statements, and for each capture its generic plan, detect
   * anti-patterns, propose candidate indexes, and HypoPG-validate them — then rank the surviving
   * recommendations across all queries. Every number is real or a labeled planner estimate.
   */
  public ScanReport scan(RankBy rankBy, int top, long minCalls) {
    List<StatementStat> stats = statsReader.topStatements(rankBy, top, minCalls);
    CatalogSnapshot catalog = catalogReader.read();

    List<QueryReport> queries = new ArrayList<>(stats.size());
    List<Recommender.Weighted> weighted = new ArrayList<>();
    for (StatementStat s : stats) {
      QueryReport qr = analyze(s, catalog);
      queries.add(qr);
      for (Recommendation r : qr.recommendations()) {
        weighted.add(new Recommender.Weighted(s.queryId(), s.totalExecTimeMs(), r));
      }
    }
    List<RankedRecommendation> topRecommendations = recommender.rank(weighted);

    return new ScanReport(
        ScanReport.SCHEMA_VERSION,
        Instant.now().toString(),
        targetInfo(),
        queries,
        topRecommendations,
        buildNotes());
  }

  /** Drill into a single statement by {@code pg_stat_statements} queryid, if it is present. */
  public Optional<QueryReport> explain(long queryId) {
    CatalogSnapshot catalog = catalogReader.read();
    return statsReader.findByQueryId(queryId).map(s -> analyze(s, catalog));
  }

  /**
   * One statement end-to-end: capture → detect → propose → validate. Shared by scan and explain.
   */
  private QueryReport analyze(StatementStat s, CatalogSnapshot catalog) {
    PlanNode plan =
        planCapturer.captureGenericPlanJson(s.query()).map(planParser::parse).orElse(null);
    List<Finding> findings = plan == null ? List.of() : detector.detect(plan, catalog);
    List<IndexCandidate> candidates = candidateGenerator.generate(findings);
    List<Recommendation> recommendations = validator.validate(s.query(), candidates);
    return new QueryReport(
        s.queryId(),
        s.query(),
        s.truncated(),
        s.calls(),
        s.totalExecTimeMs(),
        s.meanExecTimeMs(),
        plan != null,
        plan == null ? null : PlanSummary.genericPlan(plan),
        findings,
        recommendations);
  }

  /**
   * Target identity for the report header (host, database, server version, installed extensions).
   */
  public TargetInfo targetInfo() {
    return new TargetInfo(
        hostOf(target.jdbcUrl()),
        target.database(),
        safeString("SHOW server_version"),
        installedExtensions());
  }

  private List<String> installedExtensions() {
    try {
      return jdbc.queryForList(
          DataSources.INTROSPECTION_MARKER
              + "SELECT extname FROM pg_extension "
              + "WHERE extname IN ('hypopg', 'pg_stat_statements', 'vector') ORDER BY extname",
          String.class);
    } catch (DataAccessException e) {
      return List.of();
    }
  }

  private List<String> buildNotes() {
    List<String> notes = new ArrayList<>();
    notes.add(
        "Index cost deltas are HypoPG generic-plan planner estimates, not runtime measurements.");
    notes.add(
        "Generic-plan selectivity uses the planner's default assumptions and can overstate wins on "
            + "skewed columns; the real measured signal beside each is the query's "
            + "pg_stat_statements mean/total time.");
    notes.add(
        "GIN/GiST recommendations (jsonb, full-text, LIKE '%…%') are surfaced but not "
            + "planner-validated — HypoPG cannot simulate those access methods.");
    if (!validator.hypopgAvailable()) {
      notes.add(
          "hypopg is not installed on the target, so no recommendation could be planner-validated. "
              + "Run CREATE EXTENSION hypopg to enable validation.");
    }
    return notes;
  }

  private String safeString(String sql) {
    try {
      return jdbc.queryForObject(sql, String.class);
    } catch (DataAccessException e) {
      return null;
    }
  }

  private static String hostOf(String jdbcUrl) {
    try {
      String u = jdbcUrl.startsWith("jdbc:") ? jdbcUrl.substring("jdbc:".length()) : jdbcUrl;
      URI uri = new URI(u);
      return uri.getHost() != null ? uri.getHost() : "localhost";
    } catch (Exception e) {
      return "unknown";
    }
  }

  // First touch of the connection: enforce read-only + timeouts. A session-level SET sticks because
  // the whole scan reuses this one connection.
  private void applySessionGuards() {
    try {
      DataSources.applySessionGuards(jdbc);
    } catch (DataAccessException e) {
      dataSource.destroy();
      throw new PgLensException(
          "Cannot connect to the target database: " + e.getMostSpecificCause().getMessage(), e);
    }
  }

  /**
   * Safety probe: the number of hypothetical indexes currently live on the scan connection. HypoPG
   * indexes are session-local and reset after every candidate, so this is {@code 0} between and
   * after scans — a caller (or a test) can assert nothing leaked onto the target's session.
   */
  public long hypotheticalIndexCount() {
    Long n = jdbc.queryForObject("SELECT count(*) FROM hypopg()", Long.class);
    return n == null ? 0L : n;
  }

  @Override
  public void close() {
    dataSource.destroy();
  }
}
