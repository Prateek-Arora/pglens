package com.pglens.server.analysis;

import com.pglens.engine.candidate.IndexCandidateGenerator;
import com.pglens.engine.detect.AntiPatternDetector;
import com.pglens.engine.hygiene.IndexHygieneAnalyzer;
import com.pglens.engine.hygiene.IndexHygieneFinding;
import com.pglens.engine.hygiene.IndexScanWindow;
import com.pglens.engine.model.CatalogSnapshot;
import com.pglens.engine.model.Finding;
import com.pglens.engine.model.IndexCandidate;
import com.pglens.engine.model.IndexInfo;
import com.pglens.engine.model.PlanNode;
import com.pglens.engine.parse.PlanParser;
import com.pglens.server.persistence.AnalysisRepository;
import com.pglens.server.persistence.AnalysisRepository.AnalyzableQuery;
import com.pglens.server.persistence.CatalogRepository;
import com.pglens.server.persistence.HygieneRepository;
import com.pglens.server.persistence.MonitoredDb;
import com.pglens.server.persistence.MonitoredDbRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The scheduled analysis job (ADR-0028). It reuses the engine's <em>pure</em> half — {@link
 * PlanParser} → {@link AntiPatternDetector} → {@link IndexCandidateGenerator} — over each monitored
 * db's persisted plans + catalog, and enqueues the surviving candidates as validation jobs. HypoPG
 * validation itself happens at the agent edge (ADR-0023, Step 5b); this side only proposes.
 *
 * <p><b>Singleton across replicas.</b> The whole pass runs in one transaction that first takes a
 * <em>transaction-scoped</em> advisory lock ({@code pg_try_advisory_xact_lock}); a replica that
 * cannot take it simply skips this tick. Transaction-scoped is deliberate: the lock is pinned to
 * the transaction's connection and auto-released at commit, so it is correct under a pooled
 * DataSource (a session-scoped lock could be taken and released on different pooled connections).
 * Full stateless/stateful HPA separation is a Phase-5 concern; this is the honest down-payment.
 */
@Service
public class AnalysisService {

  private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

  /** Arbitrary but fixed key identifying "the PgLens analysis job" for the advisory lock. */
  static final long ANALYSIS_LOCK_KEY = 0x70676C656E73L; // "pglens" in ascii-hex

  private final TransactionTemplate tx;
  private final JdbcTemplate jdbc;
  private final MonitoredDbRepository monitoredDbs;
  private final CatalogRepository catalogs;
  private final AnalysisRepository analysis;
  private final HygieneRepository hygiene;

  // F1 knobs: don't re-validate a candidate whose recommendation is younger than this cooldown, and
  // prune terminal jobs older than the retention window — together they bound the work-queue.
  private final long revalidateAfterMs;
  private final long jobRetentionMs;

  // Reclaim knobs (ADR-0035): a LEASED job whose lease is older than lease-timeout-ms is reclaimed
  // (a crashed/throwing agent), and one reclaimed more than max-validation-attempts times is
  // dead-lettered. lease-timeout must be >> the longest legitimate validation batch (seconds), so a
  // merely-slow live validation is never reclaimed — 5 min default is generous.
  private final long leaseTimeoutMs;
  private final int maxValidationAttempts;

  // Pure, stateless engine components — no Spring, safe to hold as fields.
  private final PlanParser planParser = new PlanParser();
  private final AntiPatternDetector detector = new AntiPatternDetector();
  private final IndexCandidateGenerator candidateGenerator = new IndexCandidateGenerator();
  private final IndexHygieneAnalyzer hygieneAnalyzer = new IndexHygieneAnalyzer();

  public AnalysisService(
      TransactionTemplate tx,
      JdbcTemplate jdbc,
      MonitoredDbRepository monitoredDbs,
      CatalogRepository catalogs,
      AnalysisRepository analysis,
      HygieneRepository hygiene,
      @Value("${pglens.analysis.revalidate-after-ms:3600000}") long revalidateAfterMs,
      @Value("${pglens.analysis.job-retention-ms:604800000}") long jobRetentionMs,
      @Value("${pglens.analysis.lease-timeout-ms:300000}") long leaseTimeoutMs,
      @Value("${pglens.analysis.max-validation-attempts:5}") int maxValidationAttempts) {
    this.tx = tx;
    this.jdbc = jdbc;
    this.monitoredDbs = monitoredDbs;
    this.catalogs = catalogs;
    this.analysis = analysis;
    this.hygiene = hygiene;
    this.revalidateAfterMs = revalidateAfterMs;
    this.jobRetentionMs = jobRetentionMs;
    this.leaseTimeoutMs = leaseTimeoutMs;
    this.maxValidationAttempts = maxValidationAttempts;
  }

