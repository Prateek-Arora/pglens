---
version: 1
slug: "src-app"
primary_target: "src/app"
related_targets: []
---

# Dashboard surface brief

Scope: the whole PgLens dashboard (all routes). Mode: Operate. Audience: developers/DBAs at a desk
beside their editor; task: find where query time goes, why, and which index to try. Constraints:
every number labeled measured vs estimate; planner-validated is not safe; works with no LLM;
WCAG AA; 1440 + 390 px. The user delegated the direction choice ("pick one that is very good to look
at and visually appealing while also professional and clean").

## Direction contract

THESIS: Every query drawn to scale like an engineering drawing: measured in solid ink, planner
estimates in dashed and hatched line (the drafting convention for hidden edges). Refuses the grey
SaaS table and the neon dark dashboard.

OWN-WORLD: Light = cool drafting film with a faint non-photo-blue grid, white sheets, graphite ink,
cobalt action ink, redline vermilion for plan findings, green inspection mark for validated. Dark =
blueprint navy, chalk ink, cyan action. One ink per metric everywhere (total = cobalt/cyan, mean =
teal/mint, calls = plum/lilac). Syntax-colored SQL (JetBrains Mono) wherever a query appears.
Estimates hatched, never pale tints. Freshness states drawn: no data yet, collecting, live, stale.

STORY: The visitor sees which query owns the most time from bar length alone, reads its SQL in
color, opens it, sees the suggested index and the redlined plan node that explains it, steps to the
next query with j/k.

FIRST VIEWPORT: Leaderboard. A ruled title block (database, agent state, window, totals, last
sample) across the top; below, a white sheet holding the ranked table: rank, syntax-colored SQL
(2 lines max), a dimension bar to scale for the sorted metric with its value, mean, calls, rows,
advice mark. Window and sort toggles right-aligned above the sheet; each sort option carries its
metric ink.

FORM: Drafting Sheet (engineering drawings), candidate 7 of 7, seed key 10684582. Signature
interaction: plan tree with hatched cost bars to scale and redline callouts on problem nodes;
j/k steps through the ranking on query detail. Motion: bars draw in once (200 ms ease-out), nothing else.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
