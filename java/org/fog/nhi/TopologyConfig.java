package org.fog.nhi;

/**
 * Topology configurations for the generalisation study.
 *
 * The three topologies below were chosen BEFORE any of them was run, to span
 * the regime space rather than to flatter the method. What varies is the ratio
 * of device speed to coverage size - i.e. how much mobility pressure the
 * scheduler is under - because that is the axis the NHI's mobility and
 * dynamic-weighting machinery is supposed to act on.
 *
 *   DENSE_URBAN      many small cells, slow users. Dwell times are long
 *                    relative to task length, so mobility barely matters.
 *                    Tests whether NHI does harm when its machinery is idle.
 *
 *   SPARSE_SUBURBAN  few large cells, moderate speeds, fewer candidate nodes
 *                    per decision. Tests whether NHI still discriminates when
 *                    the candidate set is small and dwell times are long.
 *                    This is the case where dynamic weighting has least to do,
 *                    so it is the most likely to show NO benefit.
 *
 *   HIGHWAY_CORRIDOR nodes strung along a line, fast vehicles, very short
 *                    dwell. Maximum mobility pressure and the hardest case for
 *                    any placement policy.
 *
 * A method that holds up across all three is under both extremes of mobility
 * pressure and both extremes of node density, which is the argument for
 * generality. Every topology run is reported regardless of outcome.
 */
public class TopologyConfig {

    public final String name;
    public final int gateways;
    public final int mobiles;
    public final double coverageRadius;   // metres
    public final double areaSize;         // metres, square side for synthetic mobility
    public final double refLat, refLon;
    public final String resourceCsv;      // null -> generate positions
    public final String sumoTrace;
    public final boolean linear;          // nodes along a corridor rather than scattered
    public final double pedSpeedMin, pedSpeedMax;
    /** >0 confines synthetic devices to a corridor, for linear topologies. */
    public double corridorWidth = 0.0;

    public TopologyConfig(String name, int gateways, int mobiles, double coverageRadius,
                          double areaSize, double refLat, double refLon,
                          String resourceCsv, String sumoTrace, boolean linear,
                          double pedSpeedMin, double pedSpeedMax) {
        this.name = name;
        this.gateways = gateways;
        this.mobiles = mobiles;
        this.coverageRadius = coverageRadius;
        this.areaSize = areaSize;
        this.refLat = refLat;
        this.refLon = refLon;
        this.resourceCsv = resourceCsv;
        this.sumoTrace = sumoTrace;
        this.linear = linear;
        this.pedSpeedMin = pedSpeedMin;
        this.pedSpeedMax = pedSpeedMax;
    }

    public static TopologyConfig byName(String n) {
        switch (n) {
            case "dense_urban":
                // Melbourne CBD: 16 gateways over ~2 km, 450 m cells, walking pace.
                return new TopologyConfig("dense_urban", 16, 60, 450, 1800,
                        -37.8136, 144.9631,
                        "./dataset/edgeResources-melbCBD.csv",
                        "./dataset/sumo_dense_urban.csv", false, 0.8, 1.8);

            case "sparse_suburban":
                // 6 gateways over ~6 km, 1200 m cells. Long dwell times, few
                // candidates per decision.
                return new TopologyConfig("sparse_suburban", 6, 40, 1200, 6000,
                        -37.8136, 144.9631,
                        null,
                        "./dataset/sumo_sparse_suburban.csv", false, 1.0, 2.0);

            case "highway_corridor":
                // 10 gateways strung along a 10 km corridor, 600 m cells,
                // motorway speeds. Maximum mobility pressure.
                return new TopologyConfig("highway_corridor", 10, 50, 600, 10000,
                        -37.8136, 144.9631,
                        null,
                        "./dataset/sumo_highway.csv", true, 1.2, 2.2) {{
                    corridorWidth = 400.0;   // devices stay near the roadway
                }};

            default:
                throw new IllegalArgumentException("unknown topology: " + n);
        }
    }

    public static final String[] ALL = {"dense_urban", "sparse_suburban", "highway_corridor"};
}