  /**
   * Scheduled entry point. The interval + initial delay are configurable ({@code
   * pglens.analysis.*}).
   */
  @org.springframework.scheduling.annotation.Scheduled(
      fixedDelayString = "${pglens.analysis.interval-ms:30000}",
      initialDelayString = "${pglens.analysis.initial-delay-ms:15000}")
  public void runScheduled() {
    try {
      run();
    } catch (RuntimeException e) {
      // Never let a failed pass kill the scheduler; the next tick retries.
      log.warn("analysis pass failed: {}", e.toString());
    }
  }

  /**
   * Runs one analysis pass across all monitored dbs and returns the number of validation jobs
   * enqueued (0 if another replica held the lock, or nothing new was found).
   */
  public int run() {
    Instant now = Instant.now();
    Instant revalidateBefore = now.minusMillis(revalidateAfterMs);
    Instant retentionCutoff = now.minusMillis(jobRetentionMs);
    Instant leaseCutoff = now.minusMillis(leaseTimeoutMs);
    Integer enqueued =
        tx.execute(
            status -> {
              if (!tryAnalysisLock()) {
                log.debug("analysis skipped — another replica holds the lock");
                return 0;
              }
              // Reclaim timed-out leases (crashed/throwing agent) and dead-letter poison candidates
              // before enqueuing (ADR-0035), then bound the queue by retention (F1).
              int reclaimed = analysis.reclaimStuckLeases(leaseCutoff, maxValidationAttempts);
              if (reclaimed > 0) {
                log.info("reclaimed/dead-lettered {} stuck validation lease(s)", reclaimed);
              }
              analysis.pruneTerminalJobs(retentionCutoff);
              int total = 0;
              for (MonitoredDb db : monitoredDbs.findAll()) {
                total += analyzeDb(db, revalidateBefore);
              }
              return total;
            });
    return enqueued == null ? 0 : enqueued;
  }

  private int analyzeDb(MonitoredDb db, Instant revalidateBefore) {
    CatalogSnapshot catalog = catalogs.load(db.id());
    int enqueued = 0;
    for (AnalyzableQuery q : analysis.planCapturedQueries(db.id())) {
      PlanNode plan = parseOrNull(db, q);
      if (plan == null) {
        continue;
      }
      List<Finding> findings = detector.detect(plan, catalog);
      List<IndexCandidate> candidates = candidateGenerator.generate(findings);
      for (IndexCandidate candidate : candidates) {
        enqueued +=
            analysis.enqueue(
                db.id(),
                q.queryid(),
                q.normalizedText(),
                candidate.ddl(),
                candidate.accessMethod().name(),
                revalidateBefore);
      }
    }
    if (enqueued > 0) {
      log.info("analysis for db '{}' enqueued {} validation job(s)", db.name(), enqueued);
    }
    // Hygiene is independent of query plans — it runs over the same catalog + the idx_scan window
    // even when no query produced a candidate this pass.
    runHygiene(db, catalog);
    return enqueued;
  }

  /**
   * Runs the pure {@link IndexHygieneAnalyzer} over this db's existing indexes + their scan windows
   * (ADR-0029) and replaces the persisted findings. Guarded (unique/PK/FK/constraint) indexes are
   * never flagged — that invariant lives in the analyzer.
   */
  private void runHygiene(MonitoredDb db, CatalogSnapshot catalog) {
    List<IndexInfo> indexes =
        catalog.tables().values().stream().flatMap(t -> t.indexes().stream()).toList();
    Map<String, IndexScanWindow> windows = hygiene.scanWindows(db.id());
    List<IndexHygieneFinding> findings = hygieneAnalyzer.analyze(indexes, windows);
    hygiene.replaceHygiene(db.id(), findings);
    if (!findings.isEmpty()) {
      log.info("hygiene for db '{}' flagged {} index(es) for review", db.name(), findings.size());
    }
  }

  private PlanNode parseOrNull(MonitoredDb db, AnalyzableQuery q) {
    try {
      return planParser.parse(q.planJson());
    } catch (RuntimeException malformed) {
      // A stored plan that won't parse must not abort the whole pass — skip it, surfaced in the
      // log.
      log.warn(
          "skipping queryid {} on db '{}': plan JSON did not parse ({})",
          q.queryid(),
          db.name(),
          malformed.getMessage());
      return null;
    }
  }

  private boolean tryAnalysisLock() {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT pg_try_advisory_xact_lock(?)", Boolean.class, ANALYSIS_LOCK_KEY));
  }
}
