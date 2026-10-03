# Changes in v2

All changes are listed so that the code can be compared with the version used for the paper.

## 1. Speed (no change to any result)

| Change | File(s) |
|---|---|
| Allocation-free decoder (tabulated parents, transfer costs, durations, VM prices; primitive per-VM interval arrays). Same arithmetic in the same order. | `LIWSAPlanningAlgorithm`, `NSGAIIPlanningAlgorithm`, `MLEAOPlanningAlgorithm` (identical code in all three, so search-time comparisons are not skewed) |
| LIWSA: one Hamming-distance row and one kernel row per individual per generation, shared by the density estimate, the solitary vote and the social move. The old code recomputed the same Hamming distance once per task inside the solitary vote, i.e. `O(P^2 n^2)` per generation instead of the documented `O(P^2 n)`. | `LIWSAPlanningAlgorithm` |
| Vote tally in a primitive array, visited in ascending VM index. This is the iteration order the old `HashMap<Integer,Double>` produced for pools of at most 16 VMs (the evaluated pool has exactly 16). With more than 16 VMs the random stream differs from v1 (statistically equivalent). | `LIWSAPlanningAlgorithm` |

**Verification.** `RegressionHarness` prints every front point to 12 decimals, the simulator-measured makespan and cost, and front sizes. v1 (with only item 3 below applied) and v2 gave byte-identical output (timings excluded) for LIWSA, LIWSA-ML, LIWSA-NoDensity, NSGA-II and MLEAO on Montage_25/50/100/1000, CyberShake_30/50/1000, Sipht_30/60, Epigenomics_24/46/997 and Inspiral_30/50/100, seeds 1-5 on the small ones. The default benchmark run on Montage_25 reproduces the paper's Supplementary means and is column-for-column identical to v1.

**Measured speed-up (1 core, search time only):** 100 tasks: LIWSA 1062 -> 325 ms, NSGA-II 370 -> 113 ms; 1000 tasks (Montage_1000): LIWSA 15.2 s -> 1.45 s, NSGA-II 2.37 s -> 0.80 s. Re-measure on your own machine before quoting runtimes.

## 2. Instrumentation

* `LastRunMetrics.objectiveEvaluations` in all three algorithm classes. At 30 x 100: LIWSA 2930, LIWSA-ML 3330 (400 training decodes), NSGA-II 3030.
* `LastRunMetrics.paretoFrontAssignments` (cloudlet ID -> VM ID per front member) so any front member can be replayed in the simulator.
* `LIWSAPlanningAlgorithm.lastRun.seedPlanningPoints`: decoder values of the HEFT / Min-Min warm-start seeds.

## 3. HEFT determinism (changes baseline results on tie-sensitive instances)

HEFT broke rank ties, and summed per-VM costs, in `HashMap` iteration order keyed by object identity hash, so its schedule depends on the JVM's history: even adding an unrelated class to the build changes it (observed on Montage_50: simulated HEFT makespan 70.96 to 71.14 when only the number of earlier identity-hash calls changed). v2 breaks ties by task-list order and sums in VM-list order, so results are identical on every run and every JVM. `-Dliwsa.legacyHeftTies=true` restores the old behaviour.

**Which published results change.** Compared against the original build on 15 instances of 24-100 tasks, only the three Montage instances (Montage_25, Montage_50, Montage_100) differ; CyberShake, Sipht, Epigenomics and Inspiral (12 instances) are identical. The 1000-task instances were not compared. A Montage_25 benchmark run with v2 gives HEFT hypervolume 424.7 instead of the Supplementary's 537.6 (the HEFT point itself, makespan 35.52 / cost 110.27, is unchanged; the warm-start seed and therefore the stochastic algorithms' trajectories differ). No build can guarantee to reproduce the original Montage numbers bit for bit, because they depend on JVM hash order, so the Montage rows of the Supplementary have to be regenerated.

## 3b. Output folder, end-of-run summary, Python scripts in the root

* Every program writes under `Output&Results/` (`ResultsPaths.OUTPUT_DIR`); the former `results/` folder was moved there. Quote the path on the command line (the `&` is special in cmd.exe/PowerShell).
* `ResultsSummary` prints and saves (`<name>_summary.txt`, `<name>_summary.csv`) a summary of all algorithms at the end of `LIWSABenchmarkExample`, `SensitivityAblationExample`, `DecoderFidelityCheck`, `SimulatedFrontEvaluation` and `EqualTimeBenchmark`, and `run_parallel.sh` calls it on the combined CSV. It can also be run on its own: `java -cp "bin:lib/*" org.workflowsim.examples.planning.ResultsSummary "Output&Results/file.csv"`.
* `generate_figures.py`, `summarize_results.py`, `analyze_online_learning.py` and `run_parallel.sh` are in the repository root. `python generate_figures.py --all` processes every known results file. The old `results/` paths inside them were updated.

## 4. New tools (`examples/org/workflowsim/examples/planning/`)

| Tool | Purpose |
|---|---|
| `DecoderFidelityCheck` | Planning-level (decoder) vs simulator-measured makespan and cost of the committed schedule. |
| `SimulatedFrontEvaluation` | Replays every distinct final-front member in WorkflowSim and reports hypervolume from simulator-measured points for all algorithms next to the original convention. |
| `EqualTimeBenchmark` | NSGA-II re-run with the number of generations that matches LIWSA-ML's wall-clock search time. |
| `RegressionHarness` | Bit-level comparison of two builds. |
| `LIWSABenchmarkExample` flags | `-Dliwsa.coldStart=true` (no HEFT/Min-Min seeding), `-Dliwsa.planningLevelBaselines=true` (score HEFT/Min-Min with the shared decoder). Both default to off. |

