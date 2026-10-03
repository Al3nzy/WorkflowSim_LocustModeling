/**
 * Copyright 2025-2026 SDU University, Kazakhstan
 * @author Dr. Mohammed Alaa Ala'anzy
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.workflowsim.examples.planning;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.HarddriveStorage;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.VmAllocationPolicySimple;
import org.cloudbus.cloudsim.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.WorkflowDatacenter;
import org.workflowsim.WorkflowEngine;
import org.workflowsim.WorkflowPlanner;
import org.workflowsim.examples.WorkflowSimBasicExample1;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * Standalone example for LIWSAPlanningAlgorithm (Density-Adaptive
 * Multi-Objective Locust-Inspired Workflow Scheduling Algorithm).
 *
 * Structure mirrors HEFTPlanningAlgorithmExample1 exactly so it drops
 * into the same examples/planning/ package and runs the same way.
 *
 * All user-configurable settings are in the CONFIGURATION block at the
 * top of main(). Edit that block; nothing else needs to change.
 *
 * Prerequisites:
 *   1. Add LIWSA to the PlanningAlgorithm enum in Parameters.java
 *   2. Add a LIWSA case to WorkflowPlanner.getPlanningAlgorithm()
 *   3. Place LIWSAPlanningAlgorithm.java in sources/org/workflowsim/planning/
 *   (See SOURCE_PATCHES.txt for the exact lines to change.)
 */
public class LIWSAPlanningAlgorithmExample extends WorkflowSimBasicExample1 {

    // ---------------------------------------------------------------
    // VM type definitions used by createHeterogeneousVMs().
    // Each row: { mips, bandwidthMbitS, costPerSec, ram_MB,
    //             storage_MB, count }
    // These match the paper's cloud infrastructure description.
    // ---------------------------------------------------------------
    private static final double[][] VM_TYPES = {
        //  mips    bw     cost   ram    storage  count
        { 250.0, 160.0, 0.15, 512, 10000, 4},  // Micro  x4
        { 500.0, 160.0, 0.30, 512, 10000, 4},  // Small  x4
        {1000.0, 160.0, 0.60, 512, 10000, 4},  // Medium x4
        {2000.0, 160.0, 0.90, 512, 10000, 4},  // Large  x4
    };

    /**
     * Creates the heterogeneous VM pool from VM_TYPES, setting per-second
     * CPU cost explicitly so that LIWSAPlanningAlgorithm.getCost() returns
     * the correct value during Pareto fitness evaluation.
     */
    protected static List<CondorVM> createHeterogeneousVMs(int userId) {
        LinkedList<CondorVM> list = new LinkedList<>();
        int vmId = 0;
        for (double[] type : VM_TYPES) {
            double mips        = type[0];
            long   bw          = (long) type[1];  // Mbit/s
            double cost        = type[2];          // per second of CPU use
            int    ram         = (int)  type[3];   // MB
            long   storage     = (long) type[4];   // MB
            int    count       = (int)  type[5];
            for (int k = 0; k < count; k++) {
                // Full CondorVM constructor: sets getCost() to the per-second rate.
                list.add(new CondorVM(
                    vmId++, userId,
                    mips,
                    /*numberOfPes=*/ 1,
                    ram,
                    bw,
                    storage,
                    "Xen",
                    cost,          // getCost()   -> cost per CPU-second
                    0.0,           // costPerMem
                    0.0,           // costPerStorage
                    0.0,           // costPerBW
                    new CloudletSchedulerSpaceShared()
                ));
            }
        }
        return list;
    }

