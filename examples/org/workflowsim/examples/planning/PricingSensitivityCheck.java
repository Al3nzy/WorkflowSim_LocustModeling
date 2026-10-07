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
import org.workflowsim.utils.Parameters;

/**
 * Does the ranking of the algorithms depend on the VM price list?
 *
 * Re-runs HEFT, NSGA-II and LIWSA-ML (final design) on a few workflows under three price lists for the
 * four MIPS tiers (250/500/1000/2000): the evaluation default (0.15/0.30/0.60/0.90 per second), a strictly
 * linear list (0.15/0.30/0.60/1.20) and a convex, speed-penalising list (0.15/0.35/0.85/2.00). The
 * hypervolume reference point is computed per (workflow, price list) over all fronts.
 *
 * Usage (from the repository root):
 *   java -cp "bin;lib/*" org.workflowsim.examples.planning.PricingSensitivityCheck "Montage_50,CyberShake_50,Sipht_30" 1,2,3,4,5 "Output&Results/paper_pricing.csv"
 */
public class PricingSensitivityCheck {

    private static final double[][] SCHEMES = {
        {0.15, 0.30, 0.60, 0.90}, {0.15, 0.30, 0.60, 1.20}, {0.15, 0.35, 0.85, 2.00}};
    private static final String[] SCHEME_NAMES = {"Original", "Linear", "Speed-premium"};

    public static void main(String[] args) throws Exception {
        String[] names = (args.length > 0 ? args[0] : "Montage_50,CyberShake_50,Sipht_30").split(",");
        String[] seedStr = args.length > 1 ? args[1].split(",") : new String[]{"1", "2", "3", "4", "5"};
        String out = args.length > 2 ? args[2] : ResultsPaths.resolve("paper_pricing.csv");
        Log.disable();
        ResultsPaths.ensureDir();
        double[] original = new double[4];
        for (int i = 0; i < 4; i++) { original[i] = LIWSABenchmarkExample.VM_TYPES[i][2]; }
        PrintWriter pw = new PrintWriter(out);
        pw.println("workflow,scheme,algorithm,seed,hypervolume");
        try {
            for (String name : names) {
                for (int sc = 0; sc < SCHEMES.length; sc++) {
                    for (int i = 0; i < 4; i++) { LIWSABenchmarkExample.VM_TYPES[i][2] = SCHEMES[sc][i]; }
                    String dax = "config/dax/" + name.trim() + ".xml";
                    LIWSABenchmarkExample.RunResult heft = LIWSABenchmarkExample.runPlanning(dax,
                        Parameters.PlanningAlgorithm.HEFT, Parameters.SchedulingAlgorithm.STATIC, "HEFT", 0L, 30, 100, null);
                    LIWSABenchmarkExample.RunResult mm = LIWSABenchmarkExample.runPlanning(dax,
                        Parameters.PlanningAlgorithm.INVALID, Parameters.SchedulingAlgorithm.MINMIN, "Min-Min", 0L, 30, 100, null);
                    List<Map<Integer, Integer>> seeds = new ArrayList<>();
                    seeds.add(heft.assignment);
                    seeds.add(mm.assignment);
                    List<String> labels = new ArrayList<>();
                    List<Long> seedOf = new ArrayList<>();
                    List<LIWSABenchmarkExample.RunResult> runs = new ArrayList<>();
                    labels.add("HEFT"); seedOf.add(0L); runs.add(heft);
                    for (String s : seedStr) {
                        long seed = Long.parseLong(s.trim());
                        runs.add(LIWSABenchmarkExample.runPlanning(dax, Parameters.PlanningAlgorithm.NSGAII,
                            Parameters.SchedulingAlgorithm.STATIC, "NSGA-II", seed, 30, 100, seeds));
                        labels.add("NSGA-II"); seedOf.add(seed);
                        runs.add(LIWSABenchmarkExample.runPlanning(dax, Parameters.PlanningAlgorithm.LIWSAML,
                            Parameters.SchedulingAlgorithm.STATIC, "LIWSA-ML", seed, 30, 100, seeds));
                        labels.add("LIWSA-ML"); seedOf.add(seed);
                    }
                    List<List<double[]>> fronts = new ArrayList<>();
                    for (LIWSABenchmarkExample.RunResult r : runs) { fronts.add(r.frontPoints); }
                    double[] ref = ParetoMetrics.sharedReferencePoint(fronts);
                    for (int i = 0; i < runs.size(); i++) {
                        pw.printf("%s,%s,%s,%d,%.4f%n", name.trim(), SCHEME_NAMES[sc], labels.get(i), seedOf.get(i),
                            ParetoMetrics.hypervolume2D(runs.get(i).frontPoints, ref[0], ref[1]));
                    }
                    pw.flush();
                    System.out.println("done " + name + " / " + SCHEME_NAMES[sc]);
                }
            }
        } finally {
            for (int i = 0; i < 4; i++) { LIWSABenchmarkExample.VM_TYPES[i][2] = original[i]; }
            pw.close();
        }
    }
}
