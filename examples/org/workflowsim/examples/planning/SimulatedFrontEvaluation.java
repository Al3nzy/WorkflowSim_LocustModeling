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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.planning.LIWSAPlanningAlgorithm;
import org.workflowsim.planning.MLEAOPlanningAlgorithm;
import org.workflowsim.planning.NSGAIIPlanningAlgorithm;
import org.workflowsim.utils.Parameters;

/**
 * Simulator-based re-evaluation of every algorithm's final front.
 *
 * The population-based algorithms optimise, and report their fronts in, the
 * planning-level decoder's (makespan, cost). HEFT and Min-Min are reported at
 * their simulator-measured point. This tool removes that asymmetry: every
 * distinct member of every final front is replayed through WorkflowSim
 * (a one-individual, zero-generation LIWSA run seeded with that exact
 * cloudlet-to-VM assignment, so the assignment is executed unchanged), the
 * replayed points are reduced to their non-dominated set, and hypervolume is
 * computed for ALL algorithms from simulator-measured points against one
 * shared reference point per workflow.
 *
 * Output columns: workflow, algorithm, seed, distinct front members replayed,
 * non-dominated simulated points, hypervolume under the benchmark's original
 * (planning-level) convention, hypervolume under simulator-measured points.
 *
 * Usage:
 *   java ... SimulatedFrontEvaluation ALL 1,2,3,4,5 "Output&Results/simulated_A.csv" A
 *   First argument: ALL (20 workflows), SMALL (the 15 of 24-100 tasks), LARGE (the 5 of 997-1000 tasks)
 *   or a comma-separated list such as "Montage_25,Sipht_30".  Last argument: variant A, B or C.
 */
public class SimulatedFrontEvaluation {

    private static final String[] ALGS = {"MLEAO", "LIWSA", "LIWSA-ML", "NSGA-II"};

    private static class Run {
        String alg;
        long seed;
        List<double[]> planningFront;
        List<double[]> simFront;
        int replayed;
        double hvPlanning;
        double hvSim;
    }

