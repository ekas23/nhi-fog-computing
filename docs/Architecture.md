# Architecture.md — Adaptive Node Health Index (NHI)

## 1. System overview

Two halves that must be described separately, because they run on entirely
different stacks and were built at different times:

```
 ┌─────────────────────────────────────────────────────────────┐
 │  A. RESEARCH PIPELINE (already built — produces the numbers) │
 │                                                                │
 │  NASA .mat files → nasa_battery_data.py → soh_lstm_final.py  │
 │        (clean/features)      (train LSTM, 2 protocols)       │
 │                                        │                      │
 │                                        ▼                      │
 │                              soh_lstm_deploy.pt               │
 │                                        │                      │
 │                                        ▼                      │
 │                          soh_service.py (TCP :5599)           │
 │                                        │                      │
 │   SUMO (netgenerate/duarouter/sumo) ───┤                      │
 │        → fcd_to_csv.py → sumo_*.csv    │                      │
 │                                        │                      │
 │                                        ▼                      │
 │              iFogSim2 (Java/CloudSim) + org.fog.nhi package   │
 │              NHISimulation → results_topo.csv, results_*.csv  │
 │                                        │                      │
 │                                        ▼                      │
 │           analyze_multiseed.py / make_*_figures.py            │
 │              → statistics + PNG charts + results/*.csv        │
 └─────────────────────────────────────────────────────────────┘
                                        │
                          (aggregated JSON embedded)
                                        ▼
 ┌─────────────────────────────────────────────────────────────┐
 │  B. PRESENTATION DASHBOARD (new — this doc set's deliverable) │
 │                                                                │
 │   Single static HTML/JS page (no backend, no build step)      │
 │     • Results Explorer  ← reads embedded aggregated JSON      │
 │     • Live NHI Playground ← re-implements NHIScorer.java's    │
 │         scoring math in JS for live, editable demos           │
 │     • Method & Findings panel ← static content                │
 └─────────────────────────────────────────────────────────────┘
```

**Why two stacks, not one:** the research pipeline needs Java/CloudSim (for a
credible simulator), Python/PyTorch (for the LSTM), and SUMO (for real
mobility) — none of which run in a browser. The dashboard is deliberately a
thin, honest presentation layer: it shows real precomputed results and offers
a transparent, client-side re-implementation of *just the scoring formula*
for interactivity. It does not pretend to be the simulator.

## 2. Folder structure (as it exists / should exist)

```
nhi_repo/                          # what goes on GitHub
├── README.md
├── LICENSE
├── .gitignore
├── requirements.txt
├── docs/
│   ├── PRD.md                     # this doc set
│   ├── Architecture.md
│   ├── Rules.md
│   ├── Phases.md
│   ├── Design.md
│   ├── Memory.md
│   └── RESULTS.md                 # full methodology + statistics (existing)
├── src/                           # Python: data + LSTM + analysis
│   ├── nasa_battery_data.py
│   ├── soh_lstm_final.py
│   ├── soh_service.py
│   ├── analyze_multiseed.py
│   ├── make_paper_figures.py
│   └── make_topo_figure.py
├── java/org/fog/nhi/              # the iFogSim2 package (9 classes)
│   ├── NHIScorer.java             # the index + policies + dynamic weighting
│   ├── NodeHealthState.java       # per-node battery/reliability state
│   ├── SoHClient.java             # Java → Python bridge
│   ├── MobilityPredictor.java     # dwell-time prediction (SUMO/synthetic)
│   ├── NodePosition.java          # geographic node placement
│   ├── NHIModulePlacement.java    # the placement policy itself
│   ├── NHIController.java         # Controller subclass (no System.exit)
│   ├── NHISimulation.java         # experiment harness / CLI entrypoint
│   └── TopologyConfig.java        # the 3 topology definitions
├── sumo/
│   ├── generate_traces.sh
│   ├── fcd_to_csv.py
│   └── sumo_*.csv                 # committed traces (dense/sparse/highway)
├── data/
│   ├── README.md                  # where to get NASA data (not committed)
│   └── soh_lstm_deploy.pt         # trained weights (committed)
├── results/
│   ├── results_topo.csv           # 720-run raw results
│   ├── results_multi.csv
│   ├── statistical_summary.txt
│   └── fig1..fig5_*.png
└── dashboard/                     # NEW — this doc set's target
    ├── index.html                 # single-file static dashboard
    └── data.js                    # embedded aggregated results (generated
                                    #   from results_topo.csv, not hand-typed)
```

