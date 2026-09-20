/**
 * Copyright 2025-2026 SDU University, Kazakhstan
 * @author Dr. Mohammed Alaa Ala'anzy
 *
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
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.HarddriveStorage;
import org.cloudbus.cloudsim.Host;
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
import org.workflowsim.planning.LIWSAPlanningAlgorithm;
import org.workflowsim.planning.MLEAOPlanningAlgorithm;
import org.workflowsim.planning.NSGAIIPlanningAlgorithm;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * Benchmark driver: runs HEFT, Min-Min, MLEAO, LIWSA, and LIWSA-ML on a set
 * of DAX files and prints a comparison report, while also writing every
 * result to a CSV file as it completes.
 *
 * THREE FIXES vs earlier versions of this file:
 *
 *  1. Cost model: Parameters.setCostModel(Parameters.CostModel.VM). Without
 *     it, WorkflowSim bills every job at one flat datacenter-wide rate
 *     instead of the rate of the VM it actually ran on.
 *
 *  2. Hypervolume reference point: a real run surfaced this -- computing
 *     each algorithm's reference point from its OWN worst value makes a
 *     single very bad point (e.g. HEFT struggling on a data-heavy
 *     workflow) look artificially "rich" purely from scale, not quality.
 *     This version computes ONE shared reference point per workflow,
 *     20% beyond the worst makespan/cost seen across every algorithm and
 *     every seed for that workflow, and uses it for every hypervolume
 *     calculation, making the column genuinely comparable across rows.
 *
 *  3. Warm-start seeding: MLEAO, LIWSA, and LIWSA-ML are now seeded with
 *     HEFT's and Min-Min's actual computed schedules (matched by cloudlet
 *     ID across the separate simulation runs -- see
 *     LIWSAPlanningAlgorithm.CONFIG_SEED_ASSIGNMENTS), matching the setup
 *     used during prototype validation. Earlier versions of this driver
 *     never wired this up, since WorkflowPlanner's internal construction
 *     of the planning algorithm left no reachable injection point for it
 *     until the CONFIG_SEED_ASSIGNMENTS mechanism was added.
 *
 * OUTPUT: a CSV file (default results/benchmark_results.csv) with one row
 * per (workflow, algorithm, seed), written and flushed immediately after
 * each run completes -- not buffered to the end -- so an interrupted run
 * still leaves every completed result safely on disk. Uses the shared
 * ResultsCsvWriter / RunMetricsCalculator / ParetoMetrics utilities so the
 * schema and metric definitions are identical to the single-algorithm
 * example drivers.
 *
 * TIMING: every run's search wall clock (the metaheuristic loop itself,
 * for MLEAO/LIWSA/LIWSA-ML) and full simulation wall clock are recorded
 * and printed/written to CSV. Progress and elapsed time are printed after
 * each workflow, and total wall clock for the whole benchmark is printed
 * at the end.
 */
public class LIWSABenchmarkExample {

    static final double[][] VM_TYPES = {
        { 250.0, 160.0, 0.15, 512, 10000, 4},  // Micro  x4
        { 500.0, 160.0, 0.30, 512, 10000, 4},  // Small  x4
        {1000.0, 160.0, 0.60, 512, 10000, 4},  // Medium x4
        {2000.0, 160.0, 0.90, 512, 10000, 4},  // Large  x4
    };

    static class RunResult {
        String name;
        long seed;
        double makespan;
        double cost;
        double avgUtilization;
        double fairnessIndex;
        double speedup;
        List<double[]> frontPoints;   // raw, for shared-reference hypervolume
        double hypervolume;           // filled in AFTER the shared reference is known
        long searchWallClockMillis;
        long simWallClockMillis;
        Map<Integer, Integer> assignment;  // cloudletId -> vmId, for warm-start seeding
    }

    private static class Stats {
        double mean, std;

        static Stats of(List<Double> values) {
            Stats s = new Stats();
            int n = values.size();
            double sum = 0;
            for (double v : values) { sum += v; }
            s.mean = sum / n;
            double sq = 0;
            for (double v : values) { sq += (v - s.mean) * (v - s.mean); }
            s.std = n > 1 ? Math.sqrt(sq / (n - 1)) : 0.0;
            return s;
        }
    }

