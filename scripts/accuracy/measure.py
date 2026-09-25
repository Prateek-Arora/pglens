#!/usr/bin/env python3
"""Measure PgLens's recommendations against reality (Phase 2.5 accuracy benchmarks, ADR-0038/0039).

For every PLANNER_VALIDATED recommendation in a PgLens `scan --json` report, this BUILDS the index
for real on the throwaway benchmark database (each distinct index once), re-runs the workload's own
query instances with EXPLAIN (ANALYZE, BUFFERS), and compares the measured change with PgLens's estimates:

  * generic   - the HypoPG generic-plan drop (what PgLens <= v0.0.3 ranked by)
  * floor     - what v0.0.4 ranks by when a value range exists: min(generic, worst case), clamped
                at 0 (RankingScore)
  * measured  - execution time (PRIMARY) and shared buffers touched (hit + read), each as
                1 - sum(after)/sum(before) over the query's instances, median of RUNS warm runs,
                under the server's own settings with per-node timing off (MEASURE_SETTINGS below).

A recommendation counts as a measured WIN when warm execution time drops by at least the validation
gate (15 %). Why time and not buffers (decided after the first run, 2026-09-24, and published with
both): "buffers" counts every visit to a page, so an index probed inside a nested loop racks up
repeated hits on the same cached pages while doing far less work than a scan — on the first run
three indexes cut time by 67-72 % while *raising* buffers. The dogfood benchmark (ADR-0033) used
buffers because it compared scan-vs-scan on a table that fit in cache; here the whole dataset fits in
cache too, so warm time is the honest cost. Buffers stay in the table for transparency.
Every estimate stays labeled an estimate; every measured number is from this run only.

Besides precision it reports the metrics pre-registered for the JOB run (docs/benchmarks.md): M1 the
floor's vs the generic estimate's error on the same recs, M2 miss rate of cautioned (floor below the
gate) vs uncautioned recs, M5 Spearman rank correlation of each estimate with the measured drop.
A query whose baseline or indexed measurement fails (e.g. times out) is listed as not measured.

Query instances are the *.sql files in <queries-dir>; the label is the TPC-H number for qgen files
(q<N>_s<seed>.sql) and the file stem otherwise (JOB: 1a, 13d, ...).

Usage: measure.py <container> <db> <user> <queries-dir> <pglens.json> <runs> <out.md> <out.json>
Only ever run against the throwaway benchmark container: it creates and drops real indexes.
"""
import json
import os
import pathlib
import re
import statistics
import subprocess
import sys

GATE = 0.15
DISAGREE = 0.05  # M1: the floor "disagrees" with the generic estimate by at least 5 points
# Session settings for every measured run. "server" (the default since ADR-0041) keeps the server's
# own settings — the ones the workload ran with and PgLens's HypoPG validation planned under.
# "pinned" (only to reproduce the first TPC-H table, 2026-09-24) turns off parallelism + JIT and
# raises work_mem: on JOB that made the planner pick different plans (31a: > 600 s pinned vs ~2 s
# with server settings) — it measured plans the workload never runs (ADR-0040).
SETTINGS = os.environ.get("MEASURE_SETTINGS", "server")
SESSION = {
    "pinned": ("SET max_parallel_workers_per_gather = 0; SET jit = off; "
               "SET work_mem = '64MB'; SET statement_timeout = '900s';\n"),
    "server": "SET statement_timeout = '900s';\n",
}[SETTINGS]
# "server" also turns off per-node timing: EXPLAIN ANALYZE's clock reads inflate nested-loop-heavy
# plans far more than others (JOB 28a: 197 s with TIMING OFF vs > 360 s with it on), which would bias
# the before/after ratio. Execution Time and buffers are still reported with TIMING OFF.
ANALYZE = {"pinned": "ANALYZE, BUFFERS", "server": "ANALYZE, BUFFERS, TIMING OFF"}[SETTINGS]


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
    explain_json(ctx, ANALYZE, sql)  # warm-up
    buffers, millis = [], []
    for _ in range(runs):
        doc = explain_json(ctx, ANALYZE, sql)
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


def label(path):
    m = re.match(r"q(\d+)_s\d+$", path.stem)
    return m.group(1) if m else path.stem


def ddl_text_of(cand):
    return "CREATE INDEX ON {} ({})".format(cand["table"], ", ".join(cand["columns"]))


