#!/usr/bin/env python3
"""Measure PgLens's recommendations against reality (Phase 2.5 accuracy benchmark, ADR-0038).

For every PLANNER_VALIDATED recommendation in a PgLens `scan --json` report, this BUILDS the index
for real on the throwaway benchmark database, re-runs the workload's own query instances with
EXPLAIN (ANALYZE, BUFFERS), and compares the measured change with PgLens's estimates:

  * generic   - the HypoPG generic-plan drop (what PgLens <= v0.0.3 ranked by)
  * floor     - the value-range worst case (what v0.0.4 ranks by, when present)
  * measured  - execution time (PRIMARY) and shared buffers touched (hit + read), each as
                1 - sum(after)/sum(before) over the query's instances, median of RUNS warm runs.

A recommendation counts as a measured WIN when warm execution time drops by at least the validation
gate (15 %). Why time and not buffers (decided after the first run, 2026-09-24, and published with
both): "buffers" counts every visit to a page, so an index probed inside a nested loop racks up
repeated hits on the same cached pages while doing far less work than a scan — on the first run
three indexes cut time by 67-72 % while *raising* buffers. The dogfood benchmark (ADR-0033) used
buffers because it compared scan-vs-scan on a table that fit in cache; here the whole dataset fits in
cache too, so warm time is the honest cost. Buffers stay in the table for transparency.
Every estimate stays labeled an estimate; every measured number is from this run only.

Usage: measure.py <container> <db> <user> <queries-dir> <pglens.json> <runs> <out.md> <out.json>
Only ever run against the throwaway benchmark container: it creates and drops real indexes.
"""
import json
import pathlib
import re
import statistics
import subprocess
import sys

GATE = 0.15
SESSION = (
    "SET max_parallel_workers_per_gather = 0; SET jit = off; "
    "SET work_mem = '64MB'; SET statement_timeout = '900s';\n"
)


def psql(ctx, sql):
    cmd = ["docker", "exec", "-i", ctx["container"], "psql", "-X", "-qAt",
           "-v", "ON_ERROR_STOP=1", "-U", ctx["user"], "-d", ctx["db"]]
    r = subprocess.run(cmd, input=sql, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.strip())
    return r.stdout


def explain_json(ctx, options, sql):
    out = psql(ctx, SESSION + f"EXPLAIN ({options}, FORMAT JSON) " + sql)
    return json.loads(out)[0]


def query_id(ctx, sql):
    return int(explain_json(ctx, "VERBOSE", sql)["Query Identifier"])


def measure(ctx, sql, runs):
    explain_json(ctx, "ANALYZE, BUFFERS", sql)  # warm-up
    buffers, millis = [], []
    for _ in range(runs):
        doc = explain_json(ctx, "ANALYZE, BUFFERS", sql)
        plan = doc["Plan"]
        buffers.append(plan.get("Shared Hit Blocks", 0) + plan.get("Shared Read Blocks", 0))
        millis.append(doc["Execution Time"])
    return statistics.median(buffers), statistics.median(millis)


def drop(before, after):
    return None if before <= 0 else 1 - after / before


def pct(x):
    """A drop as "−58.0 %" (smaller is better after the index); a regression as "+10.0 %"."""
    if x is None:
        return "—"
    return f"−{x * 100:.1f} %" if x >= 0 else f"+{-x * 100:.1f} %"


