package com.pglens.server.errors;

/**
 * Domain failures the HTTP layer turns into RFC 9457 problem responses (400 / 404 / 409). Services
 * throw these instead of generic JDK exceptions, so an unexpected bug never masquerades as a client
 * error with its internal message exposed.
 */
public final class Errors {

  private Errors() {}

  /** The request itself is invalid (a too-short password, a malformed name, ...). → 400 */
  public static final class Invalid extends RuntimeException {
    public Invalid(String message) {
      super(message);
    }
  }

  /** The named thing doesn't exist (or isn't the caller's). → 404 */
  public static final class NotFound extends RuntimeException {
    public NotFound(String message) {
      super(message);
    }
  }

  /** The request conflicts with current state (a taken name, the last admin, ...). → 409 */
  public static final class Conflict extends RuntimeException {
    public Conflict(String message) {
      super(message);
    }
  }
}
