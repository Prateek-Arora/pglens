# PgLens · LLM explanation eval (Phase 3)

> **Part 1 (pre-registration) was written 2026-09-26, before any LLM ran on these cases**, and is not
> edited after results exist. Results go in Part 2. Plan: `docs/phases/phase_3.md` §6.
> Charter #1 applies to this file too: every failure is reported, not just the wins.

## Part 1 — Pre-registration

### 1.1 What is being tested
`pglens … --plain` asks an LLM for three prose fields (`summary`, `whyItIsSlow`,
`whatTheIndexChanges`) about one recommended index. The output is checked by `OutputGuard`: one retry,
then the deterministic template. The eval answers three questions:
1. Is the default model (`qwen3.5:4b`) good enough to be the default?
2. Do retrieved PostgreSQL docs in the prompt help, compared with the rule cards alone?
3. Does the LLM text beat PgLens's own template at all?

### 1.2 Cases (frozen)
There are **24 cases** in `explain/src/test/resources/eval/cases/`, taken from three real `pglens scan --json`
reports. Only the plan tree was removed, because the explainer doesn't read it. Everything else is
copied verbatim.

| Source | Report | Cases |
|---|---|---|
| Bundled demo | scan of `pglens_demo` on 2026-09-26 (JSON 1.2) | the 7 actionable ranked recs + the one GIN rec (not planner-validated) |
| TPC-H (SF 0.1) | `make accuracy` report (JSON 1.2, ADR-0041) | the first 8 actionable ranked recs |
| JOB / IMDB | `make accuracy-job` report (JSON 1.1, ADR-0040) | the first 8 actionable ranked recs |

**Selection rule:** per source, the first N **actionable** entries of `topRecommendations` in rank order.
These are the distinct indexes `--plain` explains. Plus the demo's only `NOT_PLANNER_VALIDATED` rec.
Script: `explain/src/test/resources/eval/make_eval_cases.py` (reads the three reports).
The cases themselves are committed, so the script isn't needed to rerun the eval.

**By rule:** R1 × 8, R3 × 12, R4 × 1, R1+R4 × 2, R7 (GIN) × 1.

**Findings per case:**
- 4 cases have exactly one finding (1).
- 15 have 2–4 findings.
- 5 JOB cases have 9–12.

Findings that aren't this index's are the "don't take credit for another finding" traps.

**Split (pre-registered):**
- **Dev (12):** 02, 04, 06, 08, 09, 10, 12, 14, 17, 18, 20, 22.
- **Held-out (12):** 01, 03, 05, 07, 11, 13, 15, 16, 19, 21, 23, 24.
- **How it was assigned:** the Step 0 spike already saw cases 09, 10, 17 and 18, so they go to dev. The single GIN case also goes to dev, so its prompt path gets developed. The rest alternate held-out/dev in rank order, which fills each source 4 + 4.
- **Tuning uses dev only.** The prompt, the cards and the guard's tolerances are tuned on dev cases only. **Decisions are made on the held-out 12.**
- **Disclosed:** the held-out half has no GIN case and no R4-only case besides 03.

**Gold:** `explain/src/test/resources/eval/gold.json` records the following for each case:
- `rootCause`: the cause M3 requires.
- `mustName`: the table and columns.
- `mustNotClaim`: case-specific traps, mostly "this index fixes another finding".

### 1.3 Scope refinements made while freezing the cases
1. **Covered/subsumed recs are not explained.** They aren't distinct indexes (the CLI prints "covered by …"). So the plan's "covered/subsumed rec" case was dropped.
2. **Caveats are PgLens-rendered and not in the prompt.** This covers:
   - the value range;
   - the index size and write-load ratio;
   - the write load;
   - the B17 build caution;
   - "planner-validated ≠ safe".

   Reason: it follows §3b-5 to its end. The facts the LLM sees are only what it needs for its three fields: the query, its measured stats, this index's findings, and the cost estimate. That means fewer numbers to repeat wrongly and a shorter prompt.

   So the JOB report predating the B17 caution doesn't matter: the caution is rendered from the report by PgLens, and unit tests cover it.

