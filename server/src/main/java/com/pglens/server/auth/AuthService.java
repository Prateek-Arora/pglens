package com.pglens.server.auth;

import com.pglens.server.auth.AuthExceptions.LoginFailed;
import com.pglens.server.auth.AuthExceptions.LoginThrottled;
import com.pglens.server.auth.UserRepository.UserWithHash;
import com.pglens.server.errors.Errors.Conflict;
import com.pglens.server.errors.Errors.Invalid;
import com.pglens.server.errors.Errors.NotFound;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Users, login sessions and API tokens for the HTTP API (ADR-0044). Every token is random and
 * stored only as a SHA-256 hash; passwords use Spring's delegating encoder (bcrypt), so a copy of
 * the metadata DB yields nothing usable.
 */
@Service
public class AuthService {

  /** A new login: the session token is returned exactly once. */
  public record Login(String token, Instant expiresAt, User user) {}

  /** A new API token: the token is returned exactly once. */
  public record NewApiToken(String token, ApiToken info) {}

  private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
  static final int MIN_PASSWORD_CHARS = 12;
  // bcrypt only reads the first 72 bytes (Spring Security rejects longer); say so up front.
  static final int MAX_PASSWORD_BYTES = 72;

  private final UserRepository users;
  private final CredentialRepository credentials;
  private final AuthProperties props;
  private final LoginThrottle throttle;
  private final Clock clock;
  private final PasswordEncoder encoder =
      PasswordEncoderFactories.createDelegatingPasswordEncoder();
  // Compared against when the username doesn't exist, so a wrong username costs the same time as a
  // wrong password (no username discovery by timing).
  private final String dummyHash = encoder.encode("pglens-dummy-password");

  public AuthService(
      UserRepository users, CredentialRepository credentials, AuthProperties props, Clock clock) {
    this.users = users;
    this.credentials = credentials;
    this.props = props;
    this.throttle = new LoginThrottle(props.loginMaxFailures(), props.loginLockout());
    this.clock = clock;
  }

  public AuthProperties properties() {
    return props;
  }

  @Transactional
  public Login login(String username, String password) {
    Instant now = clock.instant();
    throttle
        .blockedFor(username, now)
        .ifPresent(
            wait -> {
              throw new LoginThrottled(wait);
            });
    Optional<UserWithHash> found = users.findByUsername(username == null ? "" : username);
    String presented = password == null ? "" : password;
    boolean ok;
    if (found.isPresent()) {
      ok = encoder.matches(presented, found.get().passwordHash());
    } else {
      encoder.matches(presented, dummyHash); // same cost as a real check; result ignored
      ok = false;
    }
    if (!ok) {
      throttle.recordFailure(username, now);
      throw new LoginFailed();
    }
    throttle.recordSuccess(username);
    User user = found.get().user();
    String token = Tokens.newToken(Tokens.SESSION_PREFIX);
    Instant expiresAt = now.plus(props.sessionMaxAge());
    credentials.createSession(Tokens.sha256Hex(token), user.id(), now, expiresAt);
    return new Login(token, expiresAt, user);
  }

  /** Resolves a presented bearer token to its caller, or empty if it is unknown or expired. */
  public Optional<Caller> authenticate(String token) {
    if (props.disabled()) {
      return Optional.of(Caller.authDisabled());
    }
    if (token == null) {
      return Optional.empty();
    }
    Instant now = clock.instant();
    String hash = Tokens.sha256Hex(token);
    if (token.startsWith(Tokens.SESSION_PREFIX)) {
      Optional<User> user =
          credentials.findLiveSession(hash, now, now.minus(props.sessionIdleTimeout()));
      user.ifPresent(u -> credentials.touchSession(hash, now));
      return user.map(u -> new Caller(u.id(), u.username(), u.role(), Caller.Via.SESSION));
    }
    if (token.startsWith(Tokens.API_PREFIX)) {
      Optional<User> user = credentials.findApiTokenOwner(hash);
      user.ifPresent(u -> credentials.touchApiToken(hash, now));
      return user.map(u -> new Caller(u.id(), u.username(), u.role(), Caller.Via.API_TOKEN));
    }
    return Optional.empty();
  }

  public void logout(String token) {
    if (token != null) {
      credentials.deleteSession(Tokens.sha256Hex(token));
    }
  }

  /** Changes the caller's own password and ends their other sessions (this one stays). */
  @Transactional
  public void changePassword(
      Caller caller, String currentPassword, String newPassword, String currentToken) {
    UserWithHash me = requireUser(caller);
    if (!encoder.matches(currentPassword == null ? "" : currentPassword, me.passwordHash())) {
      throw new Invalid("The current password is wrong.");
    }
    users.updatePassword(me.user().id(), encoder.encode(validPassword(newPassword)));
    credentials.deleteSessionsOf(
        me.user().id(), currentToken == null ? null : Tokens.sha256Hex(currentToken));
  }

