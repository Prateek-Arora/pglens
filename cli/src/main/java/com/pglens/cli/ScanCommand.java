package com.pglens.cli;

import com.pglens.engine.PgLensEngine;
import com.pglens.engine.PgLensException;
import com.pglens.engine.model.ConnectionTarget;
import com.pglens.engine.model.RankBy;
import com.pglens.engine.model.ScanReport;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code pglens scan <conn>} — rank slow queries, capture plans safely, detect anti-patterns, and
 * print HypoPG-validated index recommendations (human report, or the {@code --json} contract).
 */
@Component
@Command(
    name = "scan",
    mixinStandardHelpOptions = true,
    description = "Scan a Postgres database for slow queries and index recommendations.")
class ScanCommand implements Callable<Integer> {

  @Parameters(
      index = "0",
      paramLabel = "CONN",
      description = "libpq connection string, e.g. postgresql://user:pw@host:5432/db")
  String conn;

  @Option(names = "--top", description = "Show the top N slow queries (default: ${DEFAULT-VALUE}).")
  int top = 10;

  @Option(
      names = "--min-calls",
      description = "Ignore statements called fewer than N times (default: ${DEFAULT-VALUE}).")
  long minCalls = 20;

  @Option(
      names = "--order-by",
      description = "Rank by: total | mean | calls (default: ${DEFAULT-VALUE}).")
  String orderBy = "total";

  @Option(names = "--json", description = "Emit the report as JSON.")
  boolean json;

  @Override
  public Integer call() {
    final ConnectionTarget target;
    final RankBy rankBy;
    try {
      target = ConnectionTarget.parse(conn);
      rankBy = RankBy.fromCli(orderBy);
    } catch (IllegalArgumentException badInput) {
      System.err.println("pglens: " + badInput.getMessage());
      return 2; // usage error
    }

    try (PgLensEngine engine = PgLensEngine.connect(target)) {
      ScanReport report = engine.scan(rankBy, top, minCalls);
      if (json) {
        System.out.println(ScanReportRenderer.toJson(report));
      } else {
        System.out.print(ScanReportRenderer.toHuman(report, rankBy));
      }
      return 0;
    } catch (PgLensException failure) {
      System.err.println("pglens: " + failure.getMessage());
      return 1;
    }
  }
}
