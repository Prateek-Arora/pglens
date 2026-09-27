---
name: PgLens
description: Postgres slow-query and index advisor, drawn like an engineering sheet
colors:
  cobalt-ink: "oklch(0.5 0.17 260)"
  graphite-ink: "oklch(0.24 0.025 255)"
  pencil-grey: "oklch(0.46 0.025 250)"
  drafting-film: "oklch(0.975 0.006 235)"
  sheet-white: "oklch(1 0 0)"
  sheet-rule: "oklch(0.885 0.014 240)"
  code-well: "oklch(0.972 0.01 240)"
  inspection-green: "oklch(0.49 0.12 155)"
  redline-vermilion: "oklch(0.58 0.19 32)"
  danger-red: "oklch(0.54 0.2 27)"
  metric-mean-teal: "oklch(0.52 0.1 195)"
  metric-calls-plum: "oklch(0.5 0.15 320)"
  sql-keyword-violet: "oklch(0.47 0.16 285)"
  sql-literal-amber: "oklch(0.53 0.15 48)"
  blueprint-navy: "oklch(0.225 0.05 258)"
  blueprint-sheet: "oklch(0.255 0.055 258)"
  chalk-ink: "oklch(0.955 0.012 240)"
  cyan-ink: "oklch(0.8 0.12 225)"
typography:
  headline:
    fontFamily: "IBM Plex Sans, ui-sans-serif, system-ui, sans-serif"
    fontSize: "1.5rem"
    fontWeight: 600
    lineHeight: 1.3
  title:
    fontFamily: "IBM Plex Sans, ui-sans-serif, system-ui, sans-serif"
    fontSize: "1.125rem"
    fontWeight: 600
    lineHeight: 1.4
  body:
    fontFamily: "IBM Plex Sans, ui-sans-serif, system-ui, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: 1.55
    fontFeature: "tnum"
  label:
    fontFamily: "IBM Plex Sans, ui-sans-serif, system-ui, sans-serif"
    fontSize: "0.8125rem"
    fontWeight: 400
    lineHeight: 1.25rem
  code:
    fontFamily: "JetBrains Mono, ui-monospace, SFMono-Regular, Menlo, monospace"
    fontSize: "13px"
    fontWeight: 400
    lineHeight: 1.6
rounded:
  sm: "3.6px"
  md: "4.8px"
  lg: "6px"
spacing:
  control: "36px"
  cell-x: "16px"
  cell-y: "12px"
  sheet-pad: "20px"
components:
  button-primary:
    backgroundColor: "{colors.cobalt-ink}"
    textColor: "{colors.sheet-white}"
    rounded: "{rounded.md}"
    height: "36px"
    padding: "0 14px"
  button-outline:
    backgroundColor: "{colors.sheet-white}"
    textColor: "{colors.graphite-ink}"
    rounded: "{rounded.md}"
    height: "36px"
  input:
    backgroundColor: "{colors.drafting-film}"
    textColor: "{colors.graphite-ink}"
    rounded: "{rounded.md}"
    height: "36px"
    padding: "0 12px"
  sheet:
    backgroundColor: "{colors.sheet-white}"
    rounded: "{rounded.lg}"
    padding: "20px"
  toggle-current:
    backgroundColor: "{colors.graphite-ink}"
    textColor: "{colors.sheet-white}"
    rounded: "{rounded.sm}"
  code-block:
    backgroundColor: "{colors.code-well}"
    textColor: "{colors.graphite-ink}"
    typography: "{typography.code}"
    rounded: "{rounded.md}"
---

# Design System: PgLens

## Overview

**Creative North Star: "The Drafting Sheet"**

PgLens draws query data the way an engineering drawing draws a part. What was measured is solid
ink; what the planner only estimates is dashed or hatched, the drafting convention for a hidden
edge. Content lies on white sheets over a cool drafting-film ground in light mode, and on blueprint
navy with chalk ink in dark mode. The theme follows the operating system (`prefers-color-scheme`).

It is an Operate surface for developers at a desk beside their editor: dense where the task is
comparison (ranked tables, plan trees), quiet everywhere else. Color is never decoration. Each hue
has one job: action, validated, plan problem, danger, or one of the three metrics. SQL is the
subject, so every query is syntax-colored wherever it appears. Rejected on the way here: a grey
"stock template" look (the user called it boring), a page-wide grid background (a generated-UI
signature), and a toy or gamified finish.

**Key Characteristics:**

- Solid = measured, dashed/hatched = planner estimate, in text, bars and plan nodes alike.
- To-scale dimension bars with an end tick, read against a round-graduation ruler.
- One ink per metric everywhere: total time, mean time, calls.
- Syntax-colored SQL in JetBrains Mono at 13 px; interface in IBM Plex Sans with tabular numerals.
- White sheets with a soft offset shadow on a cool film; blueprint navy in dark mode.
- Plan problems are redlines: a dashed vermilion outline and a numbered balloon.

