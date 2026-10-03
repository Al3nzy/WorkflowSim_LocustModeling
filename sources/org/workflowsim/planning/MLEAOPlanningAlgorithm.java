/**
 * Copyright 2025-2026 SDU University, Kazakhstan
 * @author Dr. Mohammed Alaa Ala'anzy
 * 
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
package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.cloudbus.cloudsim.Consts;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.utils.Parameters;

/**
 * MLEAO baseline: a multi-objective, HEFT-seeded Archimedes Optimization
 * Algorithm with a Local Escaping Operator, used here as a comparison
 * baseline for LIWSA, following the design described in Kushwaha et al.
 * (2025), "Multi-Objective Workflow Scheduling in Cloud Using Archimedes
 * Optimization Algorithm," Concurrency and Computation: Practice and
 * Experience.
 *
 * Provenance note, important for how this gets described in a paper: the
 * core AOA mechanics below (density/volume pulled toward the best
 * solution each generation, a transfer operator TF that gates exploration
 * versus exploitation, acceleration recomputed from a random peer and
 * normalized into a step-size) are corroborated across many independent
 * published sources on the original Archimedes Optimization Algorithm
 * (Hashim et al. 2021) and are implemented faithfully to that literature.
 * The exact Local Escaping Operator formula and the constants used to
 * convert AOA's continuous step sizes into discrete copy probabilities
 * are NOT verified against Kushwaha et al.'s original code, which is not
 * publicly accessible; they are a documented, faithful-to-the-general-
 * mechanism reconstruction, validated empirically (against HEFT, Min-Min,
 * and LIWSA, across multiple random seeds) rather than against the
 * original paper's exact reported numbers. Describe this as "a
 * reimplementation following the design of Kushwaha et al.," not as an
 * exact reproduction.
 *
 * Genotype, decoder, transfer-cost model, and Pareto ranking are
 * identical to LIWSAPlanningAlgorithm, so any difference in results
 * reflects the search strategy, not implementation drift between the two.
 */
public class MLEAOPlanningAlgorithm extends BasePlanningAlgorithm {

    // ---- tunable parameters ----
    private int populationSize = 30;
    private int generationCount = 100;
    private double c1 = 0.6;
    private double c2 = 0.5;
    private double c3 = 0.3;
    private double leoProbability = 0.15;
    private boolean leoDecay = true;
    private double mutationRate = 0.02;
    private Long randomSeed = null;

    /**
     * STATIC configuration. See the identical note in
     * LIWSAPlanningAlgorithm: WorkflowPlanner constructs this class
     * internally with a no-arg constructor, so static fields read at
     * construction time are the only reliable injection point for a
     * WorkflowSim-driven run.
     */
    public static int CONFIG_POPULATION_SIZE = 30;
    public static int CONFIG_GENERATION_COUNT = 100;
    public static Long CONFIG_RANDOM_SEED = null;

    /**
     * Snapshot of the most recently completed run. Same single-threaded,
     * sequential-runs-only safety caveat as LIWSAPlanningAlgorithm.lastRun.
     */
    public static volatile LastRunMetrics lastRun = null;

    /**
     * Optional warm-start schedules (e.g. HEFT's/Min-Min's actual
     * assignments), keyed by cloudlet ID. See the identical, more
     * detailed comment on LIWSAPlanningAlgorithm.CONFIG_SEED_ASSIGNMENTS
     * for the full rationale -- cloudlet-ID keying is what lets a
     * schedule computed in one simulation run be reused as a warm start
     * in a separate, later simulation run on the same DAX file.
     */
    public static List<Map<Integer, Integer>> CONFIG_SEED_ASSIGNMENTS = null;

    public static class LastRunMetrics {
        public double chosenMakespan;
        public double chosenCost;
        public int paretoFrontSize;
        public List<double[]> paretoFrontPoints;
        /** For each front member (same order as paretoFrontPoints): cloudlet ID -> VM ID, so the member can be replayed in the simulator. */
        public List<Map<Integer, Integer>> paretoFrontAssignments;
        public long searchWallClockMillis;
        public int populationSizeUsed;
        public int generationCountUsed;
        /** Total objective evaluations (genotype decodes), including any warm-start training decodes. */
        public long objectiveEvaluations;
    }

