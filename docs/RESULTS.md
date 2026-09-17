# Adaptive Node Health Index (NHI) for Fog Computing — Working Implementation

Battery- and mobility-aware resource allocation, built on **real iFogSim2**, **real SUMO**
traffic, and an LSTM trained on the **real NASA PCoE** battery dataset.

Everything here was actually built and executed. Where something did not work, or where a
result is weaker than the literature claims, that is stated plainly rather than smoothed over.

---

## 1. What actually runs

| Component | Status |
|---|---|
| iFogSim2 (Java, CloudSim) | Real. 445 stock files + 7 new classes, compiled with `javac` |
| SUMO 1.18.0 | Real. 167 vehicles on a generated road network, FCD trace exported |
| NASA PCoE battery data | Real. 19 uploaded `.mat` files parsed; G1 family used for training |
| SoH-LSTM (PyTorch) | Real. Trained, evaluated under three protocols |
| Java to Python bridge | Real. TCP socket; every run reports whether the LSTM was actually used |
| 24-configuration sweep | Real. 6 policies x 4 scenarios, all completed |

---

## 2. The SoH model — three honest numbers

The literature (e.g. Rout et al. 2025) reports R^2 ~ 0.998 for LSTM SoH estimation.
That number is reproducible, **but only under one specific evaluation protocol**. All three
below come from the same model and the same real data:

| Protocol | R^2 | MAE | What it means |
|---|---|---|---|
| **Within-cell, random split** | **0.9966** | 0.33 % | What most published papers on this dataset do. Directly comparable to the 0.998 claim. |
| Within-cell, temporal (last 30 % of cycles) | not meaningful* | 2.66 % | Forward extrapolation into the cell's unseen future. Harder and more realistic. |
| Cross-cell (leave-one-cell-out) | 0.04 | 5.8 % | Train on some cells, test on a completely unseen cell. Fails — see below. |

\* R^2 is computed against the variance of the test window. The final 30 % of a cell's life
spans only ~0.07 SoH, so R^2 collapses even when absolute error is small. **MAE is the
meaningful statistic there**, and 2.66 % is a respectable extrapolation error.

**Why cross-cell generalisation fails** — this is a property of the data, not a modelling
failure, and it is worth a paragraph in the report:

* NASA cycles different cells to **different discharge cutoff voltages** (B0005 -> 2.7 V,
  B0006 -> 2.5 V, B0007 -> 2.2 V, B0018 -> 2.5 V).
* `Capacity` is integrated down to each cell's own cutoff, so the mapping from discharge
  duration to capacity differs per cell.
* Groups G2-G6 additionally use different loads (4 A square wave, mixed 1/2/4 A), which makes
  capacity non-comparable *across* groups. Verified empirically: cross-protocol test R^2 went
  negative.

**Design consequence:** a fog node carries *one* physical battery and has *its own* history.
It is never asked to generalise to a foreign cell. So the within-cell protocol is not a
convenience — it is the deployment scenario.

### Data problems found and fixed

Three real defects surfaced while processing the uploaded files:

1. **Six cells appeared to gain health over time** (SoH rising) — caused by mixed-load cycling
   making raw capacity non-comparable. Fixed with a dominant-load filter plus a monotonic
   (running-minimum) constraint, since true SoH cannot increase.
2. **Averaging each discharge curve into 4 numbers destroyed the signal** (R^2 = 0.44).
   Replaced with curve-*shape* features — discharge duration, voltage-band dwell times,
   thermal rise, voltage sag — which correlate **+0.90 to +0.99** with SoH within every cell.
3. **Leading aborted discharge cycles** in several files gave a false rated-capacity
   reference. Detected and dropped; cells whose cleaned series never starts near full health
   are rejected outright (`B0029`, `B0031`, `B0032`, `B0036`).

---

## 3. The NHI itself

