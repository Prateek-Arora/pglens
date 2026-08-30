package com.pglens.agent.sample;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Pure tests for the content hash: stable, hex, and a known SHA-256 vector. */
class TextHashTest {

  @Test
  void isDeterministicForTheSameText() {
    String sql = "select * from orders where status = $1";
    assertThat(TextHash.sha256Hex(sql)).isEqualTo(TextHash.sha256Hex(sql));
  }

  @Test
  void differsForDifferentText() {
    assertThat(TextHash.sha256Hex("select 1")).isNotEqualTo(TextHash.sha256Hex("select 2"));
  }

  @Test
  void isLowercaseHexOf64Chars() {
    assertThat(TextHash.sha256Hex("anything")).hasSize(64).matches("[0-9a-f]{64}");
  }

  @Test
  void matchesTheKnownEmptyStringVector() {
    // SHA-256("") — guards against an accidental algorithm/encoding change; null hashes as "".
    String emptyDigest = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    assertThat(TextHash.sha256Hex("")).isEqualTo(emptyDigest);
    assertThat(TextHash.sha256Hex(null)).isEqualTo(emptyDigest);
  }
}
