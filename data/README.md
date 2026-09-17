# Battery data

The SoH model trains on the **NASA Ames Prognostics Center of Excellence
Li-ion Battery Dataset**. The raw `.mat` files (~122 MB) are not committed.

## Getting the data

Download the battery dataset from NASA's Prognostics Data Repository
(the "Battery Data Set" under PCoE datasets), then place the `.mat` files here:

```
data/nasa_mat/B0005.mat
data/nasa_mat/B0006.mat
data/nasa_mat/B0007.mat
data/nasa_mat/B0018.mat
...
```

**Minimum required:** the G1 family — `B0005`, `B0006`, `B0007`, `B0018`
(2 A constant-current discharge, 24 °C ambient). These are the four cells the
model is trained and evaluated on. Additional groups are parsed if present but
are excluded by the protocol-consistency filter — see `docs/RESULTS.md` §2 for
why cells from other groups are not comparable.

## What's committed instead

`soh_lstm_deploy.pt` — the trained model weights, so the simulation runs
without re-downloading the dataset. Re-train with:

```bash
python3 src/soh_lstm_final.py
python3 -c "import sys; sys.path.insert(0,'src'); import soh_lstm_final as m; m.run_both()"
```
