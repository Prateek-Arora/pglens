package com.pglens.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pglens.server.errors.Errors;
import org.junit.jupiter.api.Test;

/** The pure rules behind the auth API: names, password bounds, token shape, read-only tokens. */
class AuthRulesTest {

  @Test
  void usernamesAreShortAndPlain() {
    assertThat(AuthService.validUsername("ana.m-2_x")).isEqualTo("ana.m-2_x");
    assertThatThrownBy(() -> AuthService.validUsername("ana m")).isInstanceOf(Errors.Invalid.class);
    assertThatThrownBy(() -> AuthService.validUsername("x".repeat(65)))
        .isInstanceOf(Errors.Invalid.class);
  }

  @Test
  void passwordsNeedTwelveCharactersAndFitBcrypt() {
    assertThat(AuthService.validPassword("twelve chars")).isEqualTo("twelve chars");
    assertThatThrownBy(() -> AuthService.validPassword("short"))
        .isInstanceOf(Errors.Invalid.class)
        .hasMessageContaining("12");
    // 36 two-byte characters = 72 bytes: the most bcrypt reads.
    assertThat(AuthService.validPassword("é".repeat(36))).hasSize(36);
    assertThatThrownBy(() -> AuthService.validPassword("é".repeat(37)))
        .isInstanceOf(Errors.Invalid.class)
        .hasMessageContaining("72 bytes");
  }

  @Test
  void tokensArePrefixedRandomAndOnlyTheirHashIsKept() {
    String a = Tokens.newToken(Tokens.SESSION_PREFIX);
    String b = Tokens.newToken(Tokens.SESSION_PREFIX);

    assertThat(a).startsWith("pglens_s_").hasSize("pglens_s_".length() + 43).isNotEqualTo(b);
    assertThat(Tokens.sha256Hex(a)).hasSize(64).doesNotContain(a);
  }

  @Test
  void anApiTokenIsReadOnlyWhateverItsOwnersRole() {
    assertThat(new Caller(1L, "admin", Role.ADMIN, Caller.Via.API_TOKEN).role())
        .isEqualTo(Role.VIEWER);
    assertThat(new Caller(1L, "admin", Role.ADMIN, Caller.Via.SESSION).role())
        .isEqualTo(Role.ADMIN);
  }
}
