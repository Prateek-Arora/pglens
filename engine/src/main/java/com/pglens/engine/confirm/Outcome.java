package com.pglens.engine.confirm;

import java.util.List;

/**
 * The measured result over a set of statements, before vs after an index: total warm time each
 * side, the drop {@code 1 − Σafter / Σbefore}, and the {@link Verdict} (ADR-0042). Pure — no I/O.
 *
 * <p>Statements whose baseline failed are left out on both sides. One that ran fine before but hit
 * the timeout after counts at the timeout — a lower bound ({@code afterAtLeast}); that can still
 * prove <em>slower</em>, but never <em>faster</em> or <em>no real effect</em>, so those become
 * {@link Verdict#NOT_MEASURED}. One that failed after for another reason is left out.
 */
public record Outcome(
    Verdict verdict,
    Double beforeMs,
    Double afterMs,
    Double drop,
    boolean afterAtLeast,
    int statements,
    int failed,
    String reason) {

  /** One statement's baseline and with-index timings. */
  public record Pair(StatementTiming before, StatementTiming after) {}

  /** Aggregates the pairs; {@code timeoutMs} is the lower bound for an after-timeout. */
  public static Outcome of(List<Pair> pairs, double timeoutMs) {
    if (pairs.isEmpty()) {
      return notMeasured(0, 0, "no statement from the workload matched");
    }
    double before = 0;
    double after = 0;
    int used = 0;
    int failed = 0;
    boolean atLeast = false;
    String firstProblem = null;
    for (Pair p : pairs) {
      if (!p.before().ok()) {
        failed++;
        firstProblem = firstProblem == null ? "baseline " + p.before().describe() : firstProblem;
        continue;
      }
      if (p.after().ok()) {
        before += p.before().ms();
        after += p.after().ms();
        used++;
      } else if (p.after().failure() == StatementTiming.Failure.TIMEOUT) {
        before += p.before().ms();
        after += timeoutMs;
        atLeast = true;
        used++;
      } else {
        failed++;
        firstProblem =
            firstProblem == null
                ? "with the index a statement " + p.after().describe()
                : firstProblem;
      }
    }
    if (used == 0 || before <= 0) {
      return notMeasured(used, failed, firstProblem == null ? "nothing measured" : firstProblem);
    }
    double drop = 1 - after / before;
    Verdict verdict = Verdict.of(drop);
    if (atLeast && verdict != Verdict.SLOWER) {
      return new Outcome(
          Verdict.NOT_MEASURED,
          before,
          after,
          null,
          true,
          used,
          failed,
          "a statement hit the timeout with the index, so the result is unclear");
    }
    return new Outcome(verdict, before, after, drop, atLeast, used, failed, firstProblem);
  }

  private static Outcome notMeasured(int used, int failed, String reason) {
    return new Outcome(Verdict.NOT_MEASURED, null, null, null, false, used, failed, reason);
  }
}
