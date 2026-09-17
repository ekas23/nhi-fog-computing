package org.fog.nhi;

import java.util.ArrayList;
import java.util.List;

/**
 * The Adaptive Node Health Index.
 *
 * NHI_i = w1*CPU_free + w2*MEM_free + w3*(1/Queue) + w4*(1/Latency)
 *       + w5*SoC + w6*SoH + w7*Reliability + w8*MobilityStability
 *
 * Every raw metric is min-max normalised across the candidate set before
 * weighting, so quantities with incompatible units (MIPS, MB, ms, ratios) can
 * be combined. The task is placed on the highest-scoring candidate.
 *
 * DYNAMIC WEIGHTING (the project's own contribution beyond the fixed-weight
 * formulation):
 * A fixed weight vector is wrong in two opposite situations. When devices are
 * barely moving, spending weight on mobility stability is wasted - every
 * candidate scores the same and the term only adds noise. When the fleet's
 * batteries are all equally healthy, the SoH term likewise carries no
 * information. Conversely, when devices move fast, or when battery health
 * varies a lot across the fleet, those terms are exactly what should dominate.
 *
 * So instead of tuning one weight vector per scenario by hand, the controller
 * measures two signals each placement round and reweights itself:
 *
 *   mobilityIntensity  = spread of predicted dwell times relative to task
 *                        length (high when some nodes will lose the device
 *                        mid-task and others will not)
 *   healthDispersion   = standard deviation of SoH across candidates
 *                        (high when the fleet is unevenly worn)
 *
 * Each signal scales its own term's weight up to `maxBoost`, after which the
 * whole vector is renormalised to sum to 1. A term whose metric is flat across
 * candidates contributes nothing and is automatically de-emphasised.
 *
 * This keeps the index explainable and auditable - you can always print the
 * weights that produced a decision - unlike a learned black-box policy.
 */
public class NHIScorer {

    /** Placement policies compared in the evaluation. */
    public enum Policy {
        ROUND_ROBIN,
        LEAST_LOADED,
        LATENCY_ONLY,
        MOBILITY_AWARE,      // SMITS-style dwell-time baseline
        NHI_STATIC,          // proposed index, fixed weights
        NHI_DYNAMIC          // proposed index, self-tuning weights
    }

    // Baseline weights (sum to 1). Order: cpu, mem, queue, latency,
    //                        soc, soh, reliability, mobility, wearBalance
    public static final double[] BASE_W =
            {0.12, 0.08, 0.10, 0.13, 0.11, 0.13, 0.09, 0.12, 0.12};

    public static final double MAX_BOOST = 2.5;

    private static int rrCounter = 0;

    public static void resetRoundRobin() { rrCounter = 0; }

    /** One candidate node's raw metrics for a single placement decision. */
    public static class Candidate {
        public final int deviceId;
        public double cpuFree;      // free MIPS
        public double memFree;      // free RAM
        public double queueLen;     // tuples waiting
        public double latency;      // ms to reach the node
        public double soc;          // [0,1]
        public double soh;          // [0,1]
        public double reliability;  // [0,1]
        public double dwellTime;    // predicted seconds device stays in range
        public double tasksAlready;  // modules already placed on this node
        public boolean canHost = true;  // node has room for this module
        public double lastScore;    // filled in by score(), for logging

        public Candidate(int deviceId) { this.deviceId = deviceId; }
    }

