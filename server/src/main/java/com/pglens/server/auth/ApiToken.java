package com.pglens.server.auth;

import java.time.Instant;

/** An API token as its owner sees it — never the token or its hash. */
public record ApiToken(long id, String name, Instant createdAt, Instant lastUsedAt) {}
