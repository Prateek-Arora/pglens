package com.pglens.server.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Bearer tokens — agent tokens (ADR-0027), login sessions and API tokens (ADR-0044). A token is 32
 * random bytes, base64url, behind a short prefix that says what it is (so a leaked one is easy to
 * recognise and grep for). Only the SHA-256 hex digest is ever stored: 256 bits of randomness need
 * no slow hash, unlike a human-chosen password.
 */
public final class Tokens {

  /** A dashboard login session. */
  public static final String SESSION_PREFIX = "pglens_s_";

  /** A named, read-only API token for scripts. */
  public static final String API_PREFIX = "pglens_a_";

  /** An agent's token for the gRPC channel. */
  public static final String AGENT_PREFIX = "pglens_g_";

  private static final SecureRandom RANDOM = new SecureRandom();

  private Tokens() {}

  /** A new random token: {@code prefix} + 43 base64url characters (32 bytes). */
  public static String newToken(String prefix) {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static String sha256Hex(String token) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e); // never on a standard JRE
    }
  }
}