    // ---- problem data, set once at the start of run() ----
    private List<Task> taskOrder;
    private List<CondorVM> vmList;
    private double averageBandwidth;
    private Map<Task, Map<CondorVM, Double>> computationCosts;
    private Map<Task, Map<Task, Double>> transferCosts;
    private Random random;

    // ---- search state ----
    private List<int[]> population;
    private double[] makespans;
    private double[] costs;
    private double[] density;
    private double[] volume;
    private double[] acceleration;
    private List<int[]> seedGenotypes;

    public MLEAOPlanningAlgorithm() {
        this.populationSize = CONFIG_POPULATION_SIZE;
        this.generationCount = CONFIG_GENERATION_COUNT;
        this.randomSeed = CONFIG_RANDOM_SEED;
    }

    public void setPopulationSize(int populationSize) {
        this.populationSize = populationSize;
    }

    public void setGenerationCount(int generationCount) {
        this.generationCount = generationCount;
    }

    public void setMutationRate(double mutationRate) {
        this.mutationRate = mutationRate;
    }

    public void setRandomSeed(long seed) {
        this.randomSeed = seed;
    }

    /**
     * Optional warm-start seeds (e.g. HEFT's and Min-Min's actual
     * assignments, translated into genotypes in this algorithm's task
     * order). Matches how the validated Python comparison was run.
     * Each int[] must have length equal to the number of tasks, and
     * genotype[k] must index into whatever vmList ends up being once
     * run() executes -- safest to call this from the same place that
     * also runs HEFTPlanningAlgorithm/etc. against the same task/VM lists.
     */
    public void setSeedGenotypes(List<int[]> seedGenotypes) {
        this.seedGenotypes = seedGenotypes;
    }

    @Override
    public void run() {
        Log.printLine("MLEAO planner running with " + getTaskList().size()
                + " tasks, " + getVmList().size() + " VMs.");
        long searchStartMillis = System.currentTimeMillis();

        vmList = new ArrayList<>();
        for (Object vmObject : getVmList()) {
            vmList.add((CondorVM) vmObject);
        }

        random = (randomSeed != null) ? new Random(randomSeed) : new Random();

        averageBandwidth = calculateAverageBandwidth();
        taskOrder = topologicalOrder(getTaskList());
        calculateComputationCosts();
        calculateTransferCosts();
        prepareDecoder();

        initializePopulation();

        for (int t = 0; t < generationCount; t++) {
            int[] frontNumber = new int[populationSize];
            List<List<Integer>> fronts = nonDominatedSort(frontNumber);
            int bestIndex = bestOf(fronts.get(0));

            double TF = Math.exp(((double) (t - generationCount)) / generationCount);
            double dFactor = Math.exp(-((double) t) / generationCount);

            for (int i = 0; i < populationSize; i++) {
                if (i == bestIndex) {
                    continue;
                }
                density[i] += random.nextDouble() * (density[bestIndex] - density[i]);
                volume[i] += random.nextDouble() * (volume[bestIndex] - volume[i]);

                int mr = randomOtherIndex(i);
                double denom = Math.max(density[i] * volume[i], 1e-6);
                acceleration[i] = (density[mr] + volume[mr] * acceleration[mr]) / denom;
            }

            double accMin = Double.POSITIVE_INFINITY;
            double accMax = Double.NEGATIVE_INFINITY;
            for (double a : acceleration) {
                accMin = Math.min(accMin, a);
                accMax = Math.max(accMax, a);
            }
            double accRange = Math.max(accMax - accMin, 1e-9);
            double[] accNorm = new double[populationSize];
            for (int i = 0; i < populationSize; i++) {
                accNorm[i] = 0.1 + 0.8 * (acceleration[i] - accMin) / accRange;
            }

            for (int i = 0; i < populationSize; i++) {
                if (i == bestIndex) {
                    continue;
                }

                int[] child;
                double leoP = leoProbability * (leoDecay ? dFactor : 1.0);
                if (random.nextDouble() < leoP) {
                    child = leoJump(i);
                } else if (TF <= 0.5) {
                    int mr = randomOtherIndex(i);
                    double prob = c1 * accNorm[i];
                    child = copyByProbability(population.get(i), population.get(mr), prob);
                } else {
                    double T = Math.min(1.0, c3 * TF * 3);
                    double prob = c2 * accNorm[i] * T;
                    child = copyByProbability(population.get(i), population.get(bestIndex), prob);
                }

                for (int k = 0; k < child.length; k++) {
                    if (random.nextDouble() < mutationRate) {
                        child[k] = random.nextInt(vmList.size());
                    }
                }

                double[] mc = decode(child);
                if (!dominates(makespans[i], costs[i], mc[0], mc[1])) {
                    population.set(i, child);
                    makespans[i] = mc[0];
                    costs[i] = mc[1];
                }
            }
        }

        int[] finalFrontNumber = new int[populationSize];
        List<List<Integer>> finalFronts = nonDominatedSort(finalFrontNumber);
        int chosen = bestOf(finalFronts.get(0));

        LastRunMetrics metrics = new LastRunMetrics();
        metrics.chosenMakespan = makespans[chosen];
        metrics.chosenCost = costs[chosen];
        metrics.paretoFrontSize = finalFronts.get(0).size();
        metrics.paretoFrontPoints = new ArrayList<>();
        metrics.paretoFrontAssignments = new ArrayList<>();
        for (int i : finalFronts.get(0)) {
            metrics.paretoFrontPoints.add(new double[]{makespans[i], costs[i]});
            metrics.paretoFrontAssignments.add(toAssignment(population.get(i)));
        }
        metrics.searchWallClockMillis = System.currentTimeMillis() - searchStartMillis;
        metrics.populationSizeUsed = populationSize;
        metrics.generationCountUsed = generationCount;
        metrics.objectiveEvaluations = evaluationCount;
        lastRun = metrics;

        commitAssignment(population.get(chosen));

        Log.printLine("MLEAO finished. Pareto front size: " + finalFronts.get(0).size()
                + ", chosen makespan=" + makespans[chosen] + ", cost=" + costs[chosen]);
    }

