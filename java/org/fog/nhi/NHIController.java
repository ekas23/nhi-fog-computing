package org.fog.nhi;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;
import org.fog.entities.Actuator;
import org.fog.entities.FogDevice;
import org.fog.entities.Sensor;
import org.fog.placement.Controller;
import org.fog.utils.FogEvents;

import java.util.List;

/**
 * Controller that does not terminate the JVM when the simulation ends.
 *
 * Stock iFogSim2's Controller calls System.exit(0) as the last step of
 * handling STOP_SIMULATION. That is fine for the shipped single-run examples,
 * but it makes it impossible to run several configurations in one process:
 * the first run would kill the JVM before any results could be collected or
 * the next policy could be evaluated.
 *
 * This subclass intercepts STOP_SIMULATION, stops the CloudSim event loop as
 * normal, and simply returns - so control comes back to the caller of
 * CloudSim.startSimulation() and the harness can gather metrics and move on to
 * the next run. Every other event is delegated to the stock implementation, so
 * simulation behaviour is unchanged. The stock iFogSim2 sources are left
 * untouched.
 */
public class NHIController extends Controller {

    public NHIController(String name, List<FogDevice> fogDevices,
                         List<Sensor> sensors, List<Actuator> actuators) {
        super(name, fogDevices, sensors, actuators);
    }

    @Override
    public void processEvent(SimEvent ev) {
        if (ev.getTag() == FogEvents.STOP_SIMULATION) {
            // Stock Controller calls CloudSim.stopSimulation() and then relies
            // on System.exit(0) to actually halt. stopSimulation() alone does
            // not break the event loop - sensors keep scheduling events
            // forever - so terminateSimulation() is what genuinely ends the
            // run and returns control to the harness.
            // Termination is scheduled up-front by the harness via
            // CloudSim.terminateSimulation(endTime), which unwinds the event
            // loop cleanly. Here we simply decline to call System.exit(0) so
            // control returns to the harness and the next policy can run.
            return;
        }
        super.processEvent(ev);
    }
}
