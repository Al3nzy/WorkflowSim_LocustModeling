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
 *   2. density ablation, all workflows         -> paper_density_ablation.csv
 *   3. OLS vs naive features (5 workflows)     -> paper_naive.csv
 *   4. phase-mixing weight sweep (5 workflows) -> paper_lambda.csv
 *   5. softmax-temperature sweep (5 workflows) -> paper_theta.csv
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

        String[][] steps = {
            {"benchmark", which, "paper_main.csv", ""},
            {"benchmark", which, "paper_density_ablation.csv", "ablation"},
            {"sens", five, "paper_naive.csv", "naive"},
            {"sens", five, "paper_lambda.csv", "lambda"},
            {"sens", five, "paper_theta.csv", "theta"}};
        for (String[] st : steps) {
            String csv = ResultsPaths.resolve(st[2]);
            new java.io.File(csv).delete();           // these runs append, so start from an empty file
            System.out.println();
            System.out.println("#".repeat(78));
            System.out.println("# STEP: " + st[2]);
            System.out.println("#".repeat(78));
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
        System.out.println();
        System.out.println("ALL STEPS DONE. Please zip the folder \"" + ResultsPaths.OUTPUT_DIR + "\" and send it.");
    }
}
