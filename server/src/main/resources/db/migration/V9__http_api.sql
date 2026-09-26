-- Phase 4A (ADR-0044): the HTTP API — people and scripts using it, and agent liveness.
--
-- Every credential is stored the way agent tokens are (ADR-0027): passwords as a salted, slow hash
-- (Spring's delegating encoder, "{bcrypt}..."), bearer tokens as the SHA-256 hex of a random
-- 32-byte value. A leaked copy of this database therefore yields no usable token or password.

CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username      TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,
    role          TEXT        NOT NULL CHECK (role IN ('ADMIN', 'VIEWER')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Login matches usernames case-insensitively, so "Eve" and "eve" must be one user.
CREATE UNIQUE INDEX users_username_lower_key ON users (lower(username));

-- Dashboard logins. A session ends at whichever comes first: idle past the idle timeout
-- (last_used_at) or its absolute expiry (expires_at). Logout and password changes delete rows.
CREATE TABLE sessions (
    token_hash   TEXT        PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX sessions_user_id_idx ON sessions (user_id);

-- Named, long-lived, READ-ONLY tokens for scripts and CI (the API is a product surface, not only
-- the dashboard's backend). Revoking one deletes its row.
CREATE TABLE api_tokens (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name         TEXT        NOT NULL,
    token_hash   TEXT        NOT NULL UNIQUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ,
    UNIQUE (user_id, name)
);

-- When the server last accepted a batch from this database's agent — the dashboard's "is the agent
-- alive?" signal. Recorded per batch (not inferred from query rows: an idle database sends few).
ALTER TABLE monitored_dbs ADD COLUMN last_ingest_at TIMESTAMPTZ;
