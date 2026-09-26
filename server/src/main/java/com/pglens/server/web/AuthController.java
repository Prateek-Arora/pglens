package com.pglens.server.web;

import com.pglens.server.auth.AuthService;
import com.pglens.server.auth.Caller;
import com.pglens.server.auth.Role;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Login, logout and "who am I" (ADR-0044). */
@RestController
@RequestMapping("/api/v1")
class AuthController {

  record LoginRequest(String username, String password) {}

  record LoginResponse(String token, Instant expiresAt, UserView user) {}

  record UserView(String username, Role role) {}

  /**
   * @param via how this request authenticated: SESSION, API_TOKEN or AUTH_DISABLED
   * @param authRequired false when the server runs with {@code pglens.auth.mode=none}
   */
  record Me(String username, Role role, Caller.Via via, boolean authRequired) {}

  record PasswordChange(String currentPassword, String newPassword) {}

  private final AuthService auth;

  AuthController(AuthService auth) {
    this.auth = auth;
  }

  /** The session token is in the body exactly once; send it back as a bearer token. */
  @PostMapping("/auth/login")
  LoginResponse login(@RequestBody LoginRequest body) {
    AuthService.Login login = auth.login(body.username(), body.password());
    return new LoginResponse(
        login.token(),
        login.expiresAt(),
        new UserView(login.user().username(), login.user().role()));
  }

  @PostMapping("/auth/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void logout(HttpServletRequest request) {
    auth.logout(BearerTokens.from(request));
  }

  @GetMapping("/me")
  Me me(@AuthenticationPrincipal Caller caller) {
    return new Me(caller.username(), caller.role(), caller.via(), !auth.properties().disabled());
  }

  /** Changes your own password; your other sessions end, this one stays. */
  @PutMapping("/me/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void changePassword(
      @AuthenticationPrincipal Caller caller,
      @RequestBody PasswordChange body,
      HttpServletRequest request) {
    auth.changePassword(
        caller, body.currentPassword(), body.newPassword(), BearerTokens.from(request));
  }
}