    /**
     * Datacenter sized to accommodate the VM pool: one host per four VMs,
     * each with 16 CPUs at 2000 MIPS and 8 GB RAM. Bandwidth 100 Gbit/s
     * so scheduling decisions (not host network capacity) dominate.
     */
    protected static WorkflowDatacenter createHeterogeneousDatacenter(String name) {
        // Total VMs from VM_TYPES
        int totalVMs = 0;
        for (double[] t : VM_TYPES) { totalVMs += (int) t[5]; }
        int numHosts = Math.max(1, (totalVMs + 3) / 4);

        List<Host> hostList = new ArrayList<>();
        for (int h = 0; h < numHosts; h++) {
            List<Pe> peList = new ArrayList<>();
            for (int p = 0; p < 16; p++) {
                peList.add(new Pe(p, new PeProvisionerSimple(2000)));
            }
            hostList.add(new Host(
                h,
                new RamProvisionerSimple(8192),
                new BwProvisionerSimple(100000),
                1000000L,
                peList,
                new VmSchedulerTimeShared(peList)
            ));
        }

        DatacenterCharacteristics dc = new DatacenterCharacteristics(
            "x86", "Linux", "Xen", hostList,
            10.0,   // timezone
            3.0,    // cost per PE (not used by LIWSA, but required by WorkflowSim)
            0.05,   // costPerMem
            0.1,    // costPerStorage
            0.1     // costPerBW
        );

        WorkflowDatacenter datacenter = null;
        try {
            HarddriveStorage storage = new HarddriveStorage(name, 1e12);
            storage.setMaxTransferRate(100);   // MB/s internal datacenter bandwidth
            LinkedList<Storage> storageList = new LinkedList<>();
            storageList.add(storage);
            datacenter = new WorkflowDatacenter(
                name, dc, new VmAllocationPolicySimple(hostList), storageList, 0
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
        return datacenter;
    }

    public static void main(String[] args) {
        try {

            // ==============================================================
            // CONFIGURATION — edit this block only
            // ==============================================================

            // Path to the workflow DAX file. Paths available in config/dax/:
            //   Montage_50.xml  Montage_100.xml  Montage_1000.xml
            //   CyberShake_30.xml  CyberShake_50.xml  CyberShake_100.xml
            //   Epigenomics_100.xml  SIPHT_60.xml  SIPHT_100.xml
            //   LIGO_50.xml  LIGO_100.xml  (etc.)
            String daxPath = "config/dax/Montage_100.xml";

            // LIWSA algorithm parameters (passed via LIWSAPlanningAlgorithm setters).
            // populationSize: number of candidate schedules maintained in the swarm.
            //   Larger -> better solutions, slower per generation.
            // generationCount: number of evolution iterations.
            //   100 generations is a reasonable starting point; increase to 200-500
            //   for larger workflows or if results show the front still changing late.
            // randomSeed: fix for reproducible results; set to -1 for random.
            int populationSize = 30;
            int generationCount = 100;
            long randomSeed = 7L;

            // Path for the CSV results file. One row is written for this run.
            String csvOutputPath = ResultsPaths.resolve("LIWSA_results.csv");

            // ==============================================================
            // END CONFIGURATION
            // ==============================================================

            long programStart = System.currentTimeMillis();

            File daxFile = new File(daxPath);
            if (!daxFile.exists()) {
                Log.printLine("DAX file not found: " + daxPath);
                Log.printLine("Please update daxPath in the CONFIGURATION block.");
                return;
            }

            // Count total VMs from the type table
            int totalVMs = 0;
            for (double[] t : VM_TYPES) { totalVMs += (int) t[5]; }

            Parameters.SchedulingAlgorithm sch_method = Parameters.SchedulingAlgorithm.STATIC;
            Parameters.PlanningAlgorithm pln_method = Parameters.PlanningAlgorithm.LIWSA;
            ReplicaCatalog.FileSystem file_system = ReplicaCatalog.FileSystem.LOCAL;

            OverheadParameters op = new OverheadParameters(0, null, null, null, null, 0);
            ClusteringParameters cp = new ClusteringParameters(
                0, 0, ClusteringParameters.ClusteringMethod.NONE, null
            );

            // Parameter injection: WorkflowPlanner constructs the planning
            // algorithm internally with `new LIWSAPlanningAlgorithm()` as a
            // local variable inside processPlanning(), triggered only once
            // CloudSim.startSimulation() begins processing events -- there
            // is no point in that flow where external code can reach the
            // real instance before run() executes. Static fields, read by
            // the no-arg constructor at construction time, are the only
            // injection point that actually works here.
            org.workflowsim.planning.LIWSAPlanningAlgorithm.CONFIG_POPULATION_SIZE = populationSize;
            org.workflowsim.planning.LIWSAPlanningAlgorithm.CONFIG_GENERATION_COUNT = generationCount;
            org.workflowsim.planning.LIWSAPlanningAlgorithm.CONFIG_RANDOM_SEED = randomSeed;

            Parameters.init(totalVMs, daxPath, null, null, op, cp, sch_method, pln_method, null, 0);

            // Cost model fix: WorkflowSim defaults to CostModel.DATACENTER,
            // which bills every job at one flat datacenter-wide rate
            // regardless of which VM it ran on. CostModel.VM uses each
            // VM's own cost rate (the 0.15/0.30/0.60/0.90 schedule set in
            // createHeterogeneousVMs above), which is what LIWSA's own
            // internal fitness function assumes when it plans a schedule --
            // without this line, the makespan/cost trade-offs LIWSA found
            // during planning will not match what gets reported here.
            Parameters.setCostModel(Parameters.CostModel.VM);

            ReplicaCatalog.init(file_system);

            CloudSim.init(1, Calendar.getInstance(), false);

            WorkflowDatacenter datacenter0 = createHeterogeneousDatacenter("Datacenter_0");

            WorkflowPlanner wfPlanner = new WorkflowPlanner("planner_0", 1);
            WorkflowEngine wfEngine = wfPlanner.getWorkflowEngine();

            List<CondorVM> vmlist0 = createHeterogeneousVMs(wfEngine.getSchedulerId(0));
            wfEngine.submitVmList(vmlist0, 0);
            wfEngine.bindSchedulerDatacenter(datacenter0.getId(), 0);

            long simStart = System.currentTimeMillis();
            CloudSim.startSimulation();
            List<Job> outputList0 = wfEngine.getJobsReceivedList();
            CloudSim.stopSimulation();
            long simWallClockMillis = System.currentTimeMillis() - simStart;

            // Report what LIWSA's internal search actually found, alongside
            // what the simulator reports for the committed schedule. These
            // should be close; large gaps would suggest the decoder's
            // assumptions (e.g. transfer cost model) don't fully match how
            // WorkflowSim itself executes the schedule.
            long searchWallClockMillis = 0;
            int paretoFrontSize = 1;
            double hypervolume = 0.0;
            if (org.workflowsim.planning.LIWSAPlanningAlgorithm.lastRun != null) {
                org.workflowsim.planning.LIWSAPlanningAlgorithm.LastRunMetrics m =
                    org.workflowsim.planning.LIWSAPlanningAlgorithm.lastRun;
                searchWallClockMillis = m.searchWallClockMillis;
                paretoFrontSize = m.paretoFrontSize;
                double[] ref = ParetoMetrics.sharedReferencePoint(
                    java.util.Collections.singletonList(m.paretoFrontPoints));
                hypervolume = ParetoMetrics.hypervolume2D(m.paretoFrontPoints, ref[0], ref[1]);

                Log.printLine("");
                Log.printLine("=== LIWSA SEARCH SUMMARY ===");
                Log.printLine(String.format("  Pareto front size : %d", m.paretoFrontSize));
                Log.printLine(String.format("  Planned makespan  : %.2f s", m.chosenMakespan));
                Log.printLine(String.format("  Planned cost      : %.4f", m.chosenCost));
                Log.printLine(String.format("  Search wall clock : %d ms", m.searchWallClockMillis));
            }

            RunMetricsCalculator.Result metrics = RunMetricsCalculator.compute(
                outputList0, totalVMs, maxMips(VM_TYPES));

            printJobList(outputList0);
            printSummary(metrics, paretoFrontSize, hypervolume, searchWallClockMillis, simWallClockMillis);

            PrintWriter csv = ResultsCsvWriter.open(csvOutputPath);
            ResultsCsvWriter.writeRow(csv, daxFile.getName().replace(".xml", ""), "LIWSA",
                randomSeed,
                metrics.makespan, metrics.cost, paretoFrontSize, hypervolume,
                metrics.avgUtilization, metrics.fairnessIndex, metrics.speedup,
                searchWallClockMillis, simWallClockMillis);
            ResultsCsvWriter.close(csv);
            Log.printLine("Results written to: " + csvOutputPath);

            long totalWallClockMillis = System.currentTimeMillis() - programStart;
            Log.printLine(String.format("Total program wall clock: %d ms (%.1f s)",
                totalWallClockMillis, totalWallClockMillis / 1000.0));

        } catch (Exception e) {
            e.printStackTrace();
            Log.printLine("Simulation terminated due to an unexpected error.");
        }
    }

    private static double maxMips(double[][] vmTypes) {
        double m = 0;
        for (double[] t : vmTypes) { m = Math.max(m, t[0]); }
        return m;
    }

    /**
     * Prints the full metrics summary: makespan, cost, Pareto front size,
     * hypervolume, resource utilization, load-balancing fairness, speedup,
     * and both wall-clock timings (the search itself, and the full
     * CloudSim engine run).
     */
    protected static void printSummary(RunMetricsCalculator.Result m, int paretoFrontSize,
            double hypervolume, long searchWallClockMillis, long simWallClockMillis) {
        Log.printLine("");
        Log.printLine("=== SUMMARY ===");
        Log.printLine(String.format("  Makespan          : %.2f s", m.makespan));
        Log.printLine(String.format("  Cost              : %.4f", m.cost));
        Log.printLine(String.format("  Pareto front size : %d", paretoFrontSize));
        Log.printLine(String.format("  Hypervolume       : %.1f", hypervolume));
        Log.printLine(String.format("  Avg utilization   : %.1f%%", m.avgUtilization * 100));
        Log.printLine(String.format("  Fairness index    : %.3f", m.fairnessIndex));
        Log.printLine(String.format("  Speedup           : %.3f", m.speedup));
        Log.printLine(String.format("  Search wall clock : %d ms", searchWallClockMillis));
        Log.printLine(String.format("  Sim wall clock    : %d ms", simWallClockMillis));
    }
}
