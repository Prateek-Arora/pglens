package com.pglens.engine.db;

import com.pglens.engine.model.IndexCandidate;
import com.pglens.engine.model.PlanNode;
import com.pglens.engine.model.Recommendation;
import com.pglens.engine.model.ValidationResult;
import com.pglens.engine.model.ValidationResult.Status;
import com.pglens.engine.parse.PlanParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

/**
 * The decisive gate (ADR-0005, ADR-0016): for each validatable candidate, create a hypothetical
 * HypoPG index, re-EXPLAIN (GENERIC_PLAN), and keep it only if the planner actually uses it AND the
 * total cost drops by at least the threshold — everything else is suppressed. This is what kills
 * false-positive recommendations; nothing is ever built on the real database.
 *
 * <p>Runs on the single scan connection (HypoPG is session-local) and resets after every candidate,
 * so none leak. GIN/GiST and a missing hypopg degrade to <em>not planner-validated</em> (never a
 * fabricated number), never a failed scan. Every cost is a generic-plan planner estimate.
 */
public class HypoPGValidator {

  /** Default minimum relative cost drop to accept a candidate (provisional; see plan/spike). */
  public static final double DEFAULT_MIN_RELATIVE_IMPROVEMENT = 0.15;

  private final JdbcTemplate jdbc;
  private final PlanCapturer capturer;
  private final PlanParser parser;
  private final double minRelativeImprovement;
  private final boolean hypopgAvailable;

  public HypoPGValidator(JdbcTemplate jdbc) {
    this(jdbc, DEFAULT_MIN_RELATIVE_IMPROVEMENT);
  }

  public HypoPGValidator(JdbcTemplate jdbc, double minRelativeImprovement) {
    this.jdbc = jdbc;
    this.capturer = new PlanCapturer(jdbc);
    this.parser = new PlanParser();
    this.minRelativeImprovement = minRelativeImprovement;
    this.hypopgAvailable = detectHypopg();
  }

  /** Whether hypopg is installed on the target (else all candidates degrade to not-validated). */
  public boolean hypopgAvailable() {
    return hypopgAvailable;
  }

  /**
   * Validates every candidate for one query against a single shared baseline plan. Returns one
   * {@link Recommendation} per candidate (validated, suppressed, or not-validated — never dropped).
   */
  public List<Recommendation> validate(String normalizedSql, List<IndexCandidate> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      return List.of();
    }
    reset(); // clean slate before the baseline
    Double baselineCost =
        capturer
            .captureGenericPlanJson(normalizedSql)
            .map(j -> parser.parse(j).totalCost())
            .orElse(null);

    List<Recommendation> out = new ArrayList<>();
    for (IndexCandidate candidate : candidates) {
      out.add(validateOne(normalizedSql, baselineCost, candidate));
    }
    return out;
  }

  private Recommendation validateOne(String sql, Double baselineCost, IndexCandidate candidate) {
    if (!hypopgAvailable) {
      return notValidated(
          candidate,
          "Not planner-validated — hypopg is not installed on the target "
              + "(run CREATE EXTENSION hypopg to validate).");
    }
    if (!candidate.plannerValidatable()) {
      return notValidated(candidate, candidate.notValidatableReason());
    }
    if (baselineCost == null) {
      return notValidated(
          candidate, "Not planner-validated — could not capture a baseline plan for this query.");
    }

    try {
      String hypoName = createHypotheticalIndex(candidate.ddl());
      if (hypoName == null) {
        return notValidated(candidate, "Not planner-validated — hypopg did not create the index.");
      }
      Optional<String> replanned = capturer.captureGenericPlanJson(sql);
      if (replanned.isEmpty()) {
        return notValidated(
            candidate, "Not planner-validated — could not re-plan with the hypothetical index.");
      }
      PlanNode plan = parser.parse(replanned.get());
      boolean used = usesIndex(plan, hypoName);
      double afterCost = plan.totalCost();
      double relative = baselineCost > 0 ? (baselineCost - afterCost) / baselineCost : 0.0;
      return verdict(candidate, baselineCost, afterCost, relative, used);
    } catch (DataAccessException unsupportedOrError) {
      // e.g. an access method HypoPG can't simulate — surface labeled, never crash the scan.
      return notValidated(
          candidate,
          "Not planner-validated — HypoPG rejected the index ("
              + unsupportedOrError.getMostSpecificCause().getMessage()
              + ").");
    } finally {
      reset(); // drop this candidate's hypothetical index before the next
    }
  }

  private Recommendation verdict(
      IndexCandidate candidate, double before, double after, double relative, boolean used) {
    if (used && relative >= minRelativeImprovement) {
      String label =
          "Planner-validated (HypoPG estimate): total cost %.0f → %.0f (−%.1f%%). "
                  .formatted(before, after, relative * 100)
              + "Estimate from the planner, not a runtime measurement.";
      return new Recommendation(
          candidate,
          new ValidationResult(Status.PLANNER_VALIDATED, before, after, relative, true, label));
    }
    String label =
        used
            ? "Suppressed: hypothetical index used but cost fell only %.1f%% (< %.0f%% threshold)."
                .formatted(relative * 100, minRelativeImprovement * 100)
            : "Suppressed: the planner did not use the hypothetical index — no benefit "
                + "(a legitimate full scan).";
    return new Recommendation(
        candidate, new ValidationResult(Status.SUPPRESSED, before, after, relative, used, label));
  }

  private Recommendation notValidated(IndexCandidate candidate, String label) {
    return new Recommendation(
        candidate,
        new ValidationResult(
            Status.NOT_PLANNER_VALIDATED,
            null,
            null,
            null,
            false,
            label == null ? "Not planner-validated." : label));
  }

  private String createHypotheticalIndex(String ddl) {
    // hypopg_create_index(text) parses the CREATE INDEX statement and returns (indexrelid,
    // indexname); it ignores our index name and assigns its own, which is what appears in EXPLAIN.
    String statement = ddl.strip().replaceAll(";\\s*$", "");
    return jdbc.query(
        "SELECT indexname FROM hypopg_create_index(?)",
        (ResultSetExtractor<String>) rs -> rs.next() ? rs.getString(1) : null,
        statement);
  }

  private static boolean usesIndex(PlanNode plan, String hypoIndexName) {
    return plan.flatten().stream().anyMatch(n -> hypoIndexName.equals(n.indexName()));
  }

  private boolean detectHypopg() {
    try {
      Integer n =
          jdbc.queryForObject(
              DataSources.INTROSPECTION_MARKER
                  + "SELECT count(*) FROM pg_extension WHERE extname = 'hypopg'",
              Integer.class);
      return n != null && n > 0;
    } catch (DataAccessException absent) {
      return false;
    }
  }

  private void reset() {
    if (!hypopgAvailable) {
      return;
    }
    try {
      jdbc.execute("SELECT hypopg_reset()");
    } catch (DataAccessException ignored) {
      // best-effort cleanup; a failed reset is not worth aborting the scan over
    }
  }
}
