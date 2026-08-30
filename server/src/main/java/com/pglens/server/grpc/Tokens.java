package com.pglens.server.grpc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Hashing for per-agent bearer tokens — only the SHA-256 hex digest is ever stored (ADR-0027). */
public final class Tokens {

  private Tokens() {}

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
