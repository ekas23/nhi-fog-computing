# MASTER PROMPT — Adaptive Node Health Index (NHI) for Fog Computing

Paste this entire file to Claude Code as your first message, in the root of
this unzipped folder. Everything referenced below (code, real data, trained
weights, prior results) is already in this folder — nothing needs to be
downloaded for the project to run and be verified.

---

## 1. What you are building

A fog-computing resource scheduler that picks which fog node should run a
mobile user's task, scored on nine live metrics (CPU, memory, queue,
latency, battery charge, **battery health**, reliability, mobility
stability, wear balance) instead of one. The project's own research
contribution is **regime-adaptive weighting**: the scheduler watches how
much mobility pressure and battery-health divergence are present right now
and re-weights its own formula accordingly, instead of using fixed weights
forever.

This has already been built, run, and statistically validated once (by a
prior session — that's me, a different instance of Claude, in an earlier
conversation). You are **reproducing it**, not inventing it. Every design
decision below is final; do not second-guess or "improve" the scoring
formula, the topology choices, or the evaluation protocol without being
asked — see section 7 (rules) for exactly why.

**Read `docs/PRD.md`, `docs/Architecture.md`, `docs/Rules.md`,
`docs/Phases.md`, `docs/Design.md`, and `docs/RESULTS.md` in that order
before writing or running anything.** They contain the full detail this
prompt only summarizes. `docs/Memory.md` tells you exactly what phase is
done and what, if anything, is still open.

## 2. What's already in this folder (don't redo these)

```
handoff/
├── docs/                    <- READ THESE FIRST, in the order listed above
│   ├── PRD.md                   what & why
│   ├── Architecture.md          system design, folder structure, tech stack
│   ├── Rules.md                 hard constraints — READ BEFORE CODING
│   ├── Phases.md                the 8-phase build history + what's left
│   ├── Design.md                dashboard visual system
│   ├── Memory.md                current status, read this to avoid redoing work
│   └── RESULTS.md               full methodology + every number + every bug found
├── java/org/fog/nhi/        <- the complete, compiling iFogSim2 extension (9 classes)
├── src/                      <- Python: data loaders, LSTM, SoH service, analysis
├── sumo/                     <- SUMO traffic traces (already generated, committed)
├── data/
│   ├── nasa_mat/                 19 real NASA PCoE battery files (.mat)
│   ├── oxford/                   real Oxford Battery Degradation Dataset (.csv)
│   └── soh_lstm_deploy.pt        already-trained LSTM weights
├── results/
│   ├── results_topo.csv          720 real fog-simulation runs (the main result)
│   ├── results_multi.csv         an earlier, single-topology 240-run sweep
│   ├── statistical_summary.txt   the pre-computed statistics
│   └── fig1..5_*.png             publication-quality charts
├── dashboard/
│   ├── index.html                the interactive presentation dashboard
│   └── index_standalone.html     same thing, single-file (data inlined)
└── README.md                 <- repo landing page / quickstart
```

**Do not re-run the 720-run simulation sweep or re-train the LSTM unless
explicitly asked to.** The results in `results/` and the weights in
`data/` are the real, final, already-validated outputs. If your goal is
just "get this running and verify it," you can validate against these
files without regenerating them (see section 6, verification).

If you ARE asked to regenerate everything from scratch, this prompt gives
you everything needed to do that too — see section 5.

## 3. Full technical stack you'll need installed

| Tool | Version used | Install |
|---|---|---|
| JDK | 21 | `apt-get install openjdk-21-jdk-headless` |
| SUMO | 1.18 | `apt-get install sumo sumo-tools`, then `export SUMO_HOME=/usr/share/sumo` |
| Python | 3.12 | should already be present |
| PyTorch | 2.x, CPU-only is fine | `pip install torch --break-system-packages` |
| scipy, numpy, matplotlib | any recent | `pip install scipy numpy matplotlib --break-system-packages` |
| iFogSim2 | HEAD | `git clone https://github.com/Cloudslab/iFogSim2.git` — **not vendored here, see below** |

