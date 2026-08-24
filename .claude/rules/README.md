# Path-scoped rules (progressive disclosure)

> Files here document **module-local conventions** and load **only when Claude touches matching files** — so they cost zero context budget the rest of the time. This keeps `CLAUDE.md` lean while still giving detailed, local guidance where code lives.

## How to add a rule
Create `<name>.md` with `paths:` frontmatter listing globs it applies to:

```markdown
---
description: Conventions for the collector agent
paths:
  - "agent/**"
---
- gRPC service defs live in `proto/`; regenerate stubs with `<command>`.
- All monitored-DB access goes through the read-only connection pool; never open a writable connection here.
```

## When to add rules (not yet — no code exists)
Add them as modules land, e.g.:
- `agent.md` → collector conventions (read-only pool, gRPC stubs).
- `server.md` → server conventions (analysis core boundary, HypoPG validator).
- `dashboard.md` → Next.js App Router conventions.

Keep each rule short and local. Style belongs to the linter, not here.