### 1.4 Conditions
- **Model:** `qwen3.5:4b` via Ollama's OpenAI-compatible API.
  - Settings: `temperature 0`, `reasoning_effort none`, `json_schema`, `max_tokens 600`.
  - Hardware: CPU-only Docker (the worst realistic case). Native Mac Ollama is also run if it's installed.
- **Grounding:** **A** = rule/status cards only. **B** = cards + top-3 retrieved PostgreSQL-docs chunks (pgvector).
- **Template:** `TemplateExplainer` on the same facts. It is the baseline for the blind ranking.

### 1.5 Metrics
| Id | Metric | How |
|---|---|---|
| M1 | Guard pass on the first try | automatic |
| M2 | Fell back to the template (after the retry) | automatic |
| M3 | Root cause right | By hand: the final text states the rule's mechanism **and** names every `mustName` identifier (see the rubric below). |
| M4 | No unsupported claim | By hand: every statement in the final text is traceable to the case's facts or a card, and none of the common or case-specific must-not-claims occur. |
| M5 | Latency p50 / p95 per explanation | Wall clock including the retry, with the model already loaded. The first-call load time is reported separately. |
| M6 | Peak RAM of the runtime | `docker stats`, max |
| M7 | Retrieval recall@3 | Measured against hand-labelled relevant chunks per case, exact scan vs HNSW. The labels are written before retrieval runs. |
| M8 | Prompt tokens | `usage.prompt_tokens` |

**M3 rubric (mechanism per rule):**
- **R1:** reads the whole table and discards most rows, and the index reads only matching rows.
- **R3:** a join side is scanned because its join column has no index, and the index turns that into lookups.
- **R4:** it sorts many rows to return a few, and the index supplies rows already in order.
- **R7:** the containment filter scans rows, and a GIN index can answer it. The text must also say, or leave for PgLens to say, that the planner didn't check it.
- **R1+R4:** both the filter part and the order part.

**M4 common must-not-claims (every case):**
1. a promised speed-up or runtime ("N× faster", "will take X s", "will fix");
2. the cost-drop percentage described as time or speed;
3. any index other than the recommended one;
4. a table or column that isn't in the facts;
5. that this index fixes a different finding;
6. SQL or DDL in the prose;
7. for the GIN case, any number about its benefit.

**Grading:**
- Claude grades M3/M4 with this rubric and writes a one-line reason per case.
- The user spot-checks cases **04, 08, 12, 16, 20, 24** (every 4th, i.e. 25 %).
- Any disagreement is reported, and the user's grade wins.
- If the user agrees on fewer than 5 of those 6, all 24 are re-graded by hand.

### 1.6 Decision rules (on the held-out 12)
1. **Default model:** `qwen3.5:4b` stays the default if **M4 ≥ 11/12 and M2 ≤ 3/12** in its better grounding condition.
   - Otherwise `qwen3.5:2b`, then `granite4:3b`, are tried under the same rule.
   - If none passes, `--plain` defaults to the template and the LLM is labelled experimental.
2. **Docs in the prompt:** only if B beats A on **M3 + M4 by ≥ 2 cases** with M1 not lower.
   - Otherwise docs retrieval only supplies "further reading" links.
3. **LLM vs template:** the user blind-ranks 10 pairs: the first 10 held-out cases by id, with the LLM/template order shuffled with seed 20260926.
   - If the LLM is preferred in fewer than **6 / 10**, the README says so and `--plain` defaults to the template.
4. **HNSW vs exact:** both results are published. At a few hundred chunks, exact search is expected to be as good. HNSW is built as a deliberate pgvector exercise, not because it is needed.

Whatever the decisions come out to, they are applied and reported. A failed rule is a result, not a reason to change the rule.

## Part 2 — Results (2026-09-26)