## Colors

A restrained cool palette where every hue is a role, with a second composed palette for dark mode.

### Primary

- **Cobalt Ink** (oklch(0.5 0.17 260)): anything you can act on: primary buttons, links, the
  current tab underline, focus rings, the "with the index" cost bar. It is also the total-time metric
  ink. Dark mode: **Cyan Ink** (oklch(0.8 0.12 225)) with navy text on it.

### Secondary

- **Inspection Green** (oklch(0.49 0.12 155); dark oklch(0.82 0.15 155)): validated or healthy
  only: the Planner-validated mark, "Agent connected", faster-than-before changes.
- **Redline Vermilion** (oklch(0.58 0.19 32); text oklch(0.47 0.16 32)): a problem PgLens found in
  a plan, and slower-than-before changes. Always drawn as line (dashed outline, balloon, arrow),
  never as a filled panel.
- **Danger Red** (oklch(0.54 0.2 27)): destructive actions and the stale-agent state only.

### Tertiary

- **Metric Mean Teal** (oklch(0.52 0.1 195); dark mint oklch(0.84 0.11 175)) and **Metric Calls
  Plum** (oklch(0.5 0.15 320); dark lilac oklch(0.8 0.12 320)): the mean-time and calls inks.
- **SQL inks**: keyword violet (oklch(0.47 0.16 285)), function teal, literal and `$n` amber
  (oklch(0.53 0.15 48)), punctuation and comments in pencil grey. All are CSS variables
  (`--sql-*`), so server-rendered Shiki output follows the theme.

### Neutral

- **Drafting Film** (oklch(0.975 0.006 235)): the page ground in light mode. It is cool, never cream.
- **Sheet White** (oklch(1 0 0)): tables, cards, code panels, the title block.
- **Graphite Ink** (oklch(0.24 0.025 255)): body text, and the fill of the current toggle.
- **Pencil Grey** (oklch(0.46 0.025 250)): secondary text, captions, estimate hatching at rest.
- **Sheet Rule** (oklch(0.885 0.014 240)): borders, row rules, bar tracks, ruler graduations.
- **Code Well** (oklch(0.972 0.01 240)): the background of SQL blocks.
- **Blueprint Navy / Blueprint Sheet / Chalk Ink** (oklch(0.225 0.05 258) / oklch(0.255 0.055 258) /
  oklch(0.955 0.012 240)): the dark-mode ground, sheets and text.

### Named Rules

**The One Job Rule.** Every hue has exactly one meaning, the same on every page. A color that
means nothing is not added.

**The One Ink Per Metric Rule.** Total time, mean time and calls always print in their own ink
(`METRIC_INK`): sort toggles, column headers, values, totals tables and chart lines. The sorted
metric is bold, never a different color.

**The Hatch, Don't Tint Rule.** An estimate in a bar is hatched in its ink (`.hatch`), never a
paler tint of it. A measurement is a solid fill.

## Typography

**Body Font:** IBM Plex Sans (variable, self-hosted from npm), with system-ui fallback
**Label/Mono Font:** JetBrains Mono (variable, self-hosted), for SQL, DDL, commands and ids only

**Character:** an engineered grotesque for the interface, paired with a code face whose tall
x-height keeps long SQL legible at 13 px. Both load through `next/font/local`; nothing comes from a
font CDN.

### Hierarchy

- **Headline** (600, 1.5rem, tight tracking): the page's h1: a database name, "Databases", "Settings".
- **Title** (600, 1.125rem): section heads such as "Where the time goes" and "Top movers".
- **Subtitle** (600, 1rem): section heads inside the query page.
- **Body** (400, 0.875rem, 1.55): prose and table cells. Explanation prose is capped at 75ch.
- **Label** (400, 0.8125rem): captions over values, "est.", meta lines. Nothing is smaller than
  13 px except the ruler graduations (11 px).
- **Code** (JetBrains Mono 400, 13px, 1.6): every query, DDL and command.

### Named Rules

**The SQL Is Code Rule.** A query never renders as plain mono text: it goes through `SqlInline`
(lists, headings) or `SqlBlock` (full text, copyable), both colored by the `--sql-*` inks.

**The Tabular Rule.** Numerals are tabular everywhere, so columns of times line up.

## Layout

A 1280 px (max-w-7xl) content column with 16 px side padding. Pages stack sections with 32 px
between them. Each database gets a **title block** (name | agent state | last sample, ruled cells)
above its tabs. The leaderboard adds a **title strip** (queries | total time | window | source).
Tables use 16 px cell padding at the edges and 12 px row padding. Below 768 px the leaderboard
becomes a stacked list: rank, SQL clamped to 3 lines, the bar, then stat pairs. Title cells wrap two
to a row, and copy buttons move above code rather than over it. The query page reads in the
order of its argument: suggested index → how to measure it → the redlined plan → plain language →
measured totals → SQL → other ideas → trend.