**Disk space warning (hit this exact problem before):** installing torch
can fail with `OSError: [Errno 28] No space left on device` even when `df
-h` shows space free elsewhere — it's usually the pip cache or a `/tmp`
directory filling up. Run `pip cache purge` and clear `/tmp` before
retrying if this happens.

## 4. iFogSim2 setup (it is NOT in this folder — separate MIT project)

```bash
git clone https://github.com/Cloudslab/iFogSim2.git
cp -r java/org/fog/nhi iFogSim2/src/org/fog/
cp sumo/sumo_*.csv iFogSim2/dataset/

cd iFogSim2
CP=$(find jars -name '*.jar' | tr '\n' ':')
javac -nowarn -cp "$CP" -d out/build $(find src -name '*.java')
```

iFogSim2 vendors its own dependency jars — **no Maven needed**, plain
`javac` compiles all ~445 stock files plus the 9 new `org.fog.nhi` classes
cleanly. If this doesn't compile cleanly, something is wrong with the clone
or the JDK version — do not patch around compile errors by modifying stock
iFogSim2 files (see Rules.md §2.1).

## 5. Full reproduction, step by step (only if asked to regenerate from scratch)

```bash
# ---- 1. SoH model: train on the real data already in data/ ----
cd src
python3 nasa_battery_data.py          # sanity-check the NASA loader: should report
                                       # 15 usable cells, 4 rejected (B0029/31/32/36)
python3 oxford_battery_data.py        # sanity-check Oxford loader against its own
                                       # documented characteristics (8 cells, C1dc test)
python3 soh_lstm_final.py             # trains + evaluates the headline G1 model
python3 -c "import soh_lstm_final as m; m.run_both()"   # within-cell random-split protocol
python3 soh_lstm_multidataset.py      # the full 23-cell, 2-dataset extension (3 experiments)

# ---- 2. SUMO traces: already committed in sumo/, but to regenerate ----
cd ../sumo && bash generate_traces.sh   # needs SUMO_HOME set, ~2 min

# ---- 3. iFogSim2 + NHI package: see section 4 above ----

# ---- 4. the 720-run sweep ----
# IMPORTANT: start the SoH service FIRST. The simulation harness probes it
# on startup and ABORTS THE ENTIRE BATCH if it's not reachable on
# 127.0.0.1:5599 — this is deliberate (see Rules.md §1.3), not a bug to work
# around by skipping the probe.
python3 src/soh_service.py &
sleep 3   # let it load the cached NASA features (should take ~3s, not 60s+
          # — if it takes much longer, data/nasa_cache.pkl is stale/missing,
          # see Rules.md and docs/RESULTS.md section 5)

cd iFogSim2
CP=$(find jars -name '*.jar' | tr '\n' ':')
# Syntax: <output_csv> <scenario|-> <policy|-> <seed_from> <seed_to> <topology>
# Run per-topology in separate batches (one java process per topology) --
# each topology x all scenarios x all policies x 10 seeds takes ~1-4 minutes.
java -cp "out/build:$CP" org.fog.nhi.NHISimulation ../results/results_topo.csv - - 1 10 dense_urban
java -cp "out/build:$CP" org.fog.nhi.NHISimulation ../results/results_topo.csv - - 1 10 sparse_suburban
java -cp "out/build:$CP" org.fog.nhi.NHISimulation ../results/results_topo.csv - - 1 10 highway_corridor
# This APPENDS to the csv across calls -- don't delete it between topology runs.

# ---- 5. statistics + charts ----
cd ../src
python3 analyze_multiseed.py          # paired t-tests, effect sizes -- compare
                                       # output against results/statistical_summary.txt
python3 make_paper_figures.py
python3 make_topo_figure.py

# ---- 6. dashboard data refresh (only if results_topo.csv changed) ----
cd ../dashboard
python3 build_data.py                 # regenerates data.js from the real CSV
                                       # NEVER hand-edit data.js directly
```

## 6. How to verify you got it right (self-check against known-good numbers)

