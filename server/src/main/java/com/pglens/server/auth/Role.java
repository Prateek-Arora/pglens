package com.pglens.server.auth;

/** What a caller may do through the HTTP API (ADR-0044). Deliberately just two. */
public enum Role {
  /** Everything a viewer can, plus users, API tokens and monitored-database registration. */
  ADMIN,
  /** Read-only: every report, trend and recommendation. */
  VIEWER
}
