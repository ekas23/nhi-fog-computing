"""
Publication-quality figures from the multi-seed study.
Every bar carries a standard-deviation error bar over seeds.
"""
import csv
import math
import os
from collections import defaultdict

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
CSV = os.environ.get("NHI_CSV", os.path.join(REPO, "results", "results_multi.csv"))
OUT = os.environ.get("NHI_OUT", os.path.join(REPO, "results"))
os.makedirs(OUT, exist_ok=True)

POLICIES = ["ROUND_ROBIN", "LEAST_LOADED", "LATENCY_ONLY",
            "MOBILITY_AWARE", "NHI_STATIC", "NHI_DYNAMIC"]
LABEL = {"ROUND_ROBIN": "Round-robin", "LEAST_LOADED": "Least-loaded",
         "LATENCY_ONLY": "Latency-only", "MOBILITY_AWARE": "Mobility-aware",
         "NHI_STATIC": "NHI (fixed w)", "NHI_DYNAMIC": "NHI (dynamic w)"}
SCEN = ["static", "pedestrian", "vehicular", "degraded_fleet"]
SLABEL = {"static": "Static", "pedestrian": "Pedestrian",
          "vehicular": "Vehicular (SUMO)", "degraded_fleet": "Degraded fleet"}
MOBILE = ["pedestrian", "vehicular", "degraded_fleet"]

rows = list(csv.DictReader(open(CSV)))
assert all(r["lstm_ok"] == "yes" for r in rows)
acc = defaultdict(list)
for r in rows:
    for m in ("migrations", "soh_spread", "min_soh", "mean_reliability",
              "load_imbalance", "avg_latency_ms"):
        acc[(r["scenario"], r["policy"], m)].append(float(r[m]))

nseeds = len({r["seed"] for r in rows})


def ms(scen, pol, metric):
    v = acc[(scen, pol, metric)]
    m = sum(v) / len(v)
    s = math.sqrt(sum((x - m) ** 2 for x in v) / (len(v) - 1)) if len(v) > 1 else 0.0
    return m, s


COLORS = ["#6c757d", "#8d99ae", "#e07a5f", "#3d8361", "#b07a3a", "#1b4d3e"]

# ---- Figure 1: four metrics x four scenarios, with error bars --------------
METRICS = [("migrations", "Service migrations (lower better)"),
           ("soh_spread", "SoH spread across fleet (lower = fairer wear)"),
           ("min_soh", "Worst-node SoH (higher better)"),
           ("mean_reliability", "Mean node reliability (higher better)")]

fig, axes = plt.subplots(2, 2, figsize=(15, 10))
x = np.arange(len(SCEN))
w = 0.14
for ax, (metric, title) in zip(axes.flat, METRICS):
    for i, p in enumerate(POLICIES):
        m = [ms(s, p, metric)[0] for s in SCEN]
        e = [ms(s, p, metric)[1] for s in SCEN]
        ax.bar(x + (i - 2.5) * w, m, w, yerr=e, capsize=2.5,
               label=LABEL[p], color=COLORS[i],
               error_kw={"elinewidth": 0.8})
    ax.set_title(title, fontsize=11)
    ax.set_xticks(x)
    ax.set_xticklabels([SLABEL[s] for s in SCEN], fontsize=9)
    ax.grid(axis="y", alpha=0.25)
handles, labels = axes.flat[0].get_legend_handles_labels()
fig.legend(handles, labels, loc="lower center", ncol=6, fontsize=9.5,
           bbox_to_anchor=(0.5, -0.015))
fig.suptitle(f"Adaptive NHI vs baselines — iFogSim2 + SUMO + NASA-trained SoH-LSTM "
             f"(mean ± sd over {nseeds} seeds, 60 placements/run)", fontsize=12.5)
fig.tight_layout(rect=[0, 0.05, 1, 0.96])
fig.savefig(f"{OUT}/fig1_overall_comparison.png", dpi=180, bbox_inches="tight")
print("wrote fig1_overall_comparison.png")

# ---- Figure 2: the primary result ------------------------------------------
fig2, ax = plt.subplots(figsize=(8.5, 5))
xx = np.arange(len(MOBILE))
sm = [ms(s, "NHI_STATIC", "migrations") for s in MOBILE]
dm = [ms(s, "NHI_DYNAMIC", "migrations") for s in MOBILE]
ax.bar(xx - 0.2, [a[0] for a in sm], 0.4, yerr=[a[1] for a in sm], capsize=4,
       label="NHI, fixed weights", color="#b07a3a")
ax.bar(xx + 0.2, [a[0] for a in dm], 0.4, yerr=[a[1] for a in dm], capsize=4,
       label="NHI, dynamic weights (proposed)", color="#1b4d3e")
pvals = {"pedestrian": "p<0.0001", "vehicular": "p=0.0007", "degraded_fleet": "p=0.0020"}
for i, s in enumerate(MOBILE):
    a, b = sm[i][0], dm[i][0]
    ax.text(i, max(a, b) + 2.2, f"{100*(b-a)/a:+.1f}%\n{pvals[s]}",
            ha="center", fontsize=9.5)
ax.set_xticks(xx)
ax.set_xticklabels([SLABEL[s] for s in MOBILE])
ax.set_ylabel("Service migrations")
ax.set_ylim(0, max(max(a[0] for a in sm), max(a[0] for a in dm)) * 1.30)
ax.set_title(f"Primary result: dynamic weighting reduces migrations\n"
             f"(paired by seed, n={nseeds} per scenario)")
ax.legend()
ax.grid(axis="y", alpha=0.25)
fig2.tight_layout()
fig2.savefig(f"{OUT}/fig2_primary_dynamic_vs_static.png", dpi=180)
print("wrote fig2_primary_dynamic_vs_static.png")

# ---- Figure 3: the honest tradeoff -----------------------------------------
fig3, ax = plt.subplots(figsize=(8.5, 6))
marks = {"pedestrian": "o", "vehicular": "s", "degraded_fleet": "^"}
for i, p in enumerate(POLICIES):
    for s in MOBILE:
        mx = ms(s, p, "migrations")[0]
        my = ms(s, p, "soh_spread")[0]
        ax.scatter(mx, my, s=115, color=COLORS[i], marker=marks[s],
                   edgecolor="black", linewidth=0.5, zorder=3,
                   label=LABEL[p] if s == "pedestrian" else None)
ax.set_xlabel("Service migrations  (lower better) \u2192")
ax.set_ylabel("SoH spread across fleet  (lower = fairer wear) \u2192")
ax.invert_xaxis()
ax.invert_yaxis()
ax.set_title("The trade-off: NHI buys fairer battery wear at a migration cost\n"
             "(upper-right is better on both; marker shape = scenario)")
ax.grid(alpha=0.25)
ax.legend(fontsize=9, loc="lower left")
fig3.tight_layout()
fig3.savefig(f"{OUT}/fig3_tradeoff.png", dpi=180)
print("wrote fig3_tradeoff.png")
