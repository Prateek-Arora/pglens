package com.pglens.cli;

import com.pglens.engine.PgLensEngine;
import com.pglens.engine.PgLensException;
import com.pglens.engine.model.ConnectionTarget;
import com.pglens.engine.model.QueryReport;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * {@code pglens explain <conn> <queryid>} — drill into one statement (by {@code pg_stat_statements}
 * queryid): its captured generic plan, the anti-pattern findings, and every candidate
 * recommendation with its HypoPG verdict.
 */
@Component
@Command(
    name = "explain",
    mixinStandardHelpOptions = true,
    description = "Explain a single query (by pg_stat_statements queryid) in detail.")
class ExplainCommand implements Callable<Integer> {

  @Parameters(
      index = "0",
      paramLabel = "CONN",
      description = "libpq connection string, e.g. postgresql://user:pw@host:5432/db")
  String conn;

  @Parameters(index = "1", paramLabel = "QUERYID", description = "pg_stat_statements queryid.")
  long queryId;

  @Override
  public Integer call() {
    final ConnectionTarget target;
    try {
      target = ConnectionTarget.parse(conn);
    } catch (IllegalArgumentException badInput) {
      System.err.println("pglens: " + badInput.getMessage());
      return 2; // usage error
    }

    try (PgLensEngine engine = PgLensEngine.connect(target)) {
      Optional<QueryReport> query = engine.explain(queryId);
      if (query.isEmpty()) {
        System.err.println(
            "pglens: no top-level statement with queryid "
                + queryId
                + " in "
                + target.database()
                + " (it may have aged out of pg_stat_statements, or be a nested statement).");
        return 1;
      }
      System.out.print(
          ScanReportRenderer.toQueryDetail(
              query.get(), engine.targetInfo(), Instant.now().toString()));
      return 0;
    } catch (PgLensException failure) {
      System.err.println("pglens: " + failure.getMessage());
      return 1;
    }
  }
}
