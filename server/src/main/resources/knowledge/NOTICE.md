# PostgreSQL documentation excerpts

`pg16-docs.jsonl` holds passages from the PostgreSQL 16 documentation (indexes, `EXPLAIN`, planner
statistics, joins, GIN, jsonb indexing), split by section heading with `chunk_pgdocs.py`. Each row
keeps the source URL. PgLens embeds them into pgvector to retrieve reference text and "further
reading" links for explanations (Phase 3, ADR-0043).

Portions Copyright © 1996–2026, The PostgreSQL Global Development Group.
Portions Copyright © 1994, The Regents of the University of California.

Used under the PostgreSQL Licence, which covers "this software and its documentation":
https://www.postgresql.org/about/licence/

Regenerate: download the pages listed in `chunk_pgdocs.py`'s header from
`https://www.postgresql.org/docs/16/<page>.html`, then
`python3 chunk_pgdocs.py pg16-docs.jsonl <page>.html…`. Bump `KnowledgeLoader.CORPUS_VERSION`
whenever this file changes so servers re-embed it.
