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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a results CSV and prints (and saves) a summary of all algorithms in it.
 *
 * Works for any CSV with a header row, one identifier column naming the
 * algorithm/configuration ("algorithm", "config" or "run"), optionally a
 * "workflow" and a "seed" column, and numeric metric columns. If several rows
 * share the same (workflow, algorithm, seed) the LAST one is used, so a
 * re-run that appended to an existing file does not double count.
 *
 * Output (also written next to the CSV as &lt;name&gt;_summary.txt and
 * &lt;name&gt;_summary.csv):
 *   1. every numeric column averaged per algorithm (mean, or median),
 *   2. for hypervolume columns: a score normalised per workflow (100 = best
 *      algorithm on that workflow), wins and average rank,
 *   3. the per-workflow mean hypervolume of every algorithm,
 *   4. the relative hypervolume difference of the proposed method against
 *      each other algorithm (when LIWSA-ML is present).
 */
public final class ResultsSummary {

    private static final Set<String> ID_COLUMNS = new HashSet<>(Arrays.asList(
        "workflow", "algorithm", "config", "run", "seed"));

    private ResultsSummary() {
    }

    /** java -cp "bin:lib/*" org.workflowsim.examples.planning.ResultsSummary "Output&Results/file.csv" [hv_column] */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("usage: ResultsSummary <results.csv> [hypervolume column]");
            return;
        }
        print(args[0], null, false, args.length > 1 ? args[1] : null);
    }

    public static void print(String csvPath, Set<String> workflowFilter) {
        print(csvPath, workflowFilter, false);
    }

    public static void print(String csvPath, Set<String> workflowFilter, boolean useMedian) {
        print(csvPath, workflowFilter, useMedian, null);
    }

    /** hvColumn: which hypervolume column drives the HV score / wins / rank (null = the first one). */
    public static void print(String csvPath, Set<String> workflowFilter, boolean useMedian, String hvColumn) {
        try {
            String text = build(csvPath, workflowFilter, useMedian, hvColumn);
            if (text == null) {
                System.out.println("(No result rows found in " + csvPath + ": nothing to summarise.)");
                return;
            }
            System.out.println(text);
            String base = csvPath.endsWith(".csv") ? csvPath.substring(0, csvPath.length() - 4) : csvPath;
            try (PrintWriter pw = new PrintWriter(base + "_summary.txt")) {
                pw.print(text);
            }
            System.out.println("Summary saved to: " + base + "_summary.txt  (table: " + base + "_summary.csv)");
        } catch (IOException e) {
            System.out.println("WARNING: could not summarise " + csvPath + " (" + e.getMessage() + ")");
        }
    }

    private static String build(String csvPath, Set<String> workflowFilter, boolean useMedian,
            String hvColumn) throws IOException {
        List<String[]> rows = new ArrayList<>();
        String[] header;
        try (BufferedReader br = new BufferedReader(new FileReader(csvPath))) {
            String line = br.readLine();
            if (line == null) { return null; }
            header = line.split(",", -1);
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) { rows.add(line.split(",", -1)); }
            }
        }
        int iAlg = indexOf(header, "algorithm", "config", "run");
        int iWf = indexOf(header, "workflow");
        int iSeed = indexOf(header, "seed");
        if (iAlg < 0) { return null; }

        // metric columns = numeric, non-identifier
        List<Integer> metricCols = new ArrayList<>();
        for (int c = 0; c < header.length; c++) {
            if (!ID_COLUMNS.contains(header[c]) && !rows.isEmpty() && isNumber(rows.get(0), c)) {
                metricCols.add(c);
            }
        }

        // keep the last row for each (workflow, algorithm, seed)
        Map<String, String[]> unique = new LinkedHashMap<>();
        for (String[] r : rows) {
            if (r.length < header.length) { continue; }
            String wf = iWf >= 0 ? r[iWf] : "-";
            if (workflowFilter != null && iWf >= 0 && !workflowFilter.contains(wf)) { continue; }
            unique.put(wf + "|" + r[iAlg] + "|" + (iSeed >= 0 ? r[iSeed] : "0"), r);
        }
        if (unique.isEmpty()) { return null; }

        List<String> algs = new ArrayList<>();
        List<String> workflows = new ArrayList<>();
        for (String[] r : unique.values()) {
            if (!algs.contains(r[iAlg])) { algs.add(r[iAlg]); }
            String wf = iWf >= 0 ? r[iWf] : "-";
            if (!workflows.contains(wf)) { workflows.add(wf); }
        }

        String stat = useMedian ? "median" : "mean";
        StringBuilder sb = new StringBuilder();
        String bar = "=".repeat(100);
        sb.append(bar).append('\n');
        sb.append("RESULTS SUMMARY  (").append(csvPath).append(")\n");
        sb.append(workflows.size()).append(" workflow(s), ").append(algs.size())
          .append(" algorithm(s), ").append(unique.size()).append(" runs; values are the ")
          .append(stat).append(" over workflows and seeds.\n");
        sb.append(bar).append('\n');

        // ---- hypervolume bookkeeping ----
        List<Integer> hvCols = new ArrayList<>();
        for (int c : metricCols) {
            String h = header[c].toLowerCase();
            if (h.startsWith("hv") || h.startsWith("hypervolume")) { hvCols.add(c); }
        }
        Map<String, double[]> hvScore = new LinkedHashMap<>(); // alg -> {score, wins, rank} for first HV column
        int hvCol = hvCols.isEmpty() ? -1 : hvCols.get(0);
        if (hvColumn != null) {
            for (int c : hvCols) { if (header[c].equals(hvColumn)) { hvCol = c; } }
        }
        Map<String, Map<String, Double>> perWfHv = new LinkedHashMap<>(); // wf -> alg -> mean hv
        if (hvCol >= 0) {
            for (String wf : workflows) {
                Map<String, Double> m = new LinkedHashMap<>();
                for (String a : algs) {
                    List<Double> v = new ArrayList<>();
                    for (String[] r : unique.values()) {
                        if ((iWf < 0 || r[iWf].equals(wf)) && r[iAlg].equals(a)) { v.add(num(r[hvCol])); }
                    }
                    if (!v.isEmpty()) { m.put(a, avg(v, false)); }
                }
                perWfHv.put(wf, m);
            }
            for (String a : algs) { hvScore.put(a, new double[3]); }
            for (String wf : workflows) {
                Map<String, Double> m = perWfHv.get(wf);
                double best = 0;
                for (double v : m.values()) { best = Math.max(best, v); }
                List<String> order = new ArrayList<>(m.keySet());
                order.sort((x, y) -> Double.compare(m.get(y), m.get(x)));
                for (String a : m.keySet()) {
                    double[] s = hvScore.get(a);
                    s[0] += best > 0 ? 100.0 * m.get(a) / best : 0;
                    s[2] += order.indexOf(a) + 1;
                    if (order.get(0).equals(a)) { s[1] += 1; }
                }
            }
            for (double[] s : hvScore.values()) { s[0] /= workflows.size(); s[2] /= workflows.size(); }
        }

        // ---- table 1 ----
        List<String> cols = new ArrayList<>();
        StringBuilder csvOut = new StringBuilder("algorithm,runs");
        sb.append(String.format("%-18s %5s", "algorithm", "runs"));
        for (int c : metricCols) {
            String label = shortLabel(header[c]);
            cols.add(header[c]);
            sb.append(String.format(" %11s", label));
            csvOut.append(',').append(header[c]);
        }
        if (hvCol >= 0) {
            sb.append(String.format(" %8s %5s %8s", "HVscore", "wins", "avgRank"));
            csvOut.append(",hv_score_pct,hv_wins,hv_avg_rank");
        }
        sb.append('\n').append("-".repeat(100)).append('\n');
        csvOut.append('\n');
        for (String a : algs) {
            int n = 0;
            for (String[] r : unique.values()) { if (r[iAlg].equals(a)) { n++; } }
            sb.append(String.format("%-18s %5d", a, n));
            csvOut.append(a).append(',').append(n);
            for (int c : metricCols) {
                List<Double> v = new ArrayList<>();
                for (String[] r : unique.values()) { if (r[iAlg].equals(a)) { v.add(num(r[c])); } }
                double val = avg(v, useMedian);
                sb.append(String.format(" %11s", fmt(header[c], val)));
                csvOut.append(',').append(String.format("%.4f", val));
            }
            if (hvCol >= 0) {
                double[] s = hvScore.get(a);
                sb.append(String.format(" %8.1f %5d %8.2f", s[0], (int) s[1], s[2]));
                csvOut.append(String.format(",%.2f,%d,%.3f", s[0], (int) s[1], s[2]));
            }
            sb.append('\n');
            csvOut.append('\n');
        }
        if (hvCol >= 0) {
            sb.append("HVscore = ").append(header[hvCol]).append(" as % of the best algorithm on each workflow, averaged over workflows.\n");
        }

        // ---- table 2: per-workflow hypervolume ----
        if (hvCol >= 0 && workflows.size() > 1) {
            sb.append('\n').append("Mean ").append(header[hvCol]).append(" per workflow (best in each row marked *)\n");
            sb.append(String.format("%-18s", "workflow"));
            for (String a : algs) { sb.append(String.format(" %11s", a.length() > 11 ? a.substring(0, 11) : a)); }
            sb.append('\n').append("-".repeat(100)).append('\n');
            for (String wf : workflows) {
                Map<String, Double> m = perWfHv.get(wf);
                double best = 0;
                for (double v : m.values()) { best = Math.max(best, v); }
                sb.append(String.format("%-18s", wf));
                for (String a : algs) {
                    Double v = m.get(a);
                    String cell = v == null ? "-" : String.format("%.1f%s", v, v == best ? "*" : "");
                    sb.append(String.format(" %11s", cell));
                }
                sb.append('\n');
            }
        }

        // ---- table 3: LIWSA-ML relative to the others ----
        if (hvCol >= 0 && algs.contains("LIWSA-ML") && workflows.size() > 0) {
            sb.append('\n').append("LIWSA-ML relative ").append(header[hvCol]).append(" difference per workflow (positive = LIWSA-ML higher)\n");
            sb.append(String.format("%-18s %10s %10s %12s%n", "vs", "mean %", "median %", "workflows won"));
            for (String b : algs) {
                if (b.equals("LIWSA-ML")) { continue; }
                List<Double> rel = new ArrayList<>();
                int won = 0;
                for (String wf : workflows) {
                    Double x = perWfHv.get(wf).get("LIWSA-ML"), y = perWfHv.get(wf).get(b);
                    if (x != null && y != null && y > 0) {
                        double d = (x / y - 1.0) * 100.0;
                        rel.add(d);
                        if (d > 0) { won++; }
                    }
                }
                if (!rel.isEmpty()) {
                    sb.append(String.format("%-18s %+10.2f %+10.2f %8d / %d%n", b, avg(rel, false), avg(rel, true), won, rel.size()));
                }
            }
        }
        sb.append(bar).append('\n');

        String base = csvPath.endsWith(".csv") ? csvPath.substring(0, csvPath.length() - 4) : csvPath;
        try (PrintWriter pw = new PrintWriter(base + "_summary.csv")) {
            pw.print(csvOut);
        }
        return sb.toString();
    }

    private static int indexOf(String[] header, String... names) {
        for (String n : names) {
            for (int i = 0; i < header.length; i++) {
                if (header[i].equals(n)) { return i; }
            }
        }
        return -1;
    }

    private static boolean isNumber(String[] r, int c) {
        if (c >= r.length) { return false; }
        try { Double.parseDouble(r[c]); return true; } catch (NumberFormatException e) { return false; }
    }

    private static double num(String s) {
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return Double.NaN; }
    }

    private static double avg(List<Double> v, boolean median) {
        List<Double> w = new ArrayList<>();
        for (double d : v) { if (!Double.isNaN(d)) { w.add(d); } }
        if (w.isEmpty()) { return Double.NaN; }
        if (median) {
            w.sort(Double::compare);
            int n = w.size();
            return n % 2 == 1 ? w.get(n / 2) : 0.5 * (w.get(n / 2 - 1) + w.get(n / 2));
        }
        double s = 0;
        for (double d : w) { s += d; }
        return s / w.size();
    }

    private static String shortLabel(String col) {
        switch (col) {
            case "makespan": return "makespan";
            case "cost": return "cost";
            case "pareto_front_size": return "front_size";
            case "hypervolume": return "hypervolume";
            case "avg_utilization_pct": return "util_%";
            case "fairness_index": return "fairness";
            case "speedup": return "speedup";
            case "search_wallclock_ms": return "search_s";
            case "sim_wallclock_ms": return "sim_s";
            default: return col.length() > 11 ? col.substring(0, 11) : col;
        }
    }

    private static String fmt(String col, double v) {
        if (Double.isNaN(v)) { return "-"; }
        if (col.endsWith("_ms")) { return String.format("%.2f", v / 1000.0); }
        if (Math.abs(v) >= 10000) { return String.format("%.0f", v); }
        return String.format("%.3f", v);
    }
}
