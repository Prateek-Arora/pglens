package com.pglens.server.advice;

/**
 * What travels with every recommendation the API serves (ADR-0041, ADR-0044): the reminder that a
 * planner-validated index can still make queries slower, and the {@code pglens confirm} command
 * that measures it on a copy. The server holds no connection strings, so the command keeps
 * placeholders for the user to fill in.
 */
public record Confirm(String caveat, String command) {

  public static final Confirm INSTANCE =
      new Confirm(
          "Planner-validated ≠ safe: HypoPG showed the planner would use this index and estimates"
              + " a lower cost, but nothing was run. When the planner misjudges row counts, a new"
              + " index can make queries slower. Measure it on a copy of the database before"
              + " creating it.",
          "pglens scan <connection to this database> --json > scan.json\n"
              + "pglens confirm --report scan.json --copy <scratch copy of the database>"
              + " --statements <your statements: .sql file or PostgreSQL log>");
}
