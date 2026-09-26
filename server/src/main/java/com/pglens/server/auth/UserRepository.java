package com.pglens.server.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The {@code users} table (V9). Usernames are matched case-insensitively, stored as given. */
@Repository
class UserRepository {

  /** A user row together with its password hash — only {@link AuthService} sees this. */
  record UserWithHash(User user, String passwordHash) {}

  private static final String COLUMNS = "id, username, role, created_at, password_hash";

  private final JdbcTemplate jdbc;

  UserRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  Optional<UserWithHash> findByUsername(String username) {
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM users WHERE lower(username) = lower(?)",
            UserRepository::map,
            username)
        .stream()
        .findFirst();
  }

  Optional<UserWithHash> findById(long id) {
    return jdbc
        .query("SELECT " + COLUMNS + " FROM users WHERE id = ?", UserRepository::map, id)
        .stream()
        .findFirst();
  }

  List<User> findAll() {
    return jdbc
        .query("SELECT " + COLUMNS + " FROM users ORDER BY lower(username)", UserRepository::map)
        .stream()
        .map(UserWithHash::user)
        .toList();
  }

  long count() {
    return jdbc.queryForObject("SELECT count(*) FROM users", Long.class);
  }

  long countAdmins() {
    return jdbc.queryForObject("SELECT count(*) FROM users WHERE role = 'ADMIN'", Long.class);
  }

  boolean usernameTaken(String username) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM users WHERE lower(username) = lower(?))",
            Boolean.class,
            username));
  }

  /** Inserts a user; returns empty if the username already exists (concurrent bootstrap). */
  Optional<User> insert(String username, String passwordHash, Role role) {
    return jdbc
        .query(
            "INSERT INTO users (username, password_hash, role) VALUES (?, ?, ?) "
                + "ON CONFLICT ((lower(username))) DO NOTHING RETURNING "
                + COLUMNS,
            UserRepository::map,
            username,
            passwordHash,
            role.name())
        .stream()
        .findFirst()
        .map(UserWithHash::user);
  }

  void updatePassword(long userId, String passwordHash) {
    jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", passwordHash, userId);
  }

  void delete(long userId) {
    jdbc.update("DELETE FROM users WHERE id = ?", userId);
  }

  private static UserWithHash map(ResultSet rs, int n) throws SQLException {
    return new UserWithHash(
        new User(
            rs.getLong("id"),
            rs.getString("username"),
            Role.valueOf(rs.getString("role")),
            rs.getTimestamp("created_at").toInstant()),
        rs.getString("password_hash"));
  }
}
