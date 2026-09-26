# Phase Plans

> Each phase is a **self-contained** plan file, added here once verified via `../workflows/plan-verification.md`. A phase file restates its own prerequisites and ends with a **Definition of Done** + **Verification**.

## Index
| Phase | File | Title | Status |
|---|---|---|---|
| 0 | [`phase_0.md`](phase_0.md) | Foundations & Dev Environment | ✅ done (`v0.0.0-scaffold`) |
| 1 | [`phase_1.md`](phase_1.md) | Core Advisor Engine (CLI) — MVP | ✅ done (`v0.0.1`) |
| 2 | [`phase_2.md`](phase_2.md) | Collector Agent + Server + History | ✅ done (`v0.0.2`; hardening `v0.0.3`) |
| 2.5 | [`phase_2_5.md`](phase_2_5.md) | Recommendation Accuracy Sprint | ✅ done (`v0.0.4`; ADR-0038/0039) |
| 2.6 | [`phase_2_6.md`](phase_2_6.md) | Confirm on a Copy (measured index checks, B6) | ✅ done (ADR-0042) · `v0.0.5` ✅ |
| 3 | [`phase_3.md`](phase_3.md) | Plain-Language Explanations (local LLM, grounded + checked) | ✅ shipped 2026-09-26 · `v0.0.6` |
| 4 | [`phase_4.md`](phase_4.md) | Web API, Security, Dashboard — **ship checkpoint** (4A `v0.0.7` headless: Boot 4, TLS, login, registration, REST · 4B `v0.1.0-rc` dashboard) | 🚧 approved 2026-09-26 · 4A Steps 0–5 built |
| 5 | `phase_5.md` _(pending)_ | Containerize + Kubernetes + Helm (v0.2) | ⬜ |
| 6 | `phase_6.md` _(pending)_ | IaC + Cloud Deploy + Hardening (v0.2) | ⬜ |
| 7 | `phase_7.md` _(pending)_ | Launch & Community (v0.2) | ⬜ |

## Convention for a phase file
1. **Context & prerequisites** (self-contained restatement).
2. **Verification of the user's plan** — what was checked, corrected, or confirmed.
3. **Thorough plan** — ordered steps, files touched, patterns applied.
4. **Deliberately skipped** (YAGNI) + single best resource per new skill.
5. **Definition of Done** + **Verification**.