def main():
    (container, db, user, qdir, report_path, runs, out_md, out_json) = sys.argv[1:9]
    ctx = {"container": container, "db": db, "user": user}
    runs = int(runs)

    # 1. The workload's own query instances, keyed by pg_stat_statements queryid.
    instances = {}  # queryid -> [(tpch query number, sql)]
    for f in sorted(pathlib.Path(qdir).glob("q*_s*.sql")):
        qn = int(re.match(r"q(\d+)_s", f.name).group(1))
        sql = f.read_text()
        instances.setdefault(query_id(ctx, sql), []).append((qn, sql))

    # 2. What PgLens said.
    report = json.loads(pathlib.Path(report_path).read_text())
    ranked = {(r["queryId"], r["recommendation"]["candidate"]["table"],
               tuple(r["recommendation"]["candidate"]["columns"])): r
              for r in report.get("topRecommendations", [])}
    tried = {"PLANNER_VALIDATED": 0, "SUPPRESSED": 0, "NOT_PLANNER_VALIDATED": 0}
    validated = []
    captured = 0
    for q in report["queries"]:
        captured += 1 if q.get("planCaptured") else 0
        for rec in q.get("recommendations", []):
            status = rec["validation"]["status"]
            tried[status] = tried.get(status, 0) + 1
            if status == "PLANNER_VALIDATED":
                validated.append((q, rec))

    # 3. Build each validated index for real, one at a time, and measure its own query's instances.
    baseline = {}
    rows = []
    for q, rec in validated:
        cand, val = rec["candidate"], rec["validation"]
        mine = instances.get(q["queryId"], [])
        if not mine:
            rows.append({"query": "?", "ddl": None, "note": "no workload instance matched this queryid"})
            continue
        for _, sql in mine:
            if sql not in baseline:
                baseline[sql] = measure(ctx, sql, runs)
        ddl = "CREATE INDEX {} ON {} {}({});".format(
            "pglens_bench_idx", cand["table"],
            "" if cand["accessMethod"] == "BTREE" else f"USING {cand['accessMethod'].lower()} ",
            ", ".join(cand["columns"]))
        psql(ctx, ddl)
        try:
            after = {sql: measure(ctx, sql, runs) for _, sql in mine}
        finally:
            psql(ctx, "DROP INDEX pglens_bench_idx;")
        b_buf = sum(baseline[s][0] for _, s in mine)
        a_buf = sum(after[s][0] for _, s in mine)
        b_ms = sum(baseline[s][1] for _, s in mine)
        a_ms = sum(after[s][1] for _, s in mine)
        vr = val.get("valueRange")
        rr = ranked.get((q["queryId"], cand["table"], tuple(cand["columns"])), {})
        rows.append({
            "tpch": sorted({n for n, _ in mine}),
            "ddl": "CREATE INDEX ON {} ({})".format(cand["table"], ", ".join(cand["columns"])),
            "generic": val.get("relativeDelta"),
            "floor": vr["worstRelativeDrop"] if vr else None,
            "scoreBasis": rr.get("scoreBasis"),
            "actionable": rr.get("actionable", True) if rr else None,
            "measuredBuffers": drop(b_buf, a_buf),
            "measuredTime": drop(b_ms, a_ms),
            "beforeBuffers": b_buf, "afterBuffers": a_buf,
            "beforeMs": round(b_ms, 2), "afterMs": round(a_ms, 2),
            "instances": len(mine),
        })

    measured = [r for r in rows if r.get("ddl")]
    wins = [r for r in measured if r["measuredTime"] is not None and r["measuredTime"] >= GATE]
    buffer_wins = [r for r in measured
                   if r["measuredBuffers"] is not None and r["measuredBuffers"] >= GATE]

    def err(key):
        e = [abs(r[key] - r["measuredTime"]) for r in measured
             if r.get(key) is not None and r["measuredTime"] is not None]
        return (statistics.mean(e), len(e)) if e else (None, 0)

    g_err, g_n = err("generic")
    f_err, f_n = err("floor")
    summary = {
        "statementsAnalyzed": len(report["queries"]), "plansCaptured": captured,
        "candidatesTried": tried, "validated": len(validated), "measured": len(measured),
        "measuredWins": len(wins),
        "precision": (len(wins) / len(measured)) if measured else None,
        "bufferWins": len(buffer_wins),
        "bufferPrecision": (len(buffer_wins) / len(measured)) if measured else None,
        "meanAbsErrorGeneric": g_err, "genericN": g_n,
        "meanAbsErrorFloor": f_err, "floorN": f_n,
    }
    pathlib.Path(out_json).write_text(json.dumps({"summary": summary, "rows": rows}, indent=2))

    md = ["| TPC-H Q | Index | Generic est. | Floor est. | Measured time | Measured buffers | Win (time)? |",
          "|---|---|---|---|---|---|---|"]
    for r in measured:
        md.append("| {} | `{}` | {} | {} | {} ({:,} → {:,} ms) | {} ({:,} → {:,}) | {} |".format(
            ",".join(map(str, r["tpch"])), r["ddl"], pct(r["generic"]), pct(r["floor"]),
            pct(r["measuredTime"]), r["beforeMs"], r["afterMs"],
            pct(r["measuredBuffers"]), int(r["beforeBuffers"]), int(r["afterBuffers"]),
            "yes" if r in wins else "**no**"))
    md.append("")
    md.append(f"- Statements analyzed: {summary['statementsAnalyzed']} (plans captured: {captured})")
    md.append(f"- Candidates tried: {tried}")
    if measured:
        md.append(f"- Planner-validated recommendations measured: {len(measured)}; measured wins "
                  f"(warm time ↓ ≥ {int(GATE * 100)} %): {len(wins)} → precision "
                  f"{len(wins) / len(measured):.0%} (by buffers instead: {len(buffer_wins)} → "
                  f"{len(buffer_wins) / len(measured):.0%})")
    if g_n:
        md.append(f"- Mean |estimate − measured time drop|: generic {g_err:.1%} over {g_n} recs"
                  + (f"; value-range floor {f_err:.1%} over {f_n} recs" if f_n else ""))
    pathlib.Path(out_md).write_text("\n".join(md) + "\n")
    print("\n".join(md))


if __name__ == "__main__":
    main()
