"""
Extended SoH-LSTM evaluation: 15 NASA cells + 8 Oxford cells = 23 total,
versus the original 4-cell NASA G1 family.

Three experiments, run in order of increasing ambition and decreasing
certainty of success -- each is reported regardless of outcome:

  A. Within-cell protocol on ALL 15 usable NASA cells (vs. the original 4).
     Same evaluation protocol as before (train first 70%/random split of a
     cell's own cycles, test the rest), just over many more cells -> a much
     larger n for the headline claim, still the fair, literature-standard
     comparison.

  B. Within-cell protocol on the 8 Oxford cells, in Oxford's OWN feature
     space (13 features; not forced into NASA's 9, since the datasets
     expose different raw signals). This is a genuinely independent
     replication of the method on a second public dataset.

  C. Cross-dataset transfer: train a model on NASA cells only (using only
     the features both datasets share: discharge duration and mean/peak
     temperature), test on Oxford cells, and vice versa. This is the
     ambitious, publication-relevant question -- "does a model trained on
     one lab's cells say anything about a different lab's cells" -- and is
     reported honestly whichever way it goes. It was NOT guaranteed to
     work before running it.
"""
import sys

import numpy as np
import torch
import torch.nn as nn

from nasa_battery_data import load_all_batteries
from oxford_battery_data import load_all_cells as load_oxford

SEQ_LEN = 10


class SoHLSTM(nn.Module):
    def __init__(self, n_features, hidden=32, layers=1):
        super().__init__()
        self.lstm = nn.LSTM(n_features, hidden, num_layers=layers, batch_first=True)
        self.head = nn.Sequential(nn.Linear(hidden, 16), nn.ReLU(), nn.Linear(16, 1))

    def forward(self, x):
        out, _ = self.lstm(x)
        return self.head(out[:, -1, :]).squeeze(-1)


def r2(pred, target):
    ss_tot = ((target - target.mean()) ** 2).sum()
    if ss_tot < 1e-9:
        return None  # zero-variance target: R2 undefined, not zero
    return float(1 - ((target - pred) ** 2).sum() / ss_tot)


def seqs(feats, soh, lo, hi, seq_len=SEQ_LEN):
    X, y = [], []
    for t in range(max(lo, seq_len), hi):
        X.append(feats[t - seq_len:t])
        y.append(soh[t])
    return np.array(X, np.float32), np.array(y, np.float32)


def train_one_cell_random(cell_id, feats, soh, n_features, epochs=180, test_frac=0.30, seed=0):
    X, y = seqs(feats, soh, 0, len(soh))
    if len(X) < 8:
        return {"cell": cell_id, "status": "skipped", "reason": f"only {len(X)} usable sequences"}
    if float(np.std(y)) < 0.01:
        return {"cell": cell_id, "status": "skipped",
                "reason": f"near-zero SoH variance (std={np.std(y):.4f}) -- no degradation observed in record"}

    rng = np.random.default_rng(seed)
    idx = rng.permutation(len(X))
    ntest = max(1, int(len(X) * test_frac))
    te, tr = idx[:ntest], idx[ntest:]
    if len(tr) < 4:
        return {"cell": cell_id, "status": "skipped", "reason": "too few training sequences after split"}

    mu, sd = X[tr].mean((0, 1)), X[tr].std((0, 1)) + 1e-8
    Xtr = torch.tensor((X[tr] - mu) / sd); ytr = torch.tensor(y[tr])
    Xte = torch.tensor((X[te] - mu) / sd); yte = torch.tensor(y[te])

    torch.manual_seed(0)
    model = SoHLSTM(n_features)
    opt = torch.optim.Adam(model.parameters(), lr=3e-3)
    loss_fn = nn.MSELoss()
    bs = min(32, len(Xtr))
    for _ in range(epochs):
        model.train()
        perm = torch.randperm(len(Xtr))
        for i in range(0, len(Xtr), bs):
            b = perm[i:i + bs]
            opt.zero_grad()
            loss_fn(model(Xtr[b]), ytr[b]).backward()
            opt.step()
    model.eval()
    with torch.no_grad():
        p = model(Xte)
    r2v = r2(p, yte)
    return {"cell": cell_id, "status": "ok", "n_test": len(Xte),
            "r2": r2v, "mae": float((p - yte).abs().mean()),
            "rmse": float(((p - yte) ** 2).mean().sqrt()),
            "pred": p.numpy(), "target": yte.numpy()}