    private int randomOtherIndex(int i) {
        int j;
        do {
            j = random.nextInt(populationSize);
        } while (j == i);
        return j;
    }

    private int bestOf(List<Integer> indices) {
        int best = indices.get(0);
        for (int i : indices) {
            if (makespans[i] < makespans[best]
                    || (makespans[i] == makespans[best] && costs[i] < costs[best])) {
                best = i;
            }
        }
        return best;
    }

    private int[] copyByProbability(int[] source, int[] reference, double prob) {
        double p = Math.max(0.0, Math.min(1.0, prob));
        int[] child = source.clone();
        for (int k = 0; k < child.length; k++) {
            if (random.nextDouble() < p) {
                child[k] = reference[k];
            }
        }
        return child;
    }

    /**
     * Local escaping operator: a more disruptive move combining the
     * current individual with two random references, used occasionally
     * to break out of regions the standard operators keep returning to.
     * See the class-level provenance note: this is a documented
     * reconstruction of LEO's general published intent, not a verified
     * exact formula.
     */
    private int[] leoJump(int i) {
        int a = randomOtherIndex(i);
        int b;
        do {
            b = random.nextInt(populationSize);
        } while (b == i || b == a);

        int[] child = population.get(i).clone();
        int[] A = population.get(a);
        int[] B = population.get(b);
        for (int k = 0; k < child.length; k++) {
            double r = random.nextDouble();
            if (r < 0.34) {
                child[k] = A[k];
            } else if (r < 0.67) {
                child[k] = B[k];
            } else {
                child[k] = random.nextInt(vmList.size());
            }
        }
        return child;
    }

    // ---------------------------------------------------------------
    // Everything below is identical to LIWSAPlanningAlgorithm: same
    // computation/transfer cost setup (mirrors HEFTPlanningAlgorithm
    // exactly), same insertion-based decoder, same Pareto sort, same
    // commit step. Duplicated rather than shared so each planning
    // algorithm class stays self-contained, matching how
    // HEFTPlanningAlgorithm/DHEFTPlanningAlgorithm/etc. are each
    // independent in this codebase.
    // ---------------------------------------------------------------
    private double calculateAverageBandwidth() {
        double avg = 0.0;
        for (CondorVM vm : vmList) {
            avg += vm.getBw();
        }
        return avg / vmList.size();
    }

