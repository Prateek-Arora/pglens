package com.pglens.agent.sample;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stable content hash of a normalized query text. Used as the {@code text_hash} the agent puts on
 * each sample and registration so the server (and future dedup) can key a text by its content. A
 * query's {@code queryid} already identifies its shape; the hash is a secondary, content-derived
 * key that does not depend on the server's queryid scheme.
 */
public final class TextHash {

  private TextHash() {}

  /** Lowercase hex SHA-256 of {@code text} (UTF-8); {@code null} hashes as the empty string. */
  public static String sha256Hex(String text) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required but unavailable", e);
    }
  }
}