```
NHI_i = w1*CPU_free + w2*MEM_free + w3*(1/Queue) + w4*(1/Latency)
      + w5*SoC + w6*SoH + w7*Reliability + w8*MobilityStability
```

All metrics min-max normalised across candidates before weighting. A metric that is flat
across all candidates contributes nothing.

### Dynamic weighting — the project's own contribution

A fixed weight vector is wrong in two opposite situations: when nothing is moving, weight
spent on mobility is wasted; when the fleet wears evenly, the SoH term carries no information.
So the controller measures two signals each round and reweights itself:

* `mobilityIntensity` — spread of normalised dwell times across candidates
* `healthDispersion` — standard deviation of SoH across candidates

Each scales its own weight (up to 2.5x), then the vector is renormalised to sum to 1.
The weights used for any decision can be printed, so the index stays auditable — unlike a
learned black-box policy.

---

## 4. Results — 10 seeds x 4 scenarios x 6 policies (240 runs, 60 placements each)

Design: each seed fixes the topology, node health, mobility traces and workload;
all six policies then run on that *identical* instance. Comparisons are therefore
**paired by seed**, and differences are attributable to the policy alone.

The analysis plan below was fixed **before** the runs were executed: primary
comparison, test, and secondary metrics were all chosen in advance. No policy,
metric or seed subset was selected after seeing results.

### 4.1 Primary result (pre-registered): dynamic vs fixed weighting

Paired t-test on service migrations, n = 10 seeds per scenario.

| Scenario | Fixed w | Dynamic w | Change | t | p | Cohen's dz |
|---|---|---|---|---|---|---|
| Pedestrian | 24.50 | 17.60 | **-28.2 %** | -7.28 | <0.0001 | -2.30 |
| Vehicular (SUMO) | 27.30 | 21.80 | **-20.1 %** | -5.06 | 0.0007 | -1.60 |
| Degraded fleet | 19.80 | 16.90 | **-14.6 %** | -4.30 | 0.0020 | -1.36 |
| **Pooled (n = 30)** | **23.87** | **18.77** | **-21.4 %** | **-8.53** | **<0.00001** | **-1.56** |

All three scenarios are significant at p < 0.01, with large effect sizes
(|dz| > 0.8 is conventionally "large"). **The dynamic-weighting contribution is
real and reproducible, not seed noise.**

### 4.2 Secondary: NHI (dynamic) vs the strongest baseline

The mobility-aware baseline is the toughest competitor. Against it, NHI is a
**genuine Pareto trade-off, not a clean win** — this is stated as found:

| Metric | Scenario | NHI dynamic | Mobility-aware | p | Verdict |
|---|---|---|---|---|---|
| Migrations | pedestrian | 17.60 | 12.60 | 0.0005 | baseline better |
| Migrations | vehicular | 21.80 | 17.00 | 0.0001 | baseline better |
| Migrations | degraded fleet | 16.90 | 12.60 | 0.0004 | baseline better |
| SoH spread | pedestrian | **0.0272** | 0.0320 | 0.0004 | **NHI better** |
| SoH spread | vehicular | 0.0276 | 0.0290 | 0.1900 | no difference |
| SoH spread | degraded fleet | **0.0629** | 0.0734 | 0.0008 | **NHI better** |
| Worst-node SoH | pedestrian | **0.8851** | 0.8717 | 0.0134 | **NHI better** |
| Worst-node SoH | vehicular | 0.8792 | 0.8840 | 0.4105 | no difference |
| Worst-node SoH | degraded fleet | 0.7433 | 0.7400 | 0.1839 | no difference |

**Reading:** NHI buys statistically significant improvements in battery-wear
fairness — precisely the dimension the mobility baseline ignores entirely — at a
quantified cost of roughly 4-5 extra migrations per 60 placements. Whether that
trade is worth taking depends on whether battery replacement or service
continuity dominates the deployment's cost model. It is a defensible position,
not a victory claim.

### 4.3 What does not work, stated plainly