    private int computeDepth(Task t, Map<Task, Integer> depth) {
        if (depth.containsKey(t)) {
            return depth.get(t);
        }
        int d = 0;
        for (Object parentObj : t.getParentList()) {
            Task p = (Task) parentObj;
            d = Math.max(d, computeDepth(p, depth) + 1);
        }
        depth.put(t, d);
        return d;
    }

    private List<Task> topologicalOrder(List<Task> tasks) {
        final Map<Task, Integer> depth = new HashMap<>();
        for (Task t : tasks) {
            computeDepth(t, depth);
        }
        List<Task> order = new ArrayList<>(tasks);
        Collections.sort(order, (a, b) -> {
            int da = depth.get(a);
            int db = depth.get(b);
            if (da != db) {
                return Integer.compare(da, db);
            }
            double la = a.getCloudletTotalLength();
            double lb = b.getCloudletTotalLength();
            if (la != lb) {
                return Double.compare(lb, la);
            }
            return Integer.compare(a.getCloudletId(), b.getCloudletId());
        });
        return order;
    }

    private void calculateComputationCosts() {
        computationCosts = new HashMap<>();
        for (Task task : getTaskList()) {
            Map<CondorVM, Double> costsForTask = new HashMap<>();
            for (CondorVM vm : vmList) {
                costsForTask.put(vm, task.getCloudletTotalLength() / vm.getMips());
            }
            computationCosts.put(task, costsForTask);
        }
    }

    private void calculateTransferCosts() {
        transferCosts = new HashMap<>();
        for (Task task : getTaskList()) {
            transferCosts.put(task, new HashMap<Task, Double>());
        }
        for (Task parent : getTaskList()) {
            for (Object childObj : parent.getChildList()) {
                Task child = (Task) childObj;
                transferCosts.get(parent).put(child, calculateTransferCost(parent, child));
            }
        }
    }

    private double calculateTransferCost(Task parent, Task child) {
        List<FileItem> parentFiles = parent.getFileList();
        List<FileItem> childFiles = child.getFileList();
        double acc = 0.0;
        for (FileItem parentFile : parentFiles) {
            if (parentFile.getType() != Parameters.FileType.OUTPUT) {
                continue;
            }
            for (FileItem childFile : childFiles) {
                if (childFile.getType() == Parameters.FileType.INPUT
                        && childFile.getName().equals(parentFile.getName())) {
                    acc += childFile.getSize();
                    break;
                }
            }
        }
        acc = acc / Consts.MILLION;
        return acc * 8 / averageBandwidth;
    }

    // ---------------------------------------------------------------
    // Decoder: genotype -> (makespan, cost), feasible by construction
    // (topological order + insertion-based per-VM scheduling, so there is
    // no separate repair step).
    //
    // Implementation note: the decoder is allocation-free. Per-task parent
    // indices, transfer costs, durations and VM prices are tabulated once
    // per run in prepareDecoder(), and the per-VM busy intervals live in
    // primitive arrays that are reused across calls. The arithmetic and
    // the order of every floating-point operation are identical to the
    // earlier Map/List-based decoder, so every (makespan, cost) pair it
    // returns is bit-for-bit unchanged; only the running time differs.
    // The same decoder is used verbatim by LIWSA, LIWSA-ML, NSGA-II and
    // MLEAO so that search-time comparisons are not skewed by it.
    // ---------------------------------------------------------------
    private int decN;
    private int[][] decParentIdx;
    private double[][] decParentTransfer;
    private double[][] decDuration;
    private double[] decVmPrice;
    private double[][] decEvStart;
    private double[][] decEvFinish;
    private int[] decEvCount;
    private double[] decFinish;
    private int[] decAssigned;
    /** Number of objective evaluations (genotype decodes) performed in this run. */
    protected long evaluationCount = 0;

