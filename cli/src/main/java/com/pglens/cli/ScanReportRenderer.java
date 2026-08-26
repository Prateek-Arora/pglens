package com.pglens.cli;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.pglens.engine.model.Finding;
import com.pglens.engine.model.QueryReport;
import com.pglens.engine.model.RankBy;
import com.pglens.engine.model.RankedRecommendation;
import com.pglens.engine.model.Recommendation;
import com.pglens.engine.model.ScanReport;
import com.pglens.engine.model.TargetInfo;
import com.pglens.engine.model.ValidationResult.Status;
import java.util.List;
import java.util.Locale;

/**
 * Renders a {@link ScanReport} — as the human report (the default) or as the versioned {@code
 * --json} contract. The JSON is the frozen Phase 2 shape (the model records serialized verbatim, so
 * the contract can't drift from the code); the human report leads with the honesty labels every
 * number carries.
 */
final class ScanReportRenderer {

  // NON_NULL: null fields (unvalidated costs, absent plans) are omitted rather than serialized
  // null.
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .enable(SerializationFeature.INDENT_OUTPUT)
          .defaultPropertyInclusion(
              JsonInclude.Value.construct(
                  JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
          .build();

  private static final int SNIPPET_MAX = 100;

  private ScanReportRenderer() {}

  static String toJson(ScanReport report) {
    try {
      return JSON.writeValueAsString(report);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to render JSON report", e);
    }
  }

  static String toHuman(ScanReport report, RankBy rankBy) {
    StringBuilder sb = new StringBuilder();
    header(sb, report.target(), report.generatedAt());

    sb.append("Top ")
        .append(report.queries().size())
        .append(" slow statements (ranked by ")
        .append(rankBy.name().toLowerCase(Locale.ROOT))
        .append(")\n");
    if (report.queries().isEmpty()) {
      sb.append("  (no statements matched — try a lower --min-calls, or warm up the workload)\n");
    }
    int i = 1;
    for (QueryReport q : report.queries()) {
      sb.append('\n');
      appendQuery(sb, i++, q);
    }

    appendTopRecommendations(sb, report.topRecommendations());
    appendNotes(sb, report.notes());
    return sb.toString();
  }

  /** One query in full — used by the {@code explain} subcommand. */
  static String toQueryDetail(QueryReport q, TargetInfo target, String generatedAt) {
    StringBuilder sb = new StringBuilder();
    header(sb, target, generatedAt);
    appendQuery(sb, 1, q);
    return sb.toString();
  }

  // --- sections ---------------------------------------------------------------

  private static void header(StringBuilder sb, TargetInfo t, String generatedAt) {
    sb.append("PgLens scan — ").append(t.database()).append(" @ ").append(t.host());
    if (t.serverVersion() != null) {
      sb.append("  (PostgreSQL ").append(t.serverVersion()).append(')');
    }
    sb.append('\n');
    if (!t.extensions().isEmpty()) {
      sb.append("Extensions: ").append(String.join(", ", t.extensions())).append('\n');
    }
    sb.append("Generated ").append(generatedAt).append("\n\n");
  }

  private static void appendQuery(StringBuilder sb, int rank, QueryReport q) {
    sb.append('#')
        .append(rank)
        .append("  total ")
        .append(ms(q.totalExecMs()))
        .append(" ms   mean ")
        .append(ms(q.meanExecMs()))
        .append(" ms   calls ")
        .append(String.format(Locale.US, "%,d", q.calls()))
        .append("   queryid ")
        .append(q.queryId())
        .append('\n');
    sb.append("    ").append(snippet(q.normalizedText()));
    if (q.truncated()) {
      sb.append(" [truncated]");
    }
    sb.append('\n');

    if (!q.planCaptured()) {
      sb.append("    (plan not captured — statement can't be generically explained; skipped)\n");
      return;
    }

    if (q.findings().isEmpty()) {
      sb.append("    findings: none\n");
    } else {
      sb.append("    findings:\n");
      for (Finding f : q.findings()) {
        sb.append("      • [")
            .append(f.ruleId())
            .append("] ")
            .append(f.title())
            .append(" — ")
            .append(f.confidence())
            .append('\n');
        if (f.evidence() != null && !f.evidence().isBlank()) {
          sb.append("          ").append(f.evidence()).append('\n');
        }
      }
    }

    if (!q.recommendations().isEmpty()) {
      sb.append("    recommendations:\n");
      for (Recommendation r : q.recommendations()) {
        sb.append("      ")
            .append(marker(r.validation().status()))
            .append(' ')
            .append(r.candidate().ddl())
            .append('\n');
        sb.append("          ").append(r.validation().label()).append('\n');
      }
    }
  }

  private static void appendTopRecommendations(
      StringBuilder sb, List<RankedRecommendation> ranked) {
    List<RankedRecommendation> actionable =
        ranked.stream().filter(RankedRecommendation::actionable).toList();
    List<RankedRecommendation> covered =
        ranked.stream().filter(RankedRecommendation::subsumed).toList();

    sb.append("\nTop index recommendations (planner-validated, by estimated total time saved)\n");
    if (actionable.isEmpty()) {
      sb.append(
          "  (none planner-validated — see per-query recommendations above for what was tried)\n");
    }
    int n = 1;
    for (RankedRecommendation r : actionable) {
      sb.append(n++)
          .append(". ")
          .append(r.candidate().ddl())
          .append('\n')
          .append("     est. saves ~")
          .append(saved(r.estimatedMsSaved()))
          .append(" ms of query #")
          .append(r.queryId())
          .append("'s ")
          .append(ms(r.queryTotalExecTimeMs()))
          .append(" ms total  (−")
          .append(pct(r.recommendation().validation().relativeDelta()))
          .append(", HypoPG planner estimate)\n");
    }
    if (!covered.isEmpty()) {
      sb.append("  merged (already covered by a recommended index):\n");
      for (RankedRecommendation r : covered) {
        boolean exactDuplicate = r.subsumedBy().equals(r.candidate().suggestedName());
        sb.append("    (covered) ").append(r.candidate().ddl());
        if (exactDuplicate) {
          sb.append(" — same index already recommended (also wanted by query #")
              .append(r.queryId())
              .append(")\n");
        } else {
          sb.append(" — served by ")
              .append(r.subsumedBy())
              .append(" (from query #")
              .append(r.queryId())
              .append(")\n");
        }
      }
    }
  }

  private static void appendNotes(StringBuilder sb, List<String> notes) {
    if (notes.isEmpty()) {
      return;
    }
    sb.append("\nNotes\n");
    for (String note : notes) {
      sb.append("  • ").append(note).append('\n');
    }
  }

  // --- helpers ----------------------------------------------------------------

  private static String marker(Status status) {
    return switch (status) {
      case PLANNER_VALIDATED -> "[validated]";
      case SUPPRESSED -> "[suppressed]";
      case NOT_PLANNER_VALIDATED -> "[not planner-validated]";
    };
  }

  private static String ms(double v) {
    return String.format(Locale.US, "%,.1f", v);
  }

  private static String saved(double v) {
    return String.format(Locale.US, "%,.0f", v);
  }

  private static String pct(Double frac) {
    return String.format(Locale.US, "%.1f%%", (frac == null ? 0.0 : frac) * 100);
  }

  private static String snippet(String query) {
    String oneLine = query == null ? "" : query.replaceAll("\\s+", " ").trim();
    return oneLine.length() > SNIPPET_MAX ? oneLine.substring(0, SNIPPET_MAX - 3) + "..." : oneLine;
  }
}
