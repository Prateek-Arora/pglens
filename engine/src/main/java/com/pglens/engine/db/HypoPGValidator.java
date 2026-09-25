package com.pglens.engine.db;

import com.pglens.engine.candidate.BtreeEntryWidth;
import com.pglens.engine.estimate.EqualityParameterLocator;
import com.pglens.engine.estimate.ValueRanges;
import com.pglens.engine.model.AccessMethod;
import com.pglens.engine.model.IndexCandidate;
import com.pglens.engine.model.IndexFootprint;
import com.pglens.engine.model.PlanNode;
import com.pglens.engine.model.Recommendation;
import com.pglens.engine.model.ValidationResult;
import com.pglens.engine.model.ValidationResult.Status;
import com.pglens.engine.model.ValueRangeEstimate;
import com.pglens.engine.parse.PlanParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 *
 * <p>A planner-validated verdict also gets two pieces of evidence (ADR-0038): the index's {@link
 * IndexFootprint} (HypoPG size estimate vs the table's heap) and — when the query compares columns
 * to a {@code $N} by equality — a {@link ValueRangeEstimate}: the query re-planned with real {@code
 * pg_stats} values substituted for each such {@code $N}, without and with the index. The values are
 * used only for planning here at the edge and never leave it. A validated B-tree also gets a
 * catalog-only {@link BtreeEntryWidth} build caution when a key value could be too wide to index
 * (B17, ADR-0041).
 */
public class HypoPGValidator {

  /** At most this many of a query's equality parameters are varied (bounds extra planning). */
  static final int MAX_VARIED_PARAMS = 3;

  /** Default minimum relative cost drop to accept a candidate (provisional; see plan/spike). */
  public static final double DEFAULT_MIN_RELATIVE_IMPROVEMENT = 0.15;

  private final JdbcTemplate jdbc;
  private final PlanCapturer capturer;
  private final PlanParser parser;
  private final double minRelativeImprovement;
  private final boolean hypopgAvailable;
  private final ValueSampler sampler;

  public HypoPGValidator(JdbcTemplate jdbc) {
    this(jdbc, DEFAULT_MIN_RELATIVE_IMPROVEMENT);
  }

  public HypoPGValidator(JdbcTemplate jdbc, double minRelativeImprovement) {
    this.jdbc = jdbc;
    this.capturer = new PlanCapturer(jdbc);
    this.parser = new PlanParser();
    this.minRelativeImprovement = minRelativeImprovement;
    this.hypopgAvailable = detectHypopg();
    this.sampler = new ValueSampler(jdbc);
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
    PlanNode baseline = captureBaseline(normalizedSql);

    List<Recommendation> out = new ArrayList<>();
    for (IndexCandidate candidate : candidates) {
      out.add(
          new Recommendation(
              candidate,
              evaluate(
                  normalizedSql,
                  baseline,
                  candidate.ddl(),
                  candidate.plannerValidatable(),
                  candidate.notValidatableReason())));
    }
    return out;
  }

  /**
   * The a-pull edge path (ADR-0023): validate a single candidate expressed as its rendered DDL and
   * its access method — what the agent leases off the wire. Returns the raw {@link
   * ValidationResult} (the server maps it to a {@code ValidateResult}), never a fabricated number:
   * GIN/GiST, a missing hypopg, or a plan that can't be captured all degrade to
   * NOT_PLANNER_VALIDATED with a label.
   */
  public ValidationResult validateDdl(String normalizedSql, String ddl, AccessMethod accessMethod) {
    reset(); // clean slate before the baseline
    PlanNode baseline = captureBaseline(normalizedSql);
    boolean validatable = accessMethod.hypoPgSupported();
    String notValidatableReason =
        validatable
            ? null
            : "HypoPG cannot simulate a "
                + accessMethod.name()
                + " index — surfaced but not planner-validated.";
    return evaluate(normalizedSql, baseline, ddl, validatable, notValidatableReason);
  }

  private PlanNode captureBaseline(String normalizedSql) {
    return capturer.captureGenericPlanJson(normalizedSql).map(parser::parse).orElse(null);
  }

  private ValidationResult evaluate(
      String sql, PlanNode baseline, String ddl, boolean validatable, String notValidatableReason) {
    if (!hypopgAvailable) {
      return notValidated(
          "Not planner-validated — hypopg is not installed on the target "
              + "(run CREATE EXTENSION hypopg to validate).");
    }
    if (!validatable) {
      return notValidated(notValidatableReason);
    }
    if (baseline == null) {
      return notValidated(
          "Not planner-validated — could not capture a baseline plan for this query.");
    }
    double baselineCost = baseline.totalCost();

    ValidationResult result;
    IndexFootprint footprint = null;
    List<Variant> variants = List.of();
    List<Double> costsWithIndex = List.of();
    try {
      HypoIndex hypo = createHypotheticalIndex(ddl);
      if (hypo == null) {
        return notValidated("Not planner-validated — hypopg did not create the index.");
      }
      Optional<String> replanned = capturer.captureGenericPlanJson(sql);
      if (replanned.isEmpty()) {
        return notValidated(
            "Not planner-validated — could not re-plan with the hypothetical index.");
      }
      PlanNode plan = parser.parse(replanned.get());
      boolean used = usesIndex(plan, hypo.name());
      double afterCost = plan.totalCost();
      double relative = baselineCost > 0 ? (baselineCost - afterCost) / baselineCost : 0.0;
      result = verdict(baselineCost, afterCost, relative, used);
      if (result.isRecommended()) {
        // Evidence is gathered only for a validated index, while it still exists (ADR-0038).
        footprint =
            footprint(hypo, IndexCandidate.parseDdl(ddl).map(IndexCandidate::table).orElse(null));
        variants = variants(sql, baseline);
        costsWithIndex = planCosts(variants);
      }
    } catch (DataAccessException unsupportedOrError) {
      // e.g. an access method HypoPG can't simulate — surface labeled, never crash the scan.
      return notValidated(
          "Not planner-validated — HypoPG rejected the index ("
              + unsupportedOrError.getMostSpecificCause().getMessage()
              + ").");
    } finally {
      reset(); // drop this candidate's hypothetical index before the next
    }
    // The same variants, planned now that the hypothetical index is gone.
    ValueRangeEstimate range = valueRange(variants, planCosts(variants), costsWithIndex, result);
    ValidationResult withEvidence = result.withEvidence(range, footprint);
    return result.isRecommended()
        ? withEvidence.withBuildCaution(buildCaution(ddl).orElse(null))
        : withEvidence;
  }

  // --- build caution (B17, ADR-0041)
  // --------------------------------------------------------------

  private static final String KEY_COLUMN_SQL =
      DataSources.introspection(
          "SELECT bt.typname AS base_type, bt.typcategory AS category, "
              + "CASE WHEN t.typtype = 'd' THEN t.typtypmod ELSE a.atttypmod END AS typmod, "
              + "a.attstorage AS storage "
              + "FROM pg_attribute a "
              + "JOIN pg_type t ON t.oid = a.atttypid "
              + "JOIN pg_type bt ON bt.oid = "
              + "  CASE WHEN t.typtype = 'd' THEN t.typbasetype ELSE t.oid END "
              + "WHERE a.attrelid = to_regclass(?) AND a.attname = ? "
              + "  AND a.attnum > 0 AND NOT a.attisdropped");

  private static final String TOAST_SQL =
      DataSources.introspection(
          "SELECT CASE WHEN c.reltoastrelid = 0 THEN 0 "
              + "ELSE pg_relation_size(c.reltoastrelid) END AS toast_bytes "
              + "FROM pg_class c WHERE c.oid = to_regclass(?)");

  /**
   * A caution when a validated B-tree's key could hold a value too wide for a B-tree entry — from
   * the catalog only (column types + the table's TOAST size), never from user data. Empty for other
   * access methods, bounded columns, or when the catalog can't be read.
   */
  private Optional<String> buildCaution(String ddl) {
    Optional<IndexCandidate> parsed = IndexCandidate.parseDdl(ddl);
    if (parsed.isEmpty() || parsed.get().accessMethod() != AccessMethod.BTREE) {
      return Optional.empty();
    }
    IndexCandidate index = parsed.get();
    try {
      List<BtreeEntryWidth.KeyColumn> columns = new ArrayList<>();
      for (String column : index.columns()) {
        jdbc.query(
            KEY_COLUMN_SQL,
            (ResultSetExtractor<Void>)
                rs -> {
                  if (rs.next()) {
                    columns.add(
                        new BtreeEntryWidth.KeyColumn(
                            column,
                            rs.getString("base_type"),
                            firstChar(rs.getString("category")),
                            rs.getInt("typmod"),
                            firstChar(rs.getString("storage"))));
                  }
                  return null;
                },
            index.table(),
            column);
      }
      Long toastBytes = jdbc.queryForObject(TOAST_SQL, Long.class, index.table());
      return BtreeEntryWidth.caution(index.table(), columns, toastBytes == null ? 0L : toastBytes);
    } catch (DataAccessException catalogUnavailable) {
      return Optional.empty(); // no caution is better than a guessed one
    }
  }

  private static char firstChar(String s) {
    return s == null || s.isEmpty() ? ' ' : s.charAt(0);
  }

  // --- value-range + footprint evidence (ADR-0038) ---------------------------------------------

  /** The query with one sampled value substituted for the leading column's {@code $N}. */
  private record Variant(String column, String sql, Double frequency) {}

  /**
   * One variant per sampled value of each of the query's equality parameters (up to {@value
   * #MAX_VARIED_PARAMS} of them, the others left generic); none when it has no equality parameter
   * (the generic figure then stands). Varying every equality parameter — not just the index's own
   * column — is what catches a join index whose fetched rows are decided by another table's filter.
   */
  private List<Variant> variants(String sql, PlanNode baseline) {
    List<Variant> out = new ArrayList<>();
    List<EqualityParameterLocator.Binding> bindings = EqualityParameterLocator.all(baseline);
    for (EqualityParameterLocator.Binding b :
        bindings.subList(0, Math.min(MAX_VARIED_PARAMS, bindings.size()))) {
      Pattern placeholder = Pattern.compile("\\$" + b.param() + "(?!\\d)");
      for (ValueSampler.SampledValue v : sampler.sample(b.table(), b.column())) {
        String substituted =
            placeholder.matcher(sql).replaceAll(Matcher.quoteReplacement(v.literal()));
        out.add(new Variant(b.table() + "." + b.column(), substituted, v.frequency()));
      }
    }
    return out;
  }

  /** Total cost of each variant's generic plan (null where it can't be planned — skipped later). */
  private List<Double> planCosts(List<Variant> variants) {
    List<Double> costs = new ArrayList<>(variants.size());
    for (Variant v : variants) {
      costs.add(
          capturer
              .captureGenericPlanJson(v.sql())
              .map(j -> parser.parse(j).totalCost())
              .orElse(null));
    }
    return costs;
  }

  private ValueRangeEstimate valueRange(
      List<Variant> variants, List<Double> before, List<Double> after, ValidationResult result) {
    if (variants.isEmpty() || after.size() != variants.size()) {
      return null;
    }
    List<ValueRanges.Sample> samples = new ArrayList<>(variants.size());
    for (int i = 0; i < variants.size(); i++) {
      Variant v = variants.get(i);
      samples.add(new ValueRanges.Sample(v.column(), v.frequency(), before.get(i), after.get(i)));
    }
    double generic = result.relativeDelta() == null ? 0.0 : result.relativeDelta();
    return ValueRanges.of(samples, generic, minRelativeImprovement).orElse(null);
  }

  /** HypoPG's size estimate for the hypothetical index vs the table's heap, or null if unknown. */
  private IndexFootprint footprint(HypoIndex hypo, String table) {
    if (table == null) {
      return null;
    }
    try {
      return jdbc.query(
          DataSources.introspection(
              "SELECT hypopg_relation_size(?::oid) AS idx, "
                  + "pg_relation_size(to_regclass(?)) AS tbl"),
          (ResultSetExtractor<IndexFootprint>)
              rs -> {
                if (!rs.next()) {
                  return null;
                }
                long idx = rs.getLong("idx");
                long tbl = rs.getLong("tbl");
                return new IndexFootprint(idx, tbl, footprintLabel(idx, tbl));
              },
          hypo.oid(),
          table);
    } catch (DataAccessException sizeUnavailable) {
      return null;
    }
  }

  private static String footprintLabel(long indexBytes, long tableBytes) {
    String size = "Estimated index size %s (HypoPG estimate)".formatted(bytes(indexBytes));
    if (tableBytes > 0) {
      size +=
          ", ≈%.0f%% of the table's %s — every write to the indexed columns also maintains it."
              .formatted(100.0 * indexBytes / tableBytes, bytes(tableBytes));
    } else {
      size += ".";
    }
    return size;
  }

  private static String bytes(long b) {
    if (b >= 1L << 30) {
      return String.format(Locale.US, "%.1f GB", b / (double) (1L << 30));
    }
    if (b >= 1L << 20) {
      return String.format(Locale.US, "%.1f MB", b / (double) (1L << 20));
    }
    return String.format(Locale.US, "%d kB", Math.max(1, b / 1024));
  }

  private ValidationResult verdict(double before, double after, double relative, boolean used) {
    if (used && relative >= minRelativeImprovement) {
      String label =
          "Planner-validated (HypoPG estimate): total cost %.0f → %.0f (−%.1f%%). "
                  .formatted(before, after, relative * 100)
              + "Estimate from the planner, not a runtime measurement.";
      return new ValidationResult(Status.PLANNER_VALIDATED, before, after, relative, true, label);
    }
    String label =
        used
            ? "Suppressed: hypothetical index used but cost fell only %.1f%% (< %.0f%% threshold)."
                .formatted(relative * 100, minRelativeImprovement * 100)
            : "Suppressed: the planner did not use the hypothetical index — no benefit "
                + "(a legitimate full scan).";
    return new ValidationResult(Status.SUPPRESSED, before, after, relative, used, label);
  }

  private ValidationResult notValidated(String label) {
    return new ValidationResult(
        Status.NOT_PLANNER_VALIDATED,
        null,
        null,
        null,
        false,
        label == null ? "Not planner-validated." : label);
  }

  /** A HypoPG hypothetical index: its oid (for {@code hypopg_relation_size}) and plan name. */
  private record HypoIndex(long oid, String name) {}

  private HypoIndex createHypotheticalIndex(String ddl) {
    // hypopg_create_index(text) parses the CREATE INDEX statement and returns (indexrelid,
    // indexname); it ignores our index name and assigns its own, which is what appears in EXPLAIN.
    String statement = ddl.strip().replaceAll(";\\s*$", "");
    return jdbc.query(
        "SELECT indexrelid::bigint AS oid, indexname FROM hypopg_create_index(?)",
        (ResultSetExtractor<HypoIndex>)
            rs -> rs.next() ? new HypoIndex(rs.getLong("oid"), rs.getString("indexname")) : null,
        statement);
  }

  private static boolean usesIndex(PlanNode plan, String hypoIndexName) {
    return plan.flatten().stream().anyMatch(n -> hypoIndexName.equals(n.indexName()));
  }

  private boolean detectHypopg() {
    try {
      Integer n =
          jdbc.queryForObject(
              DataSources.introspection(
                  "SELECT count(*) FROM pg_extension WHERE extname = 'hypopg'"),
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
