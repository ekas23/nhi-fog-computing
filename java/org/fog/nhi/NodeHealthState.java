package org.fog.nhi;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Per-fog-node battery and reliability state for the Adaptive Node Health
 * Index (NHI).
 *
 * iFogSim2's FogDevice models CPU/RAM/latency/energy but has no concept of a
 * battery's long-term condition or a node's historical reliability. Rather
 * than fork FogDevice, we keep that state in this side registry keyed by
 * device id.
 *
 * Battery realism: every battery-powered node is bound to a REAL NASA PCoE
 * cell (B0005/B0006/B0007/B0018). As the node does work it consumes energy,
 * which advances a cycle counter into that cell's measured charge/discharge
 * history. State of Health is then estimated by the LSTM from that cell's
 * real measured discharge-curve features at the current cycle (see
 * SoHClient), NOT read from ground-truth capacity. So node degradation in the
 * simulation follows a real battery's real ageing trajectory.
 */
public class NodeHealthState {

    /** NASA cells available to back simulated nodes. */
    public static final String[] NASA_CELLS = {"B0005", "B0006", "B0007", "B0018"};

    public final int deviceId;
    public final String cellId;      // backing NASA cell, null if mains-powered
    public final boolean batteryPowered;

    public double soc = 1.0;         // State of Charge  [0,1]
    public double soh = 1.0;         // State of Health  [0,1], from the LSTM
    public double reliability;       // historical success rate [0,1]

    public double energyAtLastCycle = 0.0;
    public int startCycle = 0;       // cycles already on the battery at deployment
    public int cycleIndex = 0;       // startCycle + cycles accrued in simulation
    public int maxCycle = 160;

    public int tasksAssigned = 0;
    public int tasksSucceeded = 0;
    public int migrations = 0;

    /** Energy (in iFogSim2 energy units) that counts as one battery cycle. */
    public static double ENERGY_PER_CYCLE = 40000.0;

    public NodeHealthState(int deviceId, String cellId, boolean batteryPowered,
                           double initialSoc, double reliability) {
        this.deviceId = deviceId;
        this.cellId = cellId;
        this.batteryPowered = batteryPowered;
        this.soc = initialSoc;
        this.reliability = reliability;
    }

    /** Battery cycles consumed by hosting one workload module. */
    public static double CYCLES_PER_TASK = 8.0;

    /**
     * Advance the battery model.
     *
     * Cycle accrual is driven by the WORK the node was given, not by its raw
     * energy counter. In iFogSim2 a device's energy figure is dominated by
     * idle draw, which is near-identical across nodes and so would make every
     * scheduling policy look the same. What actually wears a battery is the
     * work placed on it - which is exactly what the scheduler controls - so
     * cycles accrue with the modules the node was asked to host, with the
     * dynamic (above-idle) energy as a secondary term.
     *
     * Returns true if the cycle index moved, i.e. SoH should be re-estimated.
     */
    public boolean updateFromEnergy(double cumulativeEnergy) {
        if (!batteryPowered) return false;
        double delta = cumulativeEnergy - energyAtLastCycle;

        // discharge reduces SoC; a degraded battery holds proportionally less
        soc = Math.max(0.05, soc - (delta / ENERGY_PER_CYCLE) * 0.08 / Math.max(soh, 0.3));

        // Cycles accrued during the run are ADDED to the cycles the battery
        // already had at deployment. Overwriting with the energy-derived count
        // would erase a pre-aged node's history.
        int accrued = (int) Math.floor(tasksAssigned * CYCLES_PER_TASK
                                        + cumulativeEnergy / ENERGY_PER_CYCLE);
        int newCycle = Math.min(startCycle + accrued, maxCycle);
        if (newCycle != cycleIndex) {
            cycleIndex = newCycle;
            energyAtLastCycle = cumulativeEnergy;
            // recharge event at cycle boundary
            soc = Math.min(1.0, soc + 0.85);
            return true;
        }
        return false;
    }

    public void recordAssignment() { tasksAssigned++; }

    public void recordOutcome(boolean success) {
        if (success) tasksSucceeded++;
        // exponential moving average keeps reliability responsive
        double observed = success ? 1.0 : 0.0;
        reliability = 0.95 * reliability + 0.05 * observed;
    }

    public void recordMigration() { migrations++; }

    // ---------------------------------------------------------------- registry

    private static final Map<Integer, NodeHealthState> REGISTRY = new HashMap<>();

    public static void reset() { REGISTRY.clear(); }

    public static NodeHealthState get(int deviceId) { return REGISTRY.get(deviceId); }

    public static Map<Integer, NodeHealthState> all() { return REGISTRY; }

    /**
     * Register a node. `degradedFleet` seeds a spread of starting cycle
     * positions so some nodes begin life already worn (the "degraded fleet"
     * scenario from the project plan).
     */
    public static NodeHealthState register(int deviceId, boolean batteryPowered,
                                           boolean degradedFleet, Random rng) {
        String cell = batteryPowered
                ? NASA_CELLS[Math.abs(deviceId) % NASA_CELLS.length] : null;
        double soc = batteryPowered ? 0.6 + rng.nextDouble() * 0.4 : 1.0;
        double rel = 0.90 + rng.nextDouble() * 0.09;
        NodeHealthState s = new NodeHealthState(deviceId, cell, batteryPowered, soc, rel);
        if (batteryPowered) {
            // Baseline heterogeneity: nodes are not all deployed on the same
            // day, so even a healthy fleet carries a spread of prior wear.
            s.startCycle = rng.nextInt(25);
            if (degradedFleet && rng.nextDouble() < 0.5) {
                s.startCycle = 70 + rng.nextInt(45);   // pre-aged node
                s.reliability = 0.60 + rng.nextDouble() * 0.25;
            }
            s.cycleIndex = s.startCycle;
        }
        REGISTRY.put(deviceId, s);
        return s;
    }

    @Override
    public String toString() {
        return String.format("Node%d[cell=%s soc=%.2f soh=%.3f rel=%.2f cyc=%d tasks=%d mig=%d]",
                deviceId, cellId, soc, soh, reliability, cycleIndex, tasksAssigned, migrations);
    }
}
