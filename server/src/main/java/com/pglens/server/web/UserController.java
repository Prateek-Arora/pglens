package com.pglens.server.web;

import com.pglens.server.auth.AuthService;
import com.pglens.server.auth.Caller;
import com.pglens.server.auth.Role;
import com.pglens.server.auth.User;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** User management — ADMIN only (enforced in {@link SecurityConfig}). */
@RestController
@RequestMapping("/api/v1/users")
class UserController {

  record UserView(String username, Role role, Instant createdAt) {
    static UserView of(User u) {
      return new UserView(u.username(), u.role(), u.createdAt());
    }
  }

  record NewUser(String username, String password, Role role) {}

  record NewPassword(String password) {}

  private final AuthService auth;

  UserController(AuthService auth) {
    this.auth = auth;
  }

  @GetMapping
  List<UserView> list() {
    return auth.listUsers().stream().map(UserView::of).toList();
  }

  /** Creates a user; the role defaults to VIEWER. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  UserView create(@RequestBody NewUser body) {
    return UserView.of(auth.createUser(body.username(), body.password(), body.role()));
  }

  @DeleteMapping("/{username}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@AuthenticationPrincipal Caller caller, @PathVariable String username) {
    auth.deleteUser(caller, username);
  }

  /** Sets a user's password; all of their sessions end. */
  @PutMapping("/{username}/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void resetPassword(@PathVariable String username, @RequestBody NewPassword body) {
    auth.resetPassword(username, body.password());
  }
}