    public static void main(String[] args) {

        // ==============================================================
        // CONFIGURATION
        // ==============================================================

        // Complete set of non-1000-task workflows across all five families,
        // sorted by family then size. This is the full available set in
        // config/dax/ (excluding HEFT_paper.xml, the 10-task toy graph
        // from the original HEFT paper, and floodplain.xml, an unrelated
        // single example workflow -- neither belongs to the five families
        // being swept here). Running the full size range per family is
        // what supports a scalability-vs-workflow-size analysis, not just
        // a handful of isolated data points.
        String[] standardDaxFiles = {
            "config/dax/Montage_25.xml",
            "config/dax/Montage_50.xml",
            "config/dax/Montage_100.xml",
            "config/dax/CyberShake_30.xml",
            "config/dax/CyberShake_50.xml",
            "config/dax/CyberShake_100.xml",
            "config/dax/Sipht_30.xml",
            "config/dax/Sipht_60.xml",
            "config/dax/Sipht_100.xml",
            "config/dax/Epigenomics_24.xml",
            "config/dax/Epigenomics_46.xml",
            "config/dax/Epigenomics_100.xml",
            "config/dax/Inspiral_30.xml",
            "config/dax/Inspiral_50.xml",
            "config/dax/Inspiral_100.xml",
        };

        // Large, real-trace workflows (~1000 tasks). These take notably
        // longer, and at least one of them (Epigenomics_997) contains
        // individual file transfers in the multi-gigabyte range, which
        // can make HEFT in particular behave very differently than on the
        // compute-bound standard set above -- expected, not a bug, see
        // the class-level notes in LIWSAPlanningAlgorithm if curious.
        String[] largeDaxFiles = {
            "config/dax/Montage_1000.xml",
            "config/dax/CyberShake_1000.xml",
            "config/dax/Sipht_1000.xml",
            "config/dax/Inspiral_1000.xml",
            "config/dax/Epigenomics_997.xml",
        };

        boolean includeLargeWorkflows = true;

        int numSeeds = 5;
        long[] seeds = new long[numSeeds];
        for (int i = 0; i < numSeeds; i++) { seeds[i] = i + 1; }

        int populationSize = 30;
        int generationCount = 100;

        // Whether to seed MLEAO/LIWSA/LIWSA-ML's initial population with
        // HEFT's and Min-Min's actual computed schedules. Recommended on;
        // this is what was validated during prototyping. Set false to see
        // how each algorithm performs from a cold, fully random start.
        boolean useWarmStartSeeding = true;

        String csvOutputPath = "results/benchmark_results_nsga2.csv";

        // ==============================================================
        // END CONFIGURATION
        // ==============================================================

        // Optional CLI overrides, added to let a full sweep be run in
        // several separate JVM invocations (one per workflow or small
        // group of workflows) without recompiling between them:
        //   args[0] - comma-separated workflow base names to restrict
        //             this run to (e.g. "Montage_25,Montage_50"), or
        //             omitted/blank to run the full standard+large set
        //             configured above.
        //   args[1] - output CSV path override.
        // When args[0] is given, the CSV is opened in APPEND mode (no
        // header rewritten) so multiple batch invocations accumulate
        // into one file; each per-workflow hypervolume reference point
        // is computed from only that workflow's own algorithms/seeds
        // (see the shared-reference-point block below), so splitting
        // the run by workflow changes nothing about any computed value.
        java.util.Set<String> workflowFilter = null;
        if (args.length > 0 && !args[0].trim().isEmpty()) {
            workflowFilter = new java.util.HashSet<>();
            for (String name : args[0].split(",")) {
                workflowFilter.add(name.trim());
            }
        }
        if (args.length > 1 && !args[1].trim().isEmpty()) {
            csvOutputPath = args[1].trim();
        }

        long benchmarkStart = System.currentTimeMillis();
        PrintWriter csv = (workflowFilter != null)
            ? ResultsCsvWriter.openAppend(csvOutputPath)
            : ResultsCsvWriter.open(csvOutputPath);

        List<String> daxFiles = new ArrayList<>();
        for (String f : standardDaxFiles) { daxFiles.add(f); }
        if (includeLargeWorkflows) {
            for (String f : largeDaxFiles) { daxFiles.add(f); }
        }
        if (workflowFilter != null) {
            List<String> filtered = new ArrayList<>();
            for (String f : daxFiles) {
                String name = new File(f).getName().replace(".xml", "");
                if (workflowFilter.contains(name)) { filtered.add(f); }
            }
            daxFiles = filtered;
        }

        boolean ablationMode = args.length > 2 && "ablation".equals(args[2].trim());

        DecimalFormat df2 = new DecimalFormat("#####0.00");
        DecimalFormat df1 = new DecimalFormat("#####0.0");
        DecimalFormat df3 = new DecimalFormat("0.000");

        int workflowsCompleted = 0;

        for (String daxPath : daxFiles) {
            if (!new File(daxPath).exists()) {
                System.out.println("Skipping (not found): " + daxPath);
                continue;
            }
            String workflowName = new File(daxPath).getName().replace(".xml", "");
            long workflowStart = System.currentTimeMillis();
            System.out.println();
            System.out.println("=".repeat(78));
            System.out.println("WORKFLOW: " + workflowName);
            System.out.println("=".repeat(78));

            // ---- deterministic baselines ----
            RunResult heft = runPlanning(daxPath, Parameters.PlanningAlgorithm.HEFT,
                Parameters.SchedulingAlgorithm.STATIC, "HEFT", 0L,
                populationSize, generationCount, null);

            RunResult minmin = runPlanning(daxPath, Parameters.PlanningAlgorithm.INVALID,
                Parameters.SchedulingAlgorithm.MINMIN, "Min-Min", 0L,
                populationSize, generationCount, null);

            // Warm-start seeds for the stochastic algorithms: HEFT's and
            // Min-Min's actual computed schedules, matched by cloudlet ID.
            List<Map<Integer, Integer>> warmStartSeeds = new ArrayList<>();
            if (useWarmStartSeeding) {
                if (heft != null && heft.assignment != null) { warmStartSeeds.add(heft.assignment); }
                if (minmin != null && minmin.assignment != null) { warmStartSeeds.add(minmin.assignment); }
            }

            // ---- stochastic algorithms: one run per seed ----
            List<RunResult> mleaoRuns = new ArrayList<>();
            List<RunResult> liwsaRuns = new ArrayList<>();
            List<RunResult> liwsaMlRuns = new ArrayList<>();
            List<RunResult> nsga2Runs = new ArrayList<>();
            List<RunResult> liwsaNoDensityRuns = new ArrayList<>();

            if (ablationMode) {
                for (long seed : seeds) {
                    LIWSAPlanningAlgorithm.CONFIG_DENSITY_ABLATION = false;
                    RunResult l = runPlanning(daxPath, Parameters.PlanningAlgorithm.LIWSA,
                        Parameters.SchedulingAlgorithm.STATIC, "LIWSA", seed,
                        populationSize, generationCount, warmStartSeeds);
                    if (l != null) { liwsaRuns.add(l); }

                    LIWSAPlanningAlgorithm.CONFIG_DENSITY_ABLATION = true;
                    RunResult nd = runPlanning(daxPath, Parameters.PlanningAlgorithm.LIWSA,
                        Parameters.SchedulingAlgorithm.STATIC, "LIWSA-NoDensity", seed,
                        populationSize, generationCount, warmStartSeeds);
                    LIWSAPlanningAlgorithm.CONFIG_DENSITY_ABLATION = false;
                    if (nd != null) { liwsaNoDensityRuns.add(nd); }
                }
            } else {
            for (long seed : seeds) {
                RunResult m = runPlanning(daxPath, Parameters.PlanningAlgorithm.MLEAO,
                    Parameters.SchedulingAlgorithm.STATIC, "MLEAO", seed,
                    populationSize, generationCount, warmStartSeeds);
                if (m != null) { mleaoRuns.add(m); }

                RunResult l = runPlanning(daxPath, Parameters.PlanningAlgorithm.LIWSA,
                    Parameters.SchedulingAlgorithm.STATIC, "LIWSA", seed,
                    populationSize, generationCount, warmStartSeeds);
                if (l != null) { liwsaRuns.add(l); }

                RunResult lm = runPlanning(daxPath, Parameters.PlanningAlgorithm.LIWSAML,
                    Parameters.SchedulingAlgorithm.STATIC, "LIWSA-ML", seed,
                    populationSize, generationCount, warmStartSeeds);
                if (lm != null) { liwsaMlRuns.add(lm); }

                // Canonical multi-objective baseline: standard NSGA-II
                // (Deb et al. 2002), same encoding/decoder/population/
                // generation budget/warm-start seeds as MLEAO/LIWSA above.
                RunResult ns = runPlanning(daxPath, Parameters.PlanningAlgorithm.NSGAII,
                    Parameters.SchedulingAlgorithm.STATIC, "NSGA-II", seed,
                    populationSize, generationCount, warmStartSeeds);
                if (ns != null) { nsga2Runs.add(ns); }
            }
            }

            // ---- FIX 2: shared hypervolume reference point, computed
            //      across every algorithm and every seed for THIS
            //      workflow, then applied uniformly to all of them ----
            List<RunResult> resultsThisWorkflow = ablationMode
                ? allOf(heft, minmin, liwsaRuns, liwsaNoDensityRuns)
                : allOf(heft, minmin, mleaoRuns, liwsaRuns, liwsaMlRuns, nsga2Runs);
            List<List<double[]>> allFronts = new ArrayList<>();
            for (RunResult r : resultsThisWorkflow) {
                allFronts.add(r.frontPoints);
            }
            double[] ref = ParetoMetrics.sharedReferencePoint(allFronts);
            for (RunResult r : resultsThisWorkflow) {
                r.hypervolume = ParetoMetrics.hypervolume2D(r.frontPoints, ref[0], ref[1]);
            }

            // ---- write every result to CSV now that hypervolume is final ----
            for (RunResult r : resultsThisWorkflow) {
                ResultsCsvWriter.writeRow(csv, workflowName, r.name, r.seed,
                    r.makespan, r.cost, r.frontPoints.size(), r.hypervolume,
                    r.avgUtilization, r.fairnessIndex, r.speedup,
                    r.searchWallClockMillis, r.simWallClockMillis);
            }

            // ---- console table ----
            System.out.println();
            System.out.printf("%-10s %14s %14s %7s %12s %7s %7s %8s%n",
                "Algorithm", "Makespan(s)", "Cost", "Front", "Hypervol.",
                "Util%", "Fair", "Speedup");
            System.out.println("-".repeat(90));

            printSingle(df2, df1, df3, heft);
            printSingle(df2, df1, df3, minmin);
            printAggregate(df2, df1, df3, "MLEAO", mleaoRuns);
            printAggregate(df2, df1, df3, "NSGA-II", nsga2Runs);
            printAggregate(df2, df1, df3, "LIWSA", liwsaRuns);
            printAggregate(df2, df1, df3, "LIWSA-ML", liwsaMlRuns);
            printAggregate(df2, df1, df3, "LIWSA-NoDensity", liwsaNoDensityRuns);

            if (heft != null && !liwsaRuns.isEmpty() && !mleaoRuns.isEmpty() && !liwsaMlRuns.isEmpty()) {
                double liwsaMk = mean(liwsaRuns, r -> r.makespan);
                double liwsaCost = mean(liwsaRuns, r -> r.cost);
                double mleaoMk = mean(mleaoRuns, r -> r.makespan);
                double mleaoCost = mean(mleaoRuns, r -> r.cost);
                double mlMk = mean(liwsaMlRuns, r -> r.makespan);
                double mlCost = mean(liwsaMlRuns, r -> r.cost);

                System.out.println();
                System.out.printf("  LIWSA    vs HEFT:  makespan %+6.1f%%  cost %+6.1f%%%n",
                    pct(liwsaMk, heft.makespan), pct(liwsaCost, heft.cost));
                System.out.printf("  LIWSA-ML vs HEFT:  makespan %+6.1f%%  cost %+6.1f%%%n",
                    pct(mlMk, heft.makespan), pct(mlCost, heft.cost));
                System.out.printf("  LIWSA-ML vs MLEAO: makespan %+6.1f%%  cost %+6.1f%%%n",
                    pct(mlMk, mleaoMk), pct(mlCost, mleaoCost));
                System.out.printf("  LIWSA-ML vs LIWSA: makespan %+6.1f%%  cost %+6.1f%%%n",
                    pct(mlMk, liwsaMk), pct(mlCost, liwsaCost));
            }

            // ---- timing / progress ----
            long workflowWall = System.currentTimeMillis() - workflowStart;
            long elapsedTotal = System.currentTimeMillis() - benchmarkStart;
            workflowsCompleted++;
            System.out.println();
            System.out.printf("  Workflow wall clock : %.1f s%n", workflowWall / 1000.0);
            System.out.printf("  Elapsed so far       : %.1f s (%d/%d workflows done)%n",
                elapsedTotal / 1000.0, workflowsCompleted, daxFiles.size());
        }

        ResultsCsvWriter.close(csv);

        long totalWall = System.currentTimeMillis() - benchmarkStart;
        System.out.println();
        System.out.println("=".repeat(78));
        System.out.printf("BENCHMARK COMPLETE: %.1f s total (%.1f min)%n",
            totalWall / 1000.0, totalWall / 60000.0);
        System.out.println("Results written to: " + csvOutputPath);
        System.out.println("=".repeat(78));
    }

