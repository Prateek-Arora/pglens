-- Phase 3 (ADR-0043): plain-language explanations.
--
-- `vector` is not a trusted extension, so creating it needs a superuser. The compose metadata-db
-- (pgvector/pgvector image) already creates it at initdb; IF NOT EXISTS makes this a no-op there
-- and documents the requirement everywhere else.
CREATE EXTENSION IF NOT EXISTS vector;

-- PostgreSQL-docs passages embedded for retrieval. One row per (corpus version, embedding model,
-- chunk): changing either re-embeds rather than mixing vectors from different models.
CREATE TABLE knowledge_chunks (
    corpus_version  TEXT        NOT NULL,
    embedding_model TEXT        NOT NULL,
    chunk_id        TEXT        NOT NULL,
    url             TEXT        NOT NULL,
    title           TEXT        NOT NULL,
    content         TEXT        NOT NULL,
    embedding       vector(768) NOT NULL,   -- nomic-embed-text v1.5
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (corpus_version, embedding_model, chunk_id)
);

-- Approximate nearest-neighbour search by cosine distance (`<=>`). At a few hundred rows an exact
-- scan is as fast; the index is here because the eval measures HNSW recall against exact search.
CREATE INDEX knowledge_chunks_embedding_hnsw
    ON knowledge_chunks USING hnsw (embedding vector_cosine_ops);

-- Cached explanations, one per exact input: the facts' hash + model + prompt version, so a new
-- model or prompt regenerates instead of serving stale text. Template fallbacks are cached too
-- (with their reason); a fallback caused by an outage carries retry_after so it is regenerated once
-- the model is back, while a guard failure (deterministic at temperature 0) is kept.
CREATE TABLE explanations (
    db_id           BIGINT      NOT NULL REFERENCES monitored_dbs (id) ON DELETE CASCADE,
    queryid         BIGINT      NOT NULL,
    ddl             TEXT        NOT NULL,
    facts_hash      TEXT        NOT NULL,
    model           TEXT        NOT NULL,
    prompt_version  TEXT        NOT NULL,
    source          TEXT        NOT NULL,   -- LLM | TEMPLATE
    summary         TEXT        NOT NULL,
    why_it_is_slow  TEXT        NOT NULL,
    what_changes    TEXT        NOT NULL,
    fallback_reason TEXT,
    violations      TEXT[]      NOT NULL DEFAULT '{}',
    docs            TEXT[]      NOT NULL DEFAULT '{}',
    retry_after     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (db_id, queryid, ddl, facts_hash, model, prompt_version)
);

-- The dashboard (Phase 4) reads the newest explanation per index.
CREATE INDEX explanations_by_index ON explanations (db_id, ddl, created_at DESC);
