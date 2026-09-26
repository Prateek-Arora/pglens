package com.pglens.server.web;

import jakarta.servlet.http.HttpServletRequest;

/** Reads {@code Authorization: Bearer <token>}; the API accepts credentials nowhere else. */
final class BearerTokens {

  private static final String PREFIX = "Bearer ";

  private BearerTokens() {}

  static String from(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    if (header == null || !header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
      return null;
    }
    String token = header.substring(PREFIX.length()).strip();
    return token.isEmpty() ? null : token;
  }
}
