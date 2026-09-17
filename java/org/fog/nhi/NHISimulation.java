package org.fog.nhi;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.sdn.overbooking.BwProvisionerOverbooking;
import org.cloudbus.cloudsim.sdn.overbooking.PeProvisionerOverbooking;
import org.fog.application.AppEdge;
import org.fog.application.AppLoop;
import org.fog.application.Application;
import org.fog.application.selectivity.FractionalSelectivity;
import org.fog.entities.Actuator;
import org.fog.entities.FogBroker;
import org.fog.entities.FogDevice;
import org.fog.entities.FogDeviceCharacteristics;
import org.fog.entities.Sensor;
import org.fog.entities.Tuple;
import org.fog.placement.Controller;
import org.fog.placement.ModuleMapping;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;
import org.fog.utils.TimeKeeper;
import org.fog.utils.distribution.DeterministicDistribution;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Evaluation harness for the Adaptive Node Health Index in iFogSim2.
 *
 * Runs every placement policy over identical topology + workload for each
 * mobility scenario, and writes one row of metrics per (scenario, policy) to
 * CSV.
 *
 * Topology: cloud -> proxy -> gateway fog nodes (battery-powered, positioned
 * from the Melbourne CBD edge-resource dataset) -> mobile IoT devices.
 *
 * Scenarios:
 *   static         devices do not move
 *   pedestrian     slow synthetic movement
 *   vehicular      real SUMO road-network traces
 *   degraded_fleet pedestrian movement, but half the fleet starts pre-aged
 */
public class NHISimulation {

    static List<FogDevice> fogDevices = new ArrayList<>();
    static List<Sensor> sensors = new ArrayList<>();
    static List<Actuator> actuators = new ArrayList<>();

    static TopologyConfig TOPO = TopologyConfig.byName("dense_urban");
    static int NUM_GATEWAYS = TOPO.gateways;
    static int NUM_MOBILES = TOPO.mobiles;
    static double COVERAGE_RADIUS = TOPO.coverageRadius;
    static double TASK_DURATION = 6.0;       // seconds of work per task
    static double SIM_SAMPLE_TIME = 30.0;

    static String DATASET = "./dataset/edgeResources-melbCBD.csv";
    static String SUMO_TRACE = TOPO.sumoTrace;

    public static void main(String[] args) {
        // usage: NHISimulation <out.csv> [scenario] [policy]
        // With no scenario/policy the full sweep runs. Results are APPENDED
        // after every run so a long sweep can be executed in batches without
        // losing completed work.
        String outPath = args.length > 0 ? args[0] : "./results_nhi.csv";
        String topoName = args.length > 5 ? args[5] : "dense_urban";
        TOPO = TopologyConfig.byName(topoName);
        NUM_GATEWAYS = TOPO.gateways;
        NUM_MOBILES = TOPO.mobiles;
        COVERAGE_RADIUS = TOPO.coverageRadius;
        SUMO_TRACE = TOPO.sumoTrace;
        DATASET = TOPO.resourceCsv;
        NodePosition.setReference(TOPO.refLat, TOPO.refLon);
        System.out.println("Topology: " + TOPO.name + " | gateways=" + NUM_GATEWAYS
                + " mobiles=" + NUM_MOBILES + " coverage=" + COVERAGE_RADIUS + "m");
        String onlyScenario = args.length > 1 && !args[1].equals("-") ? args[1] : null;
        String onlyPolicy = args.length > 2 && !args[2].equals("-") ? args[2] : null;
        int seedFrom = args.length > 3 ? Integer.parseInt(args[3]) : 1;
        int seedTo = args.length > 4 ? Integer.parseInt(args[4]) : seedFrom;

        String[] scenarios = (onlyScenario == null)
                ? new String[]{"static", "pedestrian", "vehicular", "degraded_fleet"}
                : new String[]{onlyScenario};
        List<NHIScorer.Policy> policies = new ArrayList<>();
        if (onlyPolicy == null) {
            policies.addAll(Arrays.asList(NHIScorer.Policy.values()));
        } else {
            policies.add(NHIScorer.Policy.valueOf(onlyPolicy));
        }

        if (!SoHClient.probe()) {
            System.out.println("ABORT: SoH service unreachable on 127.0.0.1:5599.\n"
                + "Start it first:  python3 nhi_project/src/soh_service.py\n"
                + "Refusing to run - results would not be LSTM-derived.");
            return;
        }
        System.out.println("SoH service reachable - proceeding.\n");

        java.io.File f = new java.io.File(outPath);
        boolean needHeader = !f.exists() || f.length() == 0;

        for (int seed = seedFrom; seed <= seedTo; seed++) {
        for (String scenario : scenarios) {
            for (NHIScorer.Policy p : policies) {
                String row;
                try {
                    row = runOne(scenario, p, seed);
                    System.out.println("DONE  seed=" + seed + " " + scenario + " / " + p);
                } catch (Exception e) {
                    System.out.println("FAIL  seed=" + seed + " " + scenario + " / " + p + " : " + e);
                    continue;
                }
                try (PrintWriter pw = new PrintWriter(new FileWriter(outPath, true))) {
                    if (needHeader) {
                        pw.println("topology,scenario,seed,policy,decisions,avg_latency_ms,total_energy,"
                                 + "network_usage,migrations,cloud_fallbacks,mean_soh,min_soh,"
                                 + "soh_spread,load_imbalance,mean_reliability,lstm_ok");
                        needHeader = false;
                    }
                    pw.println(row);
                } catch (Exception e) {
                    System.out.println("  [warn] could not append row: " + e);
                }
            }
        }
        }
        System.out.println("Batch complete -> " + outPath);
    }

