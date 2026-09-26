# PgLens · Phase 3 — Plain-Language Explanations (local LLM, grounded and checked)

> **Status: BUILT + EVALUATED 2026-09-26 (ADR-0043). `--plain` defaults to the template (ranking 10–0); `--plain=llm` opt-in. Shipped as `v0.0.6`.** Verified per `docs/workflows/plan-verification.md`,
> including a Step 0 spike (§3a) that ran four small models on real PgLens findings. Replaces the
> Desktop draft `phase_3_rag_llm_explanation.md`; §3b lists what changed and why.
>
> **Self-contained.** Prereqs: Phases 0–2.6 (`v0.0.5`). **Tag:** `v0.0.6`.
> **Effort:** ~3 weeks at ~10 h/week `[ASSUMPTION]` (the draft said 2; the eval and the server cache
> are real work).

## 1. Context — what exists, what's missing

PgLens's findings are correct but terse: a rule id, a plan node, a cost estimate, a DDL line. A developer
who doesn't read EXPLAIN plans has to translate them. This phase adds a **plain-language explanation**
of each recommended index — *why the query is slow, what the index changes, what to be careful about* —
written by a **local LLM from facts PgLens already owns**, and checked against those facts before it is
shown. With the LLM off (or unreachable, or failing the checks), PgLens shows a **deterministic
template explanation** instead, so the feature never depends on the model (charter #3).

Today the only user-facing surface is the **CLI**; the server stores history and advice but has no read
API until Phase 4. So this phase ships explanations in the CLI (demoable now) **and** caches them on the
server (ready for the Phase 4 dashboard).

## 2. Goal & non-goals

**Goal:** `pglens scan … --plain` (and `pglens explain … --plain`) adds a short, plain-language
explanation to each of the top recommended indexes. The LLM writes three prose fields; **PgLens itself
renders the index DDL, every estimate label and every caveat**. Every number, table, column and index the
prose mentions is checked against the facts; a failing answer is retried once, then replaced by the
template. The server generates and caches the same explanations for its advice (Phase 4 displays them).

**Non-goals:** the dashboard or a REST API (Phase 4); agents, tool calling, multi-step chains; chat /
free-form questions; fine-tuning; re-ranking; streaming output; the LLM deciding or proposing anything
(it only phrases — PgLens recommends, humans apply); explaining `pglens confirm` reports (backlog, §9).

## 3. Verification of the plan (what was checked, and what it changed)

| Claim / assumption | Checked how | Result |
|---|---|---|
| A local LLM runs on other people's machines, free | Web, 2026-09-26 + spike | ✅ Ollama (Mac/Win/Linux, one-command install, `ollama/ollama` image), Docker Model Runner, llama.cpp `llama-server`, LM Studio and vLLM all expose the **OpenAI Chat Completions API**; so do hosted services (Groq, OpenRouter, Gemini). Coding to that one API keeps PgLens runtime-agnostic. |
| Docker is a good way to ship the model | Web + spike | ⚠ On macOS, Docker **cannot use the GPU** (CPU only, 3–5× slower than native Metal). Linux + NVIDIA works in Docker. So: compose `llm` profile for Linux/CPU users; **native Ollama recommended on Mac/Windows**. The spike measured the worst case (CPU-only Docker). |
| Which model | Spike §3a (4 Apache/MIT models ≤ 3.5 GB, real findings) | ✅ **`qwen3.5:4b`** (Apache-2.0, 3.4 GB) was the only one with **no** invented number, index or claim in 4/4 cases. The other three each invented facts. Confirmed or overturned by the pre-registered eval (Step 1/8). |
| Thinking models are fine to call | Spike | ❌ With thinking on (Ollama's default for qwen3.5), one explanation took **211 s**, used 3,287 reasoning tokens, hit the 4,096-token context and returned **no answer**. → send `reasoning_effort: "none"` (Ollama's OpenAI-compat field), cap `max_tokens`, treat `finish_reason = length` / empty content as a failure. |
| Structured (JSON-schema) output works everywhere | Web + spike | ⚠ Ollama ≥ 0.5 honours `response_format: json_schema` (spike: 4/4 valid). llama.cpp has open bugs with it. `json_object` + schema-in-prompt also worked (spike). → try `json_schema`, fall back to `json_object` on HTTP 400, parse leniently; **the guard, not the runtime, is the enforcement**. |
| Ollama's default context fits | Spike (`ollama ps`: context 4096) | ⚠ Prompt + answer must fit 4,096 tokens (OpenAI-compat can't raise `num_ctx`). Spike prompts were 550–1,100 tokens. → hard prompt budget (~2,000 tokens incl. retrieved text). |
| Memory | Spike (`docker stats`) | ⚠ ~4.9 GiB with `qwen3.5:4b` loaded. Two models loaded at once exhausted a 7.75 GiB VM (HTTP 500). → document "≈ 5 GB RAM free"; one chat model; `qwen3.5:2b` as a documented lower-quality low-RAM option. |
| Spring AI for the LLM + pgvector | Web | ❌ Spring AI **2.x requires Spring Boot 4**; we pin Boot 3.5 (1.x still works but is the old line). The API is one POST + one embeddings POST; a framework would hide exactly the parts worth learning (prompting, pgvector SQL, HNSW). → plain `java.net.http` + Jackson (same call as ADR-0012 picocli, ADR-0025 grpc-java). |
| Embedding model | Web + spike | ✅ `nomic-embed-text` v1.5: Apache-2.0, 274 MB, **768** dims (spike: `/v1/embeddings` returned 768). **Requires** `search_document:` / `search_query:` prefixes (model card). |
| pgvector in the metadata DB | Image + migrations | ⚠ `pgvector/pgvector:0.8.6-pg16` ships HNSW; `vector` is **not a trusted extension** (needs superuser to create). Compose initdb already creates it; V8 uses `CREATE EXTENSION IF NOT EXISTS` and README states the requirement. |
| PostgreSQL docs may be embedded | postgresql.org licence | ✅ PostgreSQL Licence covers "this software **and its documentation**" (permissive; keep the copyright notice + source URLs). |
| RAG adds product value | ADR-0037; engine code | ⚠ Only **4 rules** exist (R1 selective seq scan, R3 unindexed join, R4 sort-for-limit, R7 GIN candidate). A card per rule, chosen by rule id, is exact; vector search over generic docs may add little. → cards are the primary grounding; docs retrieval is **A/B-measured** with a pre-registered keep/drop rule (§6). |
| Hosted free tiers are a privacy-safe fallback | Web | ⚠ Groq: no inference retention by default (free: 30 RPM). Gemini free tier: content **used to improve Google products**. → remote endpoints refused unless explicitly allowed; README says what is sent and names these differences. |
| Prereqs met | `docs/project.md` | ✅ Phases 0–2.6 shipped (`v0.0.5`); metadata DB already runs pgvector; engine model has every fact needed. |

