package com.pglens.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pglens.engine.CopyConfirmer;
import com.pglens.engine.PgLensException;
import com.pglens.engine.confirm.ConfirmPlan;
import com.pglens.engine.confirm.ConfirmReport;
import com.pglens.engine.confirm.StatementSource;
import com.pglens.engine.confirm.WorkloadStatement;
import com.pglens.engine.model.ConnectionTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code pglens confirm} — build each index a scan recommended for real on a scratch copy you
 * marked, time your own statements before and after, and report a measured verdict per index (Phase
 * 2.6, ADR-0042). Never connects to the scanned database.
 */
@Component
@Command(
    name = "confirm",
    mixinStandardHelpOptions = true,
    description = {
      "Measure a scan's recommended indexes on a scratch copy of the database.",
      "Builds each index for real on the copy, times your own statements (a .sql file or a"
          + " PostgreSQL log) before and after, and drops it again. The copy must be marked:"
          + " ALTER DATABASE <copy> SET pglens.scratch = 'on';"
    })
class ConfirmCommand implements Callable<Integer> {

  @Option(
      names = "--report",
      required = true,
      paramLabel = "FILE",
      description = "The scan report to check (pglens scan --json output).")
  Path report;

  @Option(
      names = "--copy",
      required = true,
      paramLabel = "CONN",
      description = "The scratch copy, e.g. postgresql://owner:pw@host:5432/shop_copy")
  String copy;

  @Option(
      names = "--statements",
      required = true,
      paramLabel = "FILE",
      description =
          "Your real statements: a .sql file, or a PostgreSQL stderr log with statements logged"
              + " (log_min_duration_statement or log_statement). Repeatable.")
  List<Path> statements = new ArrayList<>();

  @Option(
      names = "--top",
      description = "Check the report's top N indexes (default: ${DEFAULT-VALUE}).")
  int top = 10;

  @Option(
      names = "--per-query",
      description = "Measure at most N statements per report query (default: ${DEFAULT-VALUE}).")
  int perQuery = 5;

  @Option(
      names = "--runs",
      description =
          "Timed runs per statement after a warm-up; the median counts (default: ${DEFAULT-VALUE}).")
  int runs = 3;

  @Option(
      names = "--statement-timeout",
      paramLabel = "SECONDS",
      description = "Per statement run (default: ${DEFAULT-VALUE}).")
  long statementTimeoutSeconds = 300;

  @Option(
      names = "--build-timeout",
      paramLabel = "SECONDS",
      description = "Per CREATE INDEX (default: ${DEFAULT-VALUE}).")
  long buildTimeoutSeconds = 3600;

  @Option(
      names = "--dry-run",
      description =
          "Build nothing: match the statements (each query shape runs once, read-only) and list"
              + " what would be measured.")
  boolean dryRun;

  @Option(names = "--json", description = "Emit the result as JSON (confirm contract 1.0).")
  boolean json;

  @Override
  public Integer call() {
    final ConnectionTarget target;
    final ConfirmPlan plan;
    final StatementSource.Parsed workload;
    try {
      if (top < 1 || perQuery < 1 || runs < 1) {
        throw new IllegalArgumentException("--top, --per-query and --runs must be at least 1.");
      }
      target = ConnectionTarget.parse(copy);
      plan = ConfirmPlan.from(readJson(report), top);
      workload = readStatements(statements);
    } catch (IllegalArgumentException badInput) {
      System.err.println("pglens: " + badInput.getMessage());
      return 2; // usage error
    }
    if (plan.indexes().isEmpty()) {
      System.err.println("pglens: the scan report has no planner-validated index to confirm.");
      return 1;
    }
    if (workload.statements().isEmpty()) {
      System.err.println("pglens: no read-only statement found in " + statements + ".");
      return 1;
    }

    CopyConfirmer.Options options =
        new CopyConfirmer.Options(
            top,
            perQuery,
            runs,
            Duration.ofSeconds(statementTimeoutSeconds),
            Duration.ofSeconds(buildTimeoutSeconds),
            dryRun);
    try {
      ConfirmReport result =
          CopyConfirmer.confirm(
              plan, workload, target, options, line -> System.err.println("  " + line));
      System.out.print(
          json
              ? ConfirmReportRenderer.toJson(result) + "\n"
              : ConfirmReportRenderer.toHuman(result));
      return 0;
    } catch (PgLensException failure) {
      System.err.println("pglens: " + failure.getMessage());
      return 1;
    }
  }

  private static JsonNode readJson(Path file) {
    try {
      return new ObjectMapper().readTree(Files.readString(file));
    } catch (IOException e) {
      throw new IllegalArgumentException(
          "Can't read the scan report " + file + ": " + e.getMessage());
    }
  }

  private static StatementSource.Parsed readStatements(List<Path> files) {
    List<WorkloadStatement> all = new ArrayList<>();
    int skipped = 0;
    int noValues = 0;
    for (Path f : files) {
      String content;
      try {
        content = Files.readString(f);
      } catch (IOException e) {
        throw new IllegalArgumentException("Can't read " + f + ": " + e.getMessage());
      }
      StatementSource.Parsed p = StatementSource.parse(f.getFileName().toString(), content);
      all.addAll(p.statements());
      skipped += p.skippedNotRead();
      noValues += p.skippedNoValues();
    }
    return new StatementSource.Parsed(all, skipped, noValues);
  }
}