These are the real, already-achieved results. If your reproduction differs
by more than noise (seeds are fixed, so it should match closely, not
exactly to the last decimal across different hardware/library versions):

**Fog simulation (the main result), from `results/results_topo.csv`:**
- Pooled across all 3 topologies, 90 paired seed-comparisons: dynamic
  weighting vs fixed weighting on migrations = **−30.4%, p<1e-8**.
- Per topology: dense_urban −21.4%, sparse_suburban −26.4%,
  highway_corridor −43.4% (effect size grows with mobility pressure — this
  ordering is itself a correctness signal, not just the headline number).
- Every row's `lstm_ok` column must read `yes`. If any row says anything
  else, the SoH service was unreachable or fell back during that run and
  the row is not a valid result — do not report it, re-run it.

**SoH model, from `docs/RESULTS.md` §10:**
- NASA within-cell (6 statistically stable cells, n_test≥10 each): R²=0.9921±0.0077
- Oxford within-cell (pooled): R²=0.9430
- Cross-dataset pooled model: R²=0.8749
- Cross-dataset one-way transfer (NASA→Oxford / Oxford→NASA): R²≈0.45 / 0.32
  — this is supposed to be moderate, not strong. If you get R²>0.9 on
  one-way zero-shot transfer, something is wrong (likely data leakage
  between train/test — see the exact leakage bug described in Rules.md §1.2
  and avoid repeating it).

If your numbers are wildly different (wrong sign, order-of-magnitude off,
or suspiciously perfect), stop and diagnose before reporting — section 7.1
has the research-integrity rule this project holds itself to, and it
applies to you too.

## 7. Rules you must follow (condensed from docs/Rules.md — read the full file)

1. **Never tune a parameter, pick a topology, or select a metric after
   seeing results to make them look better.** If you add any new
   experiment, decide what counts as success before running it.
2. **If a result looks too good, distrust it before reporting it.** The
   first version of the SoH model scored R²=0.998 because of a data leak
   (randomly shuffled cycles let near-identical neighbors appear in both
   train and test). A suspiciously perfect number is a prompt to check the
   evaluation protocol, not a reason to celebrate.
3. **Never modify vendored iFogSim2 source.** Extend via subclassing
   (`NHIController extends Controller`) or a side registry
   (`NodeHealthState`), exactly as the existing code already does.
4. **A scheduler must never be allowed to pick an infeasible node.**
   Feasibility filtering is applied identically across all six policies —
   do not let one policy "win" by picking a node that then can't actually
   host the module.
5. **Report what doesn't work.** NHI does not beat the mobility-only
   baseline on raw migration count — it wins on battery-wear fairness
   instead. This is the actual, intended finding. Do not reframe it as an
   unqualified win in any summary you write.
6. **No frontend build toolchain for the dashboard.** Vanilla JS, single
   HTML file, zero dependencies beyond an optional Google Fonts CDN link
   that degrades gracefully if blocked.
7. **Every simulation row needs an audit flag for anything that could
   silently degrade** (the `lstm_ok` pattern). If you add a new experiment
   with an external dependency, make it fail loudly or flag the row —
   never substitute silently.

## 8. If you're asked to extend this further

Known good next steps, in priority order (from `docs/Phases.md` §9 and
`docs/RESULTS.md` §10.4, not yet done):
1. Per-interval re-placement in iFogSim2 (currently placement happens once
   at simulation start — the single biggest structural limitation).
2. A weight-sensitivity sweep over `BASE_W` in `NHIScorer.java`, using
   held-out seeds so the sweep doesn't just fit the reported numbers.
3. A genuine Pareto-frontier characterization of the migration/wear-fairness
   trade-off (sweep the mobility-vs-wear weight ratio, plot the curve).
4. More topologies beyond the current three (dense urban / sparse suburban
   / highway corridor) — real OpenStreetMap-derived SUMO networks via
   `osmWebWizard` instead of generated grids, if you have network access.

Do not start any of these without being asked — they are listed here so you
know what's legitimately open versus what's already decided and closed.
