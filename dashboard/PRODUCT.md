# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

Mostly backend developers and DBAs at their desks, doing a slow-query tune-up next to their editor
and terminal. Sometimes an on-call engineer checking what is slow right now, or a team looking at it
together on a shared screen.

## Product Purpose

PgLens is a self-hosted Postgres slow-query and index advisor. A read-only agent streams
`pg_stat_statements` to a server that ranks where the time goes, captures plans, flags plan
problems, and checks index ideas against the real planner with HypoPG. The dashboard answers, in
under a minute: which queries cost the most time, why, and which index (if any) is worth trying.

## Positioning

The integrated, history-aware, private loop: pg_stat_statements + HypoPG only, runs on your own
infrastructure, no data leaves it, and every number says whether it was measured or estimated.
HypoPG-checked advice by itself is standard in the field and is not the pitch.

## Operating Context

Used on a laptop or desktop browser, beside a code editor and a psql session. The user copies DDL
out of the dashboard into a migration, or runs `pglens confirm` on a copy of the database first.
Reached through `docker compose up` on localhost or behind a reverse proxy.

## Capabilities and Constraints

- Screens: databases (with agent status and registration), slow-query leaderboard, query detail
  (SQL, plan tree, suggested index, plain-language explanation, trend), trends, recommendations
  (per database and across databases, plus index hygiene), settings (users, API tokens, databases).
- Terms: "planner-validated" (HypoPG agreed the planner would use the index and cost drops),
  "not planner-validated" (GIN/GiST ideas HypoPG cannot simulate), "estimate" vs "measured".
- The monitored database is read-only, always. The dashboard never writes to it.
- Works with no LLM configured; explanations then come from a deterministic template.

## Brand Commitments

- Name: PgLens. Wordmark: a reticle mark with "Pg" in mono and "Lens" in sans (`src/components/wordmark.tsx`).
- Tone: professional and trustworthy, but good to look at and pleasant to use. Not toy-like or
  gamified, not a generic AI/SaaS template (the user's words, 2026-09-26).

## Evidence on Hand

Real data from the compose demo database (13 queries, validated and not-validated recommendations,
plan findings, trends). Benchmarks are in `docs/` and must be quoted as measured there; no invented
customers, testimonials, or performance claims.

## Product Principles

1. No fabricated evidence: every number is real and labeled measured or estimate.
2. Planner-validated is not the same as safe; the UI never implies it is.
3. Deterministic core, optional AI: every screen works with the LLM off.
4. The query is the subject: SQL must be easy to read and scan everywhere it appears.

## Accessibility & Inclusion

WCAG AA contrast, keyboard access, axe clean at desktop (1440 px) and phone (390 px) widths; color
is never the only signal (validated/not-validated also differ by icon and text).
