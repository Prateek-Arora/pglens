package com.pglens.server.auth;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** An API token as its owner sees it — never the token or its hash. */
public record ApiToken(long id, String name, Instant createdAt, @Nullable Instant lastUsedAt) {}