## 3. Technical stack

| Layer | Technology | Why |
|---|---|---|
| Fog simulator | iFogSim2 (Java 21, CloudSim) | Only credible, citable fog simulator; avoids "just a Python toy" criticism |
| Mobility | SUMO 1.18 (Eclipse) | Real traffic microsimulation, not hand-waved movement |
| SoH model | PyTorch LSTM | Matches the architecture used in the cited literature |
| Java↔Python bridge | Raw TCP socket, line protocol | No extra dependency (no gRPC/REST framework needed for a single request type) |
| Data analysis | Python (csv, math — no pandas/scipy dependency for the stats themselves) | Keeps the paired t-test implementation auditable — no black-box library call for something a reviewer might ask you to explain |
| Charts (offline) | matplotlib | Publication-quality static PNGs for the report |
| **Dashboard** | **Single static HTML file, vanilla JS + inline `<script>` Chart.js (or hand-rolled SVG bars)** | Zero build step, zero backend, works from a USB stick or a GitHub Pages link with unreliable venue Wi-Fi |

No frontend framework (React/Vue) for the dashboard: the interactivity needed
(sliders, a table, recomputing a weighted sum, redrawing bars) does not
justify a build toolchain for a presentation asset, and "double-click and it
just works" is a real requirement for a live demo.

## 4. Data flow for the dashboard specifically

1. **Build time (author's machine, once):** a small Python script reads
   `results/results_topo.csv`, aggregates mean per
   (topology, scenario, policy, metric), and writes `dashboard/data.js` as a
   plain JS object literal. This script is committed; `data.js` is generated,
   not hand-maintained, so it can never drift from the real CSV.
2. **Runtime (browser, at presentation time):**
   - Results Explorer reads `data.js` directly — no computation, just
     selection and chart rendering.
   - Live Playground holds its own small, editable JS array of candidate
     nodes and a weights object; on any input change it recomputes
     `nhiScore(node, weights)` for every node (a direct port of
     `NHIScorer.scoreNHI`'s min-max-normalise-then-weighted-sum logic) and
     re-renders a ranked bar chart. This never touches `data.js`.

## 5. Key architectural decisions already made (carried over from the build)

- **`NHIController` subclasses `Controller`** rather than modifying it, to
  suppress `System.exit(0)` without touching vendored iFogSim2 source.
- **`NetworkUsageMonitor` reset via reflection** between runs (it's a private
  static accumulator with no public reset) — documented, not hidden.
- **SoH is queried over a socket, not embedded in Java**, so the exact same
  trained PyTorch model produces every SoH value used anywhere in the study.
- **Every result row carries `lstm_ok`** — a boolean audit flag that fails
  loudly if any value came from a fallback approximation instead of the real
  LSTM. The plotting scripts refuse to chart a run with `lstm_ok != yes`.
- **Feasibility filtering** applied identically to all six policies, so a
  policy cannot "win" by picking a node that then can't actually host the
  module.

## 6. What the dashboard is explicitly NOT

- Not a live connection to the Java simulator — there is no backend.
- Not a way to launch new iFogSim2 runs from the browser.
- Not a substitute for `results/results_topo.csv` as the source of truth —
  if the two ever disagree, the CSV is right and `data.js` needs
  regenerating.
