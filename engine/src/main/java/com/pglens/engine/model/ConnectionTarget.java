package com.pglens.engine.model;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * A parsed Postgres connection target: a JDBC URL plus credentials, derived from a libpq-style
 * connection string ({@code postgresql://user:pw@host:port/db}) or an existing {@code
 * jdbc:postgresql://} URL. Pure — no I/O.
 */
public record ConnectionTarget(String jdbcUrl, String user, String password, String database) {

  /**
   * Parses a libpq-style or {@code jdbc:}-prefixed Postgres URL. Query parameters (e.g. {@code
   * ?sslmode=require}) are carried through to the JDBC URL unchanged.
   */
  public static ConnectionTarget parse(String connectionString) {
    if (connectionString == null || connectionString.isBlank()) {
      throw new IllegalArgumentException("Empty connection string.");
    }
    String trimmed = connectionString.trim();
    String work = trimmed.startsWith("jdbc:") ? trimmed.substring("jdbc:".length()) : trimmed;

    final URI uri;
    try {
      uri = new URI(work);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("Not a valid connection string: " + connectionString, e);
    }

    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equals("postgresql") || scheme.equals("postgres"))) {
      throw new IllegalArgumentException(
          "Connection string must start with postgresql:// (or jdbc:postgresql://): "
              + connectionString);
    }

    String host = uri.getHost() != null ? uri.getHost() : "localhost";
    int port = uri.getPort() != -1 ? uri.getPort() : 5432;

    String path = uri.getPath();
    String database = (path != null && path.length() > 1) ? path.substring(1) : null;
    if (database == null) {
      throw new IllegalArgumentException(
          "Connection string is missing a database name: " + connectionString);
    }

    String user = null;
    String password = null;
    String userInfo = uri.getUserInfo();
    if (userInfo != null && !userInfo.isEmpty()) {
      int colon = userInfo.indexOf(':');
      if (colon >= 0) {
        user = userInfo.substring(0, colon);
        password = userInfo.substring(colon + 1);
      } else {
        user = userInfo;
      }
    }

    String query = uri.getRawQuery();
    String jdbcUrl =
        "jdbc:postgresql://"
            + host
            + ":"
            + port
            + "/"
            + database
            + (query != null ? "?" + query : "");
    return new ConnectionTarget(jdbcUrl, user, password, database);
  }
}