## Elevation & Depth

Depth comes from **sheets on a ground**, not from stacked shadows. A sheet is a 1 px ruled border
plus one soft offset shadow. Nothing floats higher except popovers and tooltips.

### Shadow Vocabulary

- **Sheet** (`box-shadow: 0 1px 2px oklch(0.3 0.05 250 / 0.06), 0 6px 16px -8px oklch(0.3 0.05 250 / 0.12)`;
  dark: `0 1px 2px oklch(0.1 0.04 258 / 0.4), 0 8px 20px -10px oklch(0.1 0.04 258 / 0.6)`): tables,
  cards, panels, code, the title block, popovers.

### Named Rules

**The One Sheet Rule.** Sheets never nest inside sheets. Inner grouping uses rules (dividers) or a
code well.

## Shapes

Crisp, lightly softened corners: 6 px on sheets and cards, 4.8 px on controls and code, 3.6 px on
toggle segments. Bars have square ends and a 1 px end tick, like a dimension line. The only round
shapes are state marks (8 px agent dots, 20 px redline balloons). Dashed strokes are reserved for
"not measured / not validated": the estimate underline, dashed badges, redline outlines, and the
plan tree's connector lines.

## Components

### Buttons

- **Shape:** gently squared (4.8 px), one 36 px height shared with inputs and selects.
- **Primary:** Cobalt Ink with white text, 14 px side padding. Dark mode: Cyan Ink with navy text.
- **Outline:** sheet background, ruled border, graphite text. Used for Copy, Sign out and Rotate.
- **Hover / Focus:** a slight darken on hover; a ring-colored 3 px focus ring.

### Chips (status marks)

- **Planner-validated:** a green outline and soft green fill, with a check icon.
- **Not planner-validated:** a dashed outline in pencil grey, with a dashed-circle icon.
- **Agent state:** an 8 px mark plus words: solid green with a halo = connected, solid red = stale,
  a dashed ring = waiting for the agent. The API has no "collecting" state, so none is drawn.

### Cards / Containers

- **Corner Style:** 6 px. **Background:** Sheet White / Blueprint Sheet. **Border:** 1 px rule.
- **Shadow Strategy:** the Sheet shadow. **Internal Padding:** 20 px (16 px on dense lists).

### Inputs / Fields

- **Style:** drafting-film background inside white sheets, a 1 px input rule, 4.8 px corners, 36 px tall.
- **Focus:** the border shifts to the ring color, with a 3 px ring at 50%.
- Help text sits under the row, never inside it.

### Navigation

- **Header:** a sheet-white bar holding the wordmark, then Databases, Recommendations and Settings,
  then the user and Sign out. The current page is graphite and medium weight; the rest are pencil grey.
- **Tabs:** the current tab has a 2 px cobalt underline. On phones the header links get their own
  scrollable row.
- **Segmented toggles** (window, sort): a ruled sheet group. The current segment is filled graphite
  with white text. Sort segments carry their metric ink as a dot.

### Dimension Bar and Scale Ruler (signature)

A bar drawn to scale on a hairline track, closed by a 1 px end tick. It is solid for measured values
and hatched for planner estimates. The leaderboard's Query header carries a ruler whose graduations
fall on round values (1, 2, 2.5 or 5 × 10ⁿ) of the sorted metric, so bar length can be read. Bars
draw in once from the left (200 ms, expo ease-out; none under reduced motion).

### Redlined Plan Tree (signature)

A monospace tree with dashed connector lines. Each node shows its planner rows and cost ("est.")
and a hatched cost bar to scale against the root. A node with a finding gets a dashed vermilion
outline and a numbered balloon (1, 2… in plan order) before the finding's title and evidence.

### Cost Drop (signature)

Two hatched bars, "Planner cost now" (pencil) and "With the index" (cobalt), drawn to the same
scale with their estimate values.

## Do's and Don'ts

### Do:

- **Do** render every number through `<Measured>`, `<Estimate>` or `<Count>`. Estimates always
  carry the dashed underline and "est.".
- **Do** print total time, mean time and calls in their metric inks wherever they appear.
- **Do** put content on a sheet, and inner structure in rules or code wells.
- **Do** keep inputs, selects and buttons at 36 px, with help text below the row.
- **Do** screenshot every route at 1440 and 390 px, in both themes, and run axe before calling
  visual work done.

### Don't:

- **Don't** show a query as plain mono text; use `SqlInline` or `SqlBlock`.
- **Don't** put a grid or pattern on the page background.
- **Don't** use a pale tint to mean "estimate"; hatch it.
- **Don't** fill a plan problem with a tinted alert panel; draw the redline.
- **Don't** add a color without a role, or reuse a role color for decoration.
- **Don't** let "Planner-validated" read as "safe"; the measure-first step stays beside every
  validated suggestion.