def floor_of(generic, worst):
    """RankingScore.drop: the lower of the generic and worst-case drops, clamped at 0."""
    if worst is None:
        return None
    return max(0.0, min(generic, worst)) if generic is not None else max(0.0, worst)


def spearman(xs, ys):
    """Spearman rank correlation (average ranks for ties); None below 3 pairs or no variance."""
    if len(xs) < 3:
        return None

    def ranks(v):
        order = sorted(range(len(v)), key=lambda i: v[i])
        r = [0.0] * len(v)
        i = 0
        while i < len(order):
            j = i
            while j + 1 < len(order) and v[order[j + 1]] == v[order[i]]:
                j += 1
            for k in range(i, j + 1):
                r[order[k]] = (i + j) / 2 + 1
            i = j + 1
        return r

    rx, ry = ranks(xs), ranks(ys)
    mx, my = statistics.mean(rx), statistics.mean(ry)
    cov = sum((a - mx) * (b - my) for a, b in zip(rx, ry))
    vx = sum((a - mx) ** 2 for a in rx)
    vy = sum((b - my) ** 2 for b in ry)
    return None if vx == 0 or vy == 0 else cov / (vx * vy) ** 0.5


def first_line(err):
    return str(err).strip().splitlines()[-1][:160] if str(err).strip() else repr(err)


def main():
    (container, db, user, qdir, report_path, runs, out_md, out_json) = sys.argv[1:9]
    ctx = {"container": container, "db": db, "user": user}
    runs = int(runs)

    # 1. The workload's own query instances, keyed by pg_stat_statements queryid.
    instances = {}  # queryid -> [(label, sql)]
    for f in sorted(pathlib.Path(qdir).glob("*.sql")):
        sql = f.read_text()
        try:
            qid = query_id(ctx, sql)
        except RuntimeError as e:
            print(f"  ! {f.name}: can't EXPLAIN ({first_line(e)}) — left out", file=sys.stderr)
            continue
        instances.setdefault(qid, []).append((label(f), sql))

    # 2. What PgLens said.
    report = json.loads(pathlib.Path(report_path).read_text())
    ranked = {(r["queryId"], r["recommendation"]["candidate"]["table"],
               tuple(r["recommendation"]["candidate"]["columns"])): (i + 1, r)
              for i, r in enumerate(report.get("topRecommendations", []))}
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

    # 3. Measure every needed instance without secondary indexes, then build each DISTINCT validated
    #    index once, measure the instances of every query it was recommended for, and drop it.
    #    (One build per index, not per (query, index) pair: same numbers, far fewer builds.)
    def key(cand):
        return (cand["table"], tuple(cand["columns"]), cand["accessMethod"])

    rows = []
    groups = {}  # index key -> [(q, rec)]
    for q, rec in validated:
        if not instances.get(q["queryId"]):
            rows.append({"query": "?", "ddl": None, "index": ddl_text_of(rec["candidate"]),
                         "note": "no workload instance matched this queryid"})
        else:
            groups.setdefault(key(rec["candidate"]), []).append((q, rec))

    baseline, failed = {}, {}  # sql -> (buffers, ms) | error
    needed = list(dict.fromkeys(sql for recs in groups.values() for q, _ in recs
                                for _, sql in instances[q["queryId"]]))
    for n, sql in enumerate(needed, 1):
        print(f"  baseline [{n}/{len(needed)}]", file=sys.stderr, flush=True)
        try:
            baseline[sql] = measure(ctx, sql, runs)
        except RuntimeError as e:
            failed[sql] = first_line(e)

    for g, ((table, columns, am), recs) in enumerate(groups.items(), 1):
        cand = recs[0][1]["candidate"]
        ddl_text = ddl_text_of(cand)
        print(f"  index [{g}/{len(groups)}] {ddl_text} for {len(recs)} quer(y/ies)", file=sys.stderr, flush=True)
        ddl = "CREATE INDEX pglens_bench_idx ON {} {}({});".format(
            table, "" if am == "BTREE" else f"USING {am.lower()} ", ", ".join(columns))
        try:
            psql(ctx, ddl)
        except RuntimeError as e:
            # HypoPG sizes a hypothetical index but never writes an entry, so it can't know that a
            # real row is too wide for the access method (e.g. a btree entry over ~2.7 kB).
            for q, _ in recs:
                rows.append({"query": sorted({l for l, _ in instances[q["queryId"]]}), "ddl": None,
                             "index": ddl_text, "unbuildable": True,
                             "note": f"index could not be built: {first_line(e)}"})
            continue
        after = {}
        try:
            for q, _ in recs:
                for _, sql in instances[q["queryId"]]:
                    if sql in after or sql in failed:
                        continue
                    try:
                        after[sql] = measure(ctx, sql, runs)
                    except RuntimeError as e:
                        after[sql] = first_line(e)
        finally:
            psql(ctx, "DROP INDEX IF EXISTS pglens_bench_idx;")

        for q, rec in recs:
            mine = instances[q["queryId"]]
            labels = sorted({l for l, _ in mine})
            problems = [failed.get(sql) or after[sql] for _, sql in mine
                        if sql in failed or isinstance(after.get(sql), str)]
            if problems:
                rows.append({"query": labels, "ddl": None, "index": ddl_text,
                             "note": f"not measured: {problems[0]}"})
                continue
            val = rec["validation"]
            b_buf = sum(baseline[s][0] for _, s in mine)
            a_buf = sum(after[s][0] for _, s in mine)
            b_ms = sum(baseline[s][1] for _, s in mine)
            a_ms = sum(after[s][1] for _, s in mine)
            vr = val.get("valueRange")
            rank, rr = ranked.get((q["queryId"], table, columns), (None, {}))
            worst = vr["worstRelativeDrop"] if vr else None
            rows.append({
                "query": labels,
                "ddl": ddl_text,
                "rank": rank,
                "generic": val.get("relativeDelta"),
                "worst": worst,
                "worstFrequency": vr.get("worstValueFrequency") if vr else None,
                "rangeColumn": vr.get("column") if vr else None,
                "floor": floor_of(val.get("relativeDelta"), worst),
                "cautioned": worst is not None and worst < GATE,
                "scoreBasis": rr.get("scoreBasis"),
                "actionable": rr.get("actionable", True) if rr else None,
                "measuredBuffers": drop(b_buf, a_buf),
                "measuredTime": drop(b_ms, a_ms),
                "beforeBuffers": b_buf, "afterBuffers": a_buf,
                "beforeMs": round(b_ms, 2), "afterMs": round(a_ms, 2),
                "instances": len(mine),
            })

    rows.sort(key=lambda r: (r.get("rank") is None, r.get("rank") or 0))
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

    # M1 — floor vs generic on the SAME recs: every rec with a range, and those where they disagree.
    def paired(subset):
        if not subset:
            return {"n": 0}
        ge = [abs(r["generic"] - r["measuredTime"]) for r in subset]
        fe = [abs(r["floor"] - r["measuredTime"]) for r in subset]
        return {"n": len(subset), "maeGeneric": statistics.mean(ge), "maeFloor": statistics.mean(fe),
                "floorCloser": sum(f < g for f, g in zip(fe, ge)),
                "genericCloser": sum(g < f for f, g in zip(fe, ge))}

    with_range = [r for r in measured if r.get("floor") is not None
                  and r.get("generic") is not None and r["measuredTime"] is not None]
    disagree = [r for r in with_range if r["generic"] - r["floor"] >= DISAGREE]
    m1_all, m1 = paired(with_range), paired(disagree)

    # M2 — does the caution predict misses?
    def miss_rate(subset):
        return {"n": len(subset), "misses": sum(r not in wins for r in subset)}

    m2 = {"cautioned": miss_rate([r for r in measured if r.get("cautioned")]),
          "uncautioned": miss_rate([r for r in measured if not r.get("cautioned")])}

    # M5 — ranking quality: Spearman of each estimate with the measured drop.
    ranked_rows = [r for r in measured if r["measuredTime"] is not None and r.get("generic") is not None]
    truth = [r["measuredTime"] for r in ranked_rows]
    basis = [r["floor"] if r.get("floor") is not None else r["generic"] for r in ranked_rows]
    m5 = {"n": len(ranked_rows),
          "spearmanRankedBy": spearman(basis, truth),
          "spearmanGeneric": spearman([r["generic"] for r in ranked_rows], truth)}
    summary = {
        "measureSettings": SETTINGS,
        "statementsAnalyzed": len(report["queries"]), "plansCaptured": captured,
        "candidatesTried": tried, "validated": len(validated), "measured": len(measured),
        "measuredWins": len(wins),
        "precision": (len(wins) / len(measured)) if measured else None,
        "bufferWins": len(buffer_wins),
        "bufferPrecision": (len(buffer_wins) / len(measured)) if measured else None,
        "meanAbsErrorGeneric": g_err, "genericN": g_n,
        "meanAbsErrorFloor": f_err, "floorN": f_n,
        "m1AllWithRange": m1_all, "m1Disagreeing": m1, "m2": m2, "m5": m5,
        "notMeasured": [r for r in rows if not r.get("ddl")],
        "unbuildable": sum(1 for r in rows if r.get("unbuildable")),
    }
    pathlib.Path(out_json).write_text(json.dumps({"summary": summary, "rows": rows}, indent=2))

    md = ["| Query | PgLens rank | Index | Generic est. | Floor est. | Measured time | Measured buffers | Win (time)? |",
          "|---|---|---|---|---|---|---|---|"]
    for r in measured:
        md.append("| {} | {} | `{}` | {} | {}{} | {} ({:,} → {:,} ms) | {} ({:,} → {:,}) | {} |".format(
            ",".join(r["query"]), r["rank"] or "—", r["ddl"], pct(r["generic"]), pct(r["floor"]),
            " ⚠" if r.get("cautioned") else "",
            pct(r["measuredTime"]), r["beforeMs"], r["afterMs"],
            pct(r["measuredBuffers"]), int(r["beforeBuffers"]), int(r["afterBuffers"]),
            "yes" if r in wins else "**no**"))
    md.append("")
    md.append(f"- Measured with {SETTINGS} session settings")
    md.append(f"- Statements analyzed: {summary['statementsAnalyzed']} (plans captured: {captured})")
    md.append(f"- Candidates tried: {tried}")
    if measured:
        md.append(f"- Planner-validated recommendations measured: {len(measured)}; measured wins "
                  f"(warm time ↓ ≥ {int(GATE * 100)} %): {len(wins)} → precision "
                  f"{len(wins) / len(measured):.0%} (by buffers instead: {len(buffer_wins)} → "
                  f"{len(buffer_wins) / len(measured):.0%})")
    if summary["unbuildable"]:
        attempted = len(measured) + summary["unbuildable"]
        md.append(f"- Recommendations whose index could not be built at all: {summary['unbuildable']} "
                  f"(counted as misses: {len(wins)}/{attempted} → {len(wins) / attempted:.0%})")
    if g_n:
        md.append(f"- Mean |estimate − measured time drop|: generic {g_err:.1%} over {g_n} recs"
                  + (f"; value-range floor {f_err:.1%} over {f_n} recs" if f_n else ""))
    if m1_all["n"]:
        md.append(f"- M1 (same recs) over all {m1_all['n']} recs with a range: mean error generic "
                  f"{m1_all['maeGeneric']:.1%} vs floor {m1_all['maeFloor']:.1%}; floor closer on "
                  f"{m1_all['floorCloser']}, generic closer on {m1_all['genericCloser']}")
    if m1["n"]:
        md.append(f"- M1 (primary) over the {m1['n']} recs where the floor is ≥ {int(DISAGREE * 100)} points "
                  f"below generic: mean error generic {m1['maeGeneric']:.1%} vs floor {m1['maeFloor']:.1%}; "
                  f"floor closer on {m1['floorCloser']}, generic closer on {m1['genericCloser']}")
    else:
        md.append(f"- M1 (primary): no measured rec has a floor ≥ {int(DISAGREE * 100)} points below generic")
    md.append("- M2: misses among cautioned (⚠, worst case < gate) recs {}/{}; among uncautioned {}/{}".format(
        m2["cautioned"]["misses"], m2["cautioned"]["n"], m2["uncautioned"]["misses"], m2["uncautioned"]["n"]))

    def rho(x):
        return "n/a" if x is None else f"{x:.2f}"

    md.append(f"- M5: Spearman vs measured drop over {m5['n']} recs — ranked-by estimate "
              f"{rho(m5['spearmanRankedBy'])}, generic alone {rho(m5['spearmanGeneric'])}")
    for r in summary["notMeasured"]:
        md.append(f"- Not measured: {','.join(r['query']) if isinstance(r['query'], list) else r['query']} "
                  f"`{r.get('index')}` — {r['note']}")
    pathlib.Path(out_md).write_text("\n".join(md) + "\n")
    print("\n".join(md))


if __name__ == "__main__":
    main()
