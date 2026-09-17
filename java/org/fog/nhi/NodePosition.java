package org.fog.nhi;

import java.util.HashMap;
import java.util.Map;

/**
 * Geographic position of a fog node, taken from the Melbourne CBD edge-resource
 * dataset bundled with iFogSim2 (edgeResources-melbCBD.csv). Kept alongside
 * the simulator's own entities so the mobility predictor can compute dwell
 * times without modifying FogDevice.
 *
 * Latitude/longitude are projected to local metres so distances and coverage
 * radii are in sane units for the geometry in MobilityPredictor.
 */
public class NodePosition {

    public final int deviceId;
    public final double x;      // metres east of the reference point
    public final double y;      // metres north of the reference point
    public final double lat;
    public final double lon;

    // Reference point is configurable so the projection can follow whichever
    // topology is loaded. Node positions and mobility traces MUST share this
    // frame; a mismatch silently places every device outside every coverage
    // disk and makes all placements look like migrations.
    private static double REF_LAT = -37.8136;
    private static double REF_LON = 144.9631;
    private static final double M_PER_DEG_LAT = 111_132.0;

    public static void setReference(double lat, double lon) {
        REF_LAT = lat;
        REF_LON = lon;
    }

    private static final Map<Integer, NodePosition> REGISTRY = new HashMap<>();

    public NodePosition(int deviceId, double lat, double lon) {
        this.deviceId = deviceId;
        this.lat = lat;
        this.lon = lon;
        double mPerDegLon = 111_320.0 * Math.cos(Math.toRadians(REF_LAT));
        this.x = (lon - REF_LON) * mPerDegLon;
        this.y = (lat - REF_LAT) * M_PER_DEG_LAT;
    }

    public static void reset() { REGISTRY.clear(); }

    public static NodePosition register(int deviceId, double lat, double lon) {
        NodePosition p = new NodePosition(deviceId, lat, lon);
        REGISTRY.put(deviceId, p);
        return p;
    }

    public static NodePosition get(int deviceId) { return REGISTRY.get(deviceId); }

    public static Map<Integer, NodePosition> all() { return REGISTRY; }

    @Override
    public String toString() {
        return String.format("Node%d@(%.1f, %.1f)m", deviceId, x, y);
    }
}
