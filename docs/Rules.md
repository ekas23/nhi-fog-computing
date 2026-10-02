# Rules.md — Boundaries for any AI (or human) working on this project

These rules exist because several of them were learned the hard way during
the actual build. Where a rule has a story behind it, the story is included —
it's more persuasive than a bare instruction.

## 1. Non-negotiable research-integrity rules

1. **Never tune a parameter, pick a topology, or select a metric *after*
   seeing results, in order to make the reported outcome look better.**
   The three topologies, the primary comparison (NHI_DYNAMIC vs NHI_STATIC on
   migrations), and the statistical test were all fixed *before* the 720-run
   sweep executed. If you add an experiment, decide what "success" means
   before running it, and report the outcome whichever way it goes.
2. **If a result looks too good, distrust it before celebrating it.** The
   first SoH-LSTM run reported R²=0.998 — matching the literature exactly —
   because of a data leak (random cycle-splitting let neighbouring cycles of
   the same battery appear in both train and test). The honest number under a
   stricter protocol was R²=0.44 at first, which then led to finding the real
   fix (curve-shape features, not averaged curves). A suspiciously good number
   is a prompt to check the evaluation protocol, not a reason to stop looking.
3. **Every row of simulation output must carry an audit flag for anything
   that could silently degrade** (see `lstm_ok` in `results_topo.csv`). If a
   dependency (the SoH service, a data file, a network resource) is
   unreachable, the run must abort loudly or flag the row — never silently
   substitute a fallback and report it as equivalent.
4. **Tag every claim with its evidence.** "Significant" means a p-value was
   actually computed and is quoted; "a topology was tested" means a CSV row
   exists for it. Do not write summary language ("clearly better",
   "significant improvement") without the number next to it.
5. **Report what doesn't work.** The NHI does not beat the mobility-only
   baseline on raw migration count — it wins on battery-wear fairness
   instead. This is the actual finding and must not be reframed as an
   unqualified win.

## 2. Engineering rules for the simulation pipeline

1. **Never modify vendored iFogSim2 source files.** Extend via subclassing
   (`NHIController extends Controller`) or a side registry
   (`NodeHealthState`, keyed by device id) instead. This keeps `git diff`
   against upstream iFogSim2 meaningful and makes it trivial to re-apply the
   `org.fog.nhi` package to a fresh clone.
2. **A scheduler must never select an infeasible node.** Every policy is
   filtered to nodes with enough free CPU/RAM *before* scoring, applied
   identically across all six policies. (This was a real bug: NHI was
   "winning" partly by picking nodes that then rejected the module and fell
   back to cloud, which silently inflated its apparent latency advantage.)
3. **Coordinate frames must match.** Node positions and mobility traces are
   both expressed in metres relative to one shared reference point per
   topology. A mismatch here doesn't crash — it silently produces nonsense
   (e.g. every device permanently outside every node's coverage disk, making
   every placement look like a migration). Any new topology must set
   `NodePosition.setReference(...)` to the *same* point used to generate its
   SUMO trace, and this should be sanity-checked by printing the extents of
   both before trusting a result.
4. **CloudSim's `Controller.System.exit(0)` will kill a multi-run harness.**
   Use `NHIController`, and schedule `CloudSim.terminateSimulation(endTime)`
   *before* `startSimulation()` — `stopSimulation()` alone does not halt the
   event loop.
5. **Size application modules with the explicit 4-argument
   `addAppModule(name, ram, mips, size)`.** The 2-argument overload silently
   defaults MIPS to 1000, which previously made every module twice its
   intended cost and forced most placements to overflow to the cloud.

## 3. Libraries: use / avoid

**Use:**
- PyTorch for the LSTM (matches the cited literature's architecture).
- `scipy.io.loadmat` for NASA `.mat` files (never hand-parse the format).
- Plain-language paired-t-test implementation (already written, in
  `analyze_multiseed.py`) — kept dependency-free so every step is inspectable
  and explainable in a viva.
- matplotlib for offline publication charts.
- Vanilla JS (no framework) for the dashboard.

**Avoid:**
- **No new heavy ML frameworks** (TensorFlow, JAX) alongside PyTorch — one
  is enough and switching adds no value.
- **No frontend build toolchain** (webpack, Vite, npm packages) for the
  dashboard. It must open from a double-clicked HTML file with zero setup.
- **No pandas/scipy dependency for the *statistical test itself*** — the
  t-test and effect size are implemented directly so their logic is fully
  visible and defensible if a reviewer asks "how exactly is this computed."
  (Using pandas/numpy for general data wrangling elsewhere is fine.)
- **No cloud API calls, telemetry, or analytics** in the dashboard — it must
  work fully offline, since it will be demoed live.
- **No localStorage/sessionStorage reliance for anything the presentation
  depends on** — assume a fresh browser tab every time; state should be
  reconstructible from `data.js` alone.

## 4. Error handling

- **Data pipeline (Python/Java):** fail loudly. An unreachable SoH service,
  a missing SUMO trace for the requested topology, or a battery file that
  fails the quality gate should raise/abort with a clear message — never
  substitute silently. This is why `NHISimulation` probes the SoH service
  before running anything and aborts the whole batch if it's down.
- **Dashboard:** fail visibly but gracefully. If `data.js` is missing or
  malformed, show an on-page message ("results data not loaded") rather than
  a blank page or a silent console error only a developer would see — this
  will be shown live to non-technical evaluators.
- **Live Playground:** never let an invalid input crash the recompute. Clamp
  or reject bad slider/table values inline; the score panel should never show
  `NaN` on screen during a live demo.

## 5. What the AI should and shouldn't do on this project

**Should:**
- Ask before running new simulation sweeps that consume significant compute
  or session time.
- Point out when a proposed change would only affect the dashboard's
  *cosmetics* versus when it would change what a number *means*.
- Keep `docs/Memory.md` current after any change to the pipeline or the
  dashboard's data contract (see Memory.md itself for the format).
- Flag it explicitly if asked to make a change that would only improve
  results by making the comparison less fair (see Rule 1 in section 1).

**Shouldn't:**
- Shouldn't regenerate `results_topo.csv` or run new iFogSim2 sweeps just to
  make the dashboard's Results Explorer look more dramatic. The dashboard
  displays whatever the real CSV says; if the CSV needs to change, that's a
  simulation-pipeline decision made explicitly, not a dashboard-styling one.
- Shouldn't add a backend, database, or authentication to the dashboard
  without being asked — it is a static presentation artifact by design.
- Shouldn't silently change the NHI scoring formula in the dashboard's
  Playground without keeping it byte-for-byte consistent with
  `NHIScorer.java`'s logic (same normalisation, same weight boosting, same
  cap). If the Java formula changes, the JS port must be updated in the same
  commit — a dashboard that scores differently from the real system is worse
  than no dashboard.