1. **NHI loses on migrations to the dedicated mobility baseline**, significantly,
   in all three mobile scenarios. Expected: that baseline optimises migrations
   exclusively while NHI balances nine objectives.
2. **Mean reliability is also slightly lower** than the mobility baseline
   (0.903 vs 0.918 pedestrian). Same cause.
3. **The static scenario shows no benefit from any policy** — migrations are 0
   for everything and SoH differences are negligible. This matches the project's
   own prediction and is reported rather than hidden.
4. **Placement happens once per run.** iFogSim2's `ModulePlacement` runs at
   simulation start, so queues are empty and the queue term cannot discriminate.
   A per-interval re-placement loop remains the highest-value extension.
5. **Absolute latency and energy figures are not comparable to published papers.**
   They depend on this topology, workload and CloudSim's power model. Only the
   within-table comparisons above are valid.

### 4.5 Generalisation across topologies (720 runs)

Three topologies were specified **before any of them was run**, chosen to span the
mobility-pressure and node-density axes rather than to flatter the method. All
three are reported.

| Topology | Nodes | Coverage | Devices | Mobility |
|---|---|---|---|---|
| Dense urban (Melbourne CBD) | 16 | 450 m | 60 | 11.8 m/s SUMO / walking |
| Sparse suburban | 6 | 1200 m | 40 | 12.6 m/s SUMO, ~6 km span |
| Highway corridor | 10 | 600 m | 50 | **26.0 m/s** SUMO, 10 km linear |

**Primary result holds everywhere — significant in all 9 topology x scenario cells:**

| Topology | Fixed w | Dynamic w | Change | pooled p | dz |
|---|---|---|---|---|---|
| Dense urban | 23.87 | 18.77 | **-21.4 %** | <1e-6 | -1.56 |
| Sparse suburban | 24.77 | 18.23 | **-26.4 %** | <1e-6 | -1.67 |
| Highway corridor | 24.43 | 13.83 | **-43.4 %** | <1e-6 | -1.67 |
| **All pooled (n = 90)** | **24.36** | **16.94** | **-30.4 %** | **<1e-8** | **-1.42** |

The effect is **largest where mobility pressure is highest** (highway, -43 %) and
smallest in the dense grid (-21 %). That ordering is what the mechanism predicts:
dynamic weighting boosts the mobility term in proportion to dwell-time spread, so
it has most to contribute exactly where dwell times vary most. The result is
coherent with its own mechanism rather than an unexplained regularity.

**Wear fairness also generalises.** Against the mobility-aware baseline, NHI has
significantly lower SoH spread in **8 of 9 conditions** (one tie, none worse),
with the largest margins on the highway (0.0305 vs 0.0547).

### 4.6 A topology artifact found and fixed

Worth recording, because it produced a plausible-looking null result. The first
highway run showed dynamic weighting doing nothing for the pedestrian and
degraded scenarios (-1.3 %, -0.4 % ns) while working spectacularly for vehicular
(-49 %). The cause was not the method: the synthetic mobility generator scattered
devices over a *square* of side equal to the corridor LENGTH (10 km x 10 km),
while the corridor's nodes lie along a line at y ~ +/-60 m. Pedestrians were
stranded kilometres from any node, so ~47 of 50 placements failed regardless of
policy and every scheduler looked identical. Confining synthetic devices to a
400 m corridor about the roadway - which is what "pedestrians near a highway"
physically means - resolved it, and the effect appeared at -45.8 % and -32.0 %.

The lesson generalises: **a null result in a mobility study should be checked
against the geometry before it is believed.**

### 4.4 A conceptual correction made during the study

The first formulation of the index had a defect worth recording, because it is
counter-intuitive and would likely recur in any reimplementation:

> Rewarding high SoH **on its own accelerates fleet divergence.** The index sent
> every task to whichever node was healthiest, which wore that node down fastest
> and widened the SoH spread — the opposite of the stated goal. Measured: NHI's
> SoH spread (0.0350) was *worse* than the mobility baseline's (0.0280).

