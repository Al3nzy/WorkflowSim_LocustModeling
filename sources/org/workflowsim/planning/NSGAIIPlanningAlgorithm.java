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
 * NSGA-II: standard elitist non-dominated-sorting genetic algorithm
 * (Deb, Pratap, Agarwal &amp; Meyarivan, IEEE TEVC 2002), added as a
 * canonical multi-objective baseline for the makespan/cost workflow
 * scheduling problem.
 *
 * This class deliberately duplicates -- rather than reuses via
 * inheritance -- the genotype representation, decoder, computation/
 * transfer-cost setup, and warm-start seeding logic of
 * LIWSAPlanningAlgorithm, exactly the pattern already used by
 * MLEAOPlanningAlgorithm in this codebase. A genotype is an int[] of
 * length n (n = number of tasks), where genotype[k] is an index into
 * vmList for the k-th task in a fixed topological order (taskOrder).
 * decode(), commitAssignment(), topologicalOrder(),
 * calculateComputationCosts(), calculateTransferCosts(),
 * calculateTransferCost(), findFinishTime(), calculateAverageBandwidth(),
 * generateSeedGenotypes(), buildSeedsFromAssignments(), and
 * initializePopulation() below are byte-for-byte identical to the LIWSA
 * versions, so that any performance difference between NSGA-II and
 * LIWSA/LIWSA-ML reflects the search strategy alone, not the problem
 * encoding, the schedule decoder, or the warm-start seeds -- the same
 * encoding, decoder, and heuristic seeds referred to in the manuscript's
 * baseline comparison.
 *
 * FAITHFUL vs. ADAPTED, stated explicitly:
 *  - FAITHFUL to Deb et al. (2002): fast non-dominated sorting with
 *    O(MN^2) complexity, the crowding-distance diversity metric, the
 *    crowded-comparison operator (lower rank wins; ties broken by
 *    larger crowding distance) used both for binary tournament parent
 *    selection and for environmental selection, and the elitist
 *    (mu+lambda) replacement scheme that merges parent and offspring
 *    populations before truncating back to populationSize.
 *  - ADAPTED for this problem's discrete VM-assignment encoding: the
 *    original NSGA-II paper targets real-valued and binary decision
 *    variables and pairs them with simulated binary crossover (SBX) and
 *    polynomial mutation, neither of which is defined for a categorical,
 *    non-ordinal gene (a VM index carries no meaningful distance or
 *    ordering). In their place this implementation uses uniform
 *    crossover (each gene independently inherited from one parent or
 *    the other with probability 0.5, applied with the standard NSGA-II
 *    crossover probability p_c = 0.9) and random-resetting mutation
 *    (each gene independently reassigned to a different, uniformly
 *    random VM with probability p_m = 1/n, the standard NSGA-II
 *    per-gene default), both standard, widely used substitutions for
 *    categorical/assignment-style chromosomes in the scheduling-GA
 *    literature.
 */
public class NSGAIIPlanningAlgorithm extends BasePlanningAlgorithm {

    // ---- standard NSGA-II parameters ----
    private double crossoverProbability = 0.9;
    private Long randomSeed = null;

    public static int CONFIG_POPULATION_SIZE = 30;
    public static int CONFIG_GENERATION_COUNT = 100;
    public static Long CONFIG_RANDOM_SEED = null;

    public static volatile LastRunMetrics lastRun = null;

    public static class LastRunMetrics {
        public double chosenMakespan;
        public double chosenCost;
        public int paretoFrontSize;
        /** Each element is {makespan, cost} for one member of the final Pareto front. */
        public List<double[]> paretoFrontPoints;
        /** For each front member (same order as paretoFrontPoints): cloudlet ID -> VM ID, so the member can be replayed in the simulator. */
        public List<Map<Integer, Integer>> paretoFrontAssignments;
        public long searchWallClockMillis;
        public int populationSizeUsed;
        public int generationCountUsed;
        /** Total objective evaluations (genotype decodes), including any warm-start training decodes. */
        public long objectiveEvaluations;
    }