    @SafeVarargs
    static List<RunResult> allOf(RunResult a, RunResult b, List<RunResult>... lists) {
        List<RunResult> out = new ArrayList<>();
        if (a != null) { out.add(a); }
        if (b != null) { out.add(b); }
        for (List<RunResult> l : lists) { out.addAll(l); }
        return out;
    }

    private static double pct(double value, double base) {
        return 100.0 * (value - base) / base;
    }

    private interface Extractor { double get(RunResult r); }

    private static double mean(List<RunResult> runs, Extractor ex) {
        double sum = 0;
        for (RunResult r : runs) { sum += ex.get(r); }
        return sum / runs.size();
    }

    private static void printSingle(DecimalFormat df2, DecimalFormat df1, DecimalFormat df3, RunResult r) {
        if (r == null) { return; }
        System.out.printf("%-10s %14s %14s %7d %12s %7s %7s %8s%n",
            r.name, df2.format(r.makespan), df2.format(r.cost), r.frontPoints.size(),
            df1.format(r.hypervolume), df1.format(r.avgUtilization * 100),
            df3.format(r.fairnessIndex), df3.format(r.speedup));
    }

    private static void printAggregate(DecimalFormat df2, DecimalFormat df1, DecimalFormat df3,
                                        String label, List<RunResult> runs) {
        if (runs.isEmpty()) {
            System.out.printf("%-10s  (no successful runs)%n", label);
            return;
        }
        List<Double> mk = new ArrayList<>(), cost = new ArrayList<>(), hv = new ArrayList<>();
        List<Double> util = new ArrayList<>(), fair = new ArrayList<>(), speed = new ArrayList<>();
        List<Double> frontSize = new ArrayList<>();
        for (RunResult r : runs) {
            mk.add(r.makespan); cost.add(r.cost); hv.add(r.hypervolume);
            util.add(r.avgUtilization); fair.add(r.fairnessIndex); speed.add(r.speedup);
            frontSize.add((double) r.frontPoints.size());
        }
        Stats mkS = Stats.of(mk), costS = Stats.of(cost), hvS = Stats.of(hv);
        Stats utilS = Stats.of(util), fairS = Stats.of(fair), speedS = Stats.of(speed);
        Stats frontS = Stats.of(frontSize);

        String mkStr = df2.format(mkS.mean) + "+-" + df1.format(mkS.std);
        String costStr = df2.format(costS.mean) + "+-" + df1.format(costS.std);

        System.out.printf("%-10s %14s %14s %7s %12s %7s %7s %8s%n",
            label, mkStr, costStr,
            df1.format(frontS.mean),
            df1.format(hvS.mean),
            df1.format(utilS.mean * 100),
            df3.format(fairS.mean),
            df3.format(speedS.mean));
    }