**Setup.**
- Model: `qwen3.5:4b` running in `ollama/ollama` 0.34.4 under Docker Desktop on an Apple M3 Pro. Docker has no GPU there, so this is CPU only: 11 vCPU, a 7.75 GiB VM.
- Prompt: version `p1`. Runner: `make llm-eval` (`EvalRunner`).
- Raw outputs, every attempt included: `explain/build/llm-eval/*.json`. These are build outputs and aren't committed; rerun `make llm-eval SPLIT=… [DOCS=true]` to reproduce them.
- Condition B retrieves passages by exact cosine search in memory. Its top 3 is identical to pgvector's (both exact and HNSW) for every query; see M7.
- Native Ollama on macOS wasn't measured (not installed), so M5 is the worst realistic case only.

**Tuning on dev: none was needed.** The dev run (condition A) passed the guard 12/12 on the first try, and reading all 12 answers found one M4 problem (case 08, below). The prompt, cards and guard used on held-out are exactly the ones pre-registered.

### Automatic metrics

| Run | M1 first-try pass | M2 template fallback | M5 latency p50 / p95 / max | M8 prompt tokens min / median / max |
|---|---|---|---|---|
| dev, A (cards) | 12/12 | 0/12 | 16.2 / 26.6 / 30.0 s | 721 / 985 / 1,159 |
| **held-out, A (cards)** | **12/12** | **0/12** | 15.7 / 30.7 / 37.1 s | 777 / 971 / 1,172 |
| held-out, B (cards + docs) | 11/12 | 0/12 | 21.6 / 29.5 / 33.3 s | 1,506 / 1,709 / 1,891 |

- **Latency (M5)** includes the retry. The first call of a run also loads the model; the dev run's first call took 23.7 s.
- **Condition B's one guard failure:** case 07's first answer said "product_ids", a column that doesn't exist. The retry passed.
- **RAM (M6):** `docker stats` showed 6.0 GiB for the container with the chat model loaded, and 7.1 GiB with the chat and embedding models loaded together. These are single samples, not a monitored peak, and include file cache. It fits a 7.75 GiB VM. The server needs both models.

### Graded metrics (M3 root cause, M4 no unsupported claim)
Graded by Claude with the §1.5 rubric, strictly. The user spot-check of cases 04, 08, 12, 16, 20 and 24 is pending.

| Case | Split | A: M3 | A: M4 | B: M3 | B: M4 | Notes |
|---|---|---|---|---|---|---|
| 01 | held-out | ✗ | ✓ | ✓ | ✓ | A: says the join scans ~500,000 rows, but never says what the index changes (only "lower the estimated cost"). |
| 03 | held-out | ✓ | ✓ | ✓ | ✓ | |
| 05 | held-out | ✓ | ✓ | ✓ | ✓ | |
| 07 | held-out | ✓ | ✓ | ✗ | ✓ | B: never says it's a join ("scans … because product_id has no index"). |
| 11 | held-out | ✓ | ✓ | ✗ | ✗ | B: "matching roughly 600,572 rows". Those rows are *scanned*, not matched, and the join isn't mentioned. |
| 13 | held-out | ✓ | ✓ | ✓ | ✗ | B: calls the whole query's cost (20,421) the cost of the scan (19,420.75). The number is in the facts, so the guard can't catch it. |
| 15 | held-out | ✓ | ✓ | ✓ | ✓ | |
| 16 | held-out | ✓ | ✓ | ✓ | ✓ | |
| 19 | held-out | ✓ | ✓ | ✓ | ✓ | |
| 21 | held-out | ✓ | ✓ | ✓ | ✓ | |
| 23 | held-out | ✓ | ✓ | ✓ | ✓ | |
| 24 | held-out | ✗ | ✓ | ✓ | ✓ | A: says movie_companies is scanned, but not why (no index on the join key) or what the index does. |
| **held-out total** | | **10/12** | **12/12** | **10/12** | **10/12** | |
| dev (A only) | dev | 12/12 | 11/12 | — | — | Case 08 says "over 300,000 rows"; the facts say "~300000", and the table holds exactly 300,000. |

