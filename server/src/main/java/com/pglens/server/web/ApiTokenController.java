package com.pglens.server.web;

import com.pglens.server.auth.ApiToken;
import com.pglens.server.auth.AuthService;
import com.pglens.server.auth.Caller;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Your own read-only API tokens for scripts and CI (ADR-0044). Managing them needs a login session:
 * an API token can't mint or revoke tokens (no WRITE authority).
 */
@RestController
@RequestMapping("/api/v1/api-tokens")
class ApiTokenController {

  /** The token appears here once and is never retrievable again. */
  record CreatedToken(String token, ApiToken info) {}

  record NewToken(String name) {}

  private final AuthService auth;

  ApiTokenController(AuthService auth) {
    this.auth = auth;
  }

  @GetMapping
  List<ApiToken> list(@AuthenticationPrincipal Caller caller) {
    return auth.listApiTokens(caller);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  CreatedToken create(@AuthenticationPrincipal Caller caller, @RequestBody NewToken body) {
    AuthService.NewApiToken t = auth.createApiToken(caller, body.name());
    return new CreatedToken(t.token(), t.info());
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void revoke(@AuthenticationPrincipal Caller caller, @PathVariable long id) {
    auth.revokeApiToken(caller, id);
  }
}
