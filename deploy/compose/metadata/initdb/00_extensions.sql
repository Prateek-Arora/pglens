-- PgLens's own metadata store. Enables pgvector for the RAG embeddings added
-- in Phase 3. Stood up in Phase 0 so the dev environment is complete.
CREATE EXTENSION IF NOT EXISTS vector;
