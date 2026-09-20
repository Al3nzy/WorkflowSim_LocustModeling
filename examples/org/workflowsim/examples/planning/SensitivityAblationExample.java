/**
 * Copyright 2025-2026 SDU University, Kazakhstan
 * @author Dr. Mohammed Alaa Ala'anzy
 *
 * 
 * To run all of experiments, use the following command:
 * cd "C:\Users\User\git\WorkflowSim_LocustModeling"
* java -cp "bin;lib/*" org.workflowsim.examples.planning.SensitivityAblationExample lambda
* java -cp "bin;lib/*" org.workflowsim.examples.planning.SensitivityAblationExample theta
* java -cp "bin;lib/*" org.workflowsim.examples.planning.SensitivityAblationExample naive
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.workflowsim.planning.LIWSAMLPlanningAlgorithm;
import org.workflowsim.planning.LIWSAPlanningAlgorithm;
import org.workflowsim.utils.Parameters;

/**
 * Two experiments 
 * share the same "sweep a config value, rerun, compare hypervolume" shape:
 *
 * MODE "lambda": sweeps LIWSA's phase-mixing weight
 * (LIWSAPlanningAlgorithm.CONFIG_LAMBDA_MIX) across {0.1, 0.3, 0.5, 0.7,
 * 0.9} -- 
 *
 * MODE "theta": sweeps LIWSA-ML's softmax temperature
 * (LIWSAMLPlanningAlgorithm.CONFIG_PRED_TEMPERATURE) across the same five
 * values.
 *
 * MODE "naive": compares LIWSA-ML's real OLS-fitted warm-start predictor
 * against LIWSAMLPlanningAlgorithm.CONFIG_NAIVE_FEATURES=true, which scores
 * (task, VM) pairs with only the raw, un-learned predicted duration and
 * cost -- a controlled test of whether the OLS model
 * adds value beyond simple task-duration/cost features.
 *
 * Both sweeps run on one representative instance per workflow family (the
 * 100-task scale point, chosen since it is large enough to be a genuine
 * search problem but cheap enough to sweep several configurations of), to
 * keep total runtime manageable while still testing across five
 * structurally different DAG topologies rather than just one.
 *
 * Output uses the same CSV schema as LIWSABenchmarkExample by encoding the
 * swept parameter value into the algorithm name (e.g. "LIWSA_L0.30"), so
 * the existing ResultsCsvWriter and analysis tooling need no changes.
 * Hypervolume is computed against a reference point shared across every
 * swept value for a given workflow, exactly as in the main benchmark, so
 * hypervolume is comparable across the sweep but not against
 * benchmark_results.csv (different comparator set).
 */
public class SensitivityAblationExample {

    private static final String[] DEFAULT_DAX = {
        "config/dax/Montage_100.xml",
        "config/dax/CyberShake_100.xml",
        "config/dax/Sipht_100.xml",
        "config/dax/Epigenomics_100.xml",
        "config/dax/Inspiral_100.xml"
    };

    private static final double[] SWEEP_VALUES = {0.1, 0.3, 0.5, 0.7, 0.9};

