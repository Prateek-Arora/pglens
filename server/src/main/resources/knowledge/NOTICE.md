# PostgreSQL documentation excerpts

`pg16-docs.jsonl` holds passages from the PostgreSQL 16 documentation (indexes, `EXPLAIN`, planner
statistics, joins, GIN, jsonb indexing), split by section heading with `chunk_pgdocs.py`. Each row
keeps the source URL. PgLens embeds them into pgvector to retrieve reference text and "further
reading" links for explanations (Phase 3, ADR-0043).

Portions Copyright © 1996–2026, The PostgreSQL Global Development Group.
Portions Copyright © 1994, The Regents of the University of California.

Used under the PostgreSQL Licence, which covers "this software and its documentation":
https://www.postgresql.org/about/licence/

> Permission to use, copy, modify, and distribute this software and its documentation for any
> purpose, without fee, and without a written agreement is hereby granted, provided that the above
> copyright notice and this paragraph and the following two paragraphs appear in all copies.
>
> IN NO EVENT SHALL THE UNIVERSITY OF CALIFORNIA BE LIABLE TO ANY PARTY FOR DIRECT, INDIRECT,
> SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, INCLUDING LOST PROFITS, ARISING OUT OF THE USE OF
> THIS SOFTWARE AND ITS DOCUMENTATION, EVEN IF THE UNIVERSITY OF CALIFORNIA HAS BEEN ADVISED OF THE
> POSSIBILITY OF SUCH DAMAGE.
>
> THE UNIVERSITY OF CALIFORNIA SPECIFICALLY DISCLAIMS ANY WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
> THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE. THE SOFTWARE
> PROVIDED HEREUNDER IS ON AN "AS-IS" BASIS, AND THE UNIVERSITY OF CALIFORNIA HAS NO OBLIGATIONS TO
> PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR MODIFICATIONS.

Regenerate: download the pages listed in `chunk_pgdocs.py`'s header from
`https://www.postgresql.org/docs/16/<page>.html`, then
`python3 chunk_pgdocs.py pg16-docs.jsonl <page>.html…`. Bump `KnowledgeLoader.CORPUS_VERSION`
whenever this file changes so servers re-embed it.