  public List<User> listUsers() {
    return users.findAll();
  }

  @Transactional
  public User createUser(String username, String password, Role role) {
    String name = validUsername(username);
    String hash = encoder.encode(validPassword(password));
    return users
        .insert(name, hash, role == null ? Role.VIEWER : role)
        .orElseThrow(() -> new Conflict("A user named '" + name + "' already exists."));
  }

  /** Deletes a user and everything they own (sessions, API tokens). */
  @Transactional
  public void deleteUser(Caller caller, String username) {
    User target = requireByName(username);
    if (caller.userId() != null && caller.userId() == target.id()) {
      throw new Conflict("You can't delete yourself.");
    }
    if (target.role() == Role.ADMIN && users.countAdmins() <= 1) {
      throw new Conflict("You can't delete the last admin.");
    }
    users.delete(target.id());
  }

  /** An admin sets a user's password; all of that user's sessions end. */
  @Transactional
  public void resetPassword(String username, String newPassword) {
    User target = requireByName(username);
    users.updatePassword(target.id(), encoder.encode(validPassword(newPassword)));
    credentials.deleteSessionsOf(target.id(), null);
  }

  @Transactional
  public NewApiToken createApiToken(Caller caller, String name) {
    UserWithHash me = requireUser(caller);
    if (name == null || name.isBlank() || name.length() > 64) {
      throw new Invalid("A token name is required (up to 64 characters).");
    }
    String token = Tokens.newToken(Tokens.API_PREFIX);
    ApiToken info =
        credentials
            .createApiToken(me.user().id(), name.strip(), Tokens.sha256Hex(token))
            .orElseThrow(() -> new Conflict("You already have a token named '" + name + "'."));
    return new NewApiToken(token, info);
  }

  public List<ApiToken> listApiTokens(Caller caller) {
    return credentials.apiTokensOf(requireUser(caller).user().id());
  }

  public void revokeApiToken(Caller caller, long tokenId) {
    if (!credentials.deleteApiToken(requireUser(caller).user().id(), tokenId)) {
      throw new NotFound("No API token " + tokenId + ".");
    }
  }

  /**
   * Creates the first admin when there are no users. Returns the password only when PgLens
   * generated it (so the caller can show it once); empty when users exist or it was configured.
   */
  @Transactional
  public Optional<String> bootstrapAdmin() {
    if (users.count() > 0) {
      return Optional.empty();
    }
    boolean generate = props.adminPassword() == null || props.adminPassword().isBlank();
    String password =
        generate
            ? Tokens.newToken("").substring(0, 24)
            : validPassword(props.adminPassword(), "PGLENS_ADMIN_PASSWORD");
    Optional<User> created =
        users.insert(validUsername(props.adminUsername()), encoder.encode(password), Role.ADMIN);
    return created.isPresent() && generate ? Optional.of(password) : Optional.empty();
  }

  /** Deletes expired or idle sessions; returns how many. */
  public int pruneSessions() {
    Instant now = clock.instant();
    return credentials.pruneSessions(now, now.minus(props.sessionIdleTimeout()));
  }

  private UserWithHash requireUser(Caller caller) {
    if (caller.userId() == null) {
      throw new Conflict("This needs a logged-in user; authentication is disabled on this server.");
    }
    return users
        .findById(caller.userId())
        .orElseThrow(() -> new NotFound("Your user no longer exists."));
  }

  private User requireByName(String username) {
    return users
        .findByUsername(username == null ? "" : username)
        .map(UserWithHash::user)
        .orElseThrow(() -> new NotFound("No user named '" + username + "'."));
  }

  static String validUsername(String username) {
    if (username == null || !USERNAME.matcher(username).matches()) {
      throw new Invalid("A username is 1–64 characters: letters, digits, '.', '_' or '-'.");
    }
    return username;
  }

  static String validPassword(String password) {
    return validPassword(password, "The password");
  }

  private static String validPassword(String password, String what) {
    if (password == null || password.codePointCount(0, password.length()) < MIN_PASSWORD_CHARS) {
      throw new Invalid(what + " must be at least " + MIN_PASSWORD_CHARS + " characters.");
    }
    if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
      throw new Invalid(
          what + " must be at most " + MAX_PASSWORD_BYTES + " bytes (bcrypt ignores the rest).");
    }
    return password;
  }
}