    private void prepareDecoder() {
        int n = taskOrder.size();
        int m = vmList.size();
        decN = n;
        Map<Task, Integer> index = new HashMap<>();
        for (int k = 0; k < n; k++) {
            index.put(taskOrder.get(k), k);
        }
        decParentIdx = new int[n][];
        decParentTransfer = new double[n][];
        decDuration = new double[n][m];
        decVmPrice = new double[m];
        for (int v = 0; v < m; v++) {
            decVmPrice[v] = vmList.get(v).getCost();
        }
        for (int k = 0; k < n; k++) {
            Task task = taskOrder.get(k);
            List parents = task.getParentList();
            decParentIdx[k] = new int[parents.size()];
            decParentTransfer[k] = new double[parents.size()];
            for (int q = 0; q < parents.size(); q++) {
                Task parent = (Task) parents.get(q);
                decParentIdx[k][q] = index.get(parent);
                Double tc = transferCosts.get(parent).get(task);
                decParentTransfer[k][q] = (tc != null) ? tc : 0.0;
            }
            for (int v = 0; v < m; v++) {
                decDuration[k][v] = computationCosts.get(task).get(vmList.get(v));
            }
        }
        decEvStart = new double[m][n];
        decEvFinish = new double[m][n];
        decEvCount = new int[m];
        decFinish = new double[n];
        decAssigned = new int[n];
    }

    private void decInsert(int v, int pos, double start, double finish) {
        int size = decEvCount[v];
        double[] st = decEvStart[v];
        double[] fi = decEvFinish[v];
        if (pos < size) {
            System.arraycopy(st, pos, st, pos + 1, size - pos);
            System.arraycopy(fi, pos, fi, pos + 1, size - pos);
        }
        st[pos] = start;
        fi[pos] = finish;
        decEvCount[v] = size + 1;
    }

    /** Insertion-based slot search on VM v; returns the task's finish time and books the slot. */
    private double placeOnVm(int v, double readyTime, double duration) {
        double[] st = decEvStart[v];
        double[] fi = decEvFinish[v];
        int size = decEvCount[v];
        if (size == 0) {
            decInsert(v, 0, readyTime, readyTime + duration);
            return readyTime + duration;
        }
        if (size == 1) {
            double start;
            int pos;
            if (readyTime >= fi[0]) {
                pos = 1;
                start = readyTime;
            } else if (readyTime + duration <= st[0]) {
                pos = 0;
                start = readyTime;
            } else {
                pos = 1;
                start = fi[0];
            }
            decInsert(v, pos, start, start + duration);
            return start + duration;
        }
        double start = Math.max(readyTime, fi[size - 1]);
        double finish = start + duration;
        int pos = size;
        int i = size - 1;
        int j = size - 2;
        while (j >= 0) {
            double currentStart = st[i];
            double previousFinish = fi[j];
            if (readyTime > previousFinish) {
                if (readyTime + duration <= currentStart) {
                    start = readyTime;
                    finish = readyTime + duration;
                }
                break;
            }
            if (previousFinish + duration <= currentStart) {
                start = previousFinish;
                finish = previousFinish + duration;
                pos = i;
            }
            i--;
            j--;
        }
        if (readyTime + duration <= st[0]) {
            decInsert(v, 0, readyTime, readyTime + duration);
            return readyTime + duration;
        }
        decInsert(v, pos, start, finish);
        return finish;
    }

    private double[] decode(int[] genotype) {
        evaluationCount++;
        java.util.Arrays.fill(decEvCount, 0);
        double cost = 0.0;
        double makespan = 0.0;
        for (int k = 0; k < decN; k++) {
            int v = genotype[k];
            double ready = 0.0;
            int[] parents = decParentIdx[k];
            double[] transfer = decParentTransfer[k];
            for (int q = 0; q < parents.length; q++) {
                int p = parents[q];
                double pf = decFinish[p];
                if (decAssigned[p] != v) {
                    pf += transfer[q];
                }
                ready = Math.max(ready, pf);
            }
            double duration = decDuration[k][v];
            double fin = placeOnVm(v, ready, duration);
            decFinish[k] = fin;
            decAssigned[k] = v;
            cost += duration * decVmPrice[v];
            makespan = Math.max(makespan, fin);
        }
        return new double[]{makespan, cost};
    }

    /** cloudlet ID -> VM ID map for a genotype, usable as a warm-start / replay schedule. */
    private Map<Integer, Integer> toAssignment(int[] genotype) {
        Map<Integer, Integer> map = new HashMap<>();
        for (int k = 0; k < taskOrder.size(); k++) {
            map.put(taskOrder.get(k).getCloudletId(), vmList.get(genotype[k]).getId());
        }
        return map;
    }

