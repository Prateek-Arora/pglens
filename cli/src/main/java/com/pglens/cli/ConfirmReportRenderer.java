package com.pglens.cli;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.pglens.engine.confirm.ConfirmReport;
import com.pglens.engine.confirm.IndexConfirmation;
import com.pglens.engine.confirm.QueryConfirmation;
import com.pglens.engine.confirm.Verdict;
import com.pglens.engine.model.TargetInfo;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Renders a {@link ConfirmReport} — the human report, or the versioned {@code confirm --json}
 * contract (the model records serialized verbatim). Every time is labeled as measured on the copy;
 * every estimate as PgLens's generic-plan estimate.
 */
final class ConfirmReportRenderer {

  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .enable(SerializationFeature.INDENT_OUTPUT)
          .defaultPropertyInclusion(
              JsonInclude.Value.construct(
                  JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
          .build();

  private ConfirmReportRenderer() {}

  static String toJson(ConfirmReport report) {
    try {
      return JSON.writeValueAsString(report);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to render JSON report", e);
    }
  }

  static String toHuman(ConfirmReport r) {
    StringBuilder sb = new StringBuilder();
    sb.append("PgLens confirm — measured on the copy ").append(where(r.copy()));
    if (r.copy().serverVersion() != null) {
      sb.append("  (PostgreSQL ").append(r.copy().serverVersion()).append(')');
    }
    sb.append('\n');
    sb.append("Checks the scan of ").append(where(r.scanTarget()));
    if (r.scanGeneratedAt() != null) {
      sb.append(" from ").append(r.scanGeneratedAt());
    }
    sb.append('\n');
    sb.append("Generated ").append(r.generatedAt()).append("\n\n");

    ConfirmReport.StatementCounts c = r.statements();
    sb.append(
        String.format(
            Locale.US,
            "Workload: %,d read-only statements (%,d others skipped) · %,d query shapes · %,d matched"
                + " a report query · %,d statements to measure (at most %d per query)%n",
            c.read(),
            c.skippedNotRead() + c.skippedNoValues(),
            c.shapes(),
            c.shapesMatched(),
            c.statementsMeasured(),
            r.settings().perQuery()));
    if (c.unusable() > 0) {
      sb.append(
          String.format(
              Locale.US, "          %,d statements failed to plan on the copy%n", c.unusable()));
    }
    if (r.dryRun()) {
      sb.append("\nDry run — nothing was built. Would build and measure:\n");
    }

    for (IndexConfirmation i : r.indexes()) {
      sb.append('\n');
      appendIndex(sb, i, r.dryRun());
    }
    if (!r.dryRun()) {
      appendSummary(sb, r);
    }
    if (!r.notes().isEmpty()) {
      sb.append("\nNotes:\n");
      r.notes().forEach(n -> sb.append("  • ").append(n).append('\n'));
    }
    return sb.toString();
  }

  private static void appendIndex(StringBuilder sb, IndexConfirmation i, boolean dryRun) {
    sb.append('#').append(i.rank()).append("  ").append(i.ddl()).append('\n');
    if (!dryRun) {
      sb.append("    ").append(label(i.verdict()));
      if (i.measuredDrop() != null) {
        sb.append("  ")
            .append(drop(i.measuredDrop()))
            .append(" measured (")
            .append(ms(i.beforeMs()))
            .append(" → ")
            .append(i.afterAtLeast() ? "≥ " : "")
            .append(ms(i.afterMs()))
            .append(" ms warm time over its statements)");
      } else if (i.reason() != null) {
        sb.append("  ").append(i.reason());
      }
      sb.append('\n');
      if (i.buildMs() != null) {
        sb.append("    built in ")
            .append(String.format(Locale.US, "%,d", i.buildMs()))
            .append(" ms · ")
            .append(bytes(i.indexBytes()))
            .append('\n');
      }
      if (i.verdict() == Verdict.FASTER && i.slowerQueries() > 0) {
        sb.append(
            String.format(
                Locale.US,
                "    ⚠ faster overall, but slower for %d of its %d queries (below)%n",
                i.slowerQueries(),
                i.queries().size()));
      }
    }
    if (i.buildCaution() != null) {
      sb.append("    ⚠ ").append(i.buildCaution()).append('\n');
    }
    for (QueryConfirmation q : i.queries()) {
      sb.append("      queryid ").append(q.queryId());
      sb.append("   estimate ").append(q.estimatedDrop() == null ? "—" : drop(q.estimatedDrop()));
      if (dryRun || q.verdict() == null) {
        sb.append("   ")
            .append(q.statements())
            .append(q.statements() == 1 ? " statement" : " statements");
        if (q.statements() == 0) {
          sb.append(" (none in the workload)");
        }
      } else {
        sb.append("   measured ")
            .append(q.measuredDrop() == null ? "—" : drop(q.measuredDrop()))
            .append("   ")
            .append(label(q.verdict()));
        if (q.verdict() == Verdict.NOT_MEASURED && q.reason() != null) {
          sb.append(" (").append(q.reason()).append(')');
        }
      }
      sb.append('\n');
    }
  }

  private static void appendSummary(StringBuilder sb, ConfirmReport r) {
    Map<Verdict, Integer> counts = new EnumMap<>(Verdict.class);
    r.indexes().forEach(i -> counts.merge(i.verdict(), 1, Integer::sum));
    sb.append("\nSummary: ");
    StringBuilder parts = new StringBuilder();
    for (Verdict v : Verdict.values()) {
      if (parts.length() > 0) {
        parts.append(" · ");
      }
      parts.append(counts.getOrDefault(v, 0)).append(' ').append(label(v).toLowerCase(Locale.ROOT));
    }
    sb.append(parts).append('\n');
    sb.append(
        String.format(
            Locale.US,
            "  faster = warm time down ≥ %.0f%%; slower = up ≥ %.0f%%; measured on the copy, not"
                + " production%n",
            r.settings().fasterAt() * 100,
            -r.settings().slowerAt() * 100));
  }

  static String label(Verdict v) {
    if (v == null) {
      return "—";
    }
    return switch (v) {
      case FASTER -> "FASTER";
      case NO_REAL_EFFECT -> "NO REAL EFFECT";
      case SLOWER -> "SLOWER";
      case UNBUILDABLE -> "COULDN'T BE BUILT";
      case NOT_MEASURED -> "NOT MEASURED";
    };
  }

  private static String where(TargetInfo t) {
    if (t == null) {
      return "?";
    }
    return t.database() + " @ " + t.host() + (t.port() == null ? "" : ":" + t.port());
  }

  /** A drop as "−56.7%"; an increase (negative drop) as "+4.0%". */
  private static String drop(double frac) {
    return (frac < 0 ? "+" : "−") + String.format(Locale.US, "%.1f%%", Math.abs(frac) * 100);
  }

  // Sub-millisecond times keep three decimals, so a fast plan doesn't read as "0.0 ms".
  private static String ms(Double v) {
    if (v == null) {
      return "—";
    }
    return String.format(Locale.US, v < 1 ? "%.3f" : "%,.1f", v);
  }

  private static String bytes(Long b) {
    if (b == null) {
      return "—";
    }
    if (b >= 1L << 30) {
      return String.format(Locale.US, "%.1f GB", b / (double) (1L << 30));
    }
    if (b >= 1L << 20) {
      return String.format(Locale.US, "%.1f MB", b / (double) (1L << 20));
    }
    return String.format(Locale.US, "%d kB", Math.max(1, b / 1024));
  }
}
