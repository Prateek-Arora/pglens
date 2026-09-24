#!/usr/bin/env python3
"""Turn raw qgen output into runnable PostgreSQL statements (Phase 2.5 accuracy benchmark).

qgen (tpch-kit, DATABASE=POSTGRESQL) emits a few things Postgres rejects, which we fix
mechanically rather than hand-edit queries:
  * `limit N;` on its own line AFTER the statement's `;`  -> moved inside (`... limit N`), and
    `limit -1;` (no row limit) is dropped;
  * the SQL-standard interval precision `interval '90' day (3)` -> `interval '90' day`.
Q15 (create view / select / drop view) is excluded by the caller: it is DDL, not a query.

Usage: prepare_queries.py <raw-dir> <out-dir>   (raw files named q<N>_s<seed>.sql)
"""
import pathlib
import re
import sys

LIMIT = re.compile(r"^\s*limit\s+(-?\d+)\s*;\s*$", re.IGNORECASE)
INTERVAL_PRECISION = re.compile(r"(interval\s+'[^']*'\s+(?:day|month|year))\s*\(\s*\d+\s*\)", re.IGNORECASE)


def clean(text: str) -> str:
    lines = [l for l in text.splitlines() if not l.lstrip().startswith("--")]
    limit = None
    body = []
    for line in lines:
        m = LIMIT.match(line)
        if m:
            limit = int(m.group(1))
        else:
            body.append(line)
    sql = "\n".join(body).strip()
    sql = INTERVAL_PRECISION.sub(r"\1", sql)
    if sql.endswith(";"):
        sql = sql[:-1].rstrip()
    if limit is not None and limit > 0:
        sql += f"\nlimit {limit}"
    return sql + ";\n"


def main() -> None:
    raw, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    for f in sorted(raw.glob("q*_s*.sql")):
        (out / f.name).write_text(clean(f.read_text()))


if __name__ == "__main__":
    main()
