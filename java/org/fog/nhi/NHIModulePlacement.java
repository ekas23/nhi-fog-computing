package org.fog.nhi;

import org.fog.application.AppModule;
import org.fog.application.Application;
import org.fog.entities.Actuator;
import org.fog.entities.FogDevice;
import org.fog.entities.Sensor;
import org.fog.placement.ModuleMapping;
import org.fog.placement.ModulePlacement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Resource-allocation policy for iFogSim2 driven by the Adaptive Node Health
 * Index.
 *
 * This is the piece that replaces "put the module on the nearest/least-loaded
 * gateway" with "put it on the healthiest node overall". For every mobile
 * client that needs its processing module placed, the policy builds the
 * candidate set of fog nodes, gathers the eight NHI metrics for each -
 * including SoH from the LSTM service and predicted dwell time from the
 * mobility predictor - and asks NHIScorer to choose.
 *
 * The same class implements every baseline; the Policy enum switches the
 * selection rule only, so all six policies run on an identical topology,
 * workload and simulator. That is what makes the comparison fair.
 */
public class NHIModulePlacement extends ModulePlacement {

    private final List<Sensor> sensors;
    private final List<Actuator> actuators;
    private final ModuleMapping moduleMapping;
    private final NHIScorer.Policy policy;
    private final MobilityPredictor mobility;
    private final double taskDuration;
    private final double simTime;

    /** Names of modules that must stay in the cloud. */
    private final List<String> cloudOnlyModules = new ArrayList<>();

    // --- outcome bookkeeping for the results table -------------------------
    public int decisions = 0;
    public int migrationsPredicted = 0;   // placements whose dwell < task length
    public int cloudFallbacks = 0;
    public final Map<Integer, Integer> placementsPerDevice = new HashMap<>();

    public NHIModulePlacement(List<FogDevice> fogDevices, List<Sensor> sensors,
                              List<Actuator> actuators, Application application,
                              ModuleMapping moduleMapping, NHIScorer.Policy policy,
                              MobilityPredictor mobility, double taskDuration,
                              double simTime, List<String> cloudOnly) {
        this.setFogDevices(fogDevices);
        this.setApplication(application);
        this.setModuleToDeviceMap(new HashMap<String, List<Integer>>());
        this.setDeviceToModuleMap(new HashMap<Integer, List<AppModule>>());
        this.setModuleInstanceCountMap(new HashMap<Integer, Map<String, Integer>>());
        for (FogDevice device : fogDevices)
            getModuleInstanceCountMap().put(device.getId(), new HashMap<String, Integer>());

        this.sensors = sensors;
        this.actuators = actuators;
        this.moduleMapping = moduleMapping;
        this.policy = policy;
        this.mobility = mobility;
        this.taskDuration = taskDuration;
        this.simTime = simTime;
        if (cloudOnly != null) this.cloudOnlyModules.addAll(cloudOnly);

        mapModules();
    }

    private FogDevice getCloud() {
        for (FogDevice d : getFogDevices())
            if (d.getName().equals("cloud")) return d;
        return getFogDevices().get(0);
    }

    /** Fog nodes eligible to host client processing (the gateway tier). */
    private List<FogDevice> candidateNodes() {
        List<FogDevice> out = new ArrayList<>();
        for (FogDevice d : getFogDevices())
            if (d.getName().startsWith("gw-")) out.add(d);
        return out;
    }

    /** Mobile client devices that generate work. */
    private List<FogDevice> mobileDevices() {
        List<FogDevice> out = new ArrayList<>();
        for (FogDevice d : getFogDevices())
            if (d.getName().startsWith("mob-")) out.add(d);
        return out;
    }

