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
        public long searchWallClockMillis;
        public int populationSizeUsed;
        public int generationCountUsed;
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

    private static class Event {

        double start;
        double finish;

        Event(double start, double finish) {
            this.start = start;
            this.finish = finish;
        }
    }

    public LIWSAPlanningAlgorithm() {
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

        initializePopulation();
        double tau = calibrateTau();

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
        for (int i : finalFronts.get(0)) {
            metrics.paretoFrontPoints.add(new double[]{makespans[i], costs[i]});
        }
        metrics.searchWallClockMillis = System.currentTimeMillis() - searchStartMillis;
        metrics.populationSizeUsed = populationSize;
        metrics.generationCountUsed = generationCount;
        lastRun = metrics;

        commitAssignment(population.get(chosen));

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
    private double findFinishTime(List<Event> sched, double readyTime, double duration, boolean occupySlot) {
        if (sched.isEmpty()) {
            if (occupySlot) {
                sched.add(new Event(readyTime, readyTime + duration));
            }
            return readyTime + duration;
        }

        if (sched.size() == 1) {
            double start;
            int pos;
            if (readyTime >= sched.get(0).finish) {
                pos = 1;
                start = readyTime;
            } else if (readyTime + duration <= sched.get(0).start) {
                pos = 0;
                start = readyTime;
            } else {
                pos = 1;
                start = sched.get(0).finish;
            }
            if (occupySlot) {
                sched.add(pos, new Event(start, start + duration));
            }
            return start + duration;
        }

        double start = Math.max(readyTime, sched.get(sched.size() - 1).finish);
        double finish = start + duration;
        int pos = sched.size();
        int i = sched.size() - 1;
        int j = sched.size() - 2;
        while (j >= 0) {
            Event current = sched.get(i);
            Event previous = sched.get(j);
            if (readyTime > previous.finish) {
                if (readyTime + duration <= current.start) {
                    start = readyTime;
                    finish = readyTime + duration;
                }
                break;
            }
            if (previous.finish + duration <= current.start) {
                start = previous.finish;
                finish = previous.finish + duration;
                pos = i;
            }
            i--;
            j--;
        }

        if (readyTime + duration <= sched.get(0).start) {
            pos = 0;
            start = readyTime;
            if (occupySlot) {
                sched.add(pos, new Event(start, start + duration));
            }
            return start + duration;
        }

        if (occupySlot) {
            sched.add(pos, new Event(start, finish));
        }
        return finish;
    }

    protected double[] decode(int[] genotype) {
        Map<CondorVM, List<Event>> schedules = new HashMap<>();
        for (CondorVM vm : vmList) {
            schedules.put(vm, new ArrayList<Event>());
        }
        Map<Task, Double> finish = new HashMap<>();
        Map<Task, CondorVM> assignedVm = new HashMap<>();
        double cost = 0.0;

        for (int k = 0; k < taskOrder.size(); k++) {
            Task task = taskOrder.get(k);
            CondorVM vm = vmList.get(genotype[k]);

            double ready = 0.0;
            for (Object parentObj : task.getParentList()) {
                Task parent = (Task) parentObj;
                double pf = finish.get(parent);
                if (assignedVm.get(parent) != vm) {
                    Double tc = transferCosts.get(parent).get(task);
                    pf += (tc != null) ? tc : 0.0;
                }
                ready = Math.max(ready, pf);
            }

            double duration = computationCosts.get(task).get(vm);
            double fin = findFinishTime(schedules.get(vm), ready, duration, true);

            finish.put(task, fin);
            assignedVm.put(task, vm);
            cost += duration * vm.getCost();
        }

        double makespan = 0.0;
        for (double f : finish.values()) {
            makespan = Math.max(makespan, f);
        }
        return new double[]{makespan, cost};
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

    private double localDensity(int i, double tau) {
        int n = populationSize - 1;
        if (n <= 0) {
            return 0.0;
        }
        int count = 0;
        for (int j = 0; j < populationSize; j++) {
            if (j != i && hamming(population.get(i), population.get(j)) < tau) {
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
     */
    private int[] solitaryMove(int i, int[] frontNumber) {
        int n = taskOrder.size();
        int[] child = population.get(i).clone();
        for (int k = 0; k < n; k++) {
            Map<Integer, Double> votes = new HashMap<>();
            for (int j = 0; j < populationSize; j++) {
                if (j == i || frontNumber[j] == frontNumber[i]) {
                    continue;
                }
                double sign = (frontNumber[j] < frontNumber[i]) ? 1.0 : -1.0;
                double d = hamming(population.get(i), population.get(j));
                double w = sign * kernel(d);
                int v = population.get(j)[k];
                votes.put(v, votes.getOrDefault(v, 0.0) + w);
            }
            if (!votes.isEmpty() && random.nextDouble() < blendProbability) {
                List<Integer> vmsList = new ArrayList<>(votes.keySet());
                double max = Double.NEGATIVE_INFINITY;
                for (int v : vmsList) {
                    max = Math.max(max, votes.get(v));
                }
                List<Double> probs = new ArrayList<>();
                double sum = 0.0;
                for (int v : vmsList) {
                    double e = Math.exp(votes.get(v) - max);
                    probs.add(e);
                    sum += e;
                }
                for (int idx = 0; idx < probs.size(); idx++) {
                    probs.set(idx, probs.get(idx) / sum);
                }
                child[k] = weightedChoice(vmsList, probs);
            }
        }
        return child;
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
            double d = hamming(population.get(i), population.get(e));
            weights.add(kernel(d) / (frontNumber[e] + 1));
        }
        int partner = weightedChoice(candidates, weights);
        int[] Y = population.get(partner);
        double dIY = hamming(population.get(i), Y);
        double pCopy = Math.max(0.0, Math.min(1.0, copyAlpha * kernel(dIY)));

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
