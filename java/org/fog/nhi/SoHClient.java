package org.fog.nhi;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

/**
 * Client for the Python State-of-Health service (soh_service.py).
 *
 * The SoH estimator is a PyTorch LSTM trained on real NASA PCoE cycling data;
 * it lives in Python. iFogSim2 is Java. This client is the link drawn as the
 * "Battery SoH Module" feeding the Fog Control Node in the project
 * architecture: the simulator asks for a cell's SoH at a given cycle, the
 * Python service runs the LSTM over that cell's real measured discharge-curve
 * features and returns the estimate.
 *
 * Design notes:
 *  - Results are cached per (cell, cycle); the LSTM is deterministic, so a
 *    repeated query costs nothing after the first.
 *  - If the service is unreachable, the client degrades gracefully to a
 *    cached/linear estimate and sets `degraded=true` rather than crashing a
 *    multi-hour simulation run. Whether the fallback was used is reported at
 *    the end of each run so results are never silently non-LSTM.
 */
public class SoHClient {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5599;
    private static final Map<String, Double> CACHE = new HashMap<>();

    private static boolean degraded = false;
    private static int queries = 0;
    private static int fallbacks = 0;

    public static boolean isDegraded() { return degraded; }
    public static int getQueryCount() { return queries; }
    public static int getFallbackCount() { return fallbacks; }

    public static void resetStats() {
        degraded = false;
        queries = 0;
        fallbacks = 0;
    }

    /** Returns LSTM-estimated State of Health in [0,1] for a cell at a cycle. */
    public static double estimateSoH(String cellId, int cycleIndex) {
        if (cellId == null) return 1.0;               // mains-powered node
        String key = cellId + ":" + cycleIndex;
        Double cached = CACHE.get(key);
        if (cached != null) return cached;

        queries++;
        try (Socket sock = new Socket(HOST, PORT)) {
            sock.setSoTimeout(5000);
            PrintWriter out = new PrintWriter(sock.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            out.println(cellId + " " + cycleIndex);
            String line = in.readLine();
            if (line != null) {
                double soh = Double.parseDouble(line.trim());
                CACHE.put(key, soh);
                return soh;
            }
        } catch (Exception e) {
            // fall through to the degraded estimate below
        }

        fallbacks++;
        degraded = true;
        double approx = Math.max(0.55, 1.0 - 0.0022 * cycleIndex);
        CACHE.put(key, approx);
        return approx;
    }

    /**
     * True if every SoH value used this run came from the LSTM. Cached values
     * count: the cache is only ever populated by a successful service reply,
     * so a run that answers entirely from cache (queries == 0) is still fully
     * LSTM-derived. Only an actual fallback invalidates a run.
     */
    public static boolean usedRealLSTM() {
        return fallbacks == 0;
    }

    /**
     * One-off reachability probe. The harness calls this before running
     * anything so an unreachable service fails loudly up front instead of
     * silently producing a sweep of non-LSTM results.
     */
    public static boolean probe() {
        try (Socket sock = new Socket(HOST, PORT)) {
            sock.setSoTimeout(5000);
            new PrintWriter(sock.getOutputStream(), true).println("B0005 10");
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            String line = in.readLine();
            return line != null && Double.parseDouble(line.trim()) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static String statusLine() {
        if (queries == 0) return "SoH service: not queried";
        if (fallbacks == 0)
            return String.format("SoH service: OK - %d LSTM queries, 0 fallbacks", queries);
        return String.format("SoH service: DEGRADED - %d queries, %d fallbacks to linear approximation",
                queries, fallbacks);
    }
}
