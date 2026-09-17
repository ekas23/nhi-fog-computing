"""
Real NASA PCoE battery-cycling data loader (replaces the earlier synthetic
generator). Reads B00xx.mat files (as provided by the user, sourced from the
official NASA Ames PCoE Battery Data Set) and builds LSTM training sequences.

Each .mat file has top-level struct B00xx.cycle, an array of cycle records
with fields: type ('charge'/'discharge'/'impedance'), ambient_temperature,
time, data. For 'discharge' cycles, data has Voltage_measured,
Current_measured, Temperature_measured, Time and Capacity (Ah remaining that
cycle -- our SoH label after dividing by each battery's rated/initial
capacity).

Known data quirk (present in the user's uploaded G4-G6 files, e.g. B0033,
B0034, B0036, B0038, B0039, B0041): the first 1-3 discharge cycles sometimes
report an anomalously low capacity due to an incomplete/aborted discharge in
NASA's raw logs. We detect and drop leading cycles whose capacity is more
than `outlier_frac` below the *median* of the first 10 cycles, rather than
trusting cycle-1 blindly.
"""
import glob
import os
import re

import numpy as np
import scipy.io as sio

MAT_DIR = os.environ.get("NHI_MAT_DIR", os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "data", "nasa_mat"))


def _extract_discharge_series(mat_path, outlier_frac=0.5):
    bid = re.search(r"(B\d{4})", os.path.basename(mat_path)).group(1)
    m = sio.loadmat(mat_path)
    top_key = [k for k in m.keys() if not k.startswith("__")][0]
    cyc = m[top_key][0, 0]["cycle"]

    raw = []
    for i in range(cyc.shape[1]):
        if str(cyc[0, i]["type"][0]) != "discharge":
            continue
        d = cyc[0, i]["data"][0, 0]
        if "Capacity" not in d.dtype.names:
            continue
        v = d["Voltage_measured"][0]
        c = d["Current_measured"][0]
        t = d["Temperature_measured"][0]
        cap = float(d["Capacity"][0, 0])
        tm = d["Time"][0] if "Time" in d.dtype.names else np.arange(len(v), dtype=float)
        if len(v) == 0:
            continue
        raw.append((v, c, t, cap, tm))

    if not raw:
        return None

    # --- protocol consistency filter -------------------------------------
    # Capacity is only comparable across cycles discharged under the SAME
    # load. G4-G6 batteries (B0033/34/36/38/39/40/41) cycle under mixed
    # loads (1A/2A/4A), so raw capacity jumps around and would otherwise look
    # like the battery is *healing*. Keep only dominant-load cycles.
    load_bins = np.array([round(float(np.mean(np.abs(c))), 1) for _, c, _, _, _ in raw])
    dominant = float(np.bincount((load_bins * 10).astype(int)).argmax()) / 10.0
    keep = np.abs(load_bins - dominant) <= 0.15
    raw = [r for r, k in zip(raw, keep) if k]
    if len(raw) < 15:
        return None

    voltages, currents, temps, resistances, capacities = [], [], [], [], []
    for v, c, t, cap, tm in raw:
        # --- discharge-curve SHAPE features -----------------------------
        # Averaging a discharge curve destroys the degradation signal. What
        # actually tracks capacity fade is how LONG the cell holds voltage:
        # as it ages, the curve collapses earlier. These are the standard
        # features used in the SoH literature.
        dur = float(tm[-1] - tm[0]) if len(tm) > 1 else 0.0

        # time spent in each voltage band (shrinks as the cell degrades)
        bands = [(4.2, 3.9), (3.9, 3.7), (3.7, 3.5), (3.5, 3.0)]
        band_times = []
        for hi, lo in bands:
            in_band = (v <= hi) & (v > lo)
            band_times.append(float(np.sum(in_band) / max(len(v), 1)) * dur)

        # voltage-curve area (proportional to delivered energy)
        area = float(np.trapezoid(v, tm)) if len(tm) > 1 else 0.0

        # thermal signature: degraded cells run hotter and peak sooner
        t_max = float(np.max(t))
        t_rise = float(np.max(t) - t[0])

        # internal-resistance proxy: instantaneous voltage sag at load onset
        v_drop = float(np.max(v[:5]) - np.min(v[:max(5, len(v) // 20)])) if len(v) > 5 else 0.0

        voltages.append([dur, area, t_max, t_rise, v_drop] + band_times)
        capacities.append(cap)

    capacities = np.array(capacities)
    if len(capacities) < 15:
        return None  # too short a life history for sequence training

    # drop leading outlier cycles (aborted/incomplete first discharges)
    ref = np.median(capacities[:10])
    start = 0
    while start < len(capacities) - 10 and capacities[start] < ref * outlier_frac:
        start += 1
    capacities = capacities[start:]

    # Rated capacity = best observed early capacity (robust to a low first read)
    rated = float(np.max(capacities[:5]))
    soh_raw = capacities / rated

    # Physical constraint: true SoH is non-increasing. Measurement noise makes
    # individual cycles wobble upward; take the running minimum so the label is
    # a monotonic degradation curve (standard practice in SoH/RUL literature).
    soh = np.minimum.accumulate(soh_raw)
    soh = np.clip(soh, 0.3, 1.0)

    # Quality gate: if the cleaned series never starts near full health, the
    # rated-capacity reference is unreliable (the file's early cycles are all
    # partial discharges) -> the SoH label would be meaningless. Reject it.
    if soh[0] < 0.95:
        return None

    feats = np.array(voltages[start:], dtype=np.float32)

    # --- per-cell relative normalisation --------------------------------
    # Absolute discharge duration/area depend on the test protocol (1A vs 4A,
    # 4C vs 43C), so a model trained on one group cannot transfer to another.
    # Expressing every feature as a RATIO to that cell's own healthy baseline
    # (median of its first 5 usable cycles) makes the features protocol-
    # invariant: "this cell now delivers 80% of the runtime it did when new"
    # means the same thing regardless of load or temperature.
    baseline = np.median(feats[:5], axis=0)
    baseline[np.abs(baseline) < 1e-6] = 1.0
    feats = feats / baseline

    return bid, feats, soh.astype(np.float32)


def load_all_batteries(mat_dir=MAT_DIR):
    files = sorted(glob.glob(os.path.join(mat_dir, "*.mat")))
    batteries = {}
    skipped = []
    for f in files:
        result = _extract_discharge_series(f)
        if result is None:
            skipped.append(os.path.basename(f))
            continue
        bid, feats, soh = result
        batteries[bid] = (feats, soh)
    return batteries, skipped


def build_training_set(seq_len=15, mat_dir=MAT_DIR):
    batteries, skipped = load_all_batteries(mat_dir)
    X, y, groups = [], [], []
    for bid, (feats, soh) in batteries.items():
        for t in range(seq_len, len(soh)):
            X.append(feats[t - seq_len:t])
            y.append(soh[t])
            groups.append(bid)
    return (np.array(X, dtype=np.float32), np.array(y, dtype=np.float32),
            np.array(groups), batteries, skipped)


if __name__ == "__main__":
    X, y, groups, batteries, skipped = build_training_set()
    print(f"Loaded {len(batteries)} batteries: {sorted(batteries.keys())}")
    if skipped:
        print(f"Skipped (too short / unusable): {skipped}")
    print("Sequence dataset:", X.shape, y.shape, "SoH range:", y.min(), y.max())
    for bid, (feats, soh) in sorted(batteries.items()):
        print(f"  {bid}: {len(soh):3d} usable cycles | SoH {soh[0]:.3f} -> {soh[-1]:.3f}")
