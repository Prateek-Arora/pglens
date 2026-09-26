package com.pglens.server.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows password guessing: after {@code maxFailures} failed logins for a username within the
 * lockout window, that username is refused until the window passes. Keyed by username, not IP: the
 * dashboard calls the API from one address for everyone, so a per-IP limit would lock out the whole
 * team. In memory — PgLens runs one server instance (ADR-0028); a restart clears it. Pure.
 */
public final class LoginThrottle {

  private record Window(Instant start, int failures) {}

  private final int maxFailures;
  private final Duration lockout;
  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

  public LoginThrottle(int maxFailures, Duration lockout) {
    this.maxFailures = maxFailures;
    this.lockout = lockout;
  }

  /** How long this username must wait, or empty if it may try now. */
  public Optional<Duration> blockedFor(String username, Instant now) {
    Window w = windows.get(key(username));
    if (w == null || w.failures() < maxFailures) {
      return Optional.empty();
    }
    Instant until = w.start().plus(lockout);
    return now.isBefore(until) ? Optional.of(Duration.between(now, until)) : Optional.empty();
  }

  public void recordFailure(String username, Instant now) {
    windows.compute(
        key(username),
        (k, w) ->
            w == null || !now.isBefore(w.start().plus(lockout))
                ? new Window(now, 1)
                : new Window(w.start(), w.failures() + 1));
  }

  public void recordSuccess(String username) {
    windows.remove(key(username));
  }

  private static String key(String username) {
    return username == null ? "" : username.toLowerCase(Locale.ROOT);
  }
}