    static String runOne(String scenario, NHIScorer.Policy policy, int seed) throws Exception {
        // ---- reset all global state between runs --------------------------
        fogDevices = new ArrayList<>();
        sensors = new ArrayList<>();
        actuators = new ArrayList<>();
        NodeHealthState.reset();
        resetNetworkUsage();
        NodePosition.reset();
        NHIScorer.resetRoundRobin();
        SoHClient.resetStats();
        TimeKeeper.getInstance().getLoopIdToCurrentAverage().clear();

        // Shorter simulated horizon keeps the 24-run sweep tractable while
        // still generating thousands of tuples per run.
        org.fog.utils.Config.MAX_SIMULATION_TIME = 1000;
        org.cloudbus.cloudsim.Log.disable();
        CloudSim.init(1, Calendar.getInstance(), false);

        String appId = "nhi_app";
        FogBroker broker = new FogBroker("broker");
        Application application = createApplication(appId, broker.getId());
        application.setUserId(broker.getId());

        boolean degraded = scenario.equals("degraded_fleet");
        Random rng = new Random(seed);

        createTopology(broker.getId(), appId, degraded, rng);

        // ---- mobility ------------------------------------------------------
        MobilityPredictor mobility = new MobilityPredictor(COVERAGE_RADIUS, seed);
        List<Integer> mobileIds = new ArrayList<>();
        for (FogDevice d : fogDevices)
            if (d.getName().startsWith("mob-")) mobileIds.add(d.getId());

        String mobilitySource;
        switch (scenario) {
            case "static":
                mobility.generateSynthetic(mobileIds, 0.0, 0.0, TOPO.areaSize, 400, TOPO.corridorWidth);
                mobilitySource = "synthetic-static";
                break;
            case "vehicular":
                int loaded = 0;
                try {
                    loaded = mobility.loadSumoTrace(SUMO_TRACE, mobileIds);
                } catch (Exception e) {
                    loaded = 0;
                }
                if (loaded == 0) {
                    // No SUMO trace available -> say so loudly rather than
                    // silently reporting a "vehicular" result that is not.
                    throw new IllegalStateException(
                        "vehicular scenario requires SUMO trace at " + SUMO_TRACE
                        + " (run tools/gen_sumo_trace.sh first)");
                }
                mobilitySource = "sumo(" + loaded + " vehicles)";
                break;
            case "degraded_fleet":
            case "pedestrian":
            default:
                mobility.generateSynthetic(mobileIds, TOPO.pedSpeedMin, TOPO.pedSpeedMax, TOPO.areaSize, 400, TOPO.corridorWidth);
                mobilitySource = "synthetic-pedestrian";
                break;
        }

        // ---- seed SoH from the LSTM service -------------------------------
        for (Map.Entry<Integer, NodeHealthState> e : NodeHealthState.all().entrySet()) {
            NodeHealthState hs = e.getValue();
            hs.soh = SoHClient.estimateSoH(hs.cellId, hs.cycleIndex);
        }

        // ---- placement -----------------------------------------------------
        ModuleMapping moduleMapping = ModuleMapping.createModuleMapping();
        moduleMapping.addModuleToDevice("storage", "cloud");

        NHIModulePlacement placement = new NHIModulePlacement(
                fogDevices, sensors, actuators, application, moduleMapping,
                policy, mobility, TASK_DURATION, SIM_SAMPLE_TIME,
                Arrays.asList("storage"));

        NHIController controller = new NHIController("master-controller", fogDevices, sensors, actuators);
        controller.submitApplication(application, 0, placement);

        TimeKeeper.getInstance().setSimulationStartTime(Calendar.getInstance().getTimeInMillis());
        // Schedule a clean end-of-run. Without this the sensors would keep
        // scheduling events indefinitely once Controller's System.exit(0) is
        // suppressed.
        CloudSim.terminateSimulation(org.fog.utils.Config.MAX_SIMULATION_TIME);
        CloudSim.startSimulation();
        CloudSim.stopSimulation();

        // ---- collect metrics ----------------------------------------------
        double totalEnergy = 0;
        for (FogDevice d : fogDevices) totalEnergy += d.getEnergyConsumption();

        // advance battery state from the energy each gateway actually burned,
        // then re-estimate SoH from the LSTM
        for (FogDevice d : fogDevices) {
            NodeHealthState hs = NodeHealthState.get(d.getId());
            if (hs == null || !hs.batteryPowered) continue;
            if (hs.updateFromEnergy(d.getEnergyConsumption())) {
                hs.soh = SoHClient.estimateSoH(hs.cellId, hs.cycleIndex);
            }
        }

        double avgLatency = 0;
        int loops = 0;
        for (Integer loopId : TimeKeeper.getInstance().getLoopIdToCurrentAverage().keySet()) {
            Double v = TimeKeeper.getInstance().getLoopIdToCurrentAverage().get(loopId);
            if (v != null && !v.isNaN()) { avgLatency += v; loops++; }
        }
        avgLatency = loops > 0 ? avgLatency / loops : Double.NaN;

        List<Double> sohs = new ArrayList<>();
        List<Double> rels = new ArrayList<>();
        for (NodeHealthState hs : NodeHealthState.all().values()) {
            if (!hs.batteryPowered) continue;
            sohs.add(hs.soh);
            rels.add(hs.reliability);
        }
        double meanSoh = mean(sohs), minSoh = min(sohs), sohSpread = stddev(sohs);
        double meanRel = mean(rels);

        List<Double> loads = new ArrayList<>();
        for (NodeHealthState hs : NodeHealthState.all().values())
            if (hs.batteryPowered) loads.add((double) hs.tasksAssigned);
        double loadImbalance = stddev(loads);

        double netUsage = org.fog.utils.NetworkUsageMonitor.getNetworkUsage();

        System.out.println("  [" + scenario + "/" + policy + "] mobility=" + mobilitySource
                + " | " + SoHClient.statusLine());

        return String.format("%s,%s,%d,%s,%d,%.4f,%.2f,%.2f,%d,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%s",
                TOPO.name, scenario, seed, policy, placement.decisions, avgLatency, totalEnergy, netUsage,
                placement.migrationsPredicted, placement.cloudFallbacks,
                meanSoh, minSoh, sohSpread, loadImbalance, meanRel,
                SoHClient.usedRealLSTM() ? "yes" : "NO-FALLBACK");
    }

