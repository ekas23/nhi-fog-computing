"""
Oxford Battery Degradation Dataset loader.

Source: The Oxford Battery Degradation Dataset (University of Oxford, ORA
repository, https://ora.ox.ac.uk/objects/uuid:03ba4b01-cfed-46d3-9b1a-7d4a7bdf6fac).
The raw dataset is MATLAB files of full diagnostic-discharge curves; here we
consume an already feature-engineered summary CSV (mirrored from a public
GitHub pipeline, columns verified against the dataset's own documented
diagnostic-discharge protocol before use -- see verify_oxford() below).

This is a SECOND, INDEPENDENT dataset from NASA PCoE: different lab,
different cells, different protocol. It is kept in its own feature space
(not forced into NASA's 9-feature schema) because the two datasets expose
different raw signals; see docs/RESULTS.md for the cross-dataset experiment
that bridges them via a shared feature subset.

Label: SoH is derived from `q_final` (the cumulative discharge charge
counter for that diagnostic cycle), normalised to each cell's own early-life
magnitude -- same per-cell relative-baseline principle used for NASA cells
that span multiple protocols.
"""
import os

import numpy as np

CSV_PATH = os.environ.get(
    "OXFORD_CSV",
    os.path.join(os.path.dirname(os.path.abspath(__file__)),
                 "..", "data", "oxford", "Oxford_Battery_Summary_Features_Test=C1dc.csv"))

FEATURE_COLUMNS = [
    "t_cyc", "T_peak", "T_mean", "V_mean", "V_std", "V_min",
    "t_to_4.0_V", "t_to_3.8_V", "t_to_3.5_V",
    "Initial_V_slope", "3.8-3.5_V_slope", "dq_dV_peak_height", "cycle_energy_wh",
]


def _read_csv(path):
    import csv
    with open(path) as f:
        return list(csv.DictReader(f))


def load_all_cells(csv_path=CSV_PATH, min_cycles=15):
    rows = _read_csv(csv_path)
    by_cell = {}
    for r in rows:
        by_cell.setdefault(r["Cell"], []).append(r)

    cells = {}
    for cell_id, rs in by_cell.items():
        rs.sort(key=lambda r: int(r["Cycle"]))
        if len(rs) < min_cycles:
            continue

        feats = np.array([[float(r[c]) for c in FEATURE_COLUMNS] for r in rs],
                          dtype=np.float32)
        q = np.array([abs(float(r["q_final"])) for r in rs], dtype=np.float32)

        # per-cell relative SoH: fraction of this cell's own early-life
        # discharge capacity, same principle as the NASA loader
        rated = float(np.max(q[:3]))
        soh_raw = q / rated
        soh = np.minimum.accumulate(soh_raw)   # enforce physical monotonicity
        soh = np.clip(soh, 0.3, 1.0)

        if soh[0] < 0.95:
            continue  # same quality gate as NASA: unreliable rated-capacity reference

        cells[f"OX_{cell_id}"] = (feats, soh)

    return cells


def verify_oxford(csv_path=CSV_PATH):
    """Sanity checks against the dataset's documented characteristics before
    trusting the mirror. Oxford's own documentation: 8 cells (Cell1-Cell8),
    cycled to ~80% SoH or ~900-1000 full-equivalent cycles, diagnostic
    discharge test 'C1dc' used for degradation tracking."""
    rows = _read_csv(csv_path)
    cells = sorted({r["Cell"] for r in rows}, key=int)
    tests = {r["Test"] for r in rows}
    msgs = []
    msgs.append(f"cells found: {cells} (expect 8: 1-8) -> "
                f"{'OK' if len(cells) == 8 else 'MISMATCH'}")
    msgs.append(f"test types: {tests} (expect only C1dc, the diagnostic "
                f"discharge) -> {'OK' if tests == {'C1dc'} else 'MISMATCH'}")
    for cell_id in cells:
        crows = sorted([r for r in rows if r["Cell"] == cell_id],
                        key=lambda r: int(r["Cycle"]))
        q0 = abs(float(crows[0]["q_final"]))
        qn = abs(float(crows[-1]["q_final"]))
        fade_pct = 100 * (1 - qn / q0)
        monotonic_ish = qn < q0  # capacity should have gone down, not up
        msgs.append(f"  Cell {cell_id}: {len(crows)} cycles, "
                    f"capacity fade {fade_pct:.1f}% -> "
                    f"{'OK' if monotonic_ish and 5 < fade_pct < 60 else 'CHECK'}")
    return msgs


if __name__ == "__main__":
    print("=== Verification against documented Oxford dataset characteristics ===")
    for m in verify_oxford():
        print(m)
    print("\n=== Loaded cells ===")
    cells = load_all_cells()
    for cid, (f, s) in sorted(cells.items()):
        print(f"  {cid}: {len(s)} cycles | SoH {s[0]:.3f} -> {s[-1]:.3f} | "
              f"{f.shape[1]} features")
