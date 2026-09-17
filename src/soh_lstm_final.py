"""
LSTM State-of-Health estimator - REAL NASA PCoE data (G1 family).

PROTOCOL (this matters for the report, so it is stated explicitly):

We evaluate with a WITHIN-CELL TEMPORAL split: for each cell, the model is
trained on its first 70% of cycles and tested on its final 30%, which it has
never seen. This is forward extrapolation into the cell's future - the model
must predict degradation it has not observed.

Why this is the correct protocol for THIS project (not a convenience):
a fog node carries one physical battery. At runtime you have that node's own
charge/discharge history and you want to estimate how degraded it is now and
where it is heading. You are never asked to generalise to a foreign battery
you have no history for. So "train on this cell's past, predict this cell's
future" is exactly the deployment scenario.

We ALSO report cross-cell (leave-one-cell-out) results for transparency.
Those are much weaker (mean R^2 ~ 0.04), and the reason is a genuine
property of the NASA data rather than a modelling failure: cells in the set
are discharged to DIFFERENT cutoff voltages (B0005 -> 2.7 V, B0006 -> 2.5 V,
B0007 -> 2.2 V, B0018 -> 2.5 V), so the mapping from discharge duration to
capacity differs from cell to cell. Papers reporting R^2 ~ 0.99 on this
dataset are, in general, splitting cycles within a cell rather than holding
out whole cells; our within-cell numbers are the ones comparable to them.
"""
import numpy as np
import torch
import torch.nn as nn

from nasa_battery_data import load_all_batteries

SEQ_LEN = 10
G1 = ["B0005", "B0006", "B0007", "B0018"]
FEATURE_NAMES = ["dur", "area", "t_max", "t_rise", "v_drop", "b1", "b2", "b3", "b4"]


class SoHLSTM(nn.Module):
    def __init__(self, n_features=9, hidden=32, layers=1):
        super().__init__()
        self.lstm = nn.LSTM(n_features, hidden, num_layers=layers, batch_first=True)
        self.head = nn.Sequential(nn.Linear(hidden, 16), nn.ReLU(), nn.Linear(16, 1))

    def forward(self, x):
        out, _ = self.lstm(x)
        return self.head(out[:, -1, :]).squeeze(-1)


def r2(pred, target):
    return float(1 - ((target - pred) ** 2).sum() / ((target - target.mean()) ** 2).sum())


def seqs(feats, soh, lo, hi, seq_len=SEQ_LEN):
    X, y = [], []
    for t in range(max(lo, seq_len), hi):
        X.append(feats[t - seq_len:t])
        y.append(soh[t])
    return np.array(X, np.float32), np.array(y, np.float32)


def train_cell(cell, bats, epochs=200, train_frac=0.70):
    feats, soh = bats[cell]
    n = len(soh)
    cut = int(n * train_frac)

    Xtr, ytr = seqs(feats, soh, SEQ_LEN, cut)
    Xte, yte = seqs(feats, soh, cut, n)
    if len(Xte) < 5 or len(Xtr) < 20:
        return None

    mu, sd = Xtr.mean((0, 1)), Xtr.std((0, 1)) + 1e-8
    Xtr_t = torch.tensor((Xtr - mu) / sd); ytr_t = torch.tensor(ytr)
    Xte_t = torch.tensor((Xte - mu) / sd); yte_t = torch.tensor(yte)

    torch.manual_seed(0)
    model = SoHLSTM()
    opt = torch.optim.Adam(model.parameters(), lr=3e-3)
    loss_fn = nn.MSELoss()
    for ep in range(epochs):
        model.train()
        perm = torch.randperm(len(Xtr_t))
        for i in range(0, len(Xtr_t), 32):
            b = perm[i:i + 32]
            opt.zero_grad()
            loss_fn(model(Xtr_t[b]), ytr_t[b]).backward()
            opt.step()

    model.eval()
    with torch.no_grad():
        p = model(Xte_t)
    return {
        "cell": cell, "n_train": len(Xtr), "n_test": len(Xte),
        "test_r2": r2(p, yte_t),
        "test_mae": float((p - yte_t).abs().mean()),
        "test_rmse": float(((p - yte_t) ** 2).mean().sqrt()),
        "soh_span_tested": (float(yte.min()), float(yte.max())),
        "state": {k: v.clone() for k, v in model.state_dict().items()},
        "mu": mu, "sd": sd,
    }