    public static void main(String[] args) throws Exception {
        String[] names = ResultsPaths.expandWorkflowKeyword(args[0]).split(",");
        String[] seedStr = args.length > 1 ? args[1].split(",") : new String[]{"1", "2", "3", "4", "5"};
        String out = args.length > 2 ? args[2] : ResultsPaths.resolve("simulated_front_eval.csv");
        int pop = 30, gens = 100;
        Log.disable();
        // optional 4th argument: A = published algorithm, B = + output archive, C = + archive + online learning
        ResultsPaths.applyVariant(args.length > 3 ? args[3] : "A");

        PrintWriter pw = new PrintWriter(out);
        pw.println("workflow,algorithm,seed,replayed_members,sim_nondominated_points,"
                + "hv_planning_convention,hv_simulated");

        for (String name : names) {
            name = name.trim();
            String dax = "config/dax/" + name + ".xml";
            LIWSABenchmarkExample.RunResult heft = LIWSABenchmarkExample.runPlanning(dax,
                Parameters.PlanningAlgorithm.HEFT, Parameters.SchedulingAlgorithm.STATIC,
                "HEFT", 0L, pop, gens, null);
            LIWSABenchmarkExample.RunResult mm = LIWSABenchmarkExample.runPlanning(dax,
                Parameters.PlanningAlgorithm.INVALID, Parameters.SchedulingAlgorithm.MINMIN,
                "Min-Min", 0L, pop, gens, null);
            List<Map<Integer, Integer>> seeds = new ArrayList<>();
            seeds.add(heft.assignment);
            seeds.add(mm.assignment);

            List<Run> runs = new ArrayList<>();
            for (String s : seedStr) {
                long seed = Long.parseLong(s.trim());
                for (String alg : ALGS) {
                    Parameters.PlanningAlgorithm pa = alg.equals("MLEAO") ? Parameters.PlanningAlgorithm.MLEAO
                        : alg.equals("LIWSA") ? Parameters.PlanningAlgorithm.LIWSA
                        : alg.equals("LIWSA-ML") ? Parameters.PlanningAlgorithm.LIWSAML
                        : Parameters.PlanningAlgorithm.NSGAII;
                    LIWSABenchmarkExample.RunResult r = LIWSABenchmarkExample.runPlanning(dax, pa,
                        Parameters.SchedulingAlgorithm.STATIC, alg, seed, pop, gens, seeds);
                    List<Map<Integer, Integer>> members =
                        alg.equals("MLEAO") ? MLEAOPlanningAlgorithm.lastRun.paretoFrontAssignments
                        : alg.equals("NSGA-II") ? NSGAIIPlanningAlgorithm.lastRun.paretoFrontAssignments
                        : LIWSAPlanningAlgorithm.lastRun.paretoFrontAssignments;
                    Run run = new Run();
                    run.alg = alg;
                    run.seed = seed;
                    run.planningFront = r.frontPoints;
                    Set<Map<Integer, Integer>> distinct = new LinkedHashSet<>(members);
                    List<double[]> replayed = new ArrayList<>();
                    for (Map<Integer, Integer> a : distinct) {
                        replayed.add(replay(dax, a));
                    }
                    run.replayed = distinct.size();
                    run.simFront = nonDominated(replayed);
                    runs.add(run);
                }
            }

            // original convention: shared reference over planning fronts + simulated HEFT/Min-Min points
            List<List<double[]>> planningFronts = new ArrayList<>();
            List<double[]> heftPt = single(heft.makespan, heft.cost);
            List<double[]> mmPt = single(mm.makespan, mm.cost);
            planningFronts.add(heftPt);
            planningFronts.add(mmPt);
            for (Run r : runs) { planningFronts.add(r.planningFront); }
            double[] refP = ParetoMetrics.sharedReferencePoint(planningFronts);

            // simulator convention: every front is simulator-measured
            List<List<double[]>> simFronts = new ArrayList<>();
            simFronts.add(heftPt);
            simFronts.add(mmPt);
            for (Run r : runs) { simFronts.add(r.simFront); }
            double[] refS = ParetoMetrics.sharedReferencePoint(simFronts);

            pw.printf("%s,HEFT,0,1,1,%.4f,%.4f%n", name,
                ParetoMetrics.hypervolume2D(heftPt, refP[0], refP[1]),
                ParetoMetrics.hypervolume2D(heftPt, refS[0], refS[1]));
            pw.printf("%s,Min-Min,0,1,1,%.4f,%.4f%n", name,
                ParetoMetrics.hypervolume2D(mmPt, refP[0], refP[1]),
                ParetoMetrics.hypervolume2D(mmPt, refS[0], refS[1]));
            for (Run r : runs) {
                r.hvPlanning = ParetoMetrics.hypervolume2D(r.planningFront, refP[0], refP[1]);
                r.hvSim = ParetoMetrics.hypervolume2D(r.simFront, refS[0], refS[1]);
                pw.printf("%s,%s,%d,%d,%d,%.4f,%.4f%n", name, r.alg, r.seed, r.replayed,
                    r.simFront.size(), r.hvPlanning, r.hvSim);
            }
            pw.flush();
            System.out.println("done " + name);
        }
        pw.close();
        System.out.println();
        ResultsSummary.print(out, null, false, "hv_simulated");
        ResultsPaths.printHints(out);
    }

    private static List<double[]> single(double m, double c) {
        List<double[]> l = new ArrayList<>();
        l.add(new double[]{m, c});
        return l;
    }

    /** Executes the given cloudlet->VM assignment unchanged in WorkflowSim and returns {makespan, cost}. */
    private static double[] replay(String dax, Map<Integer, Integer> assignment) {
        List<Map<Integer, Integer>> one = new ArrayList<>();
        one.add(assignment);
        LIWSABenchmarkExample.RunResult r = LIWSABenchmarkExample.runPlanning(dax,
            Parameters.PlanningAlgorithm.LIWSA, Parameters.SchedulingAlgorithm.STATIC,
            "replay", 0L, 1, 0, one);
        return new double[]{r.makespan, r.cost};
    }

    private static List<double[]> nonDominated(List<double[]> pts) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) {
            double[] p = pts.get(i);
            boolean dominated = false;
            for (int j = 0; j < pts.size() && !dominated; j++) {
                if (i == j) { continue; }
                double[] q = pts.get(j);
                boolean weak = q[0] <= p[0] && q[1] <= p[1];
                boolean strict = q[0] < p[0] || q[1] < p[1];
                if (weak && (strict || (j < i && q[0] == p[0] && q[1] == p[1]))) {
                    dominated = true;
                }
            }
            if (!dominated) { out.add(p); }
        }
        return out;
    }
}