## 3a. Step 0 results (spike, 2026-09-26)

**Setup:** `ollama/ollama` 0.34.4 in Docker Desktop on an Apple M3 Pro — **CPU only** (11 vCPU, 7.75 GiB
VM), the slowest realistic setup. 4 real cases from the TPC-H and JOB scan reports (R1 and R3 findings,
planner-validated recs). Prompt = compact facts JSON + one rule card; `temperature 0`,
`reasoning_effort none`, JSON schema with 4 string fields. A rough guard flagged SQL and numbers not
in the facts; every output was also **read by hand**.

| Model (licence, download) | Valid JSON | Problems found reading the output | Time / explanation |
|---|---|---|---|
| **qwen3.5:4b** (Apache-2.0, 3.4 GB) | 4/4 | **None.** (Guard flag: "over 150 seconds" — a correct conversion of 153,258 ms.) | 16–34 s new prompt (first call 51 s incl. model load); 8–9 s repeated prompt; byte-identical output across 3 runs per case |
| qwen3.5:2b (Apache-2.0, 2.7 GB) | 4/4 | Invented "~15 million rows total"; claimed the index "will still be slower than a full scan"; "each join forces a full table scan instead of using the index" (self-contradictory) | 18–26 s |
| granite4:3b (Apache-2.0, 2.1 GB) | 4/4 | **Invented a second index** ("and similarly on `movie_info_idx(info)`"); "148 million" rows (fact: 14.8 M); called the −74 % *cost* estimate a cut in "**execution time**" | 18–29 s |
| phi4-mini (MIT, 2.5 GB) | 4/4 | Wrote the `CREATE INDEX` into the prose; wrong after-cost (129,858); copied the same text into every field | 21–68 s |

