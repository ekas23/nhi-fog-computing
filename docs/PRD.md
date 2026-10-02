# PRD.md — Adaptive Node Health Index (NHI) for Fog Computing

## 1. What this is

A resource-allocation system for fog/edge computing that decides which fog
node should host a mobile user's processing workload, using a composite score
built from eight-to-nine live metrics instead of a single one (latency, or
load). The project's own contribution is **regime-adaptive weighting**: the
composite formula re-weights itself each placement round based on how much
mobility pressure and battery-health divergence are actually present in the
fleet at that moment.

This is a research/coursework project (VIT BCSE, Adaptive Node Health Index),
not a commercial product. The "target users" below are framed accordingly.

## 2. Problem statement

Existing fog-placement schedulers optimise one thing at a time:
- Latency-only schedulers overload the fastest node until it fails.
- Mobility-aware schedulers (e.g. SMITS-style) ignore battery condition
  entirely and will run a half-dead node into the ground.
- No baseline in the reviewed literature (15-paper review; see
  `Literature_Review_15_papers_2024plus.xlsx`) combines *measured* battery
  state-of-health with mobility awareness, and none adapts its own weighting
  to the operating regime.

## 3. Target users

- **Primary:** the project author and course evaluators (VIT review
  committee) — the deliverable is a defensible, statistically validated
  research result plus a presentable demo.
- **Secondary (framing for the "why it matters" story):** operators of
  battery-powered edge/fog deployments (smart-city sensor networks, vehicular
  edge nodes, drone/robot fleets) where node replacement is costly and
  service continuity for mobile clients matters.

## 4. Goals

1. Implement the Adaptive NHI scheduler on a **real** simulator (iFogSim2 /
   CloudSim), not a toy re-implementation — so results are defensible against
   "did you just simulate this in Python" questions.
2. Train the battery State-of-Health (SoH) sub-model on **real** NASA PCoE
   battery data, not synthetic curves.
3. Evaluate mobility using **real** SUMO traffic traces, not hand-waved
   movement.
4. Show, with pre-registered statistics (paired by seed, effect sizes,
   p-values), whether dynamic weighting actually beats fixed weighting —
   and report the answer honestly whichever way it comes out.
5. Generalise the claim across more than one topology, so "it only works on
   one map" isn't a valid objection.
6. Produce an interactive dashboard for the review presentation that lets the
   evaluator (or the presenter, live) change inputs — node health, weights,
   candidate metrics — and see the placement decision change in response.

## 5. Non-goals

- This is **not** a production scheduler; it will not be deployed to real
  fog hardware.
- The dashboard is **not** a re-implementation of the CloudSim engine in the
  browser. It (a) visualises the real experimental results already computed
  offline, and (b) offers a lightweight, transparent, in-browser
  re-computation of the NHI scoring formula itself for live "what if" demos.
  These two are visually and functionally distinguished so nobody mistakes a
  browser demo for a repeat of the real simulation.
- No user authentication, multi-tenancy, or persistence layer beyond
  browser-local state.
- No claim of state-of-the-art performance across all metrics — the honest
  result is a Pareto trade-off (better fleet battery fairness, worse raw
  migration count than a mobility-only baseline), and the PRD explicitly
  keeps that framing rather than overselling.

## 6. Core features

### 6.1 Simulation & evaluation pipeline (already built — see Architecture.md)
- NASA PCoE data loader + cleaning (protocol-consistency filter, monotonic
  SoH constraint, leading-outlier rejection).
- LSTM SoH estimator (PyTorch), exposed to Java over a local TCP service.
- iFogSim2 `org.fog.nhi` package: `NHIScorer` (six policies including two
  NHI variants), `NodeHealthState`, `MobilityPredictor` (SUMO + synthetic),
  `NHIModulePlacement`, `NHIController`, `TopologyConfig`.
- Three topology families (dense urban / sparse suburban / highway corridor)
  × four mobility scenarios (static / pedestrian / vehicular / degraded
  fleet) × six policies × 10 seeds = 720 runs.
- Statistical analysis: paired t-tests, effect sizes (Cohen's dz), pre
  registered primary/secondary comparisons.

### 6.2 Presentation dashboard (the new deliverable this doc set targets)
- **Results Explorer** — browse the real 720-run dataset: pick a topology and
  scenario, see all six policies compared on migrations, SoH spread,
  worst-node SoH, reliability, latency, load imbalance, with the actual
  computed means from `results_topo.csv`.
- **Live NHI Playground** — an editable table of candidate fog nodes (CPU
  free, memory free, queue length, latency, SoC, SoH, reliability, dwell
  time, tasks already assigned). Sliders for the nine NHI weights. The
  dashboard recomputes each node's score live, in the browser, using the
  *same formula* as `NHIScorer.java`, and highlights the winning node. The
  user can edit a node's values directly (e.g. drop one node's SoH to 0.3)
  and watch the decision change immediately.
- **Dynamic-weighting toggle** — flip between fixed and adaptive weighting in
  the Playground and see the weight vector itself change in response to the
  mobility/health dispersion sliders, mirroring the real `NHIScorer` logic.
- **Method & findings panel** — the headline statistic (−30.4% migrations,
  p<1e-8, pooled n=90), the honest trade-off against the mobility baseline,
  and the wear-balance correction story, presented for a live audience.

## 7. Success criteria

- A judge/evaluator can, without prompting, change at least one input on the
  dashboard and see a different, correct placement decision within one
  interaction.
- Every number shown in the Results Explorer traces back to an actual row in
  `results_topo.csv` — no fabricated or illustrative-only figures presented
  as real results.
- The dashboard loads and works with **no backend** (static, shareable link)
  since it will be shown live in a review room with unreliable Wi-Fi.

## 8. Constraints

- iFogSim2/Java/SUMO/PyTorch stack is not portable to a browser; the
  dashboard is necessarily a *presentation layer* over precomputed results
  plus a client-side reimplementation of the scoring formula only — this is
  stated explicitly in the UI, not hidden.
- No new experiments are to be run to make the dashboard "look better" —
  see Rules.md.