    /**
     * iFogSim2's NetworkUsageMonitor keeps a private static running total and
     * exposes no reset. Without zeroing it between runs every row would report
     * the cumulative usage of all earlier runs, not its own.
     */
    static void resetNetworkUsage() {
        try {
            java.lang.reflect.Field f = org.fog.utils.NetworkUsageMonitor.class
                    .getDeclaredField("networkUsage");
            f.setAccessible(true);
            f.setDouble(null, 0.0);
        } catch (Exception e) {
            System.out.println("  [warn] could not reset NetworkUsageMonitor: " + e);
        }
    }

    // ------------------------------------------------------------------ topology

    static void createTopology(int userId, String appId, boolean degraded, Random rng)
            throws Exception {
        FogDevice cloud = createFogDevice("cloud", 44800, 40000, 100, 10000, 0, 0.01,
                16 * 103, 16 * 83.25);
        cloud.setParentId(-1);
        fogDevices.add(cloud);

        FogDevice proxy = createFogDevice("proxy", 2800, 4000, 10000, 10000, 1, 0.0,
                107.339, 83.4333);
        proxy.setParentId(cloud.getId());
        proxy.setUplinkLatency(100);
        fogDevices.add(proxy);

        // gateway fog nodes, positioned from the Melbourne CBD dataset
        List<double[]> coords = (DATASET != null)
                ? readEdgeResourceCoords(DATASET, NUM_GATEWAYS)
                : generateCoords(TOPO, NUM_GATEWAYS);
        for (int i = 0; i < NUM_GATEWAYS; i++) {
            FogDevice gw = createFogDevice("gw-" + i, 2800, 4000, 10000, 10000, 2, 0.0,
                    107.339, 83.4333);
            gw.setParentId(proxy.getId());
            gw.setUplinkLatency(4 + rng.nextInt(12));
            fogDevices.add(gw);

            double[] c = coords.get(i % coords.size());
            NodePosition.register(gw.getId(), c[0], c[1]);
            NodeHealthState.register(gw.getId(), true, degraded, rng);
        }

        // mobile IoT devices
        List<FogDevice> gateways = new ArrayList<>();
        for (FogDevice d : fogDevices) if (d.getName().startsWith("gw-")) gateways.add(d);

        for (int i = 0; i < NUM_MOBILES; i++) {
            FogDevice mob = createFogDevice("mob-" + i, 1000, 1000, 10000, 270, 3, 0,
                    87.53, 82.44);
            FogDevice parent = gateways.get(i % gateways.size());
            mob.setParentId(parent.getId());
            mob.setUplinkLatency(2);
            fogDevices.add(mob);
            NodeHealthState.register(mob.getId(), false, false, rng);

            Sensor sensor = new Sensor("s-" + i, "IOT_SENSE", userId, appId,
                    new DeterministicDistribution(6.0));
            sensors.add(sensor);
            sensor.setGatewayDeviceId(mob.getId());
            sensor.setLatency(1.0);

            Actuator act = new Actuator("a-" + i, userId, appId, "IOT_DISPLAY");
            actuators.add(act);
            act.setGatewayDeviceId(mob.getId());
            act.setLatency(1.0);
        }
    }

