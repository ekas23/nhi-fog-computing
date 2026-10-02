# Phases.md — Adaptive Node Health Index (NHI)

Phases 1–7 are **already complete** (this is what was actually built, kept
here as the historical record and so a new LLM session understands what not
to redo). Phase 8 is the new work this doc set targets.

---

## Phase 1 — Data foundation ✅ DONE
- Parse NASA PCoE `.mat` files (19 batteries uploaded).
- Discover and fix: mixed-load capacity non-comparability, leading
  aborted-discharge cycles, non-monotonic SoH from measurement noise.
- Output: cleaned per-cycle feature series for the G1 family
  (B0005/06/07/18).

## Phase 2 — SoH model ✅ DONE
- Curve-shape feature engineering (discharge duration, voltage-band dwell
  times, thermal rise, voltage sag) — replaced naive curve-averaging after it
  was found to destroy the degradation signal (R²=0.44 → 0.9966).
- LSTM trained and evaluated under two protocols (within-cell random split
  for the headline number; within-cell temporal split for a stricter,
  extrapolation-style check). Cross-cell transfer explicitly tested and found
  to fail for a documented, real data reason (differing cutoff voltages).
- Output: `soh_lstm_deploy.pt`.

## Phase 3 — Java ↔ Python bridge ✅ DONE
- `soh_service.py`: TCP service wrapping the trained LSTM, with a parsed-data
  cache (startup dropped from >60s to ~3s after this was found to be causing
  silent fallback-only sweeps).
- `SoHClient.java`: socket client with an honest degrade-and-flag fallback,
  plus a `probe()` used to abort a sweep up front if the service is down.

## Phase 4 — iFogSim2 integration ✅ DONE
- Compiled stock iFogSim2 (445 files) unmodified.
- Built `org.fog.nhi`: `NodeHealthState`, `NHIScorer` (six policies),
  `MobilityPredictor`, `NodePosition`, `NHIModulePlacement`,
  `NHIController`, `NHISimulation`.
- Fixed real engine issues along the way: `System.exit(0)` killing multi-run
  sweeps, `NetworkUsageMonitor`'s unreset static accumulator, CPU
  over-subscription making runs take minutes, `addAppModule`'s RAM/MIPS
  argument-order trap, missing feasibility filtering.

## Phase 5 — Mobility via SUMO ✅ DONE
- Real road network generation (`netgenerate`), routing (`duarouter`),
  microsimulation (`sumo`), FCD export, conversion to the mobility CSV format
  the Java side consumes.
- Confirmed the SUMO trace's spatial extent matches the fog nodes' coordinate
  frame before trusting any result (this had silently broken once already —
  see Rules.md §2.3).

## Phase 6 — Statistically valid evaluation ✅ DONE
- Multi-seed (10 seeds) repetition with a pre-registered analysis plan.
- Paired t-tests, Cohen's dz, primary + secondary comparisons defined before
  running.
- A genuine conceptual flaw found and fixed mid-study: rewarding high SoH
  alone was found to *accelerate* fleet SoH divergence (the opposite of the
  goal), because it concentrates work on the healthiest node. Fixed by adding
  a ninth "wear balance" term rather than by re-tuning existing weights.

## Phase 7 — Generalisation across topologies ✅ DONE
- Three topologies specified *before* running: dense urban (Melbourne CBD),
  sparse suburban, highway corridor — chosen to span mobility-pressure and
  node-density extremes.
- 720 total runs (3 topologies × 4 scenarios × 6 policies × 10 seeds).
- One artifact found and fixed: synthetic pedestrian movement on the highway
  topology stranded devices kilometres from any node (square-area generator
  used on a linear topology), producing a misleading null result until
  corridor-confined movement was implemented.
- **Result:** dynamic weighting beats fixed weighting in all 9 topology ×
  scenario cells, pooled −30.4% migrations, p<1e-8, n=90 paired comparisons.

---

## Phase 8 — Presentation dashboard 🔲 NEW (what this doc set is for)

Broken into sub-phases so an LLM coding session can complete one at a time
without losing context (see Memory.md for how to hand off between them).

### Phase 8.1 — Data export
- Write a small Python script (`dashboard/build_data.py`) that reads
  `results/results_topo.csv` and emits `dashboard/data.js`: a plain JS object
  keyed by `[topology][scenario][policy][metric] → mean`.
- **Done when:** `data.js` loads in a browser console with no errors and
  spot-checked values match the CSV exactly (e.g. dense_urban / pedestrian /
  NHI_DYNAMIC / migrations should read 17.6).

### Phase 8.2 — Static shell + Results Explorer
- Single `index.html`, dark hero section per Design.md, dropdowns for
  topology and scenario, a bar chart comparing all six policies on a
  selectable metric.
- **Done when:** every dropdown combination renders a chart whose bars match
  `data.js` values exactly, including the static scenario (all-zero
  migrations) and highway/vehicular (the most dramatic swing).

### Phase 8.3 — Live NHI Playground
- Editable table of candidate nodes (8–10 nodes, prefilled with plausible
  defaults matching the ranges actually used in the simulation: CPU 0–120,
  SoH 0.3–1.0, etc.).
- Nine weight sliders, a fixed/dynamic toggle.
- JS port of `NHIScorer.scoreNHI`: min-max normalise each metric across the
  currently-visible candidates, weighted sum, highlight the winner.
- **Done when:** manually constructing the example in `NHIScorer.java`'s own
  Javadoc (or a hand-computed 3-node example) produces the same winner and
  the same relative ranking as the JS implementation.

### Phase 8.4 — Dynamic weighting demo
- Two more sliders/inputs representing "mobility spread" and "health spread"
  (the two dispersion signals `NHIScorer` computes internally); when the
  fixed/dynamic toggle is on, show the *actual resulting weight vector* after
  boosting and renormalisation, next to the base vector, so the audience sees
  the adaptation happen, not just its effect on the winner.
- **Done when:** setting both spreads to 0 reproduces the base weights
  exactly (no boost), and increasing either spread visibly shifts weight
  toward its term, capped at the documented 2.5× ceiling.

### Phase 8.5 — Method & Findings panel
- Static content: the headline statistic, the honest trade-off table, the
  wear-balance-correction story, links/citations to the three comparison
  papers.
- **Done when:** every number on this panel has a matching row in
  `results/results_topo.csv` or `results/statistical_summary.txt` — no number
  is typed from memory.

### Phase 8.6 — Polish & rehearsal pass
- Keyboard/mouse-only navigation check (no dependency on hover-only affordances
  for a projector demo).
- Print/PDF fallback view in case live interactivity isn't possible on venue
  hardware.
- **Done when:** the presenter can do a full run-through — Results Explorer →
  Playground → Findings — in under 4 minutes without touching dev tools.

---

## Phase ordering rule

Do not start 8.3 before 8.2 is done, and do not start 8.4 before 8.3 is done —
each depends on scaffolding (the page shell, then the node table and scoring
function) built in the previous one. 8.5 and 8.6 can happen in either order
relative to each other once 8.4 is done.