    /**
     * Builds a warm-start assignment map keyed by the ORIGINAL Task's
     * cloudlet ID (as assigned during DAX parsing), not the Job's own ID.
     *
     * This distinction matters and is easy to get wrong: WorkflowSim's
     * BasicClustering.addTasks2Job() constructs each Job with `new
     * Job(idIndex, ...)`, where idIndex is a separate counter starting
     * at 0 -- completely independent of the Task's own cloudlet ID,
     * which was assigned earlier, during DAX parsing, starting at 1.
     * Job.getCloudletId() therefore returns a DIFFERENT number than the
     * cloudlet ID the planning algorithm sees on its Task objects, even
     * though Job extends Task. The original Task (with its real ID) is
     * still reachable via job.getTaskList() -- with no clustering
     * (ClusteringMethod.NONE, what every run in this benchmark uses)
     * that list holds exactly one Task per Job, so this loop visits each
     * Task exactly once. With clustering enabled, every Task absorbed
     * into a multi-task Job would correctly share that Job's VM
     * assignment as its seed value.
     */
    private static Map<Integer, Integer> buildAssignmentMap(List<Job> jobs) {
        Map<Integer, Integer> map = new HashMap<>();
        for (Job job : jobs) {
            if (job.getClassType() == org.workflowsim.utils.Parameters.ClassType.STAGE_IN.value) {
                continue;
            }
            for (org.workflowsim.Task task : job.getTaskList()) {
                map.put(task.getCloudletId(), job.getVmId());
            }
        }
        return map;
    }