    @Override
    protected void mapModules() {
        // 1. honour fixed mappings (cloud-pinned modules)
        for (String deviceName : moduleMapping.getModuleMapping().keySet()) {
            for (String moduleName : moduleMapping.getModuleMapping().get(deviceName)) {
                FogDevice device = getDeviceByName(deviceName);
                if (device == null) continue;
                createModuleInstanceOnDevice(getApplication().getModuleByName(moduleName), device);
            }
        }

        // 2. NHI-driven placement of the per-user processing module
        List<FogDevice> candidates = candidateNodes();
        AppModule processing = getApplication().getModuleByName("processing");
        AppModule client = getApplication().getModuleByName("client");

        for (FogDevice mob : mobileDevices()) {
            // the client module always sits on the user's own device
            if (client != null) createModuleInstanceOnDevice(client, mob);
            if (processing == null || candidates.isEmpty()) continue;

            List<NHIScorer.Candidate> cands = new ArrayList<>();
            for (FogDevice node : candidates) {
                NodeHealthState hs = NodeHealthState.get(node.getId());
                NHIScorer.Candidate c = new NHIScorer.Candidate(node.getId());

                double totalMips = node.getHost().getTotalMips();
                double usedMips = 0;
                for (AppModule m : node.getHost().getVmList().stream()
                        .filter(v -> v instanceof AppModule)
                        .map(v -> (AppModule) v).toList()) {
                    usedMips += m.getMips();
                }
                c.cpuFree = Math.max(0, totalMips - usedMips);
                c.memFree = node.getHost().getRamProvisioner().getAvailableRam();
                c.queueLen = node.getNorthTupleQueue().size() + node.getSouthTupleQueue().size();
                c.latency = Math.max(0.1, node.getUplinkLatency());

                if (hs != null) {
                    c.soc = hs.soc;
                    c.soh = hs.soh;
                    c.reliability = hs.reliability;
                } else {
                    c.soc = 1.0; c.soh = 1.0; c.reliability = 1.0;
                }

                c.tasksAlready = (hs != null) ? hs.tasksAssigned : 0;
                c.canHost = c.cpuFree >= processing.getMips()
                            && c.memFree >= processing.getRam();

                NodePosition np = NodePosition.get(node.getId());
                if (mobility != null && np != null) {
                    c.dwellTime = mobility.predictDwell(mob.getId(), simTime, np.x, np.y);
                } else {
                    c.dwellTime = Double.POSITIVE_INFINITY;
                }
                cands.add(c);
            }

            // Feasibility filter: a node that cannot fit the module is not a
            // candidate at all. Without this a policy can "win" on health or
            // latency by selecting a node that then rejects the module,
            // silently pushing the workload to the cloud. Applied identically
            // to every policy so the comparison stays fair.
            List<NHIScorer.Candidate> feasible = new ArrayList<>();
            for (NHIScorer.Candidate c : cands) if (c.canHost) feasible.add(c);

            int chosen = feasible.isEmpty()
                    ? -1
                    : NHIScorer.select(feasible, policy, taskDuration);
            decisions++;

            if (chosen < 0) {
                createModuleInstanceOnDevice(processing, getCloud());
                cloudFallbacks++;
                continue;
            }

            FogDevice target = getDeviceById(chosen);
            boolean ok = createModuleInstanceOnDevice(processing, target);
            if (!ok) {
                // node could not host it after all -> cloud
                createModuleInstanceOnDevice(processing, getCloud());
                cloudFallbacks++;
                continue;
            }

            placementsPerDevice.merge(chosen, 1, Integer::sum);
            NodeHealthState hs = NodeHealthState.get(chosen);
            if (hs != null) hs.recordAssignment();

            // a placement whose predicted dwell is shorter than the task will
            // require a service migration
            for (NHIScorer.Candidate c : feasible) {
                if (c.deviceId == chosen) {
                    boolean willMigrate = c.dwellTime < taskDuration;
                    if (willMigrate) {
                        migrationsPredicted++;
                        if (hs != null) hs.recordMigration();
                    }
                    if (hs != null) hs.recordOutcome(!willMigrate);
                    break;
                }
            }
        }
    }

    public NHIScorer.Policy getPolicy() { return policy; }
}