    /** Same semantics as LIWSAPlanningAlgorithm.CONFIG_SEED_ASSIGNMENTS. */
    public static List<Map<Integer, Integer>> CONFIG_SEED_ASSIGNMENTS = null;
    /**
     * Opt-in (default false). Same external Pareto archive that LIWSAPlanningAlgorithm offers, so that the
     * comparison can be run with every algorithm collecting its non-dominated solutions the same way.
     */
    public static boolean CONFIG_OUTPUT_ARCHIVE = Boolean.getBoolean("nsga2.outputArchive");

    // ---- problem data, set once at the start of run() ----
    protected List<Task> taskOrder;
    protected List<CondorVM> vmList;
    protected double averageBandwidth;
    protected Map<Task, Map<CondorVM, Double>> computationCosts;
    protected Map<Task, Map<Task, Double>> transferCosts;
    protected Random random;

    // ---- search state ----
    protected List<int[]> population;
    protected double[] makespans;
    protected double[] costs;
    protected int populationSize = CONFIG_POPULATION_SIZE;
    protected int generationCount = CONFIG_GENERATION_COUNT;
    private List<int[]> seedGenotypes;

    public NSGAIIPlanningAlgorithm() {
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

    public void setRandomSeed(long seed) {
        this.randomSeed = seed;
    }

    /** Same semantics as LIWSAPlanningAlgorithm.setSeedGenotypes. */
    public void setSeedGenotypes(List<int[]> seedGenotypes) {
        this.seedGenotypes = seedGenotypes;
    }

    // =================================================================
    // Main loop
    // =================================================================
    @Override
    public void run() {
        Log.printLine("NSGA-II planner running with " + getTaskList().size()
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
        int n = taskOrder.size();
        double mutationProbability = 1.0 / Math.max(n, 1);

        int[] rank = new int[populationSize];
        double[] crowding = new double[populationSize];
        rankAndCrowd(makespans, costs, populationSize, rank, crowding);
        if (CONFIG_OUTPUT_ARCHIVE) {
            archiveUpdate();
        }

        for (int gen = 0; gen < generationCount; gen++) {

            // ---- offspring generation: binary tournament + uniform
            //      crossover (p_c=0.9) + random-reset mutation (p_m=1/n) ----
            List<int[]> offspring = new ArrayList<>(populationSize);
            while (offspring.size() < populationSize) {
                int p1 = tournamentSelect(rank, crowding);
                int p2 = tournamentSelect(rank, crowding);
                int[][] children = crossover(population.get(p1), population.get(p2));
                mutate(children[0], mutationProbability);
                mutate(children[1], mutationProbability);
                offspring.add(children[0]);
                if (offspring.size() < populationSize) {
                    offspring.add(children[1]);
                }
            }

            // ---- combine parent (known makespans/costs) + offspring
            //      (freshly decoded) into one pool of size 2*populationSize ----
            int combinedSize = populationSize + offspring.size();
            List<int[]> combined = new ArrayList<>(combinedSize);
            double[] combinedMakespans = new double[combinedSize];
            double[] combinedCosts = new double[combinedSize];
            for (int i = 0; i < populationSize; i++) {
                combined.add(population.get(i));
                combinedMakespans[i] = makespans[i];
                combinedCosts[i] = costs[i];
            }
            for (int i = 0; i < offspring.size(); i++) {
                int[] child = offspring.get(i);
                double[] mc = decode(child);
                combined.add(child);
                combinedMakespans[populationSize + i] = mc[0];
                combinedCosts[populationSize + i] = mc[1];
            }

            int[] combinedRank = new int[combinedSize];
            double[] combinedCrowding = new double[combinedSize];
            List<List<Integer>> combinedFronts = rankAndCrowd(
                    combinedMakespans, combinedCosts, combinedSize,
                    combinedRank, combinedCrowding);

            // ---- elitist (mu+lambda) environmental selection: fill next
            //      generation front by front; the last, partially-admitted
            //      front is truncated by descending crowding distance ----
            List<int[]> nextPopulation = new ArrayList<>(populationSize);
            double[] nextMakespans = new double[populationSize];
            double[] nextCosts = new double[populationSize];
            int filled = 0;
            for (List<Integer> front : combinedFronts) {
                if (filled + front.size() <= populationSize) {
                    for (int idx : front) {
                        nextPopulation.add(combined.get(idx));
                        nextMakespans[filled] = combinedMakespans[idx];
                        nextCosts[filled] = combinedCosts[idx];
                        filled++;
                    }
                } else {
                    List<Integer> sortedFront = new ArrayList<>(front);
                    final double[] cc = combinedCrowding;
                    Collections.sort(sortedFront, (a, b) -> Double.compare(cc[b], cc[a]));
                    for (int idx : sortedFront) {
                        if (filled >= populationSize) {
                            break;
                        }
                        nextPopulation.add(combined.get(idx));
                        nextMakespans[filled] = combinedMakespans[idx];
                        nextCosts[filled] = combinedCosts[idx];
                        filled++;
                    }
                    break;
                }
                if (filled >= populationSize) {
                    break;
                }
            }

            population = nextPopulation;
            makespans = nextMakespans;
            costs = nextCosts;
            rank = new int[populationSize];
            crowding = new double[populationSize];
            rankAndCrowd(makespans, costs, populationSize, rank, crowding);
            if (CONFIG_OUTPUT_ARCHIVE) {
                archiveUpdate();
            }
        }

        List<Integer> finalFront = new ArrayList<>();
        for (int i = 0; i < populationSize; i++) {
            if (rank[i] == 0) {
                finalFront.add(i);
            }
        }
        int chosen = bestOf(finalFront);

        LastRunMetrics metrics = new LastRunMetrics();
        metrics.chosenMakespan = makespans[chosen];
        metrics.chosenCost = costs[chosen];
        metrics.paretoFrontSize = finalFront.size();
        metrics.paretoFrontPoints = new ArrayList<>();
        metrics.paretoFrontAssignments = new ArrayList<>();
        for (int i : finalFront) {
            metrics.paretoFrontPoints.add(new double[]{makespans[i], costs[i]});
            metrics.paretoFrontAssignments.add(toAssignment(population.get(i)));
        }
        int[] commitGenotype = population.get(chosen);
        if (CONFIG_OUTPUT_ARCHIVE && !archP.isEmpty()) {
            int best = 0;
            for (int a = 1; a < archP.size(); a++) {
                double[] pa = archP.get(a);
                double[] pb = archP.get(best);
                if (pa[0] < pb[0] || (pa[0] == pb[0] && pa[1] < pb[1])) {
                    best = a;
                }
            }
            metrics.chosenMakespan = archP.get(best)[0];
            metrics.chosenCost = archP.get(best)[1];
            metrics.paretoFrontSize = archP.size();
            metrics.paretoFrontPoints = new ArrayList<>();
            metrics.paretoFrontAssignments = new ArrayList<>();
            for (int a = 0; a < archP.size(); a++) {
                metrics.paretoFrontPoints.add(archP.get(a).clone());
                metrics.paretoFrontAssignments.add(toAssignment(archG.get(a)));
            }
            commitGenotype = archG.get(best);
        }
        metrics.searchWallClockMillis = System.currentTimeMillis() - searchStartMillis;
        metrics.populationSizeUsed = populationSize;
        metrics.generationCountUsed = generationCount;
        metrics.objectiveEvaluations = evaluationCount;
        lastRun = metrics;

        commitAssignment(commitGenotype);

        Log.printLine("NSGA-II finished. Pareto front size: " + finalFront.size()
                + ", chosen makespan=" + makespans[chosen] + ", cost=" + costs[chosen]);
    }

    // ---- optional external archive (same mechanism as LIWSAPlanningAlgorithm.CONFIG_OUTPUT_ARCHIVE) ----
    private List<int[]> archG = new ArrayList<>();
    private List<double[]> archP = new ArrayList<>();

    private void archiveUpdate() {
        for (int i = 0; i < populationSize; i++) {
            archiveConsider(population.get(i), makespans[i], costs[i]);
        }
    }

    private void archiveConsider(int[] genotype, double mk, double cost) {
        for (double[] a : archP) {
            if (a[0] <= mk && a[1] <= cost) {
                return; // weakly dominated (includes an identical objective vector)
            }
        }
        for (int a = archP.size() - 1; a >= 0; a--) {
            double[] p = archP.get(a);
            if (mk <= p[0] && cost <= p[1]) {
                archP.remove(a);
                archG.remove(a);
            }
        }
        archG.add(genotype.clone());
        archP.add(new double[]{mk, cost});
        while (archP.size() > populationSize) {
            archiveDropMostCrowded();
        }
    }

    private void archiveDropMostCrowded() {
        int sz = archP.size();
        Integer[] order = new Integer[sz];
        for (int i = 0; i < sz; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (x, y) -> Double.compare(archP.get(x)[0], archP.get(y)[0]));
        double minM = archP.get(order[0])[0], maxM = archP.get(order[sz - 1])[0];
        double minC = Double.POSITIVE_INFINITY, maxC = Double.NEGATIVE_INFINITY;
        for (double[] p : archP) {
            minC = Math.min(minC, p[1]);
            maxC = Math.max(maxC, p[1]);
        }
        double rm = Math.max(maxM - minM, 1e-12), rc = Math.max(maxC - minC, 1e-12);
        double worst = Double.POSITIVE_INFINITY;
        int drop = -1;
        for (int r = 1; r < sz - 1; r++) {
            double[] lo = archP.get(order[r - 1]), hi = archP.get(order[r + 1]);
            double crowd = (hi[0] - lo[0]) / rm + Math.abs(lo[1] - hi[1]) / rc;
            if (crowd < worst) {
                worst = crowd;
                drop = order[r];
            }
        }
        if (drop < 0) {
            drop = order[sz - 1];
        }
        archP.remove(drop);
        archG.remove(drop);
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

    // =================================================================
    // Problem data setup (identical to LIWSAPlanningAlgorithm /
    // HEFTPlanningAlgorithm, so all baselines share the same low-level
    // timing assumptions)
    // =================================================================
    private double calculateAverageBandwidth() {
        double avg = 0.0;
        for (CondorVM vm : vmList) {
            avg += vm.getBw();
        }
        return avg / vmList.size();
    }

    protected int computeDepth(Task t, Map<Task, Integer> depth) {
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

    // =================================================================
    // Decoder: genotype -> (makespan, cost), feasible by construction
    // (topological order + insertion-based per-VM scheduling)
    // =================================================================
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

    protected double[] decode(int[] genotype) {
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

    // =================================================================
    // Population initialization and warm-start seeding (identical to
    // LIWSAPlanningAlgorithm, for a matched-seed comparison)
    // =================================================================
    protected List<int[]> generateSeedGenotypes() {
        List<int[]> seeds = (seedGenotypes != null) ? new ArrayList<>(seedGenotypes) : new ArrayList<>();
        seeds.addAll(buildSeedsFromAssignments());
        return seeds;
    }

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
                Log.printLine("NSGA-II: skipped a seed schedule that did not match "
                    + "the current workflow/VM pool (incomplete cloudlet-ID mapping).");
            }
        }
        return result;
    }

    protected void initializePopulation() {
        population = new ArrayList<>();
        for (int[] g : generateSeedGenotypes()) {
            population.add(g.clone());
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
        for (int i = 0; i < populationSize; i++) {
            double[] mc = decode(population.get(i));
            makespans[i] = mc[0];
            costs[i] = mc[1];
        }
    }

    // =================================================================
    // NSGA-II core: fast non-dominated sort, crowding distance, crowded
    // tournament selection, uniform crossover, random-reset mutation
    // =================================================================
    private boolean dominates(double m1, double c1, double m2, double c2) {
        return (m1 <= m2 && c1 <= c2) && (m1 < m2 || c1 < c2);
    }

    /**
     * Standard fast non-dominated sort (Deb et al., 2002, Sec. III-A),
     * O(size^2). Fills rankOut[i] with individual i's front number
     * (0 = first/best front) and, in the same pass, computes and fills
     * crowdingOut[i] with its crowding distance within that front.
     * Returns the fronts themselves for the caller's environmental
     * selection step.
     */
    private List<List<Integer>> rankAndCrowd(double[] ms, double[] cs, int size,
            int[] rankOut, double[] crowdingOut) {
        int[] domCount = new int[size];
        List<List<Integer>> dominatedBy = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            dominatedBy.add(new ArrayList<Integer>());
        }
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                if (i == j) {
                    continue;
                }
                if (dominates(ms[i], cs[i], ms[j], cs[j])) {
                    dominatedBy.get(i).add(j);
                } else if (dominates(ms[j], cs[j], ms[i], cs[i])) {
                    domCount[i]++;
                }
            }
        }
        List<List<Integer>> fronts = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            if (domCount[i] == 0) {
                current.add(i);
            }
        }
        int rank = 0;
        while (!current.isEmpty()) {
            for (int i : current) {
                rankOut[i] = rank;
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
        for (List<Integer> front : fronts) {
            crowdingDistance(front, ms, cs, crowdingOut);
        }
        return fronts;
    }

    /**
     * Standard NSGA-II crowding distance (Deb et al., 2002, Sec. III-B):
     * for each objective, sort the front by that objective, give the two
     * boundary points infinite distance, and add each interior point's
     * normalized distance to its neighbours on either side.
     */
    private void crowdingDistance(List<Integer> front, double[] ms, double[] cs, double[] crowdingOut) {
        int m = front.size();
        for (int idx : front) {
            crowdingOut[idx] = 0.0;
        }
        if (m == 0) {
            return;
        }
        if (m <= 2) {
            for (int idx : front) {
                crowdingOut[idx] = Double.POSITIVE_INFINITY;
            }
            return;
        }

        // makespan
        List<Integer> byMakespan = new ArrayList<>(front);
        Collections.sort(byMakespan, (a, b) -> Double.compare(ms[a], ms[b]));
        double msRange = ms[byMakespan.get(m - 1)] - ms[byMakespan.get(0)];
        crowdingOut[byMakespan.get(0)] = Double.POSITIVE_INFINITY;
        crowdingOut[byMakespan.get(m - 1)] = Double.POSITIVE_INFINITY;
        if (msRange > 0) {
            for (int k = 1; k < m - 1; k++) {
                int idx = byMakespan.get(k);
                double d = (ms[byMakespan.get(k + 1)] - ms[byMakespan.get(k - 1)]) / msRange;
                crowdingOut[idx] += d;
            }
        }

        // cost
        List<Integer> byCost = new ArrayList<>(front);
        Collections.sort(byCost, (a, b) -> Double.compare(cs[a], cs[b]));
        double costRange = cs[byCost.get(m - 1)] - cs[byCost.get(0)];
        crowdingOut[byCost.get(0)] = Double.POSITIVE_INFINITY;
        crowdingOut[byCost.get(m - 1)] = Double.POSITIVE_INFINITY;
        if (costRange > 0) {
            for (int k = 1; k < m - 1; k++) {
                int idx = byCost.get(k);
                double d = (cs[byCost.get(k + 1)] - cs[byCost.get(k - 1)]) / costRange;
                crowdingOut[idx] += d;
            }
        }
    }

    /**
     * Binary tournament using the NSGA-II crowded-comparison operator:
     * lower front rank wins; ties broken by larger crowding distance
     * (a more isolated point is preferred, to spread the front).
     */
    private int tournamentSelect(int[] rank, double[] crowding) {
        int a = random.nextInt(populationSize);
        int b = random.nextInt(populationSize);
        if (rank[a] != rank[b]) {
            return (rank[a] < rank[b]) ? a : b;
        }
        return (crowding[a] >= crowding[b]) ? a : b;
    }

    /**
     * Uniform crossover, the standard substitute for SBX on a
     * categorical/assignment gene: applied with probability
     * crossoverProbability (0.9); each gene is independently swapped
     * between the two children with probability 0.5. When crossover is
     * not applied, both children are exact clones of the two parents.
     */
    private int[][] crossover(int[] p1, int[] p2) {
        int n = p1.length;
        int[] c1 = p1.clone();
        int[] c2 = p2.clone();
        if (random.nextDouble() < crossoverProbability) {
            for (int k = 0; k < n; k++) {
                if (random.nextBoolean()) {
                    int tmp = c1[k];
                    c1[k] = c2[k];
                    c2[k] = tmp;
                }
            }
        }
        return new int[][]{c1, c2};
    }

    /**
     * Random-resetting mutation, the standard substitute for polynomial
     * mutation on a categorical gene: each gene is independently
     * reassigned, with probability pm, to a uniformly random VM
     * different from its current value.
     */
    private void mutate(int[] genotype, double pm) {
        int numVms = vmList.size();
        if (numVms <= 1) {
            return;
        }
        for (int k = 0; k < genotype.length; k++) {
            if (random.nextDouble() < pm) {
                int current = genotype[k];
                int replacement;
                do {
                    replacement = random.nextInt(numVms);
                } while (replacement == current);
                genotype[k] = replacement;
            }
        }
    }
}
