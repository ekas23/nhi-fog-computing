"""
Phase 8.1 — regenerates dashboard/data.js from results/results_topo.csv.

data.js is committed but should be treated as a build artifact: if the CSV
changes, re-run this script rather than hand-editing data.js.
"""
import csv
import json
import os
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
CSV_PATH = os.path.join(HERE, "..", "results", "results_topo.csv")
OUT_PATH = os.path.join(HERE, "data.js")

POLICIES = ["ROUND_ROBIN", "LEAST_LOADED", "LATENCY_ONLY",
            "MOBILITY_AWARE", "NHI_STATIC", "NHI_DYNAMIC"]
TOPOLOGIES = ["dense_urban", "sparse_suburban", "highway_corridor"]
SCENARIOS = ["static", "pedestrian", "vehicular", "degraded_fleet"]
METRICS = ["migrations", "soh_spread", "min_soh", "mean_reliability",
           "avg_latency_ms", "load_imbalance"]


def main():
    rows = list(csv.DictReader(open(CSV_PATH)))
    assert all(r["lstm_ok"] == "yes" for r in rows), \
        "refusing to export: some rows did not use the real LSTM"

    acc = defaultdict(list)
    for r in rows:
        for m in METRICS:
            acc[(r["topology"], r["scenario"], r["policy"], m)].append(float(r[m]))

    data = {}
    for t in TOPOLOGIES:
        data[t] = {}
        for s in SCENARIOS:
            data[t][s] = {}
            for p in POLICIES:
                data[t][s][p] = {}
                for m in METRICS:
                    v = acc.get((t, s, p, m), [0.0])
                    data[t][s][p][m] = round(sum(v) / len(v), 4)

    meta = {"topologies": TOPOLOGIES, "scenarios": SCENARIOS,
            "policies": POLICIES, "metrics": METRICS,
            "totalRuns": len(rows)}

    with open(OUT_PATH, "w") as f:
        f.write("// AUTO-GENERATED from results/results_topo.csv by build_data.py\n")
        f.write("// Do not hand-edit — re-run build_data.py instead.\n")
        f.write("const NHI_DATA = " + json.dumps(data, indent=1) + ";\n")
        f.write("const NHI_META = " + json.dumps(meta, indent=1) + ";\n")

    print(f"Wrote {OUT_PATH} from {len(rows)} runs "
          f"({len(TOPOLOGIES)} topologies x {len(SCENARIOS)} scenarios x "
          f"{len(POLICIES)} policies)")


if __name__ == "__main__":
    main()
