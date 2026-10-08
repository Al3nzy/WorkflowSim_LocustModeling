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

/**
 * One command that produces every result file the paper needs, with the final algorithms
 * (LIWSA with the external archive; LIWSA-ML with OLS warm start, online learning and archive).
 *
 *   1. main benchmark, all workflows          -> paper_main.csv
 *   1b. component variants A and B             -> paper_variant_A.csv, paper_variant_B.csv
 *   2. density ablation, all workflows         -> paper_density_ablation.csv
 *   2b. NSGA-II with the external archive      -> paper_nsga2_archive.csv
 *   3. OLS vs naive features (5 workflows)     -> paper_naive.csv
 *   4. phase-mixing weight sweep (5 workflows) -> paper_lambda.csv
 *   5. softmax-temperature sweep (5 workflows) -> paper_theta.csv
 *   6. VM pricing sensitivity (3 workflows)    -> paper_pricing.csv
 *   7. matched wall-clock NSGA-II comparison   -> paper_equal_time.csv
 *
 * Hypervolume uses one planning-level evaluator for all six algorithms (HEFT and Min-Min included).
 *
 * Usage (from the repository root; ':' instead of ';' on macOS/Linux):
 *   java -cp "bin;lib/*" org.workflowsim.examples.planning.RunPaperExperiments
 *   java -cp "bin;lib/*" org.workflowsim.examples.planning.RunPaperExperiments "Montage_25,Sipht_30"   (quick test)
 *
 * Everything is written to Output&Results. Send that folder (zipped) back; each step also prints and saves
 * its own summary.
 */
public class RunPaperExperiments {

    public static void main(String[] args) throws Exception {
        String which = args.length > 0 ? ResultsPaths.expandWorkflowKeyword(args[0]) : ResultsPaths.expandWorkflowKeyword("ALL");
        String five = args.length > 0 ? which : "Montage_100,CyberShake_100,Sipht_100,Epigenomics_100,Inspiral_100";
        ResultsPaths.ensureDir();
        ResultsPaths.applyVariant("C");               // the final design

        // {kind, workflows, csv, mode, variant}; variants A and B are the component-analysis runs
        // (A = published algorithms, B = + external archive); the final design C is paper_main.csv.
        String[][] steps = {
            {"benchmark", which, "paper_main.csv", "", "C"},
            {"benchmark", which, "paper_variant_A.csv", "", "A"},
            {"benchmark", which, "paper_variant_B.csv", "", "B"},
            {"benchmark", which, "paper_density_ablation.csv", "ablation", "C"},
            {"benchmark", which, "paper_nsga2_archive.csv", "", "C"},
            {"sens", five, "paper_naive.csv", "naive", "C"},
            {"sens", five, "paper_lambda.csv", "lambda", "C"},
            {"sens", five, "paper_theta.csv", "theta", "C"}};
        for (String[] st : steps) {
            String csv = ResultsPaths.resolve(st[2]);
            new java.io.File(csv).delete();           // these runs append, so start from an empty file
            System.out.println();
            System.out.println("#".repeat(78));
            System.out.println("# STEP: " + st[2]);
            System.out.println("#".repeat(78));
            ResultsPaths.applyVariant(st[4]);
            // control run: the same external archive attached to NSGA-II (off in every other step)
            org.workflowsim.planning.NSGAIIPlanningAlgorithm.CONFIG_OUTPUT_ARCHIVE = st[2].equals("paper_nsga2_archive.csv");
            if (st[0].equals("benchmark")) {
                if (st[3].isEmpty()) {
                    LIWSABenchmarkExample.main(new String[]{st[1], csv});
                } else {
                    LIWSABenchmarkExample.main(new String[]{st[1], csv, st[3]});
                }
            } else {
                SensitivityAblationExample.main(new String[]{st[3], st[1], csv});
            }
            ResultsPaths.applyVariant("C");           // in case a step changed a switch
        }
        String pricing = ResultsPaths.resolve("paper_pricing.csv");
        System.out.println();
        System.out.println("#".repeat(78));
        System.out.println("# STEP: paper_pricing.csv");
        System.out.println("#".repeat(78));
        PricingSensitivityCheck.main(new String[]{"Montage_50,CyberShake_50,Sipht_30", "1,2,3,4,5", pricing});
        // matched wall-clock comparison of LIWSA-ML with NSGA-II (timing based: run on an otherwise idle machine)
        ResultsPaths.applyVariant("C");
        String eq = ResultsPaths.resolve("paper_equal_time.csv");
        System.out.println();
        System.out.println("#".repeat(78));
        System.out.println("# STEP: paper_equal_time.csv");
        System.out.println("#".repeat(78));
        EqualTimeBenchmark.main(new String[]{which, "1,2,3,4,5", eq});
        System.out.println();
        System.out.println("ALL STEPS DONE. Results are in Output&Results/ Results. ");
    }
}
