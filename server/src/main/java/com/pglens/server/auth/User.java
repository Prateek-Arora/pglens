package com.pglens.server.auth;

import java.time.Instant;

/** A person who can log in. The password hash never leaves the auth package. */
public record User(long id, String username, Role role, Instant createdAt) {}
