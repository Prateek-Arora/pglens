package com.pglens.server.auth;

import java.time.Duration;

/** Login failures the HTTP layer turns into 401 / 429 (the auth package knows no HTTP). */
public final class AuthExceptions {

  private AuthExceptions() {}

  /** Wrong username or password — deliberately never says which. */
  public static final class LoginFailed extends RuntimeException {
    public LoginFailed() {
      super("Invalid username or password.");
    }
  }

  /** Too many failed logins for this username. */
  public static final class LoginThrottled extends RuntimeException {
    private final Duration retryAfter;

    public LoginThrottled(Duration retryAfter) {
      super("Too many failed logins for this user. Try again later.");
      this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
      return retryAfter;
    }
  }
}