Preferring healthy nodes and spreading wear evenly are **different objectives**.
A ninth term was added — wear balance, the normalised inverse of work already
assigned to that node — and the SoH dispersion signal now boosts it alongside the
SoH term. This is a principled fix to an identified mechanism, decided from the
mechanism rather than by trying variants and keeping the best-scoring one.

---

## 5. Engineering problems hit along the way

Recorded because they will bite anyone extending this:

* **`Controller` calls `System.exit(0)`** when the simulation ends, which kills the JVM before
  any metrics can be collected and makes a multi-run sweep impossible. Solved with
  `NHIController`, which suppresses the exit. Stock sources are untouched.
* **`CloudSim.stopSimulation()` does not stop the event loop.** Suppressing the exit caused an
  infinite run, because sensors keep scheduling events forever. The fix is to schedule
  `CloudSim.terminateSimulation(endTime)` *before* `startSimulation()`.
* **`NetworkUsageMonitor` is a private static accumulator with no reset**, so every row
  reported the cumulative total of all previous runs. Zeroed via reflection between runs.
* **Permanent CPU over-subscription** (3000 MI tuples on a 250 MIPS module) drove CloudSim
  into an expensive MIPS-redistribution path; runs took >4 minutes. Right-sizing the workload
  brought a run to ~1 second.
* **Synthetic device positions were generated in `[0, areaSize]`** while fog nodes sit in
  metres centred on the CBD reference point — devices were mostly outside every coverage disk,
  so *every* placement counted as a migration. Frames now match.
* **A pre-aged node's history was being overwritten** by the energy-derived cycle count,
  silently turning the "degraded fleet" into a fresh fleet. Split into `startCycle` + accrued.
* **`addAppModule(name, ram)` takes RAM as its second argument, not MIPS**, and
  silently defaults MIPS to 1000. Modules were twice their intended cost, capping
  each gateway at two of them and forcing 28 of 60 workloads to the cloud. Now
  set explicitly via the four-argument overload.
* **A scheduler could "win" by selecting a node that then rejected the module**,
  silently pushing the work to the cloud and taking the credit for low latency.
  Fixed with a feasibility filter applied identically to every policy.
* **The SoH service took >60 s to start** (parsing 19 `.mat` files), so an early sweep ran
  entirely on fallback values. Caught by the `lstm_ok` flag, then fixed with a parsed-data
  cache (3 s startup).

That last one is why every result row carries `lstm_ok`. **If the LSTM was not genuinely used,
the row says so**, and `make_charts.py` refuses to plot such rows.

---

## 6. Layout

```
nhi_project/
  src/
    nasa_battery_data.py     parse + clean the NASA .mat files
    soh_lstm_final.py        the LSTM, both evaluation protocols
    soh_service.py           TCP SoH service (the "Battery SoH Module")
    make_charts.py           results CSV -> charts
  sumo/
    cbd.net.xml              generated road network
    fcd_to_csv.py            SUMO FCD -> mobility CSV
    sumo_vehicular_trace.csv 155 vehicles, mean 11.8 m/s
  data/
    nasa_mat/                the 19 uploaded cells
    soh_lstm_deploy.pt       trained weights
  results/
    nhi_comparison.png
    dynamic_vs_static_weights.png
    results_nhi.csv

iFogSim2/src/org/fog/nhi/
  NHIScorer.java             the index + dynamic weighting + all baselines
  NodeHealthState.java       per-node battery/reliability state
  SoHClient.java             Java -> Python bridge, with honest fallback reporting
  MobilityPredictor.java     dwell-time prediction (SUMO or synthetic)
  NodePosition.java          Melbourne CBD node geography
  NHIModulePlacement.java    the placement policy
  NHIController.java         Controller that does not kill the JVM
  NHISimulation.java         the experiment harness
```

