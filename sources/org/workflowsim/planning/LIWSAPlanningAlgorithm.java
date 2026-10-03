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
 * LIWSA: density-adaptive, multi-objective Locust-Inspired Workflow
 * Scheduling Algorithm.
 *
 * A genotype is an int[] of length n (n = number of tasks), where
 * genotype[k] is an index into vmList for the k-th task in a fixed
 * topological order (taskOrder). The population is ranked by Pareto
 * dominance (makespan, cost), not a weighted scalar, so no normalization
 * or baseline-derived bounds are needed.
 *
 * Two locust-derived operators move individuals:
 *  - solitary: every other individual in the population casts a signed,
 *    distance-weighted vote on each task's VM (positive if better-ranked,
 *    negative if worse, zero if on the same Pareto front). This is the
 *    discrete analogue of summing continuous attraction/repulsion forces.
 *  - social: roulette-selected attraction toward one member of the current
 *    elite (front-1) set only.
 * Which one applies to a given individual in a given generation is set by
 * a density-dependent probability: local crowding (neighbors within tau)
 * blended with a mild generation-based anneal term, rather than either a
 * fixed iteration schedule or a population-wide diversity threshold alone.
 *
 * A small residual mutation rate is included purely as standard EA hygiene
 * against stagnation; it is not bio-inspired and is not claimed to be.
 *
 * Validated against a Python prototype on Montage_50 (50 tasks, 16 VMs)
 * before this translation: the resulting Pareto front dominated HEFT's
 * single point outright and covered Min-Min's, with hypervolume about 19%
 * higher than the best single-objective baseline alone.
 */
public class LIWSAPlanningAlgorithm extends BasePlanningAlgorithm {

    // ---- tunable parameters ----
    private double lambdaMix = 0.5;
    private double mutationRate = 0.02;
    private double blendProbability = 0.5;
    private double copyAlpha = 1.2;
    private int minEliteSize = 3;
    private double kernelF = 3.0;
    private double kernelL = 0.3;
    private Long randomSeed = null;

    /**
     * STATIC configuration. WorkflowPlanner.processPlanning() constructs
     * this class with `new LIWSAPlanningAlgorithm()` internally, as a local
     * variable, then immediately calls run() -- there is no point in that
     * flow where external code can call instance setters before run()
     * executes. These static fields are read once in the constructor and
     * are therefore the only injection point that actually reaches a
     * WorkflowSim-driven run. Set them from your example/benchmark driver
     * BEFORE calling CloudSim.startSimulation(); a fresh instance reads
     * the current values each time CloudSim.init() + startSimulation() is
     * run, so changing them between sequential simulations (e.g. to sweep
     * seeds) works correctly.
     *
     * The instance setters below (setPopulationSize, etc.) are still
     * provided for direct unit-testing / manual instantiation outside
     * WorkflowSim, but have no effect when this class is run through
     * WorkflowPlanner -- use the static fields for that path.
     */
    public static int CONFIG_POPULATION_SIZE = 30;
    public static int CONFIG_GENERATION_COUNT = 100;
    public static Long CONFIG_RANDOM_SEED = null;
    public static double CONFIG_LAMBDA_MIX = 0.5;
    /**
     * Ablation switch: when true, the phase-mixing probability's density
     * term uses a fixed constant (0.5, the midpoint of the unit interval)
     * instead of each individual's measured local crowding, isolating the
     * causal contribution of density adaptation with every other
     * mechanism (kernel, annealing schedule, solitary/social operators,
     * elitism, seeding) held identical. Defaults to false (normal LIWSA).
     */
    public static boolean CONFIG_DENSITY_ABLATION = false;
    /**
     * Opt-in (default false, which reproduces the published behaviour exactly).
     * When true, an external archive of every distinct non-dominated solution
     * visited during the search (truncated by crowding distance to the
     * population size) is kept and returned as the final front. The search
     * itself is unchanged: the same random stream drives the same population
     * trajectory; only the reported front and the committed schedule differ.
     */
    public static boolean CONFIG_OUTPUT_ARCHIVE = Boolean.getBoolean("liwsa.outputArchive");

