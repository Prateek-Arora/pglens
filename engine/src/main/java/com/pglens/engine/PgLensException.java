package com.pglens.engine;

/**
 * An actionable, user-facing engine failure — a bad target, a missing extension, or insufficient
 * grants. Its message is meant to be printed straight to the user (with the fix), not a stack
 * trace.
 */
public class PgLensException extends RuntimeException {

  public PgLensException(String message) {
    super(message);
  }

  public PgLensException(String message, Throwable cause) {
    super(message, cause);
  }
}