def experiment_A_all_nasa():
    print("=" * 78)
    print("EXPERIMENT A: within-cell protocol, ALL usable NASA cells (vs. original 4)")
    print("=" * 78)
    bats, skipped = load_all_batteries()
    print(f"Usable NASA cells: {len(bats)} {sorted(bats.keys())}")
    print(f"Rejected by quality gate: {skipped}\n")

    results = []
    for cid, (feats, soh) in sorted(bats.items()):
        r = train_one_cell_random(cid, feats, soh, n_features=feats.shape[1])
        results.append(r)
        if r["status"] == "ok":
            print(f"  {cid:10} n_test={r['n_test']:3d}  R2={r['r2']:.4f}  "
                  f"MAE={r['mae']:.4f}  RMSE={r['rmse']:.4f}")
        else:
            print(f"  {cid:10} SKIPPED -- {r['reason']}")

    ok = [r for r in results if r["status"] == "ok" and r["r2"] is not None]
    print(f"\n{len(ok)}/{len(results)} cells yielded a valid result.")
    summarize_honestly(ok, "A (all NASA)")
    return results


MIN_N_TEST = 10  # below this, a single miss can swing R2 by double digits


def summarize_honestly(ok_results, label):
    """
    Averaging per-cell R2 across cells with very different test-set sizes is
    a real statistical mistake: R2 on n=4 points is enormously unstable (one
    bad prediction can send it to -25), so a simple mean is dominated by
    noise from the smallest cells, not by genuine model quality. Caught
    exactly this during experiment A: B0041 (n_test=4) produced R2=-25.3 and
    dragged a mean of +0.98 down to -2.3.

    Two statistically defensible alternatives, both reported:
      1. Mean R2 restricted to cells with enough test points to be stable
         (n_test >= MIN_N_TEST), with the excluded small-n cells listed.
      2. Pooled R2: concatenate every qualifying cell's test predictions and
         targets into one array and compute a single R2 over all of them.
         This is the standard meta-analysis fix for combining results across
         groups of unequal size.
    """
    if not ok_results:
        print("No valid per-cell results to summarize.")
        return

    stable = [r for r in ok_results if r["n_test"] >= MIN_N_TEST]
    unstable = [r for r in ok_results if r["n_test"] < MIN_N_TEST]

    if unstable:
        print(f"\nExcluded from the headline mean (n_test < {MIN_N_TEST}, R2 too "
              f"noisy to trust): " +
              ", ".join(f"{r['cell']}(n={r['n_test']},R2={r['r2']:.2f})" for r in unstable))

    if stable:
        r2s = [r["r2"] for r in stable]
        maes = [r["mae"] for r in stable]
        print(f"\n[{label}] Mean-of-R2 (n_test>={MIN_N_TEST} cells only, n={len(stable)}): "
              f"R2={np.mean(r2s):.4f} +/- {np.std(r2s):.4f}, MAE={np.mean(maes):.4f}")

        all_pred = np.concatenate([r["pred"] for r in ok_results if "pred" in r])
        all_tgt = np.concatenate([r["target"] for r in ok_results if "target" in r])
        pooled_r2 = r2(torch.tensor(all_pred), torch.tensor(all_tgt))
        pooled_mae = float(np.mean(np.abs(all_pred - all_tgt)))
        print(f"[{label}] Pooled R2 (every cell's test points concatenated, "
              f"n={len(all_pred)}): R2={pooled_r2:.4f}, MAE={pooled_mae:.4f}")
    else:
        print(f"\n[{label}] No cell had n_test >= {MIN_N_TEST}; cannot report a stable mean.")

    return stable


def experiment_B_oxford():
    print("\n" + "=" * 78)
    print("EXPERIMENT B: within-cell protocol, Oxford cells (independent 2nd dataset)")
    print("=" * 78)
    cells = load_oxford()
    results = []
    for cid, (feats, soh) in sorted(cells.items()):
        r = train_one_cell_random(cid, feats, soh, n_features=feats.shape[1])
        results.append(r)
        if r["status"] == "ok":
            print(f"  {cid:10} n_test={r['n_test']:3d}  R2={r['r2']:.4f}  "
                  f"MAE={r['mae']:.4f}  RMSE={r['rmse']:.4f}")
        else:
            print(f"  {cid:10} SKIPPED -- {r['reason']}")

    ok = [r for r in results if r["status"] == "ok" and r["r2"] is not None]
    summarize_honestly(ok, "B (Oxford)")
    return results