    /**
     * Lay out gateway positions for topologies with no resource CSV.
     * Linear topologies string nodes evenly along the corridor the SUMO
     * network occupies; others use a jittered grid over the area. Positions
     * are emitted as lat/lon in the topology's own reference frame so they
     * project onto the same metric frame the mobility traces use.
     */
    static List<double[]> generateCoords(TopologyConfig topo, int want) {
        List<double[]> out = new ArrayList<>();
        double mPerDegLat = 111_132.0;
        double mPerDegLon = 111_320.0 * Math.cos(Math.toRadians(topo.refLat));
        if (topo.linear) {
            // evenly spaced along x, slight alternating y offset
            for (int i = 0; i < want; i++) {
                double x = -topo.areaSize / 2 + (i + 0.5) * (topo.areaSize / want);
                double y = (i % 2 == 0 ? 60 : -60);
                out.add(new double[]{topo.refLat + y / mPerDegLat,
                                      topo.refLon + x / mPerDegLon});
            }
        } else {
            int cols = (int) Math.ceil(Math.sqrt(want));
            Random r = new Random(99);
            for (int i = 0; i < want; i++) {
                int cx = i % cols, cy = i / cols;
                double step = topo.areaSize / cols;
                double x = -topo.areaSize / 2 + (cx + 0.5) * step + (r.nextDouble() - 0.5) * step * 0.3;
                double y = -topo.areaSize / 2 + (cy + 0.5) * step + (r.nextDouble() - 0.5) * step * 0.3;
                out.add(new double[]{topo.refLat + y / mPerDegLat,
                                      topo.refLon + x / mPerDegLon});
            }
        }
        return out;
    }

