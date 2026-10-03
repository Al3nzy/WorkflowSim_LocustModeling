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

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.planning.LIWSAPlanningAlgorithm;
import org.workflowsim.planning.NSGAIIPlanningAlgorithm;
import org.workflowsim.utils.Parameters;

/**
 * Quantifies how closely the planning-level decoder (the model every
 * population-based algorithm optimises) agrees with the values WorkflowSim
 * reports when the committed schedule is actually executed.
 *
 * For each workflow it reports, per run, the decoder-predicted makespan and
 * cost of the schedule that was committed to the simulator next to the
 * simulator-measured values, and for HEFT's own schedule the same
 * comparison (HEFT's assignment decoded by the shared decoder versus HEFT
 * executed in the simulator).
 *
 * Usage:
 *   java ... DecoderFidelityCheck "Montage_25,Sipht_30" 1,2,3 "Output&Results/decoder_fidelity.csv"
 *
 * Why the two can differ: WorkflowSim adds a job's stage-in transfer time to
 * the job's length, so the VM is occupied, and billed, while inputs are
 * staged; and tasks on one VM are served in simulated arrival order, whereas
 * the decoder inserts tasks in a fixed topological order into the earliest
 * feasible gap. The decoder charges compute time only.
 */
public class DecoderFidelityCheck {

    public static void main(String[] args) throws Exception {
        String[] names = args.length > 0 ? args[0].split(",") : new String[]{"Montage_25"};
        String[] seedStr = args.length > 1 ? args[1].split(",") : new String[]{"1", "2", "3"};
        String out = args.length > 2 ? args[2] : ResultsPaths.resolve("decoder_fidelity.csv");
        int pop = 30, gens = 100;
        Log.disable();

        PrintWriter pw = new PrintWriter(out);
        pw.println("workflow,run,seed,planned_makespan,sim_makespan,makespan_rel_err_pct,"
                + "planned_cost,sim_cost,cost_rel_err_pct");
        for (String name : names) {
            String dax = "config/dax/" + name.trim() + ".xml";
            LIWSABenchmarkExample.RunResult heft = LIWSABenchmarkExample.runPlanning(dax,
                Parameters.PlanningAlgorithm.HEFT, Parameters.SchedulingAlgorithm.STATIC,
                "HEFT", 0L, pop, gens, null);
            LIWSABenchmarkExample.RunResult mm = LIWSABenchmarkExample.runPlanning(dax,
                Parameters.PlanningAlgorithm.INVALID, Parameters.SchedulingAlgorithm.MINMIN,
                "Min-Min", 0L, pop, gens, null);
            List<Map<Integer, Integer>> seeds = new ArrayList<>();
            seeds.add(heft.assignment);
            seeds.add(mm.assignment);

            boolean heftDone = false;
            for (String s : seedStr) {
                long seed = Long.parseLong(s.trim());
                LIWSABenchmarkExample.RunResult l = LIWSABenchmarkExample.runPlanning(dax,
                    Parameters.PlanningAlgorithm.LIWSA, Parameters.SchedulingAlgorithm.STATIC,
                    "LIWSA", seed, pop, gens, seeds);
                LIWSAPlanningAlgorithm.LastRunMetrics lm = LIWSAPlanningAlgorithm.lastRun;
                if (!heftDone && lm.seedPlanningPoints != null && lm.seedPlanningPoints.size() >= 2) {
                    row(pw, name, "HEFT-schedule", 0, lm.seedPlanningPoints.get(0), heft.makespan, heft.cost);
                    row(pw, name, "MinMin-schedule", 0, lm.seedPlanningPoints.get(1), mm.makespan, mm.cost);
                    heftDone = true;
                }
                row(pw, name, "LIWSA-chosen", seed,
                    new double[]{lm.chosenMakespan, lm.chosenCost}, l.makespan, l.cost);

                LIWSABenchmarkExample.RunResult n = LIWSABenchmarkExample.runPlanning(dax,
                    Parameters.PlanningAlgorithm.NSGAII, Parameters.SchedulingAlgorithm.STATIC,
                    "NSGA-II", seed, pop, gens, seeds);
                NSGAIIPlanningAlgorithm.LastRunMetrics nm = NSGAIIPlanningAlgorithm.lastRun;
                row(pw, name, "NSGA-II-chosen", seed,
                    new double[]{nm.chosenMakespan, nm.chosenCost}, n.makespan, n.cost);
            }
            System.out.println("done " + name);
        }
        pw.close();
        System.out.println();
        ResultsSummary.print(out, null, true);   // median planned-vs-simulated error per schedule type
        ResultsPaths.printHints(out);
    }

    private static void row(PrintWriter pw, String wf, String run, long seed,
            double[] planned, double simMk, double simCost) {
        double emk = (simMk - planned[0]) / planned[0] * 100.0;
        double ec = (simCost - planned[1]) / planned[1] * 100.0;
        pw.printf("%s,%s,%d,%.6f,%.6f,%.3f,%.6f,%.6f,%.3f%n", wf, run, seed,
            planned[0], simMk, emk, planned[1], simCost, ec);
    }
}
