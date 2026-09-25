package com.pglens.engine;

import com.pglens.engine.confirm.ConfirmPlan;
import com.pglens.engine.confirm.ConfirmPlan.IndexToConfirm;
import com.pglens.engine.confirm.ConfirmPlan.QueryEstimate;
import com.pglens.engine.confirm.ConfirmReport;
import com.pglens.engine.confirm.IndexConfirmation;
import com.pglens.engine.confirm.Outcome;
import com.pglens.engine.confirm.QueryConfirmation;
import com.pglens.engine.confirm.QueryText;
import com.pglens.engine.confirm.StatementSource;
import com.pglens.engine.confirm.StatementTiming;
import com.pglens.engine.confirm.Verdict;
import com.pglens.engine.confirm.WorkloadStatement;
import com.pglens.engine.db.CopyMeasurer;
import com.pglens.engine.db.CopyTarget;
import com.pglens.engine.model.ConnectionTarget;
import com.pglens.engine.model.TargetInfo;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * {@code pglens confirm} (Phase 2.6, ADR-0042): builds each recommended index for real on a
 * user-marked scratch copy and measures the workload's own statements before and after.
 *
 * <ol>
 *   <li><b>Guard</b> ({@link CopyTarget#open}) — marker, not the scanned DB, primary, pgss, owner.
 *   <li><b>Match</b> — plan each statement on the copy ({@code EXPLAIN (VERBOSE)}) to group it by
 *       the copy's queryid; for each group, read pg_stat_statements' normalized text (running one
 *       statement once if the copy hasn't seen it) and match it with a report query's text.
 *   <li><b>Baseline</b> — time every matched statement (capped per query) without the indexes.
 *   <li><b>Per index</b> — build it, time its queries' statements, drop it (always), and compare.
 * </ol>
 *
 * <p>The monitored database is never contacted. Values from the statements never leave this class:
 * the report carries queryids, counts and times only.
 */
public final class CopyConfirmer {

  /** How to run a confirmation. */
  public record Options(
      int top,
      int perQuery,
      int runs,
      Duration statementTimeout,
      Duration buildTimeout,
      boolean dryRun) {}

  private final ConfirmPlan plan;
  private final StatementSource.Parsed workload;
  private final Options options;
  private final Consumer<String> progress;

  private CopyConfirmer(
      ConfirmPlan plan,
      StatementSource.Parsed workload,
      Options options,
      Consumer<String> progress) {
    this.plan = plan;
    this.workload = workload;
    this.options = options;
    this.progress = progress;
  }

  /** Runs the confirmation against {@code copy}; {@code progress} receives one-line updates. */
  public static ConfirmReport confirm(
      ConfirmPlan plan,
      StatementSource.Parsed workload,
      ConnectionTarget copy,
      Options options,
      Consumer<String> progress) {
    LinkedHashSet<String> tables = new LinkedHashSet<>();
    plan.indexes().forEach(i -> tables.add(i.candidate().table()));
    try (CopyTarget target = CopyTarget.open(copy, plan.scanTarget(), tables)) {
      return new CopyConfirmer(plan, workload, options, progress).run(target);
    }
  }

  private ConfirmReport run(CopyTarget target) {
    List<String> notes = new ArrayList<>();
    CopyMeasurer m = new CopyMeasurer(target, options.statementTimeout(), options.buildTimeout());
    if (!options.dryRun()) {
      List<String> leftovers = target.dropLeftovers();
      if (!leftovers.isEmpty()) {
        notes.add(
            "Dropped indexes left by an interrupted earlier run: " + String.join(", ", leftovers));
      }
    }

    Matching matching = match(m);
    Map<WorkloadStatement, StatementTiming> baseline = new IdentityHashMap<>();
    List<IndexConfirmation> results = new ArrayList<>();
    if (!options.dryRun()) {
      List<WorkloadStatement> needed = new ArrayList<>();
      matching.byQuery().values().forEach(needed::addAll);
      int n = 0;
      for (WorkloadStatement s : needed) {
        progress.accept("baseline [%d/%d] %s".formatted(++n, needed.size(), s.origin()));
        baseline.put(s, m.time(s, options.runs()));
      }
    }
    for (IndexToConfirm index : plan.indexes()) {
      results.add(
          options.dryRun() ? dryRun(index, matching) : confirm(m, index, matching, baseline));
    }

    notes.addAll(notes(target.info(), matching));
    return new ConfirmReport(
        ConfirmReport.SCHEMA_VERSION,
        Instant.now().toString(),
        options.dryRun(),
        plan.scanGeneratedAt(),
        plan.scanTarget(),
        target.info(),
        new ConfirmReport.Settings(
            options.top(),
            options.perQuery(),
            options.runs(),
            options.statementTimeout().toMillis(),
            Verdict.FASTER_AT,
            Verdict.SLOWER_AT),
        new ConfirmReport.StatementCounts(
            workload.statements().size(),
            workload.skippedNotRead(),
            workload.skippedNoValues(),
            matching.unusable(),
            matching.shapes(),
            matching.shapesMatched(),
            matching.byQuery().values().stream().mapToInt(List::size).sum()),
        results,
        notes);
  }

  // --- matching -------------------------------------------------------------------------------

  /** Report queryId → the statements measured for it (in workload order, capped). */
  private record Matching(
      Map<Long, List<WorkloadStatement>> byQuery, int unusable, int shapes, int shapesMatched) {}

  private Matching match(CopyMeasurer m) {
    Map<Long, QueryEstimate> wanted = new LinkedHashMap<>();
    plan.indexes().forEach(i -> i.queries().forEach(q -> wanted.putIfAbsent(q.queryId(), q)));

    Map<Long, List<WorkloadStatement>> byShape = new LinkedHashMap<>();
    int unusable = 0;
    int n = 0;
    for (WorkloadStatement s : workload.statements()) {
      progress.accept(
          "planning [%d/%d] %s".formatted(++n, workload.statements().size(), s.origin()));
      try {
        byShape.computeIfAbsent(m.copyQueryId(s), k -> new ArrayList<>()).add(s);
      } catch (SQLException e) {
        unusable++; // e.g. a table the copy doesn't have; its message may quote values — not kept
      }
    }

    Map<Long, List<WorkloadStatement>> byQuery = new LinkedHashMap<>();
    int matched = 0;
    for (Map.Entry<Long, List<WorkloadStatement>> shape : byShape.entrySet()) {
      Optional<String> text;
      try {
        text = m.normalizedText(shape.getKey(), shape.getValue().get(0));
      } catch (SQLException e) {
        unusable += shape.getValue().size();
        continue;
      }
      if (text.isEmpty()) {
        continue;
      }
      boolean hit = false;
      for (QueryEstimate q : wanted.values()) {
        if (QueryText.matches(q.normalizedText(), q.truncated(), text.get())) {
          hit = true;
          List<WorkloadStatement> list =
              byQuery.computeIfAbsent(q.queryId(), k -> new ArrayList<>());
          for (WorkloadStatement s : shape.getValue()) {
            if (list.size() < options.perQuery() && list.stream().noneMatch(x -> same(x, s))) {
              list.add(s);
            }
          }
        }
      }
      matched += hit ? 1 : 0;
    }
    return new Matching(byQuery, unusable, byShape.size(), matched);
  }

  private static boolean same(WorkloadStatement a, WorkloadStatement b) {
    return a.sql().equals(b.sql()) && a.parameters().equals(b.parameters());
  }

  // --- measuring --------------------------------------------------------------------------------

  private IndexConfirmation confirm(
      CopyMeasurer m,
      IndexToConfirm index,
      Matching matching,
      Map<WorkloadStatement, StatementTiming> baseline) {
    String label = "#%d %s".formatted(index.rank(), index.ddl().replaceFirst(";$", ""));
    List<QueryEstimate> queries = index.queries();
    boolean anyStatement =
        queries.stream()
            .anyMatch(q -> !matching.byQuery().getOrDefault(q.queryId(), List.of()).isEmpty());
    if (!anyStatement) {
      progress.accept("index " + label + ": no matched statement, skipped");
      return notMeasured(
          index, null, null, "no statement from the workload matched its queries", matching);
    }
    try {
      Optional<String> existing = m.equivalentIndex(index.candidate());
      if (existing.isPresent()) {
        return notMeasured(
            index,
            null,
            null,
            "the copy already has this index (" + existing.get() + ")",
            matching);
      }
    } catch (SQLException e) {
      return notMeasured(
          index, null, null, "could not read the copy's indexes: " + e.getMessage(), matching);
    }

    progress.accept("index " + label + ": building");
    CopyMeasurer.Built built;
    try {
      built = m.build(index.candidate(), index.rank());
    } catch (SQLException e) {
      Verdict v = "57014".equals(e.getSQLState()) ? Verdict.NOT_MEASURED : Verdict.UNBUILDABLE;
      String why =
          v == Verdict.NOT_MEASURED
              ? "building it took longer than the build timeout"
              : firstLine(e.getMessage());
      return new IndexConfirmation(
          index.rank(),
          index.ddl(),
          index.candidate().table(),
          index.candidate().columns(),
          index.candidate().accessMethod(),
          v,
          null,
          null,
          null,
          false,
          null,
          null,
          why,
          index.buildCaution(),
          estimatesOnly(index, matching));
    }

    Map<WorkloadStatement, StatementTiming> after = new IdentityHashMap<>();
    try {
      int n = 0;
      int total =
          queries.stream()
              .mapToInt(q -> matching.byQuery().getOrDefault(q.queryId(), List.of()).size())
              .sum();
      for (QueryEstimate q : queries) {
        for (WorkloadStatement s : matching.byQuery().getOrDefault(q.queryId(), List.of())) {
          progress.accept("index %s: measuring [%d/%d]".formatted(label, ++n, total));
          after.computeIfAbsent(s, k -> m.time(k, options.runs()));
        }
      }
    } finally {
      try {
        m.drop(built);
      } catch (SQLException e) {
        throw new PgLensException(
            "Could not drop %s on the copy (%s); drop it by hand."
                .formatted(built.name(), e.getMessage()),
            e);
      }
    }

    double timeoutMs = options.statementTimeout().toMillis();
    List<Outcome.Pair> all = new ArrayList<>();
    List<QueryConfirmation> perQuery = new ArrayList<>();
    for (QueryEstimate q : queries) {
      List<Outcome.Pair> pairs = new ArrayList<>();
      for (WorkloadStatement s : matching.byQuery().getOrDefault(q.queryId(), List.of())) {
        pairs.add(new Outcome.Pair(baseline.get(s), after.get(s)));
      }
      all.addAll(pairs);
      perQuery.add(
          QueryConfirmation.of(q.queryId(), q.estimatedDrop(), Outcome.of(pairs, timeoutMs)));
    }
    Outcome overall = Outcome.of(all, timeoutMs);
    return new IndexConfirmation(
        index.rank(),
        index.ddl(),
        index.candidate().table(),
        index.candidate().columns(),
        index.candidate().accessMethod(),
        overall.verdict(),
        overall.drop(),
        overall.beforeMs(),
        overall.afterMs(),
        overall.afterAtLeast(),
        built.buildMs(),
        built.bytes(),
        overall.reason(),
        index.buildCaution(),
        perQuery);
  }

  private IndexConfirmation dryRun(IndexToConfirm index, Matching matching) {
    return new IndexConfirmation(
        index.rank(),
        index.ddl(),
        index.candidate().table(),
        index.candidate().columns(),
        index.candidate().accessMethod(),
        null,
        null,
        null,
        null,
        false,
        null,
        null,
        null,
        index.buildCaution(),
        estimatesOnly(index, matching));
  }

  private IndexConfirmation notMeasured(
      IndexToConfirm index, Long buildMs, Long bytes, String reason, Matching matching) {
    return new IndexConfirmation(
        index.rank(),
        index.ddl(),
        index.candidate().table(),
        index.candidate().columns(),
        index.candidate().accessMethod(),
        Verdict.NOT_MEASURED,
        null,
        null,
        null,
        false,
        buildMs,
        bytes,
        reason,
        index.buildCaution(),
        estimatesOnly(index, matching));
  }

  // Per-query rows with the estimate and how many statements matched, but no measurement.
  private static List<QueryConfirmation> estimatesOnly(IndexToConfirm index, Matching matching) {
    return index.queries().stream()
        .map(
            q ->
                new QueryConfirmation(
                    q.queryId(),
                    q.estimatedDrop(),
                    null,
                    null,
                    null,
                    null,
                    false,
                    matching.byQuery().getOrDefault(q.queryId(), List.of()).size(),
                    0,
                    null))
        .toList();
  }

  private List<String> notes(TargetInfo copy, Matching matching) {
    List<String> notes = new ArrayList<>();
    notes.add(
        ("Times were measured on the copy (warm execution time, EXPLAIN ANALYZE with TIMING OFF, "
                + "median of %d runs after a warm-up, the copy's own settings). Hardware, cache and "
                + "concurrent load differ from production, so treat them as evidence, not a promise.")
            .formatted(options.runs()));
    notes.add(
        "Each index is measured only on the queries PgLens recommended it for; it can also change "
            + "other queries' plans, and it adds work to every write on its table.");
    notes.add("Estimates are PgLens's HypoPG generic-plan estimates from the scan report.");
    if (workload.skippedNoValues() > 0) {
      notes.add(
          "%d logged statements had $N placeholders but no logged values and were skipped."
              .formatted(workload.skippedNoValues()));
    }
    long unmatchedQueries =
        plan.indexes().stream()
            .flatMap(i -> i.queries().stream())
            .map(QueryEstimate::queryId)
            .distinct()
            .filter(id -> !matching.byQuery().containsKey(id))
            .count();
    if (unmatchedQueries > 0) {
      notes.add(
          ("%d report queries had no matching statement in the workload you gave; statements from "
                  + "a log only appear if they were logged (log_min_duration_statement logs the "
                  + "slow ones).")
              .formatted(unmatchedQueries));
    }
    String scanned = plan.scanTarget().serverVersion();
    if (scanned != null
        && copy.serverVersion() != null
        && !major(scanned).equals(major(copy.serverVersion()))) {
      notes.add(
          ("The copy runs PostgreSQL %s but the scan was of %s: plans, and pg_stat_statements' "
                  + "normalized text, can differ between major versions.")
              .formatted(copy.serverVersion(), scanned));
    }
    return notes;
  }

  private static String major(String version) {
    int dot = version.indexOf('.');
    return dot < 0 ? version.split(" ")[0] : version.substring(0, dot);
  }

  private static String firstLine(String message) {
    if (message == null) {
      return "CREATE INDEX failed";
    }
    String first = message.strip().lines().findFirst().orElse(message);
    return first.startsWith("ERROR: ") ? first.substring("ERROR: ".length()) : first;
  }
}