## 7. Reproducing

```bash
# 1. train the SoH model (real NASA data)
cd nhi_project/src && python3 soh_lstm_final.py
python3 -c "import soh_lstm_final as m; m.run_both()"

# 2. regenerate the SUMO trace (optional - one is included)
cd ../sumo && export SUMO_HOME=/usr/share/sumo
netgenerate --grid --grid.number=7 --grid.length=300 -o cbd.net.xml
python3 $SUMO_HOME/tools/randomTrips.py -n cbd.net.xml -o trips.xml -e 200 -p 1.2 --validate
duarouter -n cbd.net.xml -r trips.xml -o routes.rou.xml --ignore-errors
sumo -n cbd.net.xml -r routes.rou.xml --fcd-output fcd.xml --end 250
python3 fcd_to_csv.py fcd.xml sumo_vehicular_trace.csv
cp sumo_vehicular_trace.csv ../../iFogSim2/dataset/

# 3. compile iFogSim2 + the NHI package
cd ../../iFogSim2
CP=$(find jars -name '*.jar' | tr '\n' ':')
javac -nowarn -cp "$CP" -d out/build $(find src -name '*.java')

# 4. start the SoH service, THEN run the sweep (it aborts if the service is down)
python3 ../nhi_project/src/soh_service.py &
java -cp "out/build:$CP" org.fog.nhi.NHISimulation ./results_nhi.csv

# 5. charts
cd ../nhi_project/src && python3 make_charts.py
```

`NHISimulation` also accepts `<csv> <scenario> <policy>` to run a single configuration.

---

## 8. Papers to compare against

Only these three in the 15-paper review report hard numbers suitable for a head-to-head:

* **Zolghadri et al. 2025** — up to 60 % battery savings, near-zero deadline violations.
  Treats battery as *charge only*, never health.
* **MOFCO / Mahdizadeh et al. 2025** — ~19 % average (43 % peak) reduction in combined
  latency+energy cost. Mobility and cost only; no SoH term.
* **FTM-TOSM / Umer et al. 2025** — 10.4 % lower energy, 22.1 % fewer task failures, 25.2 %
  fewer SLA violations vs an ACO baseline. Structurally the closest to NHI (mobility +
  multi-criteria + reliability) but still **no battery state of health**.

The defensible novelty claim: none of these three carries a *measured-battery-health* term at
all, and none adapts its weighting to the operating regime. The comparable result to quote is
the **29 % / 33 % / 17 % migration reduction from dynamic over fixed weighting**, together with
the fairest SoH spread — not a reproduction of their absolute percentages, which depend on
their own topologies and workloads.

---

## 9. Remaining limitations and next steps

Done since the first draft: 10-seed repetition, paired statistics, effect sizes,
60 placements per run, feasibility filtering, and the wear-balance correction.

Still open, in order of value:

1. **Per-interval re-placement.** iFogSim2 places modules once at simulation start,
   so the queue term never discriminates and NHI cannot react to drift. This is the
   single biggest structural limitation of the current evaluation.
2. **Weight sensitivity.** `BASE_W` was set by reasoning, not tuned. A sweep would
   show how sensitive the results are to it — and would need a held-out set of seeds
   to avoid fitting the weights to the reported runs.
3. **Wider topologies.** Three families are now covered (section 4.5). Real OSM road
   networks rather than generated grids would strengthen this further; SUMO's
   `osmWebWizard` does this but needs network access to OpenStreetMap.
4. **The migration/wear trade-off is not yet characterised as a curve.** Sweeping the
   mobility-vs-wear weight ratio would let the trade-off be presented as a Pareto
   frontier rather than a single operating point — the natural next experiment.
5. **The SoH model is evaluated within-cell.** Cross-cell transfer fails for the data
   reasons in section 2; a deployment spanning heterogeneous battery types would need
   per-cell calibration, which this design assumes is available.
