package org.fog.test.perfeval;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
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
import org.fog.placement.ModulePlacementEdgewards;
import org.fog.placement.ModulePlacementMapping;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;
import org.fog.utils.TimeKeeper;
import org.fog.utils.distribution.DeterministicDistribution;

public class SmartPrecipitationAnalyser {
    static List<FogDevice> fogDevices = new ArrayList<FogDevice>();
    static List<Sensor> sensors = new ArrayList<Sensor>();
    static List<Actuator> actuators = new ArrayList<Actuator>();

    private static boolean CLOUD = false;

    // represent the field area in which the cameras will be disposed
    static int numOfAreas = 2;
    // number of cameras per area in the field
    static int numOfCamerasPerArea = 4;
    static double CAMERA_DELAY = 2;

    public static void main(String[] args) {
        Log.printLine("Starting smart precipitation analyser system...");

        try {
            Log.disable();
            // number of cloud users
            int numOfUser = 1;
            Calendar calendar = Calendar.getInstance();
            // set trace events to false
            boolean trace_flag = true;
            CloudSim.init(numOfUser, calendar, trace_flag);
            // application identifier
            String appId = "SmartPrecipitationAnalyser";

            FogBroker broker = new FogBroker("broker");
            Application app = createApplication(appId, broker.getId());

            app.setUserId(broker.getId());
            createFogDevices(broker.getId(), appId);
            Controller controller = null;
            // initializing a module mapping
            ModuleMapping moduleMapping = ModuleMapping.createModuleMapping();
            for(FogDevice device : fogDevices){
                // if device is a Camera => 'c'
                if(device.getName().startsWith("c")){
                    // 1 instance of the Motion Detector module to each Camera
                    moduleMapping.addModuleToDevice("picture-capture", device.getName());
                }
            }
            for(FogDevice device : fogDevices){
                // if device is a fog (they start with 'a')
                if(device.getName().startsWith("a")){
                    moduleMapping.addModuleToDevice("slot-detector", device.getName());
                }
            }
            //moduleMapping.addModuleToDevice("user_interface", "cloud"); // fixing instances of User Interface module in the Cloud

            // if the mode of deployment is cloud-based
            if(CLOUD){
                // putting all instances of Object Detector and Tracker  module in the Cloud
                moduleMapping.addModuleToDevice("picture-capture", "cloud");
                moduleMapping.addModuleToDevice("slot-detector", "cloud");
            }

            controller = new Controller("master-controller", fogDevices, sensors,
                    actuators);

            if(CLOUD){
                controller.submitApplication(app, new ModulePlacementMapping(fogDevices, app, moduleMapping));
            }else {
                controller.submitApplication(app, new ModulePlacementEdgewards(fogDevices, sensors, actuators, app, moduleMapping));
            }

            TimeKeeper.getInstance().setSimulationStartTime(Calendar.getInstance().getTimeInMillis());

            CloudSim.startSimulation();

            CloudSim.stopSimulation();

            Log.printLine("Simulation finished!");
        } catch (Exception e) {
            e.printStackTrace();
            Log.printLine("Some errors happen");
        }
    }

    private static FogDevice addArea(String id, int userId, String appId, int parentId){
        FogDevice router = createFogDevice("a-"+id, 2800, 4000, 1000, 10000, 2, 0.0, 107.339, 83.4333);
        fogDevices.add(router);
        // latency of connection between router and proxy server is 2 ms
        router.setUplinkLatency(2);
        for(int i=0;i<numOfCamerasPerArea;i++){
            String mobileId = id+"-"+i;
            FogDevice camera = addCamera(mobileId, userId, appId, router.getId());
            // latency of connection between camera and router is 2 ms
            camera.setUplinkLatency(2);
            fogDevices.add(camera);
        }
        router.setParentId(parentId);
        return router;
    }


    private static void createFogDevices(int userId, String appId) {
        FogDevice cloud = createFogDevice("cloud", 44800, 40000, 100, 10000, 0, 0.01, 16*103, 16*83.25);
        cloud.setParentId(-1);
        fogDevices.add(cloud);
        FogDevice proxy = createFogDevice("proxy-server", 2800, 4000, 10000, 10000, 1, 0.0, 107.339, 83.4333);
        proxy.setParentId(cloud.getId());
        // latency of connection between proxy server and cloud is 100 ms
        proxy.setUplinkLatency(100);
        fogDevices.add(proxy);
        for(int i=0;i<numOfAreas;i++){
            addArea(i+"", userId, appId, proxy.getId());
        }
    }