    public static void main(String[] args) {
        String mode = args.length > 0 && !args[0].trim().isEmpty() ? args[0].trim() : "lambda";
        List<String> daxFiles = new ArrayList<>();
        if (args.length > 1 && !args[1].trim().isEmpty()) {
            for (String name : args[1].split(",")) {
                daxFiles.add("config/dax/" + name.trim() + ".xml");
            }
        } else {
            for (String f : DEFAULT_DAX) { daxFiles.add(f); }
        }
        String csvOutputPath = args.length > 2 && !args[2].trim().isEmpty()
            ? args[2].trim() : ("results/" + mode + "_results.csv");

        long[] seeds = {1L, 2L, 3L, 4L, 5L};
        int populationSize = 30;
        int generationCount = 100;

        PrintWriter csv = ResultsCsvWriter.openAppend(csvOutputPath);
        long benchmarkStart = System.currentTimeMillis();

        for (String daxPath : daxFiles) {
            if (!new File(daxPath).exists()) {
                System.out.println("Skipping (not found): " + daxPath);
                continue;
            }
            String workflowName = new File(daxPath).getName().replace(".xml", "");
            System.out.println();
            System.out.println("=".repeat(78));
            System.out.println("WORKFLOW: " + workflowName + "  (mode=" + mode + ")");
            System.out.println("=".repeat(78));

            // Deterministic warm-start seeds, exactly as in the main benchmark.
            LIWSABenchmarkExample.RunResult heft = LIWSABenchmarkExample.runPlanning(
                daxPath, Parameters.PlanningAlgorithm.HEFT,
                Parameters.SchedulingAlgorithm.STATIC, "HEFT", 0L,
                populationSize, generationCount, null);
            LIWSABenchmarkExample.RunResult minmin = LIWSABenchmarkExample.runPlanning(
                daxPath, Parameters.PlanningAlgorithm.INVALID,
                Parameters.SchedulingAlgorithm.MINMIN, "Min-Min", 0L,
                populationSize, generationCount, null);
            List<Map<Integer, Integer>> warmStartSeeds = new ArrayList<>();
            if (heft != null && heft.assignment != null) { warmStartSeeds.add(heft.assignment); }
            if (minmin != null && minmin.assignment != null) { warmStartSeeds.add(minmin.assignment); }

            List<List<LIWSABenchmarkExample.RunResult>> variantRuns = new ArrayList<>();
            List<String> variantLabels = new ArrayList<>();

            if (mode.equals("lambda") || mode.equals("theta")) {
                for (double val : SWEEP_VALUES) {
                    String label = mode.equals("lambda")
                        ? String.format("LIWSA_L%.2f", val)
                        : String.format("LIWSAML_T%.2f", val);
                    List<LIWSABenchmarkExample.RunResult> runs = new ArrayList<>();
                    for (long seed : seeds) {
                        LIWSAPlanningAlgorithm.CONFIG_LAMBDA_MIX =
                            mode.equals("lambda") ? val : 0.5;
                        LIWSAMLPlanningAlgorithm.CONFIG_PRED_TEMPERATURE =
                            mode.equals("theta") ? val : 0.5;
                        LIWSAMLPlanningAlgorithm.CONFIG_NAIVE_FEATURES = false;

                        Parameters.PlanningAlgorithm alg = mode.equals("lambda")
                            ? Parameters.PlanningAlgorithm.LIWSA
                            : Parameters.PlanningAlgorithm.LIWSAML;
                        LIWSABenchmarkExample.RunResult r = LIWSABenchmarkExample.runPlanning(
                            daxPath, alg, Parameters.SchedulingAlgorithm.STATIC,
                            label, seed, populationSize, generationCount, warmStartSeeds);
                        if (r != null) { runs.add(r); }
                    }
                    variantRuns.add(runs);
                    variantLabels.add(label);
                }
                // Reset to defaults after the sweep.
                LIWSAPlanningAlgorithm.CONFIG_LAMBDA_MIX = 0.5;
                LIWSAMLPlanningAlgorithm.CONFIG_PRED_TEMPERATURE = 0.5;
            } else if (mode.equals("naive")) {
                for (boolean naive : new boolean[]{false, true}) {
                    String label = naive ? "LIWSA-ML-Naive" : "LIWSA-ML";
                    List<LIWSABenchmarkExample.RunResult> runs = new ArrayList<>();
                    for (long seed : seeds) {
                        LIWSAMLPlanningAlgorithm.CONFIG_NAIVE_FEATURES = naive;
                        LIWSABenchmarkExample.RunResult r = LIWSABenchmarkExample.runPlanning(
                            daxPath, Parameters.PlanningAlgorithm.LIWSAML,
                            Parameters.SchedulingAlgorithm.STATIC,
                            label, seed, populationSize, generationCount, warmStartSeeds);
                        if (r != null) { runs.add(r); }
                    }
                    variantRuns.add(runs);
                    variantLabels.add(label);
                }
                LIWSAMLPlanningAlgorithm.CONFIG_NAIVE_FEATURES = false;
            } else {
                System.out.println("Unknown mode: " + mode + " (expected lambda, theta, or naive)");
                return;
            }

            // Shared hypervolume reference point across HEFT, Min-Min, and
            // every swept variant for this workflow.
            List<LIWSABenchmarkExample.RunResult> allResults = new ArrayList<>();
            if (heft != null) { allResults.add(heft); }
            if (minmin != null) { allResults.add(minmin); }
            for (List<LIWSABenchmarkExample.RunResult> runs : variantRuns) {
                allResults.addAll(runs);
            }
            List<List<double[]>> allFronts = new ArrayList<>();
            for (LIWSABenchmarkExample.RunResult r : allResults) { allFronts.add(r.frontPoints); }
            double[] ref = ParetoMetrics.sharedReferencePoint(allFronts);
            for (LIWSABenchmarkExample.RunResult r : allResults) {
                r.hypervolume = ParetoMetrics.hypervolume2D(r.frontPoints, ref[0], ref[1]);
            }

            if (csv != null) {
                for (LIWSABenchmarkExample.RunResult r : allResults) {
                    ResultsCsvWriter.writeRow(csv, workflowName, r.name, r.seed,
                        r.makespan, r.cost, r.frontPoints.size(), r.hypervolume,
                        r.avgUtilization, r.fairnessIndex, r.speedup,
                        r.searchWallClockMillis, r.simWallClockMillis);
                }
            }

            for (int i = 0; i < variantLabels.size(); i++) {
                List<LIWSABenchmarkExample.RunResult> runs = variantRuns.get(i);
                double meanHv = runs.stream().mapToDouble(r -> r.hypervolume).average().orElse(0);
                double meanMk = runs.stream().mapToDouble(r -> r.makespan).average().orElse(0);
                System.out.printf("  %-18s meanHV=%.1f  meanMakespan=%.2f  (n=%d)%n",
                    variantLabels.get(i), meanHv, meanMk, runs.size());
            }
        }

        if (csv != null) { ResultsCsvWriter.close(csv); }
        long elapsed = System.currentTimeMillis() - benchmarkStart;
        System.out.println();
        System.out.println("=".repeat(78));
        System.out.printf("SENSITIVITY/ABLATION RUN COMPLETE (mode=%s): %.1f s (%.1f min)%n",
            mode, elapsed / 1000.0, elapsed / 60000.0);
        System.out.println("Results written to: " + csvOutputPath);
        System.out.println("=".repeat(78));
    }
}
