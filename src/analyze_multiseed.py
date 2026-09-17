"""
Statistical analysis of the multi-seed NHI study.

Analysis plan was fixed BEFORE the runs were executed:
  * primary comparison : NHI_DYNAMIC vs NHI_STATIC on service migrations
  * design             : paired by seed (identical topology, mobility and
                         workload across policies within a seed), so a paired
                         t-test is the right test
  * secondary          : SoH spread, worst-node SoH, load imbalance
  * report             : mean +/- sd over seeds, effect size, p-value, and the
                         result regardless of which way it comes out

No policy, metric or seed subset was chosen after seeing the numbers.
"""
import csv
import math
import os
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
CSV = os.environ.get("NHI_CSV", os.path.join(REPO, "results", "results_topo.csv"))
OUT = os.environ.get("NHI_OUT", os.path.join(REPO, "results"))
os.makedirs(OUT, exist_ok=True)

POLICIES = ["ROUND_ROBIN", "LEAST_LOADED", "LATENCY_ONLY",
            "MOBILITY_AWARE", "NHI_STATIC", "NHI_DYNAMIC"]
SCENARIOS = ["static", "pedestrian", "vehicular", "degraded_fleet"]
MOBILE_SCENARIOS = ["pedestrian", "vehicular", "degraded_fleet"]

rows = list(csv.DictReader(open(CSV)))
assert all(r["lstm_ok"] == "yes" for r in rows), "some runs did not use the LSTM"

# (scenario, policy, seed) -> row
D = {(r["scenario"], r["policy"], int(r["seed"])): r for r in rows}
SEEDS = sorted({int(r["seed"]) for r in rows})
print(f"{len(rows)} runs | seeds {SEEDS[0]}-{SEEDS[-1]} ({len(SEEDS)}) | "
      f"{rows[0]['decisions']} placement decisions per run\n")


def vals(scen, pol, metric):
    return [float(D[(scen, pol, s)][metric]) for s in SEEDS if (scen, pol, s) in D]


def mean(v):
    return sum(v) / len(v)


def sd(v):
    if len(v) < 2:
        return 0.0
    m = mean(v)
    return math.sqrt(sum((x - m) ** 2 for x in v) / (len(v) - 1))


def t_cdf(t, df):
    """Two-sided p-value for Student's t, via the incomplete beta function."""
    x = df / (df + t * t)

    def betacf(a, b, x, itmax=200, eps=3e-12):
        qab, qap, qam = a + b, a + 1.0, a - 1.0
        c, d = 1.0, 1.0 - qab * x / qap
        if abs(d) < 1e-30:
            d = 1e-30
        d = 1.0 / d
        h = d
        for m in range(1, itmax + 1):
            m2 = 2 * m
            aa = m * (b - m) * x / ((qam + m2) * (a + m2))
            d = 1.0 + aa * d
            if abs(d) < 1e-30:
                d = 1e-30
            c = 1.0 + aa / c
            if abs(c) < 1e-30:
                c = 1e-30
            d = 1.0 / d
            h *= d * c
            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2))
            d = 1.0 + aa * d
            if abs(d) < 1e-30:
                d = 1e-30
            c = 1.0 + aa / c
            if abs(c) < 1e-30:
                c = 1e-30
            d = 1.0 / d
            delta = d * c
            h *= delta
            if abs(delta - 1.0) < eps:
                break
        return h

    def betai(a, b, x):
        if x <= 0:
            return 0.0
        if x >= 1:
            return 1.0
        lbeta = (math.lgamma(a + b) - math.lgamma(a) - math.lgamma(b)
                 + a * math.log(x) + b * math.log(1 - x))
        if x < (a + 1) / (a + b + 2):
            return math.exp(lbeta) * betacf(a, b, x) / a
        return 1 - math.exp(lbeta) * betacf(b, a, 1 - x) / b

    return betai(0.5 * df, 0.5, x)


def paired_test(a, b):
    """Paired t-test on differences a-b. Returns (mean_diff, t, p, cohen_dz)."""
    d = [x - y for x, y in zip(a, b)]
    n = len(d)
    md, sdd = mean(d), sd(d)
    if sdd == 0:
        return md, float("inf") if md else 0.0, 0.0 if md else 1.0, float("inf") if md else 0.0
    t = md / (sdd / math.sqrt(n))
    return md, t, t_cdf(t, n - 1), md / sdd