    static List<double[]> readEdgeResourceCoords(String path, int want) {
        List<double[]> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line = br.readLine(); // header
            while ((line = br.readLine()) != null && out.size() < want * 4) {
                String[] p = line.split(",");
                if (p.length < 5) continue;
                // use gateway-level rows (Level >= 2) where available
                try {
                    double lat = Double.parseDouble(p[1].trim());
                    double lon = Double.parseDouble(p[2].trim());
                    out.add(new double[]{lat, lon});
                } catch (NumberFormatException ignored) { }
            }
        } catch (Exception e) {
            System.out.println("  [warn] could not read " + path + " (" + e
                    + ") - falling back to synthetic node positions");
        }
        if (out.isEmpty()) {
            Random r = new Random(1);
            for (int i = 0; i < want; i++)
                out.add(new double[]{-37.8136 + (r.nextDouble() - 0.5) * 0.02,
                                      144.9631 + (r.nextDouble() - 0.5) * 0.02});
        }
        return out;
    }

    static FogDevice createFogDevice(String nodeName, long mips, int ram, long upBw,
                                     long downBw, int level, double ratePerMips,
                                     double busyPower, double idlePower) throws Exception {
        List<Pe> peList = new ArrayList<>();
        peList.add(new Pe(0, new PeProvisionerOverbooking(mips)));

        int hostId = FogUtils.generateEntityId();
        long storage = 1000000;
        int bw = 10000;

        PowerHost host = new PowerHost(hostId, new RamProvisionerSimple(ram),
                new BwProvisionerOverbooking(bw), storage, peList,
                new StreamOperatorScheduler(peList),
                new FogLinearPowerModel(busyPower, idlePower));

        List<Host> hostList = new ArrayList<>();
        hostList.add(host);

        FogDeviceCharacteristics characteristics = new FogDeviceCharacteristics(
                "x86", "Linux", "Xen", host, 10.0, 3.0, 0.05, 0.001, 0.0);

        FogDevice fd = new FogDevice(nodeName, characteristics,
                new AppModuleAllocationPolicy(hostList), new LinkedList<Storage>(),
                10, upBw, downBw, 0, ratePerMips);
        fd.setLevel(level);
        return fd;
    }

    // ---------------------------------------------------------------- application

    static Application createApplication(String appId, int userId) {
        Application application = Application.createApplication(appId, userId);

        //                        name,         ram, mips, size
        application.addAppModule("client",      128,  100, 10000);
        application.addAppModule("processing",  256,  320, 10000);
        application.addAppModule("storage",     256,  200, 10000);

        application.addAppEdge("IOT_SENSE", "client", 100, 500, "IOT_SENSE",
                Tuple.UP, AppEdge.SENSOR);
        application.addAppEdge("client", "processing", 400, 500, "RAW",
                Tuple.UP, AppEdge.MODULE);
        application.addAppEdge("processing", "storage", 200, 300, "SUMMARY",
                Tuple.UP, AppEdge.MODULE);
        application.addAppEdge("processing", "client", 100, 200, "RESULT",
                Tuple.DOWN, AppEdge.MODULE);
        application.addAppEdge("client", "IOT_DISPLAY", 200, 100, "ACTION",
                Tuple.DOWN, AppEdge.ACTUATOR);

        application.addTupleMapping("client", "IOT_SENSE", "RAW",
                new FractionalSelectivity(1.0));
        application.addTupleMapping("processing", "RAW", "SUMMARY",
                new FractionalSelectivity(0.4));
        application.addTupleMapping("processing", "RAW", "RESULT",
                new FractionalSelectivity(1.0));
        application.addTupleMapping("client", "RESULT", "ACTION",
                new FractionalSelectivity(1.0));

        final AppLoop loop = new AppLoop(new ArrayList<String>() {{
            add("IOT_SENSE"); add("client"); add("processing");
            add("client"); add("IOT_DISPLAY");
        }});
        List<AppLoop> loops = new ArrayList<AppLoop>() {{ add(loop); }};
        application.setLoops(loops);
        return application;
    }

    // ------------------------------------------------------------------- stats

    static double mean(List<Double> v) {
        if (v.isEmpty()) return Double.NaN;
        double s = 0; for (double x : v) s += x; return s / v.size();
    }

    static double min(List<Double> v) {
        if (v.isEmpty()) return Double.NaN;
        double m = Double.MAX_VALUE; for (double x : v) m = Math.min(m, x); return m;
    }

    static double stddev(List<Double> v) {
        if (v.isEmpty()) return Double.NaN;
        double m = mean(v), s = 0;
        for (double x : v) s += (x - m) * (x - m);
        return Math.sqrt(s / v.size());
    }
}