    private void commitAssignment(int[] genotype) {
        for (int k = 0; k < taskOrder.size(); k++) {
            taskOrder.get(k).setVmId(vmList.get(genotype[k]).getId());
        }
    }

    /**
     * Converts each schedule in CONFIG_SEED_ASSIGNMENTS (cloudlet ID -> VM
     * ID) into a genotype matching this instance's own taskOrder and
     * vmList. See LIWSAPlanningAlgorithm.buildSeedsFromAssignments for
     * the identical logic and rationale.
     */
    private List<int[]> buildSeedsFromAssignments() {
        List<int[]> result = new ArrayList<>();
        if (CONFIG_SEED_ASSIGNMENTS == null || CONFIG_SEED_ASSIGNMENTS.isEmpty()) {
            return result;
        }
        Map<Integer, Integer> vmIdToIndex = new HashMap<>();
        for (int idx = 0; idx < vmList.size(); idx++) {
            vmIdToIndex.put(vmList.get(idx).getId(), idx);
        }
        for (Map<Integer, Integer> assignment : CONFIG_SEED_ASSIGNMENTS) {
            int[] genotype = new int[taskOrder.size()];
            boolean valid = true;
            for (int k = 0; k < taskOrder.size(); k++) {
                int cloudletId = taskOrder.get(k).getCloudletId();
                Integer vmId = assignment.get(cloudletId);
                if (vmId == null || !vmIdToIndex.containsKey(vmId)) {
                    valid = false;
                    break;
                }
                genotype[k] = vmIdToIndex.get(vmId);
            }
            if (valid) {
                result.add(genotype);
            } else {
                Log.printLine("MLEAO: skipped a seed schedule that did not match "
                    + "the current workflow/VM pool (incomplete cloudlet-ID mapping).");
            }
        }
        return result;
    }

    private void initializePopulation() {
        population = new ArrayList<>();
        if (seedGenotypes != null) {
            for (int[] g : seedGenotypes) {
                population.add(g.clone());
            }
        }
        for (int[] g : buildSeedsFromAssignments()) {
            population.add(g);
        }
        int n = taskOrder.size();
        while (population.size() < populationSize) {
            int[] genotype = new int[n];
            for (int k = 0; k < n; k++) {
                genotype[k] = random.nextInt(vmList.size());
            }
            population.add(genotype);
        }
        if (population.size() > populationSize) {
            population = new ArrayList<>(population.subList(0, populationSize));
        }

        makespans = new double[populationSize];
        costs = new double[populationSize];
        density = new double[populationSize];
        volume = new double[populationSize];
        acceleration = new double[populationSize];
        for (int i = 0; i < populationSize; i++) {
            double[] mc = decode(population.get(i));
            makespans[i] = mc[0];
            costs[i] = mc[1];
            density[i] = random.nextDouble();
            volume[i] = random.nextDouble();
            acceleration[i] = random.nextDouble();
        }
    }

    private boolean dominates(double m1, double c1, double m2, double c2) {
        return (m1 <= m2 && c1 <= c2) && (m1 < m2 || c1 < c2);
    }

    private List<List<Integer>> nonDominatedSort(int[] frontNumberOut) {
        int n = populationSize;
        int[] domCount = new int[n];
        List<List<Integer>> dominatedBy = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            dominatedBy.add(new ArrayList<Integer>());
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    continue;
                }
                if (dominates(makespans[i], costs[i], makespans[j], costs[j])) {
                    dominatedBy.get(i).add(j);
                } else if (dominates(makespans[j], costs[j], makespans[i], costs[i])) {
                    domCount[i]++;
                }
            }
        }
        List<List<Integer>> fronts = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (domCount[i] == 0) {
                current.add(i);
            }
        }
        int rank = 0;
        while (!current.isEmpty()) {
            for (int i : current) {
                frontNumberOut[i] = rank;
            }
            fronts.add(current);
            List<Integer> next = new ArrayList<>();
            for (int i : current) {
                for (int j : dominatedBy.get(i)) {
                    domCount[j]--;
                    if (domCount[j] == 0) {
                        next.add(j);
                    }
                }
            }
            current = next;
            rank++;
        }
        return fronts;
    }
}
