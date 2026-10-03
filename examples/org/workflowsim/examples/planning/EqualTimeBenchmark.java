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
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.planning.NSGAIIPlanningAlgorithm;
import org.workflowsim.utils.Parameters;

/**
 * Matched-wall-clock comparison of LIWSA-ML and NSGA-II.
 *
 * The standard benchmark gives both algorithms the same population size and
 * generation count, which is not the same computational budget. Here, for
 * every workflow and seed:
 *   1. LIWSA-ML runs with the standard 100 generations; its search time T is
 *      recorded.
 *   2. NSGA-II runs with 100 generations (matched generations), and its time
 *      t100 is recorded.
 *   3. NSGA-II is run again with G = max(100, round(100 * T / t100))
 *      generations, so that its search time is approximately T (matched
 *      wall-clock). Neither algorithm's code is modified; only the
 *      generation count of NSGA-II changes.
 * Hypervolume uses one shared reference point per workflow over all three
 * configurations and all seeds, with HEFT and Min-Min measured in the
 * simulator, exactly as in the standard benchmark.
 *
 * Because step 3 uses a time ratio measured in step 1-2, the achieved NSGA-II
 * time differs from T by normal timing noise; achieved times are written to
 * the CSV so the match can be checked. Run on an otherwise idle machine.
 *
 * Usage:
 *   java ... EqualTimeBenchmark "Montage_100,Sipht_100" 1,2,3,4,5 "Output&Results/equal_time.csv"
 */
public class EqualTimeBenchmark {

    private static class Row {
        String workflow;
        String config;
        long seed;
        int generations;
        long searchMs;
        List<double[]> front;
        double hv;
    }

    public static void main(String[] args) throws Exception {
        String[] names = args.length > 0 ? args[0].split(",") : new String[]{"Montage_100"};
        String[] seedStr = args.length > 1 ? args[1].split(",") : new String[]{"1", "2", "3", "4", "5"};
        String out = args.length > 2 ? args[2] : ResultsPaths.resolve("equal_time.csv");
        int pop = 30, gens = 100;
        Log.disable();

        PrintWriter pw = new PrintWriter(out);
        pw.println("workflow,config,seed,generations,search_ms,front_size,hypervolume");

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

            List<Row> rows = new ArrayList<>();
            for (String s : seedStr) {
                long seed = Long.parseLong(s.trim());

                LIWSABenchmarkExample.RunResult lm = LIWSABenchmarkExample.runPlanning(dax,
                    Parameters.PlanningAlgorithm.LIWSAML, Parameters.SchedulingAlgorithm.STATIC,
                    "LIWSA-ML", seed, pop, gens, seeds);
                rows.add(make(name, "LIWSA-ML_100gen", seed, gens, lm));

                LIWSABenchmarkExample.RunResult n100 = LIWSABenchmarkExample.runPlanning(dax,
                    Parameters.PlanningAlgorithm.NSGAII, Parameters.SchedulingAlgorithm.STATIC,
                    "NSGA-II", seed, pop, gens, seeds);
                rows.add(make(name, "NSGA-II_100gen", seed, gens, n100));

                double ratio = (double) Math.max(1L, lm.searchWallClockMillis)
                        / (double) Math.max(1L, n100.searchWallClockMillis);
                int g = (int) Math.max(gens, Math.round(gens * ratio));
                LIWSABenchmarkExample.RunResult nT = LIWSABenchmarkExample.runPlanning(dax,
                    Parameters.PlanningAlgorithm.NSGAII, Parameters.SchedulingAlgorithm.STATIC,
                    "NSGA-II", seed, pop, g, seeds);
                rows.add(make(name, "NSGA-II_matchedTime", seed, g, nT));
            }

            List<List<double[]>> fronts = new ArrayList<>();
            List<double[]> h = new ArrayList<>();
            h.add(new double[]{heft.makespan, heft.cost});
            List<double[]> m = new ArrayList<>();
            m.add(new double[]{mm.makespan, mm.cost});
            fronts.add(h);
            fronts.add(m);
            for (Row r : rows) {
                fronts.add(r.front);
            }
            double[] ref = ParetoMetrics.sharedReferencePoint(fronts);
            for (Row r : rows) {
                r.hv = ParetoMetrics.hypervolume2D(r.front, ref[0], ref[1]);
                pw.printf("%s,%s,%d,%d,%d,%d,%.4f%n", r.workflow, r.config, r.seed,
                    r.generations, r.searchMs, r.front.size(), r.hv);
            }
            pw.flush();
            System.out.println("done " + name);
        }
        pw.close();
        System.out.println();
        ResultsSummary.print(out, null);
        ResultsPaths.printHints(out);
    }

    private static Row make(String wf, String config, long seed, int gens,
            LIWSABenchmarkExample.RunResult r) {
        Row row = new Row();
        row.workflow = wf;
        row.config = config;
        row.seed = seed;
        row.generations = gens;
        row.searchMs = r.searchWallClockMillis;
        row.front = r.frontPoints;
        return row;
    }
}
