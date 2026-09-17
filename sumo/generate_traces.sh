#!/usr/bin/env bash
# Regenerates the three SUMO mobility traces used in the study.
# Requires SUMO (apt install sumo sumo-tools) and $SUMO_HOME set.
set -e
: "${SUMO_HOME:=/usr/share/sumo}"
cd "$(dirname "$0")"

gen () {  # name gridArgs endTime period seed extraNetArgs
  local name=$1 grid=$2 end=$3 period=$4 seed=$5; shift 5
  echo "=== $name ==="
  netgenerate --grid $grid "$@" -o "${name}.net.xml"
  python3 "$SUMO_HOME/tools/randomTrips.py" -n "${name}.net.xml" \
      -o "${name}.trips.xml" -e "$end" -p "$period" --seed "$seed" --validate
  duarouter -n "${name}.net.xml" -r "${name}.trips.xml" \
      -o "${name}.rou.xml" --ignore-errors
  sumo -n "${name}.net.xml" -r "${name}.rou.xml" \
      --fcd-output "${name}.fcd.xml" --end $((end + 50)) --step-length 1
  python3 fcd_to_csv.py "${name}.fcd.xml" "sumo_${name}.csv"
}

# dense urban: 7x7 grid, 300 m blocks (~1.8 km), city speeds
gen dense_urban "--grid.number=7 --grid.length=300" 200 1.2 42

# sparse suburban: 7x7 grid, 1000 m blocks (~6 km)
gen sparse_suburban "--grid.number=7 --grid.length=1000" 250 1.5 11

# highway corridor: 11x2, 1000 m x-spacing (~10 km), 120 km/h, 3 lanes
gen highway "--grid.x-number=11 --grid.y-number=2 --grid.x-length=1000 --grid.y-length=200" \
    250 1.2 13 --default.speed 33.3 --default.lanenumber 3

echo
echo "Done. Copy into iFogSim2:"
echo "  cp sumo_*.csv ../iFogSim2/dataset/"
