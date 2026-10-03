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

/**
 * Single place that decides where every output file is written.
 *
 * All CSV results, summaries, logs and figures go under {@link #OUTPUT_DIR}
 * (relative to the directory the program is started from, i.e. the repository
 * root). Change the constant to relocate everything; the Python scripts have
 * a matching OUTPUT_DIR constant at the top of each file.
 *
 * Note for shells: the folder name contains an ampersand, so always quote it
 * on the command line ("Output&Results/...") in cmd.exe, PowerShell and bash.
 */
public final class ResultsPaths {

    public static final String OUTPUT_DIR = "Output&Results";

    private ResultsPaths() {
    }

    /** Output folder-relative path for a file name, e.g. resolve("benchmark_results.csv"). */
    public static String resolve(String fileName) {
        return OUTPUT_DIR + "/" + fileName;
    }

    public static final String SMALL_WORKFLOWS =
        "Montage_25,Montage_50,Montage_100,CyberShake_30,CyberShake_50,CyberShake_100,"
        + "Sipht_30,Sipht_60,Sipht_100,Epigenomics_24,Epigenomics_46,Epigenomics_100,"
        + "Inspiral_30,Inspiral_50,Inspiral_100";
    public static final String LARGE_WORKFLOWS =
        "Montage_1000,CyberShake_1000,Sipht_1000,Inspiral_1000,Epigenomics_997";

    /** ALL / SMALL / LARGE (any case) -> the workflow lists above; anything else is returned unchanged. */
    public static String expandWorkflowKeyword(String arg) {
        String a = arg == null ? "" : arg.trim();
        if (a.equalsIgnoreCase("SMALL")) { return SMALL_WORKFLOWS; }
        if (a.equalsIgnoreCase("LARGE")) { return LARGE_WORKFLOWS; }
        if (a.equalsIgnoreCase("ALL")) { return SMALL_WORKFLOWS + "," + LARGE_WORKFLOWS; }
        return a;
    }

    /**
     * Switches the optional LIWSA features in code, so no -D command-line option is needed.
     * A = published algorithm; B = + external Pareto archive; C = + archive + online learning.
     */
    public static void applyVariant(String variant) {
        String v = variant == null ? "A" : variant.trim().toUpperCase();
        org.workflowsim.planning.LIWSAPlanningAlgorithm.CONFIG_OUTPUT_ARCHIVE = v.equals("B") || v.equals("C");
        org.workflowsim.planning.LIWSAMLPlanningAlgorithm.CONFIG_ONLINE_LEARNING = v.equals("C");
    }

    /** Prints where the files are and how to turn the CSV into figures. */
    public static void printHints(String csvPath) {
        String base = csvPath.endsWith(".csv") ? csvPath.substring(0, csvPath.length() - 4) : csvPath;
        System.out.println();
        System.out.println("Where everything is saved (folder \"" + OUTPUT_DIR + "\"):");
        System.out.println("  results CSV   : " + csvPath);
        System.out.println("  summary       : " + base + "_summary.txt  and  " + base + "_summary.csv");
        System.out.println("  figures       : " + OUTPUT_DIR + "/figures/   (created by the Python script below)");
        System.out.println("To see the figures and the summary (run from the repository root, keep the quotes):");
        System.out.println("  python generate_figures.py \"" + csvPath + "\"");
        System.out.println("  python generate_figures.py --all          (every results file in " + OUTPUT_DIR + ")");
        System.out.println("  python summarize_results.py \"" + csvPath + "\"   (summary table only)");
    }

    /** Creates the output folder if it does not exist yet. */
    public static void ensureDir() {
        File d = new File(OUTPUT_DIR);
        if (!d.exists()) {
            d.mkdirs();
        }
    }
}