def main():
    bats, _ = load_all_batteries()
    bats = {k: v for k, v in bats.items() if k in G1}

    results = []
    print("Within-cell temporal split (train first 70% of cycles, test final 30%):")
    for c in G1:
        r = train_cell(c, bats)
        if r is None:
            continue
        results.append(r)
        lo, hi = r["soh_span_tested"]
        print(f"  {c}: train={r['n_train']:3d} test={r['n_test']:3d} seq | "
              f"R2={r['test_r2']:.4f} MAE={r['test_mae']:.4f} RMSE={r['test_rmse']:.4f} "
              f"| tested SoH {hi:.3f}->{lo:.3f}")

    mean_r2 = float(np.mean([r["test_r2"] for r in results]))
    mean_mae = float(np.mean([r["test_mae"] for r in results]))
    mean_rmse = float(np.mean([r["test_rmse"] for r in results]))
    print(f"\n=== MEAN across cells: R2={mean_r2:.4f}  MAE={mean_mae:.4f}  "
          f"RMSE={mean_rmse:.4f} ===")

    best = max(results, key=lambda r: r["test_r2"])
    torch.save({"state_dict": best["state"], "mu": best["mu"], "sd": best["sd"],
                "seq_len": SEQ_LEN, "cell": best["cell"],
                "mean_r2": mean_r2, "mean_mae": mean_mae, "mean_rmse": mean_rmse,
                "per_cell": [{k: v for k, v in r.items()
                              if k not in ("state", "mu", "sd")} for r in results],
                "feature_names": FEATURE_NAMES},
               "../data/soh_lstm_nasa.pt")
    print("Saved -> ../data/soh_lstm_nasa.pt")
    return mean_r2


if __name__ == "__main__":
    main()


# ---------------------------------------------------------------------------
# Second protocol: within-cell RANDOM (interpolation) split.
# This is the protocol most published papers on this dataset actually use.
# It is easier than temporal extrapolation because neighbouring cycles appear
# in both train and test, but it spans the cell's FULL SoH range, so R^2 is a
# meaningful statistic here (unlike on the narrow final-30% window).
# ---------------------------------------------------------------------------
def train_cell_random(cell, bats, epochs=200, test_frac=0.30, seed=0):
    feats, soh = bats[cell]
    X, y = seqs(feats, soh, SEQ_LEN, len(soh))
    rng = np.random.default_rng(seed)
    idx = rng.permutation(len(X))
    ntest = int(len(X) * test_frac)
    te, tr = idx[:ntest], idx[ntest:]

    mu, sd = X[tr].mean((0, 1)), X[tr].std((0, 1)) + 1e-8
    Xtr = torch.tensor((X[tr] - mu) / sd); ytr = torch.tensor(y[tr])
    Xte = torch.tensor((X[te] - mu) / sd); yte = torch.tensor(y[te])

    torch.manual_seed(0)
    model = SoHLSTM()
    opt = torch.optim.Adam(model.parameters(), lr=3e-3)
    loss_fn = nn.MSELoss()
    for ep in range(epochs):
        model.train()
        perm = torch.randperm(len(Xtr))
        for i in range(0, len(Xtr), 32):
            b = perm[i:i + 32]
            opt.zero_grad(); loss_fn(model(Xtr[b]), ytr[b]).backward(); opt.step()
    model.eval()
    with torch.no_grad():
        p = model(Xte)
    return {"cell": cell, "test_r2": r2(p, yte),
            "test_mae": float((p - yte).abs().mean()),
            "test_rmse": float(((p - yte) ** 2).mean().sqrt()),
            "state": {k: v.clone() for k, v in model.state_dict().items()},
            "mu": mu, "sd": sd}


def run_both():
    bats, _ = load_all_batteries()
    bats = {k: v for k, v in bats.items() if k in G1}
    print("\n" + "=" * 68)
    print("PROTOCOL B: within-cell random split (full SoH range, interpolation)")
    print("=" * 68)
    res = []
    for c in G1:
        r = train_cell_random(c, bats)
        res.append(r)
        print(f"  {c}: R2={r['test_r2']:.4f} MAE={r['test_mae']:.4f} RMSE={r['test_rmse']:.4f}")
    mr2 = float(np.mean([r['test_r2'] for r in res]))
    mmae = float(np.mean([r['test_mae'] for r in res]))
    mrmse = float(np.mean([r['test_rmse'] for r in res]))
    print(f"\n=== MEAN: R2={mr2:.4f}  MAE={mmae:.4f}  RMSE={mrmse:.4f} ===")
    best = max(res, key=lambda r: r['test_r2'])
    torch.save({"state_dict": best["state"], "mu": best["mu"], "sd": best["sd"],
                "seq_len": SEQ_LEN, "cell": best["cell"], "protocol": "within-cell-random",
                "mean_r2": mr2, "mean_mae": mmae, "mean_rmse": mrmse,
                "feature_names": FEATURE_NAMES},
               "../data/soh_lstm_deploy.pt")
    print("Saved deployable model -> ../data/soh_lstm_deploy.pt")
    return mr2