    private static double[] minMax(double[] v) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (double x : v) { lo = Math.min(lo, x); hi = Math.max(hi, x); }
        double[] out = new double[v.length];
        if (hi - lo < 1e-9) {
            java.util.Arrays.fill(out, 1.0);   // flat metric carries no information
            return out;
        }
        for (int i = 0; i < v.length; i++) out[i] = (v[i] - lo) / (hi - lo);
        return out;
    }

    private static double stddev(double[] v) {
        double m = 0;
        for (double x : v) m += x;
        m /= v.length;
        double s = 0;
        for (double x : v) s += (x - m) * (x - m);
        return Math.sqrt(s / v.length);
    }

    /** Weights actually used by the last NHI_DYNAMIC decision (for logging). */
    public static double[] lastDynamicWeights = BASE_W.clone();

    /**
     * Select a node. Returns the chosen device id, or -1 when no candidate is
     * acceptable (caller should then offload to cloud).
     */
    public static int select(List<Candidate> cands, Policy policy, double taskDuration) {
        if (cands.isEmpty()) return -1;
        int n = cands.size();

        switch (policy) {
            case ROUND_ROBIN:
                return cands.get(Math.abs(rrCounter++) % n).deviceId;

            case LEAST_LOADED: {
                int best = 0;
                double bestVal = -Double.MAX_VALUE;
                for (int i = 0; i < n; i++) {
                    double v = cands.get(i).cpuFree - 5.0 * cands.get(i).queueLen;
                    if (v > bestVal) { bestVal = v; best = i; }
                }
                return cands.get(best).deviceId;
            }

            case LATENCY_ONLY: {
                int best = 0;
                double bestVal = Double.MAX_VALUE;
                for (int i = 0; i < n; i++) {
                    if (cands.get(i).latency < bestVal) {
                        bestVal = cands.get(i).latency; best = i;
                    }
                }
                return cands.get(best).deviceId;
            }

            case MOBILITY_AWARE: {
                // SMITS-style: prefer nodes that will outlast the task, then
                // pick the least loaded among them.
                List<Candidate> ok = new ArrayList<>();
                for (Candidate c : cands) if (c.dwellTime >= taskDuration) ok.add(c);
                List<Candidate> pool = ok.isEmpty() ? cands : ok;
                Candidate best = pool.get(0);
                for (Candidate c : pool)
                    if (c.cpuFree - 5.0 * c.queueLen > best.cpuFree - 5.0 * best.queueLen) best = c;
                return best.deviceId;
            }

            case NHI_STATIC:
            case NHI_DYNAMIC:
            default:
                return scoreNHI(cands, policy == Policy.NHI_DYNAMIC, taskDuration);
        }
    }

    private static int scoreNHI(List<Candidate> cands, boolean dynamic, double taskDuration) {
        int n = cands.size();
        double[] cpu = new double[n], mem = new double[n], que = new double[n],
                 lat = new double[n], soc = new double[n], soh = new double[n],
                 rel = new double[n], mob = new double[n], wear = new double[n];

        for (int i = 0; i < n; i++) {
            Candidate c = cands.get(i);
            cpu[i] = c.cpuFree;
            mem[i] = c.memFree;
            que[i] = 1.0 / (1.0 + c.queueLen);           // inverted: shorter is better
            lat[i] = 1.0 / Math.max(c.latency, 1e-3);    // inverted: lower is better
            soc[i] = c.soc;
            soh[i] = c.soh;
            rel[i] = c.reliability;
            // mobility stability: does the node outlive the task?
            mob[i] = Double.isInfinite(c.dwellTime) ? 1.0
                    : Math.min(1.0, c.dwellTime / Math.max(taskDuration, 1e-6));
            // Wear balance: prefer nodes that have not already absorbed a lot
            // of work. Without this the index sends every task to whichever
            // node is healthiest, wearing it down and ACCELERATING divergence
            // across the fleet - the opposite of the goal. Preferring healthy
            // nodes and spreading wear are different objectives and the index
            // needs both.
            wear[i] = 1.0 / (1.0 + c.tasksAlready);
        }

        double[] nCpu = minMax(cpu), nMem = minMax(mem), nQue = minMax(que),
                 nLat = minMax(lat), nSoc = minMax(soc), nSoh = minMax(soh),
                 nRel = minMax(rel), nMob = minMax(mob), nWear = minMax(wear);

        double[] w = BASE_W.clone();

        if (dynamic) {
            // --- self-tuning step -------------------------------------------
            // Boost a term in proportion to how much discriminating information
            // it currently carries. A flat metric (all candidates alike) has
            // ~zero spread and so gets no boost.
            double mobilitySpread = stddev(nMob);      // 0 when nobody is moving
            double healthSpread = stddev(soh);         // 0 when fleet wears evenly

            // spread of a normalised vector saturates around 0.5, so scale by 2
            double mobBoost = 1.0 + Math.min(MAX_BOOST - 1.0, 2.0 * mobilitySpread * (MAX_BOOST - 1.0));
            // SoH spread is in raw SoH units; 0.15 spread is already a very
            // unevenly worn fleet, so normalise against that.
            double sohBoost = 1.0 + Math.min(MAX_BOOST - 1.0, (healthSpread / 0.15) * (MAX_BOOST - 1.0));

            w[7] *= mobBoost;   // mobility stability
            w[5] *= sohBoost;   // state of health
            // An unevenly worn fleet also needs wear spread more carefully,
            // so the balancing term rises with the same dispersion signal.
            w[8] *= sohBoost;   // wear balance

            double sum = 0;
            for (double x : w) sum += x;
            for (int i = 0; i < w.length; i++) w[i] /= sum;
            lastDynamicWeights = w.clone();
        }

        int best = -1;
        double bestScore = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double s = w[0] * nCpu[i] + w[1] * nMem[i] + w[2] * nQue[i] + w[3] * nLat[i]
                     + w[4] * nSoc[i] + w[5] * nSoh[i] + w[6] * nRel[i] + w[7] * nMob[i]
                     + w[8] * nWear[i];
            cands.get(i).lastScore = s;
            if (s > bestScore) { bestScore = s; best = i; }
        }
        return cands.get(best).deviceId;
    }
}
