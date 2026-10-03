package org.workflowsim.examples.planning;

/**
 * Regression harness: prints, at full precision, the final front, simulated
 * makespan/cost and front size for chosen algorithms and seeds so two builds can
 * be compared with diff (ignore searchMs). Variants: LIWSA, LIWSAML, LIWSAND
 * (no density), LIWSAA / LIWSAMLA (output archive), LIWSAMLO / LIWSAMLAO (online
 * learning without / with archive), NSGAII, MLEAO.
 * Usage: java ... RegressionHarness config/dax/Montage_25.xml LIWSA,LIWSAML,NSGAII 1,2,3 [pop gens]
 */

import java.util.*;
import org.workflowsim.planning.*;
import org.workflowsim.utils.Parameters;

public class RegressionHarness {
    public static void main(String[] a) {
        String dax = a[0];
        String[] algs = a[1].split(",");
        String[] seedStr = a[2].split(",");
        int pop = a.length > 3 ? Integer.parseInt(a[3]) : 30;
        int gens = a.length > 4 ? Integer.parseInt(a[4]) : 100;
        org.cloudbus.cloudsim.Log.disable();
        LIWSABenchmarkExample.RunResult heft = LIWSABenchmarkExample.runPlanning(dax, Parameters.PlanningAlgorithm.HEFT,
            Parameters.SchedulingAlgorithm.STATIC, "HEFT", 0L, pop, gens, null);
        LIWSABenchmarkExample.RunResult mm = LIWSABenchmarkExample.runPlanning(dax, Parameters.PlanningAlgorithm.INVALID,
            Parameters.SchedulingAlgorithm.MINMIN, "Min-Min", 0L, pop, gens, null);
        List<Map<Integer,Integer>> seeds = new ArrayList<>();
        seeds.add(heft.assignment); seeds.add(mm.assignment);
        System.out.printf("HEFT sim mk=%.10f cost=%.10f%n", heft.makespan, heft.cost);
        System.out.printf("MINMIN sim mk=%.10f cost=%.10f%n", mm.makespan, mm.cost);
        for (String alg : algs) for (String s : seedStr) {
            long seed = Long.parseLong(s);
            Parameters.PlanningAlgorithm pa; 
            switch (alg) { case "LIWSA": pa = Parameters.PlanningAlgorithm.LIWSA; break;
                case "LIWSAML": pa = Parameters.PlanningAlgorithm.LIWSAML; break;
                case "NSGAII": pa = Parameters.PlanningAlgorithm.NSGAII; break;
                default: pa = Parameters.PlanningAlgorithm.MLEAO; }
            boolean ol = alg.equals("LIWSAMLO") || alg.equals("LIWSAMLAO");
            if (ol) { pa = Parameters.PlanningAlgorithm.LIWSAML; }
            LIWSAMLPlanningAlgorithm.CONFIG_ONLINE_LEARNING = ol;
            boolean arch = alg.equals("LIWSAA") || alg.equals("LIWSAMLA") || alg.equals("LIWSAMLAO");
            if (alg.equals("LIWSAA")) { pa = Parameters.PlanningAlgorithm.LIWSA; }
            if (alg.equals("LIWSAMLA")) { pa = Parameters.PlanningAlgorithm.LIWSAML; }
            LIWSAPlanningAlgorithm.CONFIG_OUTPUT_ARCHIVE = arch;
            if (alg.equals("LIWSAND")) { pa = Parameters.PlanningAlgorithm.LIWSA; LIWSAPlanningAlgorithm.CONFIG_DENSITY_ABLATION = true; }
            LIWSABenchmarkExample.RunResult r = LIWSABenchmarkExample.runPlanning(dax, pa,
                Parameters.SchedulingAlgorithm.STATIC, alg, seed, pop, gens, seeds);
            LIWSAPlanningAlgorithm.CONFIG_DENSITY_ABLATION = false;
            StringBuilder fp = new StringBuilder();
            List<double[]> pts = new ArrayList<>(r.frontPoints);
            pts.sort((x,y)->Double.compare(x[0],y[0]) != 0 ? Double.compare(x[0],y[0]) : Double.compare(x[1],y[1]));
            for (double[] p : pts) fp.append(String.format("(%.12f,%.12f)", p[0], p[1]));
            System.out.printf("RUN %s seed=%d simMk=%.10f simCost=%.10f front=%d searchMs=%d%n  FRONT %s%n",
                alg, seed, r.makespan, r.cost, r.frontPoints.size(), r.searchWallClockMillis, fp);
            try {
                long ev = -1;
                if (pa == Parameters.PlanningAlgorithm.NSGAII) ev = NSGAIIPlanningAlgorithm.lastRun.objectiveEvaluations;
                else if (pa == Parameters.PlanningAlgorithm.MLEAO) ev = MLEAOPlanningAlgorithm.lastRun.objectiveEvaluations;
                else ev = LIWSAPlanningAlgorithm.lastRun.objectiveEvaluations;
                System.out.printf("EVALS %s seed=%d %d%n", alg, seed, ev);
            } catch (Throwable t) { }
            if (pa == Parameters.PlanningAlgorithm.LIWSA || pa == Parameters.PlanningAlgorithm.LIWSAML) {
                LIWSAPlanningAlgorithm.LastRunMetrics m = LIWSAPlanningAlgorithm.lastRun;
                System.out.printf("  PRED chosen mk=%.10f cost=%.10f%n", m.chosenMakespan, m.chosenCost);
            }
        }
    }
}
