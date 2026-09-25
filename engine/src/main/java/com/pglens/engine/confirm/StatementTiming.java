package com.pglens.engine.confirm;

/**
 * One statement's measured warm execution time on the copy — the median of the runs after a
 * warm-up, from {@code EXPLAIN (ANALYZE, TIMING OFF)} — or why there is none. A failure keeps only
 * its kind and SQLSTATE, never the error text: an error can quote the statement's values
 * (ADR-0042).
 */
public record StatementTiming(Double ms, Failure failure, String sqlState) {

  /** Why a statement has no timing. */
  public enum Failure {
    /** It ran past the statement timeout. */
    TIMEOUT,
    /** It tried to write and the read-only transaction rejected it. */
    WRITE_REJECTED,
    /** Any other error (the SQLSTATE says which). */
    ERROR
  }

  public static StatementTiming measured(double ms) {
    return new StatementTiming(ms, null, null);
  }

  public static StatementTiming failed(Failure failure, String sqlState) {
    return new StatementTiming(null, failure, sqlState);
  }

  public boolean ok() {
    return ms != null;
  }

  /** A short, value-free description of the failure, for the report. */
  public String describe() {
    return switch (failure) {
      case TIMEOUT -> "hit the statement timeout";
      case WRITE_REJECTED -> "tried to write (rejected by the read-only transaction)";
      case ERROR -> "failed (SQLSTATE " + sqlState + ")";
    };
  }
}