**What it changed:** (1) default model `qwen3.5:4b`; (2) the guard must understand magnitudes and units
("~600k", "4.5 million", "150 seconds") — the rough guard both missed invented numbers that happened to
be near a fact and flagged correct conversions; (3) the guard needs **index-mention** and **cost-vs-runtime**
checks (granite's two worst errors contain no bad number); (4) PgLens, not the model, renders DDL and
caveats (phi); (5) thinking must be off (211 s → no answer). These four outputs become guard test fixtures.

## 3b. Critique of the Desktop draft → changes

1. **Server-only delivery would be invisible** until Phase 4 (no read API). → Also ship in the CLI, the
   one surface users have today (`--plain`).
2. **`explain-svc` (Python)** = a 4th deployable and a 2nd language, unusable by the CLI. → An in-JVM
   `:explain` Gradle module used by both CLI and server.
3. **"Ollama"** → any OpenAI-compatible endpoint; Ollama is the documented default runtime, not a dependency.
4. **"a small instruct model"** → measured choice (`qwen3.5:4b`), confirmed by the eval.
5. **The LLM wrote DDL + numbers, then the guard checked them.** → Invert it: the LLM writes only
   `summary`, `whyItIsSlow`, `whatTheIndexChanges`; PgLens renders the DDL, the estimate label, "planner-validated
   ≠ safe", build caution, write load and links. Removes the biggest hallucination surface instead of policing it.
6. **"Flag, don't fail" unsupported numbers** (review softening) contradicts charter #1, and the spike saw
   invented numbers. → Hard-fail with a magnitude-/unit-aware tolerance, **one** retry that lists the
   violations, then the template.
7. **Guard gaps the spike exposed:** invented index in prose; invented table/column; cost % described as
   runtime/speed; "N× faster". → Explicit checks for each (§4.4).
8. **RAG over generic docs as the grounding** → deterministic rule/caveat **cards** first (exact, no
   infra, works in the CLI); pgvector docs retrieval on the server, kept in the prompt only if the
   pre-registered A/B says it helps — otherwise it only supplies "further reading" links.
9. **Missing runtime realities:** thinking models, JSON-schema support gaps, 4,096-token context, one-model
   memory budget, Mac Docker has no GPU. → All handled in §4.
10. **Cache key (queryid, plan-hash, rec-hash)** misses model/prompt upgrades. → key = hash of the exact
    facts + model + prompt version.
11. **Eval of 15–20 demo pairs** (small, homogeneous; guard tuned on the test set). → 24 cases from demo +
    TPC-H + JOB, gold points written first, decision rules pre-registered, guard tuned on a 12-case dev half and
    reported on the 12-case held-out half, and an honest check that the LLM beats the template at all.
12. **Remote LLMs** weren't gated. → Non-local endpoints refused unless explicitly allowed (§4.6).

## 4. Design

```
engine QueryReport / RankedRecommendation / TableWriteLoad
        │  FactsBuilder (pure): only facts, numbers pre-formatted for humans, no sampled values
        ▼
ExplanationFacts ──► TemplateExplainer (pure, always available) ───────────────┐
        │                                                                      │ fallback
        ├─► cards (by rule id / status / caveat)  [+ server: pgvector docs, if A/B keeps them]
        ▼                                                                      │
PromptBuilder ─► OpenAiCompatibleClient ─► OutputGuard ─ pass ─► Explanation ◄─┘
                     (any runtime)            │ fail → 1 retry with violations → template
                                              ▼
Renderer: LLM prose (3 fields) + PgLens-rendered DDL, estimate label, caveats, links, provenance line
```

### 4.1 Module and boundaries
New Gradle module **`:explain`** (depends on `:engine` model types + Jackson; no Spring). `:engine`
stays LLM-free, so "deterministic core" is enforced by the build graph. `:cli` and `:server` depend on
`:explain`. The pgvector retriever lives in `:server` behind `:explain`'s `ReferenceSource` interface.

### 4.2 LLM access (`OpenAiCompatibleClient`, `LlmSettings`)
`POST {baseUrl}/chat/completions` and `POST {baseUrl}/embeddings` via `java.net.http`. Settings (CLI
flags / env / server properties): `base-url` (default `http://localhost:11434/v1`), `model` (default
`qwen3.5:4b`), `api-key` (env only), `timeout` (default 120 s), `reasoning-effort` (default `none`;
blank = omit, for runtimes that reject it), `allow-remote` (default false). Request: `temperature 0`,
`max_tokens` 600, `response_format json_schema` → on 400 retry once with `json_object`. Failure modes
(unreachable, timeout, 4xx/5xx, `finish_reason=length`, empty or unparseable content) → template, with
the reason in the output.

### 4.3 Facts and grounding
- **`ExplanationFacts`** built from the engine model: normalized query text (≤ 1,200 chars), measured
  calls/mean/total, this rec's findings (rule, table, columns, evidence), the recommended index, status,
  planner cost before/after and drop, footprint, build caution, write load, value range summary. **Numbers
  are given pre-formatted** ("153.3 s", "~14.8 million rows", "−74.2 %", "328.5 MB") so the model repeats
  rather than converts. **Never included:** sampled values (ADR-0038), connection details, confirm-log values.
- **Cards** (`explain/src/main/resources/knowledge/*.md`, written by us, each citing PostgreSQL docs URLs):
  one per rule (R1, R3, R4, R7), per status (not planner-validated GIN/GiST, suppressed), per caveat
  (generic-plan estimate, planner-validated ≠ safe, build caution, write cost). Chosen by id — deterministic.
- **Docs retrieval (server):** PostgreSQL 16 docs sections on indexes (ch. 11), `EXPLAIN` and planner
  statistics (ch. 14) chunked by heading (~300 tokens), embedded with `nomic-embed-text`
  (`search_document:`), stored in `knowledge_chunks` (V8, `vector(768)`, HNSW `vector_cosine_ops`).
  The retrieval query is built from **signals** (rule titles, node types, access method), never the SQL.
  Top-3, within the prompt budget. Kept in the prompt only if the §6 A/B says so.

### 4.4 `OutputGuard` (pure, the trust layer)
A response passes only if **all** hold:
1. **Shape:** a JSON object with exactly the 3 fields, each non-empty, each ≤ 400 chars.
2. **No SQL / code:** no statements (`CREATE|DROP|ALTER|INSERT|UPDATE|DELETE|VACUUM|REINDEX|SET …`), no
   `USING <am>`, no code fences.
3. **Numbers:** every number, parsed with its magnitude and unit (`k`, `thousand`, `million`, `%`, `ms`,
   `s`, `min`, `MB`, `GB`…), matches a fact value within its display rounding (≤ 5 % relative). Bare
   integers 1–3 used as counts are allowed (disclosed).
4. **Identifiers:** every `table.column` / `table(column…)` / "index on …" reference names only tables and
   columns in the facts, and any **index** reference is the recommended one.
5. **Honesty phrasing:** a sentence carrying the cost-drop percentage must also say cost/estimate/planner
   and must not say runtime/execution time/faster by; no "N× faster", "guarantee", "will fix".
On failure → one retry that lists the violated rules → template. The result records `source`
(`LLM` | `TEMPLATE`), the model, the violations and the fallback reason (for the eval and for debugging).
What the guard **can't** catch — a wrong but number-free sentence — is measured by the eval and disclosed
in the provenance line: *"Written by qwen3.5:4b from PgLens's findings. Numbers, tables and the index
were checked against them; the wording wasn't."*

### 4.5 Surfaces
- **CLI:** `--plain` on `scan` and `explain` explains the top 3 actionable indexes (`--plain-top N`).
  Uses the LLM when reachable, else the template with a one-line reason; `--plain=template` forces the
  template. `--json` gains `explanations` (contract **1.3**, additive). Output without `--plain` is unchanged.
- **Server:** `ExplanationService` — a scheduled pass (separate from the analysis job so a slow model
  never delays analysis), disabled by default (`pglens.explain.enabled`), explains the top N `IndexAdvice`
  per database that lack a cached explanation, bounded per pass. Cache table `explanations` (V8) keyed by
  `(db_id, queryid, ddl, facts_hash, model, prompt_version)`; template results are cached too (cheap,
  and they carry the reason). Phase 4 reads it.

### 4.6 Privacy
The prompt contains: pg_stat_statements' normalized query text (constants replaced by `$N`), table and
column names, plan evidence, costs, sizes, timings. By default it goes only to a **local** endpoint.
A base URL whose host isn't loopback / private / `host.docker.internal` is **refused** unless
`--allow-remote-llm` (server: `pglens.llm.allow-remote=true`); PgLens then prints the host it sends to.
README states exactly what is sent and the retention differences between hosted providers.
Precise claim: *with a local model, no query text is sent to a third-party LLM service* (it still reaches
your own PgLens server, per the Phase 2 note).

## 5. Steps (in order)

0. ✅ **Spike** (§3a).
1. ✅ **Eval set + pre-registration** (`docs/llm-eval.md` Part 1; 24 cases + gold frozen) — before building the LLM path: 24 frozen facts fixtures
   (`explain/src/test/resources/eval/`), gold key points per case, grading rubric, decision rules, dev/held-out split
   → `docs/llm-eval.md`. Cases: demo (all recs), TPC-H (R1/R3/R4), JOB (R1/R3, multi-finding, build caution),
   a GIN "not planner-validated" case, a covered/subsumed rec. TPC-H/JOB are public schemas; no values.
2. ✅ **`:explain` core (pure):** `ExplanationFacts` + `FactsBuilder` + number formatting, cards,
   `TemplateExplainer`, `Explanation` model. Unit tests per rule/status.
3. ✅ **LLM client:** `LlmSettings`, `OpenAiCompatibleClient` (chat + embeddings, schema→json_object fallback,
   failure mapping), `EndpointPolicy` (remote refusal). Tests against a stub server (JDK `HttpServer`) — no
   new test dependency.
4. ✅ **`PromptBuilder` + `OutputGuard` + `Explainer`** (retry-once, fallback, provenance). Guard tests use
   the spike's real bad outputs (invented index, "148 million", "execution time by 74 %", DDL in prose)
   plus hand-written ones ("10× faster", invented table, wrong unit).
5. ✅ **CLI:** `--plain` / `--plain-top` / `--llm-url` / `--llm-model` / `--allow-remote-llm`; renderer
   section; JSON 1.3. Renderer + command tests (stub LLM).
6. ✅ **Server:** V8 (`CREATE EXTENSION IF NOT EXISTS vector`, `knowledge_chunks` + HNSW, `explanations`),
   docs corpus files + `KnowledgeLoader` (re-embeds when corpus version or embedding model changes),
   `PgvectorReferenceSource`, `ExplanationService` + cache. ITs: Testcontainers pgvector + stub LLM
   (cache hit = no second call; model change = regenerate; LLM down = template cached with reason).
7. ✅ **Compose + docs:** `llm` profile (`ollama/ollama` pinned + one-shot model pull, volume), server env;
   `make llm-up`, `make llm-eval`; README "Plain-language explanations" (runtimes table, Mac native vs
   Docker, RAM, privacy, hosted caveats).
8. ✅ **Run the eval** (results + all 4 decisions: `docs/llm-eval.md` Part 2) with the real model (CPU-only Docker; native Mac too if you install Ollama), exact
   vs HNSW retrieval, cards vs cards+docs → results in `docs/llm-eval.md`; apply the pre-registered decisions.
9. **Record:** ADR-0043, architecture, project, backlog, phases README, `.claude/rules/explain.md`, memory;
   tag `v0.0.6` when you say so.

## 6. Evaluation (pre-registered in Step 1, before any result)

Metrics per case: **M1** guard pass on the first try · **M2** fell back to template · **M3** root cause
right (names the rule's cause + the right table/column) · **M4** no unsupported claim in the final text
(graded by hand against the facts — grader: Claude with the rubric, you spot-check 25 %; disclosed) ·
**M5** latency p50/p95 (CPU-only Docker; native if available) · **M6** peak RAM · **M7** retrieval recall@3
on hand-labelled relevant chunks, exact scan vs HNSW · **M8** prompt tokens.

Decision rules (applied to the **held-out** 12):
- **Default model** = `qwen3.5:4b` if M4 ≥ 11/12 and M2 ≤ 3/12; else try `qwen3.5:2b`/others under the same
  rule; if none passes, `--plain` defaults to the template and the LLM is labelled experimental.
- **Docs in the prompt** only if cards+docs beats cards-only on M3+M4 by ≥ 2 cases with M1 not lower;
  otherwise docs retrieval supplies further-reading links only. Either result is published.
- **LLM vs template:** you blind-rank 10 pairs (~15 min). If the LLM isn't preferred in ≥ 6/10, the docs
  say so and `--plain` defaults to the template.
- HNSW is built as a deliberate pgvector rep; at a few hundred chunks exact search is expected to be as good —
  the result is published either way.

## 7. Definition of Done

- [x] `--plain` explains the top indexes of the demo, TPC-H and JOB scans (demo: a real `scan --plain` run; TPC-H/JOB: the same explainer on the 24 frozen report cases); each explanation names the root cause,
      shows the engine's index verbatim (rendered by PgLens) and its estimate labelled as a planner estimate.
- [x] Guard proven by tests with the spike's real bad outputs + adversarial fixtures (invented index, "10× faster",
      invented number, wrong unit, cost-as-runtime, SQL in prose): each is retried then falls back.
- [x] Degradation proven: Ollama stopped / thinking left on / schema unsupported / timeout → template with a reason;
      scan output otherwise identical.
- [x] Remote endpoint refused without the explicit flag (test).
- [x] Server: explanations cached by facts hash + model + prompt version; no repeat LLM call for unchanged facts (IT).
- [x] Docs corpus embedded in pgvector with an HNSW index; exact-vs-HNSW recall measured.
- [~] `docs/llm-eval.md`: pre-registration **written** before results, but committed *together with* them in the `v0.0.6` commit (commits waited for the go-ahead, so git can't prove the order); results with every metric, including failures.
- [x] README: runtimes, RAM, Mac-native advice, the precise privacy statement, hosted caveats.
- [~] CI green with a stub LLM (local full build green: 384 tests on PG16, ITs on PG17/18; CI runs on push) (no model in CI); real model only in `make llm-eval`.
- [x] ADR-0043 + docs updated; tagged `v0.0.6`.

## 8. Verification (self-check)

Take three explanations the guard passed and try to find one claim not traceable to the facts or a card.
If one exists, it goes into the eval's M4 count and the guard or prompt is tightened (on the dev half only).
Re-run the full build on PG16 + the compat ITs on PG17/18 (explanations touch no monitored-DB code).

## 9. Skipped (YAGNI), backlog, and one resource per new skill

**Skipped:** Spring AI; a Python service; LangChain/LlamaIndex; agents/tool calling; fine-tuning; re-rankers;
LLM-as-judge (hand grading instead, disclosed); streaming; explanations for queries with no recommendation
(template-only later); on-disk cache in the CLI.
**Backlog (new):** B19 — explain `pglens confirm` reports (measured verdicts are the best facts to phrase;
same guard); B20 — `num_ctx`/native-API tuning per runtime if the eval shows the 4,096 budget binds.

**Resources (one each, and what to skip):**
- *LLM API + structured output:* Ollama docs — OpenAI compatibility + Structured Outputs pages. Skip model-serving internals.
- *Embeddings + pgvector:* the pgvector README (querying, HNSW, `ef_search`) + the nomic-embed-text model card (prefixes). Skip vector-DB comparisons.
- *Evaluating LLM features:* Hamel Husain, "Your AI Product Needs Evals" (blog). Skip eval frameworks (RAGAS etc.) — 24 hand-graded cases don't need one.
