package com.pglens.explain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A stable hash of exactly what the model is shown about one index — the server's cache key, so an
 * explanation is regenerated when the facts change and reused when they don't. Pure.
 */
public final class FactsHash {

  private FactsHash() {}

  public static String of(ExplanationFacts facts) {
    try {
      byte[] json = PromptBuilder.JSON.writeValueAsBytes(facts);
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(json))
          .substring(0, 32);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    } catch (Exception e) {
      throw new IllegalStateException("facts not serializable", e);
    }
  }
}
