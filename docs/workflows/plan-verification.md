# Workflow: Verify-Before-Plan (every user-provided phase plan)

> When the user hands over a phase plan, **do not jump to implementation.** Run this first. Established by [ADR-0008](../decisions.md#adr-0008). Output is a thorough, verified plan the user approves before any code is written.

## Steps

1. **Restate the goal** — one line: what this phase ships (self-contained deliverable) and its checkpoint, if any.

2. **Verify feasibility & facts**
   - Check any factual/technical claims (versions, library capabilities, API shapes). Web-search when a claim is load-bearing or could be stale.
   - Confirm prerequisites from prior phases are actually met (check `docs/project.md` tracker).
   - Confirm tools/libraries named actually do what the plan assumes (e.g., HypoPG access-method limits).

3. **Reconcile with existing decisions & architecture**
   - Cross-check against `docs/decisions.md` and `docs/architecture.md`. Flag any conflict with a locked decision or the stated architecture.
   - If the plan contradicts an ADR, surface it — don't silently follow either.

4. **Check against the guardrails**
   - "Actually usable" acceptance bar (5 items, `docs/project.md`).
   - Cross-cutting principles (no fabricated evidence, safe-by-default, deterministic core, ship each phase, drafts-not-actions, HypoPG honesty).

5. **Surface gaps, risks, ambiguities**
   - List missing steps, unstated assumptions, and risks with mitigations.
   - Ask the user only about **blocking** ambiguities (use AskUserQuestion). Make sensible defaults for the rest and state them.

6. **Produce the thorough plan** (in plan mode where applicable)
   - Ordered steps; files/modules touched; design patterns applied (SOLID/etc. where relevant).
   - **Definition of Done** + **Verification** (how we'll prove it works — tests, real DB run).
   - **What to deliberately skip** (YAGNI) for this phase.
   - The single best fast resource per *new* skill, and what to skip in it.
   - `[ASSUMPTION]`-tag anything unverified.

7. **Get go-ahead**, then execute. Respect plan mode: present via ExitPlanMode, don't start editing until approved.

8. **Record** — if the plan sets direction or resolves a design question, add/append an ADR in `docs/decisions.md`; update `docs/project.md` (Current focus + tracker) when the phase starts and when it ships.

## Anti-patterns to avoid
- Implementing a plan verbatim without the feasibility/conflict check.
- Fabricating numbers or capabilities to make a plan look complete.
- Expanding scope beyond the phase's self-contained deliverable.