## 5. Opt-in output archive (`-Dliwsa.outputArchive=true`, default off)

Diagnosis: LIWSA's acceptance rule keeps a child unless its parent strictly dominates it, and nothing preserves the best trade-off points, so the final population loses non-dominated solutions the search had already found (about 5 distinct front points versus about 15 for NSGA-II, which truncates by crowding distance). LIWSA also stops improving between 100 and 400 generations while NSGA-II keeps improving.

With the flag on, every distinct non-dominated solution visited is kept in an external archive (weak-dominance filter, crowding-distance truncation to the population size) and returned as the front; the committed schedule is the archive's minimum-makespan member. The search dynamics are untouched (same random stream, same population trajectory). With the flag off the output is bit-identical to the previous build (checked on Montage_50, Sipht_30, CyberShake_50).

Exploratory result, planning-level hypervolume, 15 instances of 24-100 tasks, 5 seeds, instance as the experimental unit, no parameters tuned:

| Comparison | mean | median | wins / losses | Wilcoxon p |
|---|---|---|---|---|
| LIWSA+archive vs LIWSA | +4.6% | +3.8% | 13 / 0 | 0.001 |
| LIWSA-ML+archive vs LIWSA-ML | +3.4% | +3.0% | 14 / 0 | 0.001 |
| LIWSA-ML+archive vs NSGA-II | +0.5% | 0.0% | 8 / 7 | 0.52 |
| LIWSA-ML (no archive) vs NSGA-II | -2.8% | -2.7% | 3 / 12 | 0.002 |
| LIWSA-ML (no archive) vs LIWSA | +3.2% | +2.5% | 13 / 2 | 0.010 |

Simulator-measured hypervolume (7 instances, `SimulatedFrontEvaluation`): LIWSA-ML+archive vs NSGA-II +4.0% mean, 0.0% median, 4 of 7 wins, p = 0.81; vs HEFT +14.5%, vs MLEAO +3.6%. This is parity with NSGA-II, not superiority. It was found on the same instances the paper uses, so any use in the paper requires a full 20-instance rerun and disclosure as a design change.

## 6. Opt-in online learning in LIWSA-ML (`-Dliwsa.onlineLearning=true`, default off)

In v1 the predictor is trained once, before the search, on 400 random genotypes, and is never updated. With the flag on it keeps learning during the search: every schedule the search evaluates is added to the OLS statistics (exact sufficient statistics per task and VM, so each refit is the exact OLS on all data seen so far), and every 10 generations (`-Dliwsa.onlinePeriod`) the model is refitted and 4 fresh model-biased genotypes (the same four trade-off weights as the warm-start seeds) are decoded and injected. Each immigrant replaces the worst-ranked individual outside the first front, unless that individual strictly dominates it. Extra cost: 36 evaluations (3330 -> 3366). With the flag off the output is bit-identical to the previous build.

Protocol, fixed before running: one design, no tuning loop; development on the five instances where published LIWSA-ML trailed NSGA-II most (Inspiral_30, Montage_50, Epigenomics_24, Epigenomics_100, Inspiral_50), 10 seeds; then the other ten instances of 24-100 tasks, 10 seeds, nothing changed. Planning-level hypervolume with a shared per-instance reference; instance is the experimental unit. 1000-task instances were not run.

| Held-out comparison (10 instances) | mean | median | instances won | seed-level wins | Wilcoxon p |
|---|---|---|---|---|---|
| online vs published LIWSA-ML | +2.0% | +1.3% | 8 / 10 | 64 / 100 | 0.064 |
| online + archive vs archive only | +1.2% | +0.2% | 8 / 10 | 71 / 100 | 0.105 |
| online + archive vs NSGA-II | +2.65% | +0.13% | 6 / 10 | 62 / 100 | 0.275 |
| online + archive vs NSGA-II at matched evaluations (111 gen) | +2.3% | +0.11% | 6 / 10 | - | 0.275 |
| published LIWSA-ML vs NSGA-II | -1.5% | -2.3% | 1 / 10 | 23 / 100 | 0.020 |

Online + archive vs NSGA-II is concentrated in the larger instances (Montage_100 +15.3%, CyberShake_100 +9.5%) and is a tie on most of the small ones (mean far above median). It is not statistically significant. Raw outputs are in `Output&Results/online_learning_eval/`; `python analyze_online_learning.py` reproduces the table. Not yet checked: 1000-task instances, simulator-measured hypervolume, a full 20-instance run.

## 7. Findings to be aware of

* WorkflowSim adds a job's stage-in transfer time to the job's length, so the simulator occupies and bills the VM during staging; the decoder charges compute time only. Cost therefore differs by about 0.3% at the median, and makespan of evolved schedules is higher in the simulator (median +10% LIWSA, +12% NSGA-II over 15 instances of 24-100 tasks; HEFT +0.3%).
* Hypervolume in the benchmark uses decoder values for the four population-based algorithms and simulator values for HEFT and Min-Min. Run `SimulatedFrontEvaluation` to apply one evaluator to all.
* `Pareto front size` counts members of the final non-dominated population, including duplicates of the same objective vector.