# ---------------------------------------------------------------- main tables
METRICS = [("migrations", "Service migrations", "lower"),
           ("soh_spread", "SoH spread across fleet", "lower"),
           ("min_soh", "Worst-node SoH", "higher"),
           ("load_imbalance", "Load imbalance", "lower"),
           ("mean_reliability", "Mean reliability", "higher"),
           ("cloud_fallbacks", "Cloud fallbacks", "lower")]

lines = []
for metric, label, direction in METRICS:
    lines.append(f"\n### {label} (mean +/- sd over {len(SEEDS)} seeds, {direction} is better)\n")
    hdr = f"{'scenario':16}" + "".join(f"{p[:13]:>17}" for p in POLICIES)
    lines.append(hdr)
    for scen in SCENARIOS:
        cells = []
        for p in POLICIES:
            v = vals(scen, p, metric)
            cells.append(f"{mean(v):>10.3f}+/-{sd(v):<5.2f}")
        lines.append(f"{scen:16}" + "".join(cells))

print("\n".join(lines))

# ------------------------------------------------- PRIMARY pre-registered test
print("\n" + "=" * 78)
print("PRIMARY TEST (pre-registered): NHI_DYNAMIC vs NHI_STATIC, migrations")
print("Paired by seed. H0: no difference.")
print("=" * 78)
primary = []
for scen in MOBILE_SCENARIOS:
    a = vals(scen, "NHI_DYNAMIC", "migrations")
    b = vals(scen, "NHI_STATIC", "migrations")
    md, t, p, dz = paired_test(a, b)
    pct = 100 * md / mean(b) if mean(b) else float("nan")
    primary.append((scen, mean(b), mean(a), pct, t, p, dz))
    sig = "SIGNIFICANT" if p < 0.05 else "not significant"
    print(f"  {scen:15} static={mean(b):6.2f}  dynamic={mean(a):6.2f}  "
          f"diff={md:+6.2f} ({pct:+5.1f}%)  t={t:+6.2f}  p={p:.4f}  dz={dz:+.2f}  [{sig}]")

# pooled across the three mobile scenarios
pa = [x for scen in MOBILE_SCENARIOS for x in vals(scen, "NHI_DYNAMIC", "migrations")]
pb = [x for scen in MOBILE_SCENARIOS for x in vals(scen, "NHI_STATIC", "migrations")]
md, t, p, dz = paired_test(pa, pb)
print(f"\n  POOLED (n={len(pa)}): static={mean(pb):.2f} dynamic={mean(pa):.2f} "
      f"diff={md:+.2f} ({100*md/mean(pb):+.1f}%) t={t:+.2f} p={p:.5f} dz={dz:+.2f}  "
      f"[{'SIGNIFICANT' if p < 0.05 else 'not significant'}]")

# ---------------------------------------------- SECONDARY vs strongest baseline
print("\n" + "=" * 78)
print("SECONDARY: NHI_DYNAMIC vs MOBILITY_AWARE (strongest baseline)")
print("=" * 78)
for metric, label, direction in [("migrations", "migrations", "lower"),
                                  ("soh_spread", "SoH spread", "lower"),
                                  ("min_soh", "worst-node SoH", "higher")]:
    print(f"\n  {label}:")
    for scen in MOBILE_SCENARIOS:
        a = vals(scen, "NHI_DYNAMIC", metric)
        b = vals(scen, "MOBILITY_AWARE", metric)
        md, t, p, dz = paired_test(a, b)
        better = ((md < 0) if direction == "lower" else (md > 0))
        verdict = ("NHI better" if better else "baseline better") if p < 0.05 else "no difference"
        print(f"    {scen:15} NHI={mean(a):8.4f}  baseline={mean(b):8.4f}  "
              f"diff={md:+8.4f}  p={p:.4f}  -> {verdict}")

with open(f"{OUT}/statistical_summary.txt", "w") as f:
    f.write("\n".join(lines))
print(f"\nTables written to {OUT}/statistical_summary.txt")