    private static FogDevice addCamera(String id, int userId, String appId, int parentId){
        FogDevice camera = createFogDevice("c-"+id, 500, 1000, 10000, 10000, 3, 0, 87.53, 82.44);
        camera.setParentId(parentId);
        Sensor sensor = new Sensor("s-"+id, "CAMERA", userId, appId, new DeterministicDistribution(CAMERA_DELAY));
        sensors.add(sensor);
        Actuator ptz = new Actuator("ptz-"+id, userId, appId, "PTZ_CONTROL");
        actuators.add(ptz);
        sensor.setGatewayDeviceId(camera.getId());
        // latency of connection between camera (sensor) and the parent Smart Camera is 1 ms
        sensor.setLatency(1.0);
        ptz.setGatewayDeviceId(parentId);
        // latency of connection between PTZ Control and the parent Smart Camera is 1 ms
        ptz.setLatency(1.0);
        return camera;
    }

    /**
     * Creates a vanilla fog device
     * @param nodeName name of the device to be used in simulation
     * @param mips MIPS
     * @param ram RAM
     * @param upBw uplink bandwidth
     * @param downBw downlink bandwidth
     * @param level hierarchy level of the device
     * @param ratePerMips cost rate per MIPS used
     * @param busyPower
     * @param idlePower
     * @return
     */
    private static FogDevice createFogDevice(String nodeName, long mips,
                                             int ram, long upBw, long downBw, int level, double ratePerMips, double busyPower, double idlePower) {

        List<Pe> peList = new ArrayList<Pe>();

        // need to store Processing Element id and MIPS Rating
        peList.add(new Pe(0, new PeProvisionerOverbooking(mips)));

        int hostId = FogUtils.generateEntityId();
        long storage = 1000000; // host storage
        int bw = 10000;

        PowerHost host = new PowerHost(
                hostId,
                new RamProvisionerSimple(ram),
                new BwProvisionerOverbooking(bw),
                storage,
                peList,
                new StreamOperatorScheduler(peList),
                new FogLinearPowerModel(busyPower, idlePower)
        );
        List<Host> hostList = new ArrayList<Host>();
        hostList.add(host);
        String arch = "x86"; // system architecture
        String os = "Linux"; // operating system
        String vmm = "Xen";
        double time_zone = 10.0; // time zone this resource located
        double cost = 3.0; // the cost of using processing in this resource
        double costPerMem = 0.05; // the cost of using memory in this resource
        double costPerStorage = 0.001; // the cost of using storage in this
        // resource
        double costPerBw = 0.0; // the cost of using bw in this resource
        LinkedList<Storage> storageList = new LinkedList<Storage>(); // we are not adding SAN
        // devices by now
        FogDeviceCharacteristics characteristics = new FogDeviceCharacteristics(
                arch, os, vmm, host, time_zone, cost, costPerMem,
                costPerStorage, costPerBw);
        FogDevice fogdevice = null;
        try {
            fogdevice = new FogDevice(nodeName, characteristics,
                    new AppModuleAllocationPolicy(hostList), storageList, 10, upBw, downBw, 0, ratePerMips);
        } catch (Exception e) {
            e.printStackTrace();
        }
        fogdevice.setLevel(level);
        return fogdevice;
    }

    /**
     * Function to create the Intelligent Surveillance application in the DDF model.
     * @param appId unique identifier of the application
     * @param userId identifier of the user of the application
     */
    @SuppressWarnings({"serial" })
    private static Application createApplication(String appId, int userId){

        Application application = Application.createApplication(appId, userId);
        // Adding modules (vertices) to the application model (directed graph)
        application.addAppModule("picture-capture", 10);
        application.addAppModule("slot-detector", 10);

        //Connecting the application modules (vertices) in the application model (directed graph) with edges
        application.addAppEdge("CAMERA", "picture-capture", 1000, 500, "CAMERA", Tuple.UP, AppEdge.SENSOR); // adding edge from CAMERA (sensor) to Motion Detector module carrying tuples of type CAMERA
        application.addAppEdge("picture-capture", "slot-detector",
                1000, 500, "slots",Tuple.UP, AppEdge.MODULE);
        // adding edge from Slot Detector to PTZ CONTROL (actuator)
        application.addAppEdge("slot-detector", "PTZ_CONTROL", 100,
                28, 100, "PTZ_PARAMS",
                Tuple.UP, AppEdge.ACTUATOR);
        application.addTupleMapping("picture-capture", "CAMERA", "slots",
                new FractionalSelectivity(1.0));
        application.addTupleMapping("slot-detector", "slots",
                "PTZ_PARAMS", new FractionalSelectivity(1.0));
        final AppLoop loop1 = new AppLoop(new ArrayList<String>()
        {{add("CAMERA");
            add("picture-capture");add("slot-detector");
            add("PTZ_CONTROL");}});
        List<AppLoop> loops = new ArrayList<AppLoop>(){{add(loop1);}};
        application.setLoops(loops);
        return application;
    }
}