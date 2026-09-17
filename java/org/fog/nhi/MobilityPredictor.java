package org.fog.nhi;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Predicts how long a mobile device will remain inside a fog node's coverage
 * (its "dwell" or sojourn time) - the eighth NHI input.
 *
 * Two sources of movement:
 *  1. A SUMO floating-car-data trace (vehicular scenario). Real road-network
 *     movement exported by SUMO as (id, time, x, y, speed, angle) and read
 *     here. This is the co-simulation path described by iFogSUMO.
 *  2. Synthetic kinematics for the static / pedestrian scenarios, where a full
 *     traffic simulation is unnecessary (pedestrians are not constrained to
 *     a road network).
 *
 * Dwell time is computed geometrically: given the device's position and
 * velocity and a node's coverage disk, solve for when the device crosses the
 * disk boundary. If the device is already outside, dwell is 0; if it is not
 * moving, dwell is infinite.
 */
public class MobilityPredictor {

    public static class Trace {
        public final List<double[]> samples = new ArrayList<>(); // t, x, y, speed, angleRad
    }

    /** deviceId -> movement trace */
    private final Map<Integer, Trace> traces = new HashMap<>();
    private final Random rng;
    public double coverageRadius;

    public MobilityPredictor(double coverageRadius, long seed) {
        this.coverageRadius = coverageRadius;
        this.rng = new Random(seed);
    }

    /**
     * Load a SUMO floating-car-data CSV: vehId,time,x,y,speed,angle
     * Vehicles are assigned to device ids in order of first appearance.
     */
    public int loadSumoTrace(String csvPath, List<Integer> deviceIds) throws Exception {
        Map<String, List<double[]>> byVeh = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(csvPath))) {
            String line = br.readLine(); // header
            while ((line = br.readLine()) != null) {
                String[] p = line.split(",");
                if (p.length < 6) continue;
                String veh = p[0];
                double t = Double.parseDouble(p[1]);
                double x = Double.parseDouble(p[2]);
                double y = Double.parseDouble(p[3]);
                double sp = Double.parseDouble(p[4]);
                double ang = Math.toRadians(Double.parseDouble(p[5]));
                byVeh.computeIfAbsent(veh, k -> new ArrayList<>())
                     .add(new double[]{t, x, y, sp, ang});
            }
        }
        List<String> vehs = new ArrayList<>(byVeh.keySet());
        vehs.sort(null);
        int assigned = 0;
        for (int i = 0; i < deviceIds.size() && i < vehs.size(); i++) {
            Trace tr = new Trace();
            tr.samples.addAll(byVeh.get(vehs.get(i)));
            traces.put(deviceIds.get(i), tr);
            assigned++;
        }
        return assigned;
    }

    /** Synthetic straight-line movement for static/pedestrian scenarios. */
    public void generateSynthetic(List<Integer> deviceIds, double minSpeed, double maxSpeed,
                                  double areaSize, int steps) {
        generateSynthetic(deviceIds, minSpeed, maxSpeed, areaSize, steps, 0.0);
    }

    /**
     * @param corridorWidth if > 0, devices are confined to a corridor of this
     *   width about y = 0 instead of a square area. Required for linear
     *   topologies: scattering devices over a square whose side equals the
     *   corridor LENGTH strands them kilometres from every node, so nearly
     *   every placement fails regardless of policy and the comparison becomes
     *   meaningless.
     */
    public void generateSynthetic(List<Integer> deviceIds, double minSpeed, double maxSpeed,
                                  double areaSize, int steps, double corridorWidth) {
        // Positions are centred on the origin so they share the frame used by
        // NodePosition (metres relative to the CBD reference point). Generating
        // them in [0, areaSize] instead would place most devices outside every
        // node's coverage disk.
        for (int id : deviceIds) {
            Trace tr = new Trace();
            double ySpan = (corridorWidth > 0) ? corridorWidth : areaSize;
            double x = (rng.nextDouble() - 0.5) * areaSize;
            double y = (rng.nextDouble() - 0.5) * ySpan;
            double ang = rng.nextDouble() * 2 * Math.PI;
            double sp = minSpeed + rng.nextDouble() * (maxSpeed - minSpeed);
            for (int t = 0; t < steps; t++) {
                tr.samples.add(new double[]{t, x, y, sp, ang});
                x += sp * Math.cos(ang);
                y += sp * Math.sin(ang);
                if (rng.nextDouble() < 0.05) ang += (rng.nextDouble() - 0.5) * 0.8;
                // reflect at the area boundary
                double halfX = areaSize / 2.0, halfY = ySpan / 2.0;
                if (x < -halfX || x > halfX) { ang = Math.PI - ang; x = Math.max(-halfX, Math.min(halfX, x)); }
                if (y < -halfY || y > halfY) { ang = -ang; y = Math.max(-halfY, Math.min(halfY, y)); }
            }
            traces.put(id, tr);
        }
    }

    public double[] sampleAt(int deviceId, double time) {
        Trace tr = traces.get(deviceId);
        if (tr == null || tr.samples.isEmpty()) return null;
        int idx = (int) Math.floor(time);
        if (idx < 0) idx = 0;
        if (idx >= tr.samples.size()) idx = tr.samples.size() - 1;
        return tr.samples.get(idx);
    }

    /**
     * Predicted dwell time (seconds) of `deviceId` inside the coverage disk
     * centred at (nodeX, nodeY). Positive infinity for a stationary device.
     */
    public double predictDwell(int deviceId, double time, double nodeX, double nodeY) {
        double[] s = sampleAt(deviceId, time);
        if (s == null) return Double.POSITIVE_INFINITY;
        double x = s[1], y = s[2], speed = s[3], ang = s[4];
        if (speed < 1e-6) return Double.POSITIVE_INFINITY;

        double vx = speed * Math.cos(ang), vy = speed * Math.sin(ang);
        double rx = x - nodeX, ry = y - nodeY;

        // |r + v t| = R  ->  (v.v) t^2 + 2(r.v) t + (r.r - R^2) = 0
        double a = vx * vx + vy * vy;
        double b = 2 * (rx * vx + ry * vy);
        double c = rx * rx + ry * ry - coverageRadius * coverageRadius;

        if (c > 0) return 0.0;                 // already outside coverage
        double disc = b * b - 4 * a * c;
        if (disc < 0) return Double.POSITIVE_INFINITY;
        double tExit = (-b + Math.sqrt(disc)) / (2 * a);
        return Math.max(0.0, tExit);
    }

    public boolean hasTrace(int deviceId) { return traces.containsKey(deviceId); }

    public int traceCount() { return traces.size(); }
}
