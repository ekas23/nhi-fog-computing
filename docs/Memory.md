# Memory.md — Project state, for LLM session handoff

**Read this file first, before re-reading any code.** It exists so a new chat
session doesn't burn tokens re-discovering what's already done, and doesn't
silently redo (or contradict) a decision that was already made deliberately.

Update this file at the end of any session that changes what's built, what's
decided, or what's next. Keep entries short — this is a status board, not a
diary. When it grows past ~150 lines, compress the "Completed" log into a
one-paragraph summary and keep only the last few entries in detail.

---

## Current phase

**Phase 8.1 — Dashboard data export.** Not yet started. Phases 1–7 (the
entire research pipeline: data, LSTM, iFogSim2 integration, SUMO, statistics,
cross-topology generalisation) are complete and are NOT to be redone or
re-run casually — see Rules.md §1 and the note below.

## What exists right now (do not rebuild these)

- `results/results_topo.csv` — 720 real simulation runs (3 topologies × 4
  scenarios × 6 policies × 10 seeds). **This is the single source of truth
  for every number shown anywhere.**
- `results/statistical_summary.txt`, `results/fig1..5_*.png` — derived from
  the CSV above via `src/analyze_multiseed.py` / `src/make_*_figures.py`.
- `data/soh_lstm_deploy.pt` — trained SoH-LSTM weights (mean R² = 0.9966,
  within-cell protocol). Loadable without re-training.
- `java/org/fog/nhi/*.java` — the complete, compiling NHI package for
  iFogSim2. `NHIScorer.java` is the canonical implementation of the scoring
  formula; any dashboard reimplementation must match it exactly (see Rules.md
  §5).
- `docs/RESULTS.md` — the full methodology writeup, including every bug found
  and fixed during the build. Read this before explaining any result.

## Headline numbers (quote these exactly; don't re-derive from memory)

- Primary result, pooled across all 3 topologies: **−30.4% migrations**,
  dynamic vs fixed weighting, paired by seed, **n=90, p<1e-8, dz=−1.42**.
- Per-topology: dense urban −21.4%, sparse suburban −26.4%, highway corridor
  −43.4% (effect scales with mobility pressure — this ordering is itself
  evidence the effect is mechanistic, not noise).
- Against the mobility-only baseline: NHI loses on raw migrations
  (significantly, ~4–5 extra per 60 placements) but wins on SoH spread
  (fleet battery-wear fairness) in 8 of 9 topology×scenario conditions.
- SoH-LSTM: R²=0.9966 (within-cell random split, matches literature's
  0.998 claim); R²≈0.04 (cross-cell, genuinely fails — documented data
  reason, not a bug).

## Decisions already made — don't relitigate without a new reason

1. Dashboard has **no backend**. Static HTML/JS only. (PRD §7, Architecture §3)
2. Dashboard shows real precomputed results (`data.js`, generated from the
   CSV) in the Results Explorer, and a **separate**, clearly-labelled
   client-side re-implementation of the scoring formula in the Live
   Playground. These are never to be blended or mistaken for each other.
3. No frontend framework — vanilla JS, single HTML file. (Architecture §3,
   Rules.md §3)
4. Wear-balance term (9th metric) was added mid-study to fix a real
   mechanism flaw (rewarding SoH alone accelerated fleet divergence). Any
   dashboard formula must include this 9th term — it is not optional legacy.
5. Three topologies (dense_urban, sparse_suburban, highway_corridor) were
   fixed before running and all three are always reported together — never
   show one topology's result without the option to see the others in the
   same view.

## Open questions / things to decide before continuing

- [x] Chart approach: hand-rolled inline SVG bars, zero dependency. Done.
- [x] Default node set (6 nodes, CPU 20-95, SoH 0.42-0.97, one pre-aged
      "degraded" node) set and verified to produce a sensible winner. Done.
- [ ] Confirm whether the dashboard needs to be published (shareable link) or
      just opened locally at presentation time — affects whether it should
      go through the Artifact publish flow or stay a plain file handoff.
- [ ] Dashboard's Results Explorer still reads `results_topo.csv` (the
      fog-simulation sweep) and has NOT been extended to show the SoH-model
      extension results (section below). If the dashboard needs to show SoH
      numbers too, that's unbuilt — currently SoH results only live in
      docs/RESULTS.md section 10 and the terminal output of
      `soh_lstm_multidataset.py`.

## SoH dataset extension (done after Phase 8)

Not part of the original 8-phase plan — added later when the SoH study
needed to be bigger for a conference submission. Summary (full detail in
`docs/RESULTS.md` section 10):

- Extended from NASA G1 only (4 cells) to **all 15 usable NASA cells** +
  **8 Oxford Battery Degradation Dataset cells** (23 total, 14 with stable
  enough signal to report).
- Found and fixed two more real bugs: (1) B0040 has a one-cycle capacity
  cliff at the very end of its record (aborted-test artifact) — fixed with
  a trailing-outlier guard in `nasa_battery_data.py`, mirroring the existing
  leading-outlier guard; (2) naively averaging R2 across cells of very
  different test-set sizes is statistically unstable (one 4-sample cell
  produced R2=-25 and wrecked the mean) — fixed in
  `soh_lstm_multidataset.py` with a minimum-n threshold plus a pooled-
  residual R2, the standard meta-analysis fix.
- Headline numbers now: NASA within-cell R2=0.9921 (6 stable cells, up from
  4), Oxford R2=0.943 pooled (independent dataset replication), cross-
  dataset pooled model R2=0.875. Direct one-way transfer (train one dataset,
  test the other, zero-shot) is only moderate: R2=0.32-0.45 — reported
  honestly, not hidden.
- New files: `src/oxford_battery_data.py` (Oxford loader + verification
  against the dataset's own documented characteristics),
  `src/soh_lstm_multidataset.py` (the three experiments: all-NASA,
  Oxford-alone, cross-dataset transfer). `src/nasa_battery_data.py` was
  edited (trailing-outlier guard added), not replaced.
- Data added: `data/oxford/Oxford_Battery_Summary_Features_Test=C1dc.csv`
  (244KB, committed directly — small enough, unlike the NASA .mat files).

## Log

*(newest first; compress older entries once this section gets long)*

- **[Phase 8 build]** Dashboard built: `dashboard/index.html`,
  `dashboard/data.js` (720 real runs aggregated), `dashboard/build_data.py`
  (regenerates data.js from results_topo.csv — re-run this, never hand-edit
  data.js). Verified with Playwright: no console errors, JS scoring logic
  matches NHIScorer.java (weights sum to 1.0, healthy node wins as expected,
  identical-nodes edge case doesn't produce NaN), live editing correctly
  updates both the winner and the dynamic weight vector.
  **Remaining:** Phase 8.6 rehearsal pass (keyboard-only nav check, PDF
  fallback) not done. Google Fonts CDN blocked in the dev sandbox (403) —
  irrelevant outside it, but if the presentation venue also blocks
  fonts.googleapis.com, the CSS `--font-*` vars fall back to system
  serif/sans/mono automatically, so nothing breaks, just looks slightly
  plainer.
- **[seed entry]** Docs created: PRD.md, Architecture.md, Rules.md,
  Phases.md, Design.md, Memory.md.