    static RunResult runPlanning(
            String daxPath,
            Parameters.PlanningAlgorithm planningAlg,
            Parameters.SchedulingAlgorithm schedulingAlg,
            String label, long seed,
            int populationSize, int generationCount,
            List<Map<Integer, Integer>> warmStartSeeds) {

        try {
            LIWSAPlanningAlgorithm.CONFIG_POPULATION_SIZE = populationSize;
            LIWSAPlanningAlgorithm.CONFIG_GENERATION_COUNT = generationCount;
            LIWSAPlanningAlgorithm.CONFIG_RANDOM_SEED = seed;
            LIWSAPlanningAlgorithm.CONFIG_SEED_ASSIGNMENTS = warmStartSeeds;
            MLEAOPlanningAlgorithm.CONFIG_POPULATION_SIZE = populationSize;
            MLEAOPlanningAlgorithm.CONFIG_GENERATION_COUNT = generationCount;
            MLEAOPlanningAlgorithm.CONFIG_RANDOM_SEED = seed;
            MLEAOPlanningAlgorithm.CONFIG_SEED_ASSIGNMENTS = warmStartSeeds;
            NSGAIIPlanningAlgorithm.CONFIG_POPULATION_SIZE = populationSize;
            NSGAIIPlanningAlgorithm.CONFIG_GENERATION_COUNT = generationCount;
            NSGAIIPlanningAlgorithm.CONFIG_RANDOM_SEED = seed;
            NSGAIIPlanningAlgorithm.CONFIG_SEED_ASSIGNMENTS = warmStartSeeds;

            int totalVMs = 0;
            for (double[] t : VM_TYPES) { totalVMs += (int) t[5]; }

            OverheadParameters op = new OverheadParameters(0, null, null, null, null, 0);
            ClusteringParameters cp = new ClusteringParameters(
                0, 0, ClusteringParameters.ClusteringMethod.NONE, null);

            Parameters.init(totalVMs, daxPath, null, null, op, cp,
                schedulingAlg, planningAlg, null, 0);
            Parameters.setCostModel(Parameters.CostModel.VM);  // FIX 1

            ReplicaCatalog.init(ReplicaCatalog.FileSystem.LOCAL);
            CloudSim.init(1, Calendar.getInstance(), false);

            WorkflowDatacenter dc = createDatacenter("DC_" + label + "_" + seed);
            WorkflowPlanner wfPlanner = new WorkflowPlanner("planner_" + label + "_" + seed, 1);
            WorkflowEngine wfEngine = wfPlanner.getWorkflowEngine();
            List<CondorVM> vmList = createVMs(wfEngine.getSchedulerId(0));
            wfEngine.submitVmList(vmList, 0);
            wfEngine.bindSchedulerDatacenter(dc.getId(), 0);

            long simStart = System.currentTimeMillis();
            CloudSim.startSimulation();
            List<Job> jobs = wfEngine.getJobsReceivedList();
            CloudSim.stopSimulation();
            long simWall = System.currentTimeMillis() - simStart;

            RunResult r = new RunResult();
            r.name = label;
            r.seed = seed;
            r.simWallClockMillis = simWall;
            r.assignment = buildAssignmentMap(jobs);

            double fastestMips = 0;
            for (double[] t : VM_TYPES) { fastestMips = Math.max(fastestMips, t[0]); }
            RunMetricsCalculator.Result m = RunMetricsCalculator.compute(jobs, vmList.size(), fastestMips);
            r.makespan = m.makespan;
            r.cost = m.cost;
            r.avgUtilization = m.avgUtilization;
            r.fairnessIndex = m.fairnessIndex;
            r.speedup = m.speedup;

            long searchWall = 0;
            List<double[]> frontPoints = null;
            if ((planningAlg == Parameters.PlanningAlgorithm.LIWSA
                    || planningAlg == Parameters.PlanningAlgorithm.LIWSAML)
                    && LIWSAPlanningAlgorithm.lastRun != null) {
                frontPoints = LIWSAPlanningAlgorithm.lastRun.paretoFrontPoints;
                searchWall = LIWSAPlanningAlgorithm.lastRun.searchWallClockMillis;
            } else if (planningAlg == Parameters.PlanningAlgorithm.MLEAO
                    && MLEAOPlanningAlgorithm.lastRun != null) {
                frontPoints = MLEAOPlanningAlgorithm.lastRun.paretoFrontPoints;
                searchWall = MLEAOPlanningAlgorithm.lastRun.searchWallClockMillis;
            } else if (planningAlg == Parameters.PlanningAlgorithm.NSGAII
                    && NSGAIIPlanningAlgorithm.lastRun != null) {
                frontPoints = NSGAIIPlanningAlgorithm.lastRun.paretoFrontPoints;
                searchWall = NSGAIIPlanningAlgorithm.lastRun.searchWallClockMillis;
            }
            r.searchWallClockMillis = searchWall;
            if (frontPoints != null) {
                r.frontPoints = frontPoints;
            } else {
                r.frontPoints = new ArrayList<>();
                r.frontPoints.add(new double[]{r.makespan, r.cost});
            }

            return r;

        } catch (Exception e) {
            System.err.println("[" + label + "] simulation failed: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    static List<CondorVM> createVMs(int userId) {
        LinkedList<CondorVM> list = new LinkedList<>();
        int vmId = 0;
        for (double[] type : VM_TYPES) {
            double mips = type[0]; long bw = (long) type[1];
            double cost = type[2]; int ram = (int) type[3];
            long storage = (long) type[4]; int count = (int) type[5];
            for (int k = 0; k < count; k++) {
                list.add(new CondorVM(vmId++, userId, mips, 1, ram, bw, storage,
                    "Xen", cost, 0.0, 0.0, 0.0, new CloudletSchedulerSpaceShared()));
            }
        }
        return list;
    }

    static WorkflowDatacenter createDatacenter(String name) {
        int totalVMs = 0;
        for (double[] t : VM_TYPES) { totalVMs += (int) t[5]; }
        int numHosts = Math.max(1, (totalVMs + 3) / 4);

        List<Host> hostList = new ArrayList<>();
        for (int h = 0; h < numHosts; h++) {
            List<Pe> peList = new ArrayList<>();
            for (int p = 0; p < 16; p++) {
                peList.add(new Pe(p, new PeProvisionerSimple(2000)));
            }
            hostList.add(new Host(h,
                new RamProvisionerSimple(8192),
                new BwProvisionerSimple(100000),
                1000000L, peList,
                new VmSchedulerTimeShared(peList)));
        }

        DatacenterCharacteristics dc = new DatacenterCharacteristics(
            "x86", "Linux", "Xen", hostList, 10.0, 3.0, 0.05, 0.1, 0.1);
        WorkflowDatacenter datacenter = null;
        try {
            HarddriveStorage s = new HarddriveStorage(name, 1e12);
            s.setMaxTransferRate(100);
            LinkedList<Storage> sl = new LinkedList<>();
            sl.add(s);
            datacenter = new WorkflowDatacenter(name, dc,
                new VmAllocationPolicySimple(hostList), sl, 0);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return datacenter;
    }
}