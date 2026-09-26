"""Freeze the Phase 3 eval cases from real scan reports (pre-registered selection rule, docs/llm-eval.md).

Per source, in rank order: the first N *actionable* top recommendations (what `--plain` explains),
plus the demo's one not-planner-validated (GIN) rec. The plan tree is dropped (FactsBuilder doesn't
read it); everything else is copied verbatim from the report.
"""
import json, sys

# usage: make_eval_cases.py <dir with demo.json, tpch.json, job.json> <output dir>
S, OUT = sys.argv[1], sys.argv[2]
SPIKE = {("tpch", 1811352665468614583, "lineitem", ("l_shipdate",)),
         ("tpch", 8184203962545297210, "lineitem", ("l_partkey",)),
         ("job", -7644606318543067753, "movie_info", ("movie_id",)),
         ("job", -7644606318543067753, "movie_info", ("info",))}

def ddl(c):
    using = "" if c["accessMethod"] == "BTREE" else "USING " + c["accessMethod"].lower() + " "
    return f"CREATE INDEX idx_{c['table']}_{'_'.join(c['columns'])} ON {c['table']} {using}({', '.join(c['columns'])});"

cases = []
for source, take in [("demo", 7), ("tpch", 8), ("job", 8)]:
    r = json.load(open(f"{S}/{source}.json"))
    qs = {q["queryId"]: q for q in r["queries"]}
    wl = {w["table"]: w for w in r["tableWriteLoad"]}
    top = r["topRecommendations"]
    picked = [t for t in top if t["actionable"]][:take]
    rows = []
    for t in picked:
        c = t["recommendation"]["candidate"]
        d = ddl(c)
        covered = sum(1 for o in top if o["coveredBySubsumer"] and o.get("subsumedByDdl") == d)
        rows.append((t["queryId"], t["recommendation"], t, wl.get(c["table"]), covered))
    if source == "demo":
        for q in r["queries"]:
            for rec in q["recommendations"]:
                if rec["validation"]["status"] == "NOT_PLANNER_VALIDATED":
                    rows.append((q["queryId"], rec, None, wl.get(rec["candidate"]["table"]), 0))
    for i, (qid, rec, ranked, w, covered) in enumerate(rows):
        c = rec["candidate"]
        q = dict(qs[qid]); q["plan"] = None
        spike = (source, qid, c["table"], tuple(c["columns"])) in SPIKE
        cases.append(dict(source=source, rank=i, spikeSeen=spike, sourceReport=dict(
            schemaVersion=r["schemaVersion"], generatedAt=r["generatedAt"]),
            input=dict(query=q, recommendation=rec, ranked=ranked, writeLoad=w, otherQueriesCovered=covered)))

# Split (pre-registered): spike-seen and the one GIN case -> dev; the rest alternate held-out/dev in
# rank order, filling each source to 4 + 4.
for source in ("demo", "tpch", "job"):
    group = [x for x in cases if x["source"] == source]
    dev = [x for x in group if x["spikeSeen"] or x["input"]["recommendation"]["validation"]["status"] == "NOT_PLANNER_VALIDATED"]
    rest = [x for x in group if x not in dev]
    turn = "dev" if not dev else "heldout"
    for x in rest:
        if turn == "dev" and len(dev) < 4:
            dev.append(x); turn = "heldout"
        else:
            turn = "dev"
    for x in group:
        x["split"] = "dev" if x in dev else "heldout"

for n, x in enumerate(cases, 1):
    c = x["input"]["recommendation"]["candidate"]
    x["id"] = f"{n:02d}-{x['source']}-{c['table']}-{'-'.join(c['columns'])}"
    body = {k: x[k] for k in ("id", "split", "source", "spikeSeen", "sourceReport", "input")}
    json.dump(body, open(f"{OUT}/{x['id']}.json", "w"), indent=1)
    print(x["id"], x["split"], "spike" if x["spikeSeen"] else "")