# Features present, in comparable form, in BOTH datasets -- the only ones a
# cross-dataset model can honestly use.
NASA_SHARED_IDX = {"dur": 0, "t_max": 2}       # from nasa's [dur,area,t_max,t_rise,v_drop,b1..b4]
OXFORD_SHARED_IDX = {"dur": 0, "t_max": 1}     # from oxford's [t_cyc,T_peak,T_mean,...]


def _shared_features(feats, which):
    idx = NASA_SHARED_IDX if which == "nasa" else OXFORD_SHARED_IDX
    dur = feats[:, idx["dur"]]
    tmax = feats[:, idx["t_max"]]
    return np.stack([dur, tmax], axis=1)


def experiment_C_cross_dataset():
    print("\n" + "=" * 78)
    print("EXPERIMENT C: cross-dataset transfer (shared features: duration, peak temp)")
    print("This was NOT guaranteed to work -- reporting the real outcome.")
    print("=" * 78)

    nasa_bats, _ = load_all_batteries()
    oxford_cells = load_oxford()

    def build_pool(cell_dict, which):
        X, y, groups = [], [], []
        for cid, (feats, soh) in cell_dict.items():
            sf = _shared_features(feats, which)
            # per-cell relative normalisation so duration/temp scale is comparable
            baseline = np.median(sf[:3], axis=0)
            baseline[np.abs(baseline) < 1e-6] = 1.0
            sf = sf / baseline
            for t in range(SEQ_LEN, len(soh)):
                X.append(sf[t - SEQ_LEN:t]); y.append(soh[t]); groups.append(cid)
        return np.array(X, np.float32), np.array(y, np.float32), groups

    Xn, yn, gn = build_pool(nasa_bats, "nasa")
    Xo, yo, go = build_pool(oxford_cells, "oxford")
    print(f"NASA pool: {len(Xn)} sequences from {len(set(gn))} cells")
    print(f"Oxford pool: {len(Xo)} sequences from {len(set(go))} cells")

    def train_eval(Xtr, ytr, Xte, yte, label):
        mu, sd = Xtr.mean((0, 1)), Xtr.std((0, 1)) + 1e-8
        Xtr_t = torch.tensor((Xtr - mu) / sd); ytr_t = torch.tensor(ytr)
        Xte_t = torch.tensor((Xte - mu) / sd); yte_t = torch.tensor(yte)
        torch.manual_seed(0)
        model = SoHLSTM(n_features=2)
        opt = torch.optim.Adam(model.parameters(), lr=3e-3)
        loss_fn = nn.MSELoss()
        for _ in range(150):
            model.train()
            perm = torch.randperm(len(Xtr_t))
            for i in range(0, len(Xtr_t), 64):
                b = perm[i:i + 64]
                opt.zero_grad()
                loss_fn(model(Xtr_t[b]), ytr_t[b]).backward()
                opt.step()
        model.eval()
        with torch.no_grad():
            p = model(Xte_t)
        r2v = r2(p, yte_t)
        mae = float((p - yte_t).abs().mean())
        print(f"  {label}: R2={r2v if r2v is None else round(r2v,4)}  MAE={mae:.4f}  n_test={len(yte)}")
        return r2v, mae

    print("\nTrain on NASA (shared features) -> test on Oxford:")
    train_eval(Xn, yn, Xo, yo, "NASA->Oxford")
    print("\nTrain on Oxford (shared features) -> test on NASA:")
    train_eval(Xo, yo, Xn, yn, "Oxford->NASA")
    print("\nTrain on POOLED NASA+Oxford, test on a held-out 20% (both datasets mixed):")
    Xall = np.concatenate([Xn, Xo]); yall = np.concatenate([yn, yo])
    rng = np.random.default_rng(0)
    idx = rng.permutation(len(Xall))
    cut = int(len(Xall) * 0.8)
    train_eval(Xall[idx[:cut]], yall[idx[:cut]], Xall[idx[cut:]], yall[idx[cut:]], "Pooled (mixed test)")


if __name__ == "__main__":
    a = experiment_A_all_nasa()
    b = experiment_B_oxford()
    experiment_C_cross_dataset()
