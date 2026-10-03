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

/**
 * Runs the whole benchmark three times and prints one table that says which algorithm is better.
 *
 *   A  the published algorithms (nothing extra switched on)
 *   B  A + external Pareto archive for LIWSA / LIWSA-ML   (what -Dliwsa.outputArchive=true does)
 *   C  B + online learning in LIWSA-ML                   (what -Dliwsa.onlineLearning=true does)
 *
 * HEFT, Min-Min, MLEAO and NSGA-II are identical in all three runs; only LIWSA and LIWSA-ML change.
 * No command-line option is needed: the variants are switched in code.
 *
 * Usage (from the repository root; use ':' instead of ';' on macOS/Linux):
 *   java -cp "bin;lib/*" org.workflowsim.examples.planning.RunAllVariants
 *   java -cp "bin;lib/*" org.workflowsim.examples.planning.RunAllVariants SMALL     (15 workflows, faster)
 *   java -cp "bin;lib/*" org.workflowsim.examples.planning.RunAllVariants "Montage_25,Sipht_30"
 *
 * Output (folder Output&Results): variant_A_published.csv, variant_B_archive.csv,
 * variant_C_archive_online.csv (+ their _summary files) and variants_comparison.txt.
 */
public class RunAllVariants {

    public static void main(String[] args) throws Exception {
        String which = args.length > 0 ? ResultsPaths.expandWorkflowKeyword(args[0]) : "";
        ResultsPaths.ensureDir();
        String[][] variants = {
            {"A", "variant_A_published.csv", "A: published algorithms"},
            {"B", "variant_B_archive.csv", "B: + Pareto archive"},
            {"C", "variant_C_archive_online.csv", "C: + archive + online learning"}};

        for (String[] v : variants) {
            String csv = ResultsPaths.resolve(v[1]);
            new File(csv).delete();                       // start every variant from an empty file
            System.out.println();
            System.out.println("#".repeat(78));
            System.out.println("# VARIANT " + v[2]);
            System.out.println("#".repeat(78));
            ResultsPaths.applyVariant(v[0]);
            LIWSABenchmarkExample.main(new String[]{which, csv});
        }
        ResultsPaths.applyVariant("A");

        StringBuilder sb = new StringBuilder();
        String bar = "=".repeat(100);
        sb.append(bar).append('\n');
        sb.append("WHO IS BETTER?  Hypervolume difference in percent; positive = the first algorithm is better.\n");
        sb.append("Each cell: mean % / median % / workflows where the first algorithm is higher.\n");
        sb.append("Compared inside one run per variant, so both algorithms use the same reference point.\n");
        sb.append(bar).append('\n');
        String[][] pairs = {{"LIWSA-ML", "NSGA-II"}, {"LIWSA", "NSGA-II"}, {"LIWSA-ML", "MLEAO"},
                            {"LIWSA-ML", "HEFT"}, {"LIWSA-ML", "Min-Min"}};
        int[][] ranges = {{0, 100000}, {0, 899}, {900, 100000}};
        String[] rangeNames = {"all workflows", "small (24-100 tasks)", "large (997-1000 tasks)"};
        for (int r = 0; r < ranges.length; r++) {
            sb.append('\n').append(rangeNames[r]).append('\n');
            sb.append(String.format("%-24s", "comparison"));
            for (String[] v : variants) { sb.append(String.format(" | %-30s", v[2].substring(0, Math.min(30, v[2].length())))); }
            sb.append('\n').append("-".repeat(24 + 3 * 33)).append('\n');
            for (String[] p : pairs) {
                sb.append(String.format("%-24s", p[0] + " vs " + p[1]));
                for (String[] v : variants) {
                    double[] x = ResultsSummary.relative(ResultsPaths.resolve(v[1]), p[0], p[1], ranges[r][0], ranges[r][1]);
                    String cell = x == null ? "-" : String.format("%+7.2f / %+7.2f / %2d of %2d", x[1], x[2], (int) x[3], (int) x[0]);
                    sb.append(String.format(" | %-30s", cell));
                }
                sb.append('\n');
            }
        }
        sb.append('\n').append(bar).append('\n');
        String text = sb.toString();
        System.out.println();
        System.out.println(text);
        try (PrintWriter pw = new PrintWriter(ResultsPaths.resolve("variants_comparison.txt"))) {
            pw.print(text);
        }
        System.out.println("Saved: " + ResultsPaths.resolve("variants_comparison.txt"));
        System.out.println("Figures for a variant:  python generate_figures.py \"" + ResultsPaths.resolve("variant_C_archive_online.csv") + "\"");
    }
}
