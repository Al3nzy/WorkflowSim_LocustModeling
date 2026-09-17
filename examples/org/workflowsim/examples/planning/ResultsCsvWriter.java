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
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import org.cloudbus.cloudsim.Log;

/**
 * One shared CSV schema used by LIWSAPlanningAlgorithmExample,
 * LIWSAMLPlanningAlgorithmExample, MLEAOPlanningAlgorithmExample, and
 * LIWSABenchmarkExample, so results from any of them can be concatenated
 * or compared directly without reconciling different column layouts.
 *
 * Each row is one completed simulation run (one workflow, one algorithm,
 * one seed). The single-algorithm examples write exactly one row; the
 * benchmark writes one row per (workflow, algorithm, seed) combination,
 * flushed to disk immediately after each run completes -- not buffered
 * to the end -- so a long benchmark that is interrupted partway through
 * still leaves every completed result safely on disk.
 */
public class ResultsCsvWriter {

    private static final String HEADER =
        "workflow,algorithm,seed,makespan,cost,pareto_front_size,hypervolume,"
        + "avg_utilization_pct,fairness_index,speedup,"
        + "search_wallclock_ms,sim_wallclock_ms";

    /**
     * Opens (overwriting any existing file) a new CSV at filePath and
     * writes the header row. Creates parent directories if needed.
     * Returns null (and logs a warning) if the file could not be opened,
     * so callers can simply skip writing rather than crash the
     * simulation over a results-logging failure.
     */
    public static PrintWriter open(String filePath) {
        try {
            File f = new File(filePath);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            PrintWriter pw = new PrintWriter(new FileWriter(f, false), true); // autoFlush
            pw.println(HEADER);
            return pw;
        } catch (IOException e) {
            Log.printLine("WARNING: could not open results file at " + filePath
                + " (" + e.getMessage() + "). Continuing without CSV output.");
            return null;
        }
    }

    /**
     * Writes one result row and flushes immediately (the writer returned
     * by open() is already in autoFlush mode, but this makes the
     * crash-safety intent explicit at the call site). Safe to call with
     * pw == null (writes nothing, does not throw), so callers don't need
     * to null-check before every call.
     */
    public static void writeRow(PrintWriter pw, String workflow, String algorithm, long seed,
            double makespan, double cost, int paretoFrontSize, double hypervolume,
            double avgUtilizationFraction, double fairnessIndex, double speedup,
            long searchWallClockMillis, long simWallClockMillis) {
        if (pw == null) {
            return;
        }
        pw.printf("%s,%s,%d,%.4f,%.4f,%d,%.4f,%.2f,%.4f,%.4f,%d,%d%n",
            workflow, algorithm, seed, makespan, cost, paretoFrontSize, hypervolume,
            avgUtilizationFraction * 100.0, fairnessIndex, speedup,
            searchWallClockMillis, simWallClockMillis);
        pw.flush();
    }

    /**
     * Opens filePath for appending (writing the header only if the file
     * is new/empty), so several separate JVM invocations -- e.g. one per
     * workflow batch -- can accumulate rows into a single results file.
     */
    public static PrintWriter openAppend(String filePath) {
        try {
            File f = new File(filePath);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            boolean needsHeader = !f.exists() || f.length() == 0;
            PrintWriter pw = new PrintWriter(new FileWriter(f, true), true); // append, autoFlush
            if (needsHeader) {
                pw.println(HEADER);
            }
            return pw;
        } catch (IOException e) {
            Log.printLine("WARNING: could not open results file at " + filePath
                + " (" + e.getMessage() + "). Continuing without CSV output.");
            return null;
        }
    }

    public static void close(PrintWriter pw) {
        if (pw != null) {
            pw.close();
        }
    }
}
