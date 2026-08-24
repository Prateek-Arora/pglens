# PgLens — Glossary

> Domain terms, so any session (and the teacher skill) shares vocabulary. Add terms as they come up.

- **pg_stat_statements** — Postgres extension that tracks execution stats (calls, total/mean time, rows) per normalized query. PgLens's primary data source on the monitored DB.
- **EXPLAIN / EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)** — shows the planner's chosen execution plan; `ANALYZE` actually runs the query to get real timings/rows. PgLens uses plan-only `EXPLAIN` by default; `ANALYZE` only in `BEGIN; … ROLLBACK` and never on non-SELECT.
- **HypoPG** — Postgres extension for **hypothetical indexes**: create an index that exists only in the planner's mind, ask the planner to re-cost a query, then drop it. Yields a real planner-cost delta without building the index. Covers **btree/brin/hash/bloom + partial**; cannot simulate **GIN/GiST**.
- **Planner cost delta** — difference in the planner's estimated cost with vs without a hypothetical index. An *estimate* from the cost model — not a measured runtime. Always labeled as such.
- **Anti-pattern (detector)** — a heuristically-detected problem in a plan/query: sequential scan on a large table, missing index, bad row estimates, etc.
- **Access method (AM)** — the index type in Postgres (btree, hash, brin, gin, gist, bloom …). Relevant because HypoPG only supports some.
- **RAG (Retrieval-Augmented Generation)** — grounding the LLM's explanation in retrieved Postgres docs + schema so it explains facts rather than inventing them.
- **pgvector** — Postgres extension for vector similarity search; stores embeddings for RAG in the metadata DB.
- **Ollama** — runs LLMs locally; keeps query text inside the user's infra (privacy differentiator).
- **Monitored DB** — the user's Postgres that PgLens observes **read-only**.
- **Metadata DB** — PgLens's own Postgres (with pgvector) holding snapshots, recs, embeddings, trends.
- **Collector / agent (`pglens-agent`)** — lightweight process that reads stats from the monitored DB and streams to the server over gRPC.
