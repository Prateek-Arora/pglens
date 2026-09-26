---
description: Conventions for the :explain module (plain-language explanations, Phase 3)
paths:
  - "explain/**"
  - "server/src/main/java/com/pglens/server/explain/**"
  - "cli/src/main/java/com/pglens/cli/*Explanation*"
  - "cli/src/main/java/com/pglens/cli/LlmOptions.java"
  - "cli/src/main/java/com/pglens/cli/PlainExplanations.java"
---
- **The LLM only phrases; PgLens renders every fact** (ADR-0043). The model writes exactly three prose
  fields (`summary`, `whyItIsSlow`, `whatTheIndexChanges`). The DDL, estimate label, value-range and
  build cautions, write load and "planner-validated ≠ safe" are rendered from the report by
  `ExplanationRenderer`, and are **kept out of the prompt** (`ExplanationFacts` has no DDL and no
  caveats). Don't add a caveat to the facts; render it.
- **`:engine` never depends on `:explain`.** The deterministic core must build and run with no LLM
  code on its classpath. `:explain` depends on `:engine`'s model only.
- **Facts are pre-formatted** (`Formats`: "153.3 s (about 2.6 min)", "9,761", "58.3%") so the model
  copies instead of converting. `FactsBuilder` keeps only the findings the index *addresses*
  (same rule + table + a key column); the rest is a count (`otherFindingsInQuery`).
- **`OutputGuard` is the trust layer; tune it on the dev split only.** It checks shape, no SQL,
  unit-aware numbers (±5 %), identifiers/index mentions, and honesty phrasing. It must stay
  consistent with the template: `OutputGuardTest.theTemplatePassesTheGuardOnEveryEvalCase`. Add
  every real bad answer you see as a guard test. Never loosen a check to make a held-out case pass
  (`docs/llm-eval.md` §1.2).
- **Retry once, then the template, always with a reason.** `Explainer` circuit-breaks on failures
  that repeat for every target (refused / unreachable / timeout / HTTP) so a down model costs one
  attempt, not one timeout per index. `Trace.failure` is set only for LLM-call failures; the server
  retries those after `retry-after-ms`, but not guard rejections (temperature 0 repeats).
- **Runtime-agnostic HTTP, no SDK** (`OpenAiCompatibleClient`, `java.net.http` + Jackson). Always
  send `temperature 0`, `max_tokens`, and `reasoning_effort: "none"` (thinking on = 211 s and no
  answer in the spike). `json_schema` → on HTTP 400 downgrade to `json_object`, then drop
  `reasoning_effort`.
- **Privacy gate before any request** (`EndpointPolicy`): only loopback / private / CGNAT / ULA /
  `host.docker.internal` / single-label hosts unless `allowRemote`. Never log the API key or prompt.
- **Bump `PromptBuilder.VERSION`** on any change to the system prompt, schema or facts shape: it
  is part of the server cache key. Bump `KnowledgeStore.CORPUS_VERSION` when
  `knowledge/pg16-docs.jsonl` changes.
- **Retrieval queries use signals, never SQL** (`ReferenceSource.signals`). nomic-embed-text needs
  the `search_document:` / `search_query:` prefixes.
- **Tests:** everything runs against the stub (`StubLlmServer`, a test fixture) — no model in CI.
  Real-model runs are `make llm-eval` (explain) and `./gradlew :server:retrievalEval`, never part of
  `build`. The 24 eval cases are frozen: don't regenerate them to make a result look better.