    /**
     * Snapshot of the most recently completed run, published at the end of
     * run(). CloudSim/WorkflowSim is single-threaded and each simulation
     * (CloudSim.init() ... CloudSim.stopSimulation()) constructs and runs
     * exactly one planning algorithm instance, so reading this immediately
     * after CloudSim.stopSimulation() and before starting the next
     * simulation is safe. Not safe for concurrent/parallel simulation runs
     * within a single JVM.
     */
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
        /**
         * Planning-level (decoder) {makespan, cost} of each externally supplied
         * warm-start schedule, in the order supplied (HEFT first, then Min-Min,
         * when both are provided), evaluated by the same decoder as every
         * population member. Lets a benchmark score the deterministic baselines
         * with the same evaluator as the population-based algorithms.
         */
        public List<double[]> seedPlanningPoints;
        /** Total objective evaluations (genotype decodes), including any warm-start training decodes. */
        public long objectiveEvaluations;
    }

    /**
     * Optional warm-start schedules to seed the initial population with,
     * e.g. HEFT's and Min-Min's actual assignments. Keyed by CLOUDLET ID
     * (Task.getCloudletId()) rather than array position, because each
     * schedule is typically produced by a SEPARATE simulation run (its own
     * WorkflowPlanner, its own freshly-constructed planning algorithm
     * instance with its own internal task ordering) -- cloudlet IDs are
     * the one thing guaranteed to line up across separate runs on the
     * same DAX file, since WorkflowParser assigns them deterministically,
     * in document order, starting fresh from a per-instance counter every
     * time the file is parsed.
     *
     * Each entry in the outer list is one complete schedule: a map from
     * cloudlet ID to the VM ID (CondorVM.getId(), not a list index) that
     * task was assigned to. Set this from the benchmark driver right
     * before CloudSim.startSimulation(), after computing the schedule(s)
     * you want to seed with in a prior simulation run.
     *
     * If a seed schedule is missing an entry for some task in the current
     * workflow, or maps a task to a VM ID not present in the current VM
     * pool, that whole seed is skipped (logged, not silently dropped) and
     * search proceeds with whatever seeds remain plus random fill-in.
     */
    public static List<Map<Integer, Integer>> CONFIG_SEED_ASSIGNMENTS = null;

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
    private int externalSeedCount = 0;
    private List<double[]> seedPlanningPoints = new ArrayList<>();

    public LIWSAPlanningAlgorithm() {
        this.populationSize = CONFIG_POPULATION_SIZE;
        this.generationCount = CONFIG_GENERATION_COUNT;
        this.randomSeed = CONFIG_RANDOM_SEED;
        this.lambdaMix = CONFIG_LAMBDA_MIX;
    }

    public void setPopulationSize(int populationSize) {
        this.populationSize = populationSize;
    }

    public void setGenerationCount(int generationCount) {
        this.generationCount = generationCount;
    }

    public void setLambdaMix(double lambdaMix) {
        this.lambdaMix = lambdaMix;
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
     * order). Matches how the validated Python comparison was run, and
     * mirrors MLEAOPlanningAlgorithm's setSeedGenotypes for a fair,
     * identically-seeded comparison between the two.
     */
    public void setSeedGenotypes(List<int[]> seedGenotypes) {
        this.seedGenotypes = seedGenotypes;
    }

    @Override
    public void run() {
        Log.printLine("LIWSA planner running with " + getTaskList().size()
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
        allocateMoveBuffers();
        archG = new ArrayList<>();
        archP = new ArrayList<>();
        double tau = calibrateTau();
        if (CONFIG_OUTPUT_ARCHIVE) {
            archiveUpdate();
        }

        for (int gen = 0; gen < generationCount; gen++) {
            int[] frontNumber = new int[populationSize];
            List<List<Integer>> fronts = nonDominatedSort(frontNumber);

            List<Integer> elite = new ArrayList<>(fronts.get(0));
            int idx = 1;
            while (elite.size() < minEliteSize && idx < fronts.size()) {
                elite.addAll(fronts.get(idx));
                idx++;
            }

            int bestIndex = bestOf(fronts.get(0));

            for (int i = 0; i < populationSize; i++) {
                if (i == bestIndex) {
                    continue;
                }
                computeDistanceRows(i);
                double density = CONFIG_DENSITY_ABLATION ? 0.5 : localDensity(i, tau);
                double pSocial = (1 - lambdaMix) * ((double) gen / Math.max(generationCount, 1))
                        + lambdaMix * density;

                int[] child;
                if (random.nextDouble() > pSocial) {
                    child = solitaryMove(i, frontNumber);
                } else {
                    child = socialMove(i, frontNumber, elite);
                }
                mutate(child);

                double[] mc = decode(child);
                if (!dominates(makespans[i], costs[i], mc[0], mc[1])) {
                    population.set(i, child);
                    makespans[i] = mc[0];
                    costs[i] = mc[1];
                }
            }
            if (CONFIG_OUTPUT_ARCHIVE) {
                archiveUpdate();
            }
            observeGeneration(gen);
            if (immigrantPeriod > 0 && (gen + 1) % immigrantPeriod == 0 && gen + 1 < generationCount) {
                integrateImmigrants(proposeImmigrants(gen));
            }
        }

        int[] finalFrontNumber = new int[populationSize];
        List<List<Integer>> finalFronts = nonDominatedSort(finalFrontNumber);
        int chosen = bestOf(finalFronts.get(0));

        // Capture metrics before committing, while makespans/costs/population
        // still hold the full final state.
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
        metrics.seedPlanningPoints = seedPlanningPoints;
        lastRun = metrics;

        commitAssignment(commitGenotype);

        Log.printLine("LIWSA finished. Pareto front size: " + finalFronts.get(0).size()
                + ", chosen makespan=" + makespans[chosen] + ", cost=" + costs[chosen]);
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

    // ---------------------------------------------------------------
    // Problem data setup (computation/transfer costs mirror
    // HEFTPlanningAlgorithm exactly, so LIWSA and HEFT share identical
    // low-level timing assumptions)
    // ---------------------------------------------------------------
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
        // file size is in bytes, acc converted to MB, then to Mbit -- identical
        // to HEFTPlanningAlgorithm.calculateTransferCost
        acc = acc / Consts.MILLION;
        return acc * 8 / averageBandwidth;
    }

    // ---------------------------------------------------------------
    // Decoder: genotype -> (makespan, cost), feasible by construction
    // (topological order + insertion-based per-VM scheduling, so there is
    // no separate repair step)
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

    // ---------------------------------------------------------------
    // Population, Pareto ranking, density, and the two locust-derived
    // movement operators
    // ---------------------------------------------------------------
    /**
     * Hook for subclasses: return any warm-start genotypes to seed the
     * initial population with before filling the rest randomly.
     * Returns, in order: any genotypes set via setSeedGenotypes() (array-
     * position based, for direct/manual use outside WorkflowSim), then
     * any schedules in CONFIG_SEED_ASSIGNMENTS translated into genotypes
     * via cloudlet-ID lookup (the path used when run through WorkflowSim,
     * e.g. to warm-start from HEFT's/Min-Min's actual schedules).
     * LIWSAMLPlanningAlgorithm further appends ML-biased starting points
     * on top of whatever this returns.
     */
    protected List<int[]> generateSeedGenotypes() {
        List<int[]> seeds = (seedGenotypes != null) ? new ArrayList<>(seedGenotypes) : new ArrayList<>();
        seeds.addAll(buildSeedsFromAssignments());
        externalSeedCount = seeds.size();
        return seeds;
    }

    /**
     * Converts each schedule in CONFIG_SEED_ASSIGNMENTS (cloudlet ID -> VM
     * ID) into a genotype matching this instance's own taskOrder and
     * vmList. A schedule is skipped entirely, with a log line, if any
     * task in the current workflow has no entry in it, or if it maps a
     * task to a VM ID not present in the current VM pool -- both would
     * indicate the seed was computed for a different workflow or VM
     * configuration than the one currently running.
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
                Log.printLine("LIWSA: skipped a seed schedule that did not match "
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
        seedPlanningPoints = new ArrayList<>();
        for (int i = 0; i < Math.min(externalSeedCount, populationSize); i++) {
            seedPlanningPoints.add(new double[]{makespans[i], costs[i]});
        }
    }

    private double calibrateTau() {
        List<Double> sample = new ArrayList<>();
        for (int t = 0; t < 200; t++) {
            int a = random.nextInt(populationSize);
            int b = random.nextInt(populationSize);
            if (a != b) {
                sample.add(hamming(population.get(a), population.get(b)));
            }
        }
        if (sample.isEmpty()) {
            return 0.3;
        }
        Collections.sort(sample);
        return sample.get(sample.size() / 2);
    }

    private double hamming(int[] a, int[] b) {
        int diff = 0;
        for (int k = 0; k < a.length; k++) {
            if (a[k] != b[k]) {
                diff++;
            }
        }
        return (double) diff / a.length;
    }

    private double kernel(double d) {
        double raw = kernelF * Math.exp(-d / kernelL) - Math.exp(-d);
        return 1.0 / (1.0 + Math.exp(-raw));
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

    // Per-individual caches, valid for the individual currently being moved.
    // The population changes only when an accepted child replaces its
    // parent at the end of that individual's turn, so one distance row and
    // one kernel row per turn is exactly what the operators need; the
    // earlier implementation recomputed the same Hamming distance for
    // every task of the genotype inside the solitary vote (n times more
    // work, same values).
    private double[] distRow;
    private double[] kernRow;
    private int[] voterIdx;
    private double[] voterWeight;
    private int[][] voterGenotype;
    private double[] voteSum;
    private boolean[] votePresent;
    private int[] optionVm;
    private double[] optionProb;

    // ---- extension hooks (no-ops here; LIWSA-ML overrides them for online learning) ----
    /** If > 0, proposeImmigrants() is consulted every this many generations. */
    protected int immigrantPeriod = 0;

    /** Called once per generation after the population has been updated. */
    protected void observeGeneration(int gen) {
    }

    /** Candidate genotypes to inject during the search; every one is decoded and counted as an evaluation. */
    protected List<int[]> proposeImmigrants(int gen) {
        return new ArrayList<>();
    }

    /**
     * Each immigrant replaces the worst-ranked individual not on the first
     * front (one victim per immigrant), unless that individual strictly
     * dominates it. The first front is never displaced.
     */
    private void integrateImmigrants(List<int[]> immigrants) {
        if (immigrants == null || immigrants.isEmpty()) {
            return;
        }
        int[] frontNumber = new int[populationSize];
        nonDominatedSort(frontNumber);
        boolean[] used = new boolean[populationSize];
        for (int[] g : immigrants) {
            double[] mc = decode(g);
            int victim = -1;
            for (int i = 0; i < populationSize; i++) {
                if (used[i] || frontNumber[i] == 0) {
                    continue;
                }
                if (victim < 0 || frontNumber[i] > frontNumber[victim]) {
                    victim = i;
                }
            }
            if (victim < 0) {
                continue;
            }
            used[victim] = true;
            if (!dominates(makespans[victim], costs[victim], mc[0], mc[1])) {
                population.set(victim, g);
                makespans[victim] = mc[0];
                costs[victim] = mc[1];
            }
        }
        if (CONFIG_OUTPUT_ARCHIVE) {
            archiveUpdate();
        }
    }

    // ---- optional external archive (see CONFIG_OUTPUT_ARCHIVE) ----
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

    private void allocateMoveBuffers() {
        int p = populationSize;
        int m = vmList.size();
        distRow = new double[p];
        kernRow = new double[p];
        voterIdx = new int[p];
        voterWeight = new double[p];
        voterGenotype = new int[p][];
        voteSum = new double[m];
        votePresent = new boolean[m];
        optionVm = new int[m];
        optionProb = new double[m];
    }

    private void computeDistanceRows(int i) {
        int[] gi = population.get(i);
        for (int j = 0; j < populationSize; j++) {
            if (j == i) {
                continue;
            }
            distRow[j] = hamming(gi, population.get(j));
            kernRow[j] = kernel(distRow[j]);
        }
    }

    private double localDensity(int i, double tau) {
        int n = populationSize - 1;
        if (n <= 0) {
            return 0.0;
        }
        int count = 0;
        for (int j = 0; j < populationSize; j++) {
            if (j != i && distRow[j] < tau) {
                count++;
            }
        }
        return (double) count / n;
    }

    private int weightedChoice(List<Integer> options, List<Double> weights) {
        double total = 0.0;
        for (double w : weights) {
            total += w;
        }
        if (total <= 0) {
            return options.get(random.nextInt(options.size()));
        }
        double r = random.nextDouble() * total;
        double acc = 0.0;
        for (int k = 0; k < options.size(); k++) {
            acc += weights.get(k);
            if (r <= acc) {
                return options.get(k);
            }
        }
        return options.get(options.size() - 1);
    }

    /**
     * Solitary phase: every other individual casts a signed,
     * distance-weighted vote on each task's VM (positive if better-ranked,
     * negative if worse, zero if on the same Pareto front), then the VM
     * for that task is resampled from a softmax over the accumulated
     * votes. This is the discrete stand-in for summing continuous
     * attraction/repulsion force vectors.
     *
     * The voter weights depend only on the voter's front and its distance
     * to individual i, so they are formed once per call; each task then
     * costs one pass over the voters. Candidate VMs are visited in
     * ascending index order, which is the iteration order the earlier
     * HashMap-based tally produced for VM pools of at most 16 machines.
     */
    private int[] solitaryMove(int i, int[] frontNumber) {
        int n = taskOrder.size();
        int m = vmList.size();
        int[] child = population.get(i).clone();

        int nv = 0;
        for (int j = 0; j < populationSize; j++) {
            if (j == i || frontNumber[j] == frontNumber[i]) {
                continue;
            }
            double sign = (frontNumber[j] < frontNumber[i]) ? 1.0 : -1.0;
            voterIdx[nv] = j;
            voterWeight[nv] = sign * kernRow[j];
            voterGenotype[nv] = population.get(j);
            nv++;
        }

        for (int k = 0; k < n; k++) {
            java.util.Arrays.fill(voteSum, 0.0);
            java.util.Arrays.fill(votePresent, false);
            for (int q = 0; q < nv; q++) {
                int v = voterGenotype[q][k];
                voteSum[v] += voterWeight[q];
                votePresent[v] = true;
            }
            if (nv > 0 && random.nextDouble() < blendProbability) {
                double max = Double.NEGATIVE_INFINITY;
                for (int v = 0; v < m; v++) {
                    if (votePresent[v]) {
                        max = Math.max(max, voteSum[v]);
                    }
                }
                int cnt = 0;
                double sum = 0.0;
                for (int v = 0; v < m; v++) {
                    if (votePresent[v]) {
                        double e = Math.exp(voteSum[v] - max);
                        optionVm[cnt] = v;
                        optionProb[cnt] = e;
                        sum += e;
                        cnt++;
                    }
                }
                for (int c = 0; c < cnt; c++) {
                    optionProb[c] = optionProb[c] / sum;
                }
                child[k] = weightedChoiceArray(cnt);
            }
        }
        return child;
    }

    private int weightedChoiceArray(int cnt) {
        double total = 0.0;
        for (int c = 0; c < cnt; c++) {
            total += optionProb[c];
        }
        if (total <= 0) {
            return optionVm[random.nextInt(cnt)];
        }
        double r = random.nextDouble() * total;
        double acc = 0.0;
        for (int c = 0; c < cnt; c++) {
            acc += optionProb[c];
            if (r <= acc) {
                return optionVm[c];
            }
        }
        return optionVm[cnt - 1];
    }

    /**
     * Social phase: roulette-selected attraction toward one member of the
     * current elite (front-1, expanded if too small) set only, then a
     * per-task copy-by-probability toward that single partner.
     */
    private int[] socialMove(int i, int[] frontNumber, List<Integer> elite) {
        List<Integer> candidates = new ArrayList<>();
        for (int e : elite) {
            if (e != i) {
                candidates.add(e);
            }
        }
        if (candidates.isEmpty()) {
            return population.get(i).clone();
        }
        List<Double> weights = new ArrayList<>();
        for (int e : candidates) {
            weights.add(kernRow[e] / (frontNumber[e] + 1));
        }
        int partner = weightedChoice(candidates, weights);
        int[] Y = population.get(partner);
        double pCopy = Math.max(0.0, Math.min(1.0, copyAlpha * kernRow[partner]));

        int[] child = population.get(i).clone();
        for (int k = 0; k < child.length; k++) {
            if (random.nextDouble() < pCopy) {
                child[k] = Y[k];
            }
        }
        return child;
    }

    private void mutate(int[] child) {
        for (int k = 0; k < child.length; k++) {
            if (random.nextDouble() < mutationRate) {
                child[k] = random.nextInt(vmList.size());
            }
        }
    }
}
