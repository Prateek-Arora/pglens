package com.pglens.server.web;

import com.pglens.server.errors.Errors.Invalid;
import com.pglens.server.queries.QueryReadService;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;

/** Parses and bounds the read API's query parameters; a bad value is a 400 that says why. */
final class ApiParams {

  static final Map<String, Duration> WINDOWS =
      Map.of("24h", Duration.ofHours(24), "7d", Duration.ofDays(7), "30d", Duration.ofDays(30));

  static final Duration MAX_TREND_SPAN = Duration.ofDays(31);

  private ApiParams() {}

  /** A {@code queryid} path segment: the signed 64-bit id, sent as a string. */
  static long queryId(String raw) {
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException e) {
      throw new Invalid("A queryid is a signed 64-bit integer, sent as a string: '" + raw + "'.");
    }
  }

  static Duration window(String raw) {
    Duration d = WINDOWS.get(raw);
    if (d == null) {
      throw new Invalid("window must be one of 24h, 7d, 30d (got '" + raw + "').");
    }
    return d;
  }

  static String sort(String raw) {
    String s = raw.toLowerCase(Locale.ROOT);
    if (!s.equals("total") && !s.equals("mean") && !s.equals("calls")) {
      throw new Invalid("sort must be one of total, mean, calls (got '" + raw + "').");
    }
    return s;
  }

  static int limit(int value, int max) {
    if (value < 1 || value > max) {
      throw new Invalid("limit must be between 1 and " + max + " (got " + value + ").");
    }
    return value;
  }

  static int offset(int value) {
    if (value < 0) {
      throw new Invalid("offset must be 0 or more (got " + value + ").");
    }
    return value;
  }

  /** {@code auto} is null: the service picks by span. */
  static QueryReadService.Resolution resolution(String raw) {
    return switch (raw.toLowerCase(Locale.ROOT)) {
      case "auto" -> null;
      case "raw" -> QueryReadService.Resolution.RAW;
      case "hour" -> QueryReadService.Resolution.HOUR;
      default -> throw new Invalid("resolution must be raw, hour or auto (got '" + raw + "').");
    };
  }

  static Instant instant(String raw, String name) {
    try {
      return Instant.parse(raw);
    } catch (DateTimeParseException e) {
      throw new Invalid(name + " must be an ISO-8601 instant, e.g. 2026-09-26T10:00:00Z.");
    }
  }

  static void span(Instant from, Instant to) {
    if (!from.isBefore(to)) {
      throw new Invalid("from must be before to.");
    }
    if (Duration.between(from, to).compareTo(MAX_TREND_SPAN) > 0) {
      throw new Invalid("A trend covers at most 31 days; narrow from/to.");
    }
  }
}