**No answer in any run** invented an index, a table or a column that reached the user, promised a speed-up, called a cost a runtime, or gave a percentage for the GIN index. What slipped through were wrong-but-plausible phrasings around real numbers (dev 08; B 11, 13). This is the class of error the guard can't see, and every explanation's provenance line says the wording isn't checked.

### M7 — retrieval recall@3 (`./gradlew :server:retrievalEval`, real `nomic-embed-text` in pgvector 0.8.6)

| Rule query | Exact top 3 | HNSW top 3 | Recall@3 |
|---|---|---|---|
| R1 | using-explain-18, using-explain-03, planner-optimizer-03 | identical | 0.33 |
| R3 | explicit-joins-04, explicit-joins-06, using-explain-15 | identical | 0.33 |
| R4 | indexes-ordering-01, indexes-ordering-02, indexes-types-02 | identical | 0.67 |
| R1+R4 | using-explain-18, indexes-ordering-01, using-explain-10 | identical | 0.67 |
| R7 | indexes-types-06, gin-intro-01, datatype-json-01 | identical | 1.00 |
| **mean** | | **5/5 identical** | **0.60 both** |

On 113 passages, HNSW loses nothing against an exact scan, as expected (rule 4). Retrieval itself is mediocre for R1 and R3. Short, generic signal queries ("Unindexed join key") land on passages about join *syntax* (`explicit-joins`) instead of join *strategies*.

### Decisions (pre-registered rules applied, §1.6)
1. **Default model — `qwen3.5:4b` stays.** Held-out condition A: M4 = 12/12 (rule: ≥ 11) and M2 = 0/12 (rule: ≤ 3). No other model needed trying.
2. **Docs in the prompt — no.** Measured on M3 + M4, B scored 20 and A scored 22, so B is 2 cases *worse*, not ≥ 2 better. M1 was also lower (11 vs 12). B's prompts were ~75 % longer and ~6 s slower at p50. Retrieved passages are used **only as "further reading" links** (server default `pglens.explain.docs-in-prompt=false`). Both results are published here, as pre-registered.
3. **LLM vs template — the template wins, 10 to 0. `--plain` now defaults to the template; `--plain=llm` opts in to the model.**
   - **Who judged, and how:** the user delegated the ranking to Claude on 2026-09-26 and set the criterion: *"easy to understand while still keeping all the metrics and impact."*
   - **Not blind (disclosed):** Claude built both writers and generated the shuffle key.
   - **Pairs:** held-out cases 01, 03, 05, 07, 11, 13, 15, 16, 19 and 21, shown in the order set by seed 20260926.
   - **Why the model lost every pair:** its prose reads more smoothly, but on this criterion it lost each time.
     - It never gave the measured runtime (calls and time). The prompt never asked for it, which is a prompt-design gap, not a model error.
     - It never said the index is also validated for other queries (46 in pair 9).
     - It gave the percentage cost drop in only 2 of 10.
     - In 2 of 10 it didn't say what the index changes.
   - **The template** had every number every time. Its one weakness was the engine's raw evidence line ("~36236964 rows, total cost 479201.03"). Since then it prints those numbers grouped and rounded ("~36,236,964 rows, total cost 479,201"). That is a template-only change; the guard still passes it on all 24 cases.
   - **What would reopen this:** a prompt that asks for the runtime and the other-queries count (`p2`). It needs *new* held-out cases, because these 12 have been seen. Backlog B23.
4. **HNSW vs exact** — identical top 3 on all 5 queries (above).

### What this doesn't show
- **The size.** 12 held-out cases can't separate 11/12 from 12/12 with confidence. The rules were set before the data, and that is what they are for, not a claim of precision.
- **The grader.** Claude graded M3/M4 alone. The user's spot-check (25 %) is still to come.
- **Speed on other hardware.** Latency is for CPU-only Docker on one machine. A GPU (native Ollama on a Mac, or NVIDIA on Linux) will be several times faster; that wasn't measured.
- **The quality ceiling.** The template is PgLens's own wording, and the pre-registered rule 3 decides whether the model is worth its cost at all.
