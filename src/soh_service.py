"""
State-of-Health service: the "Battery SoH Module" from the project
architecture, exposed over a TCP socket so the Java iFogSim2 simulator can
query it.

Protocol (line-oriented, one query per connection):
    request :  "<CELL_ID> <CYCLE_INDEX>\n"     e.g. "B0005 37"
    response:  "<soh>\n"                        e.g. "0.8642"

The returned SoH is produced by the LSTM trained on real NASA PCoE cycling
data (soh_lstm_final.py), run over that cell's REAL measured discharge-curve
features at the requested cycle. It is an estimate from measurements, not the
ground-truth capacity label.

Run:  python3 soh_service.py
Stop: Ctrl-C, or send "SHUTDOWN".
"""
import os
import pickle
import socketserver
import sys
import threading

import numpy as np
import torch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from nasa_battery_data import load_all_batteries          # noqa: E402
from soh_lstm_final import SoHLSTM, SEQ_LEN, G1           # noqa: E402

HOST, PORT = "127.0.0.1", 5599
MODEL_PATH = os.environ.get("NHI_MODEL", os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "data", "soh_lstm_deploy.pt"))

# Parsing the raw .mat files takes several seconds; a cache keeps service
# startup fast enough that a batch harness can rely on it being ready.
_CACHE_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                           "..", "data", "nasa_cache.pkl")
if os.path.exists(_CACHE_PATH):
    print("Loading cached NASA cell data ...")
    with open(_CACHE_PATH, "rb") as _f:
        _BATS = pickle.load(_f)
else:
    print("Parsing NASA .mat files (no cache) ...")
    _BATS, _ = load_all_batteries()
    _BATS = {k: v for k, v in _BATS.items() if k in G1}
    with open(_CACHE_PATH, "wb") as _f:
        pickle.dump(_BATS, _f)

print(f"Loading trained LSTM from {MODEL_PATH} ...")
_ckpt = torch.load(MODEL_PATH, weights_only=False)
_MODEL = SoHLSTM()
_MODEL.load_state_dict(_ckpt["state_dict"])
_MODEL.eval()
_MU, _SD = _ckpt["mu"], _ckpt["sd"]

print(f"Ready. Cells: {sorted(_BATS.keys())} | "
      f"model trained with mean R2={_ckpt.get('mean_r2', float('nan')):.4f}")

_cache = {}
_lock = threading.Lock()
_stats = {"queries": 0, "unknown_cell": 0}


def estimate_soh(cell_id, cycle_index):
    """LSTM estimate of SoH for `cell_id` at `cycle_index`."""
    key = (cell_id, cycle_index)
    with _lock:
        if key in _cache:
            return _cache[key]

    if cell_id not in _BATS:
        _stats["unknown_cell"] += 1
        return 1.0

    feats, _ = _BATS[cell_id]
    n = len(feats)
    # clamp into the cell's real measured history
    idx = max(SEQ_LEN, min(int(cycle_index), n - 1))
    window = feats[idx - SEQ_LEN:idx]

    x = (window - _MU) / _SD
    with torch.no_grad():
        soh = float(_MODEL(torch.tensor(x[None, :, :], dtype=torch.float32)).item())
    soh = float(np.clip(soh, 0.3, 1.0))

    with _lock:
        _cache[key] = soh
    return soh


class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        line = self.rfile.readline().decode("utf-8", "ignore").strip()
        if not line:
            return
        if line == "SHUTDOWN":
            self.wfile.write(b"bye\n")
            threading.Thread(target=self.server.shutdown, daemon=True).start()
            return
        try:
            parts = line.split()
            cell_id, cycle = parts[0], int(float(parts[1]))
            soh = estimate_soh(cell_id, cycle)
            _stats["queries"] += 1
            self.wfile.write(f"{soh:.6f}\n".encode())
        except Exception as e:  # never kill the server on a malformed query
            print(f"  [warn] bad request {line!r}: {e}", file=sys.stderr)
            self.wfile.write(b"1.0\n")


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    with Server((HOST, PORT), Handler) as srv:
        print(f"SoH service listening on {HOST}:{PORT}")
        try:
            srv.serve_forever()
        except KeyboardInterrupt:
            pass
    print(f"Shutting down. Served {_stats['queries']} queries "
          f"({_stats['unknown_cell']} unknown-cell).")
