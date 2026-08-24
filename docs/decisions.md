# PgLens — Decision Log (ADRs)

> Append-only record of **why** we chose what we chose. The teacher skill and future sessions rely on this. Newest decisions at the bottom of the detail section; the index table below is the fast scan. When a decision is reversed, don't delete it — add a new ADR that supersedes it and mark the old one **Superseded**.

## Index
| ID | Date | Decision | Status |
|---|---|---|---|
| [ADR-0001](#adr-0001) | 2026-08-21 | Collector/server language = Java + Spring Boot | Accepted |
| [ADR-0002](#adr-0002) | 2026-08-21 | Scope = full arc (0–7); ship-after-Phase-4 is the plan of record | Accepted |
| [ADR-0003](#adr-0003) | 2026-08-21 | Two Postgres instances (monitored read-only + metadata w/ pgvector) | Accepted |
| [ADR-0004](#adr-0004) | 2026-08-21 | Deterministic core, LLM as an optional layer | Accepted |
| [ADR-0005](#adr-0005) | 2026-08-21 | HypoPG for index validation (real planner cost delta) | Accepted |
| [ADR-0006](#adr-0006) | 2026-08-21 | Local LLM (Ollama) for privacy; query text never leaves infra | Accepted |
| [ADR-0007](#adr-0007) | 2026-08-22 | AI-context system: lean CLAUDE.md + progressive-disclosure docs + separate memory | Accepted |
| [ADR-0008](#adr-0008) | 2026-08-22 | Verify-before-plan workflow for every user-provided phase plan | Accepted |
| [ADR-0009](#adr-0009) | 2026-08-24 | License = Apache-2.0 | Accepted |
| [ADR-0010](#adr-0010) | 2026-08-24 | Phase 0 tooling: defer Java build tooling; CI = lint-artifacts + compose smoke | Accepted |
| [ADR-0011](#adr-0011) | 2026-08-24 | Postgres image strategy: custom monitored image (hypopg via PGDG), pinned versions, pg16 | Accepted |

---

## ADR-0001
**Collector/server language = Java + Spring Boot** · 2026-08-21 · Accepted
- **Context:** Need a language/framework for the collector agent and central server; also aligning with the user's job-search skill targets (Java/Spring is a "have").
- **Decision:** Java 21 + Spring Boot for both agent and server. `[ASSUMPTION: Java 21 until Phase 0 pins exact versions.]`
- **Alternatives:** Go (great gRPC ergonomics, but off the user's skill target) · Node/TS (already covered by the dashboard).
- **Consequences:** Production-shaped, keeps the tool real; slightly heavier than Go for a collector agent. Pin versions in Phase 0.

## ADR-0002
**Scope = full arc (Phases 0–7); ship-after-Phase-4 is the plan of record** · 2026-08-21 · Accepted
- **Context:** ~10 hrs/week bandwidth; full arc re-baselined to ~24–34 weeks (gRPC/RAG/K8s/Terraform all from zero).
- **Decision:** Build toward the full arc but treat **Phase 4** (`v0.1.0-rc`, self-hostable web tool) as the real ship target; Phases 5–7 = v0.2.
- **Alternatives:** Commit to full 0–7 upfront (scope-creep risk) · Stop at Phase 1 CLI (under-delivers on skills).
- **Consequences:** Hard shippable checkpoints at Phases 1, 4, 7 protect momentum; 5–7 explicitly deferrable.

## ADR-0003
**Two Postgres instances: monitored (read-only) + metadata (pgvector)** · 2026-08-21 · Accepted
- **Context:** Must never mutate the user's monitored DB; also need our own store for snapshots/recs/embeddings.
- **Decision:** Monitored DB touched read-only only; PgLens runs a separate metadata Postgres with pgvector.
- **Alternatives:** Store metadata inside the monitored DB (violates safe-by-default; pollutes user's DB).
- **Consequences:** Clean safety boundary; a real growing schema to dogfood indexing on; one more service to run (acceptable, Compose-managed).

## ADR-0004
**Deterministic core, LLM as an optional layer** · 2026-08-21 · Accepted
- **Context:** LLM tools that "explain plans" are commoditizing and can hallucinate; trust is the product.
- **Decision:** The analyzer produces a correct, explainable result with zero LLM. The LLM only phrases facts the core owns. Tool is fully useful with the LLM off.
- **Alternatives:** LLM-first analysis (untrustworthy, non-reproducible).
- **Consequences:** Trustworthy output; graceful degradation; clear boundary the LLM cannot cross (it never invents numbers or DDL).

## ADR-0005
**HypoPG for index validation** · 2026-08-21 · Accepted
- **Context:** Suggesting an index is easy; proving it helps without building it (minutes on big tables) is the hard, valuable part.
- **Decision:** Validate recommendations with HypoPG (hypothetical indexes) → real planner-cost delta. Only surface recs whose cost win clears a threshold.
- **Alternatives:** Actually build indexes to measure (slow, invasive) · pure heuristics (false positives).
- **Consequences:** Honest "expected improvement." **Limitation:** HypoPG covers btree/brin/hash/bloom + partial only; GIN/GiST (LIKE `%…%`, jsonb, full-text) are surfaced but labeled *not planner-validated* — must catch the unsupported-AM error. (See ADR linkage in `docs/architecture.md`.)

## ADR-0006
**Local LLM (Ollama) for privacy** · 2026-08-21 · Accepted
- **Context:** Target user wants query text to stay inside their infra; hosted AI-EXPLAIN tools send it out.
- **Decision:** Use local Ollama; no query text sent to any third-party LLM service.
- **Alternatives:** Hosted LLM API (better quality, breaks the privacy differentiator).
- **Consequences:** Sharpest differentiator vs competitors; quality bounded by local models; degrade gracefully when none configured. **Claim narrowed to:** "no query text is sent to a third-party LLM."

## ADR-0007
**AI-context system: lean CLAUDE.md + progressive-disclosure docs + separate memory** · 2026-08-22 · Accepted
- **Context:** Long, multi-session project at ~10 hrs/wk; need low token usage and correct context recovery every session. Researched 2026 best practices (Anthropic context-engineering guidance + CLAUDE.md guides).
- **Decision:** (a) `CLAUDE.md` < 200 lines = lean pointers only, loaded every turn. (b) Detailed context in `docs/` read on demand (project/architecture/decisions/phases/workflows/glossary). (c) Path-scoped `.claude/rules/*.md` for module-local conventions (loaded only when matching files are touched). (d) Keep repo docs (authored truth) separate from Claude auto-memory (`MEMORY.md` index = accumulated learnings). (e) `docs/project.md` is the living external-memory status doc read first each session.
- **Alternatives:** One big CLAUDE.md (burns budget every turn, degrades accuracy past a threshold) · docs only in memory (don't travel with the repo).
- **Consequences:** Lower per-turn token cost; fast context recovery; clear ownership of each info type. Requires discipline: update `project.md`/`decisions.md` as work happens (encoded in the CLAUDE.md session protocol).

## ADR-0008
**Verify-before-plan workflow for every user-provided phase plan** · 2026-08-22 · Accepted
- **Context:** User will supply phase plans; they must be fact-checked and reconciled with existing architecture/decisions before execution, not implemented blindly.
- **Decision:** Every phase plan runs through `docs/workflows/plan-verification.md` (feasibility check → conflict check vs architecture/decisions → gaps/risks surfaced → thorough plan with DoD/verification/skips → user go-ahead) before implementation.
- **Alternatives:** Implement plans as given (risks building on wrong assumptions).
- **Consequences:** Slower start per phase, higher correctness; produces a paper trail the teacher skill can use.

## ADR-0009
**License = Apache-2.0** · 2026-08-24 · Accepted
- **Context:** Phase 0 needs a license from commit 1. MIT (matches PgHero) vs Apache-2.0.
- **Decision:** Apache-2.0.
- **Alternatives:** MIT (shorter, permissive, but no explicit patent grant).
- **Consequences:** Explicit patent grant + the norm for infrastructure/DB tooling (K8s, Terraform, CNCF); friendlier to contributor/company adoption. Slightly longer `LICENSE` file. `CONTRIBUTING.md` states contributions are inbound under Apache-2.0.

## ADR-0010
**Phase 0 tooling: defer Java build tooling; CI = lint-artifacts + compose smoke** · 2026-08-24 · Accepted
- **Context:** The user's Phase 0 plan listed Gradle multi-module + Spotless/Checkstyle + Testcontainers "from day 1." But Phase 0 ships **no Java** — its artifacts are Docker, SQL, shell, Makefile, Markdown. Standing up a Java test harness before any Java product code is premature (YAGNI/KISS).
- **Decision:** Defer **all** Java tooling (Gradle/Spotless/Testcontainers) to Phase 1, when the `engine` module lands. Phase 0 CI = a `lint` job over what it actually ships (shellcheck + hadolint + sqlfluff) and a `smoke` job that runs the real `docker compose` stack end-to-end. The smoke test is a bash script (asserts on plan shape + row counts, never wall-clock, so it isn't CI-flaky).
- **Alternatives:** (a) Gradle skeleton now (hygiene-from-day-1, but nothing to build); (b) full Testcontainers now (heaviest; provisions JDK 21 for a test with nothing to test).
- **Consequences:** Lighter, honest Phase 0; no JDK-21 provisioning on the critical path. sqlfluff is scoped to pure-SQL files with opinionated layout/ambiguity rules disabled (aligned DDL, `SELECT *` and bare `JOIN` are *deliberate* demo anti-patterns) — it stays a real Postgres-dialect parser + core-rule check. Testcontainers pattern gets its first real use in Phase 1.

## ADR-0011
**Postgres image strategy: custom monitored image, pinned versions, pg16** · 2026-08-24 · Accepted
- **Context:** hypopg is not in the stock `postgres` image (the plan's #1 risk); images should be reproducible.
- **Decision:** Build a 2-line `monitored/Dockerfile` `FROM postgres:16-bookworm` that installs `postgresql-16-hypopg` from PGDG (the repo the official image already configures). Pin all images to exact versions (`pgvector/pgvector:0.8.6-pg16`). Stay on **Postgres 16** (conservative; hypopg packaged and confirmed) rather than 17/18.
- **Alternatives:** find a community image bundling hypopg (opaque, unpinned); build hypopg from source (heavier); rolling `:pg16` tag (non-reproducible).
- **Consequences:** Reproducible, transparent, minimal. `pg_stat_statements` is enabled at server start via `shared_preload_libraries` in the compose `command` (an `ALTER SYSTEM` after boot would not load it). **Observed & noted for Phase 1:** `pg_stat_statements.track=all` captures per-row FK-check trigger statements during bulk seed (~500k calls) — revisit `track=top` vs `track=all` when building the ranker so internal RI checks don't pollute the leaderboard.
