"""
Convert SUMO floating-car-data (FCD) XML into the flat CSV that
org.fog.nhi.MobilityPredictor reads.

Output columns: vehId,time,x,y,speed,angle
  x, y     metres in SUMO's local network coordinates, re-centred so the
           network's midpoint sits at the origin. The fog-node positions read
           from the Melbourne CBD dataset are likewise expressed in metres
           relative to the CBD reference point, so the two share a frame.
  speed    m/s
  angle    degrees, SUMO convention (0 = north, clockwise). MobilityPredictor
           converts to radians.

Only vehicles with a reasonable number of samples are kept, so each simulated
mobile device gets a usable movement history.
"""
import csv
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

IN_XML = sys.argv[1] if len(sys.argv) > 1 else "fcd.xml"
OUT_CSV = sys.argv[2] if len(sys.argv) > 2 else "sumo_vehicular_trace.csv"
MIN_SAMPLES = 60

rows_by_veh = defaultdict(list)

for _, elem in ET.iterparse(IN_XML, events=("end",)):
    if elem.tag != "timestep":
        continue
    t = float(elem.get("time"))
    for v in elem.findall("vehicle"):
        rows_by_veh[v.get("id")].append((
            t,
            float(v.get("x")),
            float(v.get("y")),
            float(v.get("speed")),
            float(v.get("angle")),
        ))
    elem.clear()

kept = {k: v for k, v in rows_by_veh.items() if len(v) >= MIN_SAMPLES}
if not kept:
    sys.exit(f"ERROR: no vehicle had >= {MIN_SAMPLES} samples in {IN_XML}")

all_x = [r[1] for v in kept.values() for r in v]
all_y = [r[2] for v in kept.values() for r in v]
cx = (min(all_x) + max(all_x)) / 2.0
cy = (min(all_y) + max(all_y)) / 2.0

with open(OUT_CSV, "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["vehId", "time", "x", "y", "speed", "angle"])
    for veh, samples in sorted(kept.items()):
        samples.sort(key=lambda r: r[0])
        # re-index time from 0 so every vehicle's trace starts at step 0
        t0 = samples[0][0]
        for t, x, y, sp, ang in samples:
            w.writerow([veh, f"{t - t0:.1f}", f"{x - cx:.2f}", f"{y - cy:.2f}",
                        f"{sp:.3f}", f"{ang:.2f}"])

speeds = [r[3] for v in kept.values() for r in v]
print(f"Vehicles kept: {len(kept)} (of {len(rows_by_veh)}) | "
      f"samples: {sum(len(v) for v in kept.values())}")
print(f"Speed m/s: mean={sum(speeds)/len(speeds):.2f} max={max(speeds):.2f}")
print(f"Extent metres: x [{min(all_x)-cx:.0f}, {max(all_x)-cx:.0f}]  "
      f"y [{min(all_y)-cy:.0f}, {max(all_y)-cy:.0f}]")
print(f"Wrote {OUT_CSV}")
