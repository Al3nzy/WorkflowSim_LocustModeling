# 🦗 WorkflowSim — Locust-Inspired Workflow Scheduling

<p align="center">
  <img src="https://img.shields.io/badge/Java-11%2B-orange?logo=java" />
  <img src="https://img.shields.io/badge/WorkflowSim-1.1.0-blue" />
  <img src="https://img.shields.io/badge/CloudSim-3.0-blue" />
  <img src="https://img.shields.io/badge/License-Apache%202.0-green" />
  <img src="https://img.shields.io/badge/Benchmark-Pegasus%20DAX-purple" />
  <img src="https://img.shields.io/badge/Paper-IEEE%20TCC-red" />
</p>

<p align="center">
  <b>Density-Adaptive Locust Swarm Optimisation with Simulation-Trained OLS Warm-Start<br>for Pareto-Based Cloud Workflow Scheduling</b><br>
  <i>Dr. Mohammed Alaa Ala'anzy — SDU University, Kazakhstan</i>
</p>

> **Version note (v2).** This release makes the search roughly 3x faster on 100-task and
> up to roughly 10x faster on 1000-task workflows **without changing any result**
> (see [`CHANGES_v2.md`](CHANGES_v2.md)), makes HEFT deterministic, and adds tools that
> measure how closely the planning-level decoder matches WorkflowSim
> (`DecoderFidelityCheck`, `SimulatedFrontEvaluation`) and that compare NSGA-II and
> LIWSA-ML at matched wall-clock time (`EqualTimeBenchmark`). Search-time figures
> quoted below were measured with the earlier, slower implementation.

## Quick start

The final algorithms are now the default: **LIWSA** uses the external Pareto archive, and **LIWSA-ML** uses the OLS
warm start, keeps learning during the search, and uses the archive. HEFT, Min-Min, MLEAO and NSGA-II are unchanged.
(`-Dliwsa.outputArchive=false -Dliwsa.onlineLearning=false` returns to the earlier LIWSA/LIWSA-ML.)

From the repository root (Java 11 or newer; on macOS/Linux replace `;` with `:` in `-cp`; always keep the quotes):

```bash
# 1. Everything the paper needs (main benchmark, component variants, density ablation, OLS-vs-naive, lambda and theta sweeps,
#    pricing sensitivity, matched wall-clock comparison). On Code Ocean the `run` script does steps 1 and 3 and paper_statistics.py
java -cp "bin;lib/*" org.workflowsim.examples.planning.RunPaperExperiments
#    quick test on two workflows:   ... RunPaperExperiments "Montage_25,Sipht_30"

# 2. Only the main benchmark (writes Output&Results/benchmark_results_nsga2.csv and prints the summary; HEFT/Min-Min hypervolume uses the common planning-level evaluator)
java -cp "bin;lib/*" org.workflowsim.examples.planning.LIWSABenchmarkExample

# 3. Statistics quoted in the paper, then figures and summaries (PDF + PNG in  Output&Results/figures/ )
python paper_statistics.py
python make_paper_figures.py "Output&Results/paper_main.csv" --outdir "Output&Results/paper_figures"
python generate_figures.py "Output&Results/paper_main.csv"
python generate_figures.py --all
python make_paper_figures.py                 # the six result figures of the paper, written to figs/

# 4. Optional: compare the earlier published version (A), + archive (B) and the final version (C)
java -cp "bin;lib/*" org.workflowsim.examples.planning.RunAllVariants
```
Every run prints a summary of all algorithms at the end and saves it next to the CSV (`*_summary.txt`, `*_summary.csv`).
## 📁 Repository Structure

```
WorkflowSim_LocustModeling/
├── sources/org/workflowsim/planning/
│   ├── LIWSAPlanningAlgorithm.java          ← LIWSA (density-adaptive search)
│   ├── LIWSAMLPlanningAlgorithm.java        ← LIWSA-ML (OLS warm-start)
│   ├── NSGAIIPlanningAlgorithm.java         ← Standard NSGA-II baseline
│   ├── MLEAOPlanningAlgorithm.java          ← MLEAO baseline (2025 comparison paper)
│   └── HEFTPlanningAlgorithm.java           ← HEFT baseline
├── examples/org/workflowsim/examples/planning/
│   ├── LIWSAPlanningAlgorithmExample.java      ← LIWSA single-run example
│   ├── LIWSAMLPlanningAlgorithmExample.java    ← LIWSA-ML single-run example
│   ├── LIWSABenchmarkExample.java              ← Full 20-instance, 6-algorithm benchmark driver
│   ├── SensitivityAblationExample.java         ← Lambda/theta sweeps + OLS-vs-naive ablation
│   ├── MLEAOPlanningAlgorithmExample.java      ← MLEAO baseline example
│   ├── HEFTPlanningAlgorithmExample1.java      ← HEFT baseline example
│   ├── DHEFTPlanningAlgorithmExample1.java     ← DHEFT variant example
│   ├── HEFTBenchmark.java                      ← HEFT benchmark with metrics
│   ├── ParetoMetrics.java                      ← 2D hypervolume calculator
│   ├── ResultsCsvWriter.java                   ← Shared CSV output writer
│   └── RunMetricsCalculator.java               ← Shared metrics (all algorithms)
├── generate_figures.py                         ← Figures + summary for any results CSV (Python)
├── summarize_results.py                        ← Summary table of all algorithms (Python)
├── analyze_online_learning.py                  ← Online-learning vs NSGA-II comparison (Python)
├── run_parallel.sh                             ← Safe process-level parallel batch runner
└── Output&Results/                             ← EVERYTHING the programs write goes here
    ├── *.csv                                   ← Benchmark, ablation, sweep and tool results
    ├── *_summary.txt / *_summary.csv           ← All-algorithm summary written at the end of each run
    ├── figures/                                ← PDF figures made by generate_figures.py
    └── logs/                                   ← Per-batch logs from run_parallel.sh
```

> **Windows / shells:** the output folder is called `Output&Results`. The `&` is a command separator in
> `cmd.exe` and the call operator in PowerShell, so **always put the path in quotes** (`"Output&Results/..."`).
> The folder name is defined in one place in the Java code (`ResultsPaths.OUTPUT_DIR`) and at the top of each
> Python script (`OUTPUT_DIR`), so it can be changed easily.

---

## 🧠 The Algorithms

### LIWSA — Locust-Inspired Workflow Scheduling Algorithm

Each candidate schedule is an integer vector `X = (x₁, …, xₙ)` assigning task `tₖ` to VM `vmₓₖ`. At every generation, each individual:

1. **Measures its local crowding density** `ρᵢ` — the fraction of population members within normalised Hamming distance `τ` (self-calibrated to the initial population's median pairwise distance, no hand-tuning needed).
2. **Decides its own phase probability** `p_soc = (1−λ)·t/T_max + λ·ρᵢ` — blending measured crowding with mild global annealing.
3. If **solitary** (`rand() > p_soc`): every other individual casts a signed, distance-weighted vote on each task's VM assignment. Better-ranked neighbours attract; worse-ranked ones repel. A softmax draw over the vote totals preserves diversity.
4. If **gregarious** (`rand() ≤ p_soc`): selects a partner from the elite set (the current Pareto front) via roulette weighted by proximity and front rank, then copies tasks probabilistically.
5. **Acceptance**: a child replaces its parent only if the parent does not strictly dominate the child — lateral moves to new non-dominated solutions are permitted.

Fitness is determined by **Pareto dominance** over makespan `M(X)` and execution cost `Γ(X)` — no weights, no normalisation.

### LIWSA-ML — OLS Warm-Start Extension

LIWSA-ML adds a pure-Java, zero-dependency warm-start that runs *inside* WorkflowSim before the main search:

1. **Sample** `Nₛ = 400` random genotypes and decode them through the simulation.
2. **Fit** two ordinary least-squares regressions (9×9 normal equations, solved via Gaussian elimination) predicting makespan and cost from a 9-dimensional (task, VM) feature vector: task length, topological level, fan-in/out, VM MIPS, cost rate, predicted duration, predicted cost, intercept — all normalised.
3. **Inject** `Nₚ = 4` OLS-biased seed genotypes covering different points on the makespan-cost trade-off axis, plus the actual HEFT and Min-Min schedules (via cloudlet-ID-keyed assignment maps), for 6 warm-start seeds total.
4. **Run LIWSA** from this biased initial population.

No TensorFlow. No PyTorch. No Python. One `.java` file.

### NSGA-II — Canonical Multi-Objective Baseline

A standard, faithful implementation (Deb et al., 2002): fast non-dominated sorting, crowding distance, and the crowded-comparison tournament are used exactly as originally specified. The one deliberate adaptation is uniform crossover and random-resetting mutation in place of simulated binary crossover and polynomial mutation, since neither is defined for this problem's categorical VM-index genes. It shares LIWSA's exact genotype, decoder, and warm-start seeding (see `Sec:Baselines` in the paper), so the comparison isolates search strategy from encoding effects.

---

## 📊 Key Results (20 Pegasus Benchmark Instances, 5 Families, 5 Seeds Each)

All six algorithms are scored by **one planning-level evaluator**: the hypervolume of every algorithm, HEFT and Min-Min included, is computed from decoder objective values (set `-Dliwsa.planningLevelBaselines=false` to restore the earlier simulator-measured HEFT/Min-Min point). Simulator-measured makespan and cost of the committed schedules are reported alongside. `python3 paper_statistics.py` recomputes every number below from the CSV files.

| Algorithm | HV score (% of best) | Highest HV on | Mean rank | Mean front size (archive for LIWSA variants, final-population front for MLEAO/NSGA-II) | Search time per run |
|-----------|---------------------:|:-------------:|:---------:|:---------------:|:-------------------:|
| HEFT | 71.0 | 0 | 5.08 | 1.0 | no search |
| Min-Min | 35.2 | 0 | 5.90 | 1.0 | no search |
| MLEAO | 83.8 | 0 | 4.03 | 6.1 | 0.13 s |
| LIWSA | 91.9 | 0 | 2.65 | 15.8 | 0.18 s |
| NSGA-II | 93.3 | 6 | 1.90 | 26.9 | 0.13 s |
| **LIWSA-ML** | **99.7** | **14** | **1.45** | 16.5 | 0.29 s |

LIWSA-ML exceeds HEFT, Min-Min, and MLEAO in mean hypervolume by 104.5%, 265.1%, and 21.4% (higher on all 20 workflows), LIWSA by 10.1% (17 of 20), and NSGA-II by 8.1% (14 of 20, Wilcoxon p = 0.024, Holm-adjusted). The difference depends on scale: on the five workflows of about 1000 tasks LIWSA-ML is higher on every one (+28.6% on average), and on the 15 workflows of 24 to 100 tasks the two are within 1.3% (higher on 9 of 15, p = 0.42). LIWSA-ML needs about 2.3x NSGA-II's search time (timing measured on a single-core container; hardware dependent).

**Objective evaluations per run:** LIWSA 2930, NSGA-II 3030, LIWSA-ML 3366 (the 2930 of LIWSA, 400 simulator-generated OLS training decodes, and 36 online-learning immigrant decodes).

**Approximately matched wall-clock time.** With NSGA-II given as many generations as fit into LIWSA-ML's search time (347 on average; total search time 100.5% of LIWSA-ML's, within 10% per workflow on 17 of 20), NSGA-II is 2.2% ahead on the 15 smaller workflows (LIWSA-ML higher on 3 of 15) and LIWSA-ML is 22.1% ahead on the five largest (higher on all five). Over all 20 workflows the difference is not significant (+3.8%, p = 0.81). Reproduce with `EqualTimeBenchmark` (part of `RunPaperExperiments`).

**Component analysis** (hypervolume relative to NSGA-II): the external archive adds 7.6 percentage points to LIWSA-ML (19 of 20 workflows improved) and online learning a further 1.6 (18 of 20). The archive and online learning were developed on the 15 workflows of 24-100 tasks (online learning on the five where the earlier LIWSA-ML trailed NSGA-II most, ten others as a check); no workflow of about 1000 tasks was used in development. See the manuscript's Algorithm Parameters section and Supplementary S14.

On **data-intensive workflows** (Epigenomics, Inspiral at about 1000 tasks), LIWSA-ML reduces makespan and cost relative to HEFT at once (Epigenomics_997: -80.0% makespan, -9.8% cost), a pattern that stems from a structural HEFT weakness on large file transfers and is shared to some degree by all population-based algorithms.

<p align="center">
  <img src="Output%26Results/paper_figures/hypervolume_families.png" alt="Hypervolume by workflow family" width="800"><br>
  <sub><b>Fig. 1</b> - Hypervolume as a percentage of the best algorithm on each workflow, by family and scale (the paper's Fig. 2).</sub>
</p>

### Ablations and Sensitivity Analysis

| Experiment | Finding |
|---|---|
| **NSGA-II with the same archive** | Hypervolume changes by at most 0.05% per run (front size 26.9 to 18.6 after duplicate removal); the archive asymmetry does not affect the comparison. |
| **Density ablation** (`LIWSA` vs `LIWSA-NoDensity`, density fixed at 0.5) | Measured density adds no hypervolume (-0.07% mean, higher on 8 of 20 workflows, p = 0.73); its benefit is self-calibration of the phase mixing, with no mixing value to choose. |
| **lambda sensitivity** (phase-mixing weight, 0.1-0.9) | Low sensitivity: hypervolume varies by 2.0% on average across values (at most 4.0%); no value changes the mean by more than 0.8% relative to the default. |
| **theta sensitivity** (softmax temperature, 0.1-0.9) | Low sensitivity: 1.2% on average (at most 3.1%); no value changes the mean by more than 0.7%. |
| **OLS vs naive features** (`LIWSA-ML` vs `LIWSA-ML-Naive`) | The learned predictor is higher on four of five workflows (+4.7% mean, 21 of 25 seed runs); Inspiral_100 favours the naive scoring (-3.3%). |
| **VM pricing sensitivity** | LIWSA-ML / NSGA-II hypervolume ratio stays within 0.971-1.010 under three pricing schemes (HEFT scored by its simulator point in this table). |

---

## 🔧 Shared Infrastructure

All algorithm drivers share the same supporting classes, so results are directly comparable without reconciling different column layouts or metric definitions:

**`RunMetricsCalculator`** — computes makespan, execution cost (using `CostModel.VM` per-second rates), average VM utilisation, Jain's fairness index, and scheduling speedup from the simulator's actual job results. One implementation, used by every driver.

**`ParetoMetrics`** — 2D hypervolume calculator with a shared cross-algorithm reference point. The reference point is computed once per workflow, across every algorithm being compared for that workflow, and reused — ensuring hypervolume comparisons are meaningful and not inflated by a single algorithm's own bad points. (This also means hypervolume values are only comparable *within* one CSV/comparator set, not across different CSVs — see the note below.)

**`ResultsCsvWriter`** — single CSV schema, flushed to disk after every completed run (not buffered to the end), so a long benchmark interrupted partway through still leaves every completed result safely on disk. Also provides `openAppend()` for batched/parallel runs that accumulate into one file.

```
workflow, algorithm, seed, makespan, cost, pareto_front_size, hypervolume,
avg_utilization_pct, fairness_index, speedup, search_wallclock_ms, sim_wallclock_ms
```

---

## ⚙️ VM Pool Configuration

The benchmark uses 16 heterogeneous VM instances (4 types × 4 each), spanning an 8× processing speed range and 6× cost range:

| Type   | MIPS | BW (Mbit/s) | $/s  | RAM    | Count |
|--------|-----:|------------:|-----:|-------:|------:|
| Micro  | 250  | 160         | 0.15 | 512 MB | 4     |
| Small  | 500  | 160         | 0.30 | 512 MB | 4     |
| Medium | 1000 | 160         | 0.60 | 512 MB | 4     |
| Large  | 2000 | 160         | 0.90 | 512 MB | 4     |

Scheduling uses `CloudletSchedulerSpaceShared`, no clustering, `FileSystem.LOCAL` replica catalog, 160 Mbit/s shared-fabric bandwidth.

---

## 🚀 Quick Start

**1. Clone and set up WorkflowSim 1.1.0 / CloudSim 3.0** as usual (already bundled in `sources/org/cloudbus`, no separate install needed).

**2. Compile:**
```bash
find sources examples -name "*.java" > sources.txt
javac -nowarn -cp "lib/*" -d bin @sources.txt
```

**3. Run the full benchmark** (20 workflows, 6 algorithms, 5 seeds, CSV output):
```bash
java -cp "bin:lib/*" org.workflowsim.examples.planning.LIWSABenchmarkExample
# Results written to: Output&Results/benchmark_results_nsga2.csv (~17 min single-threaded)
```
When the run finishes, the program prints a **summary of all algorithms** (mean makespan, cost, front size,
hypervolume, utilisation, fairness, speedup and search time per algorithm; a hypervolume score where 100 = best
algorithm on each workflow, wins and average rank; hypervolume per workflow; LIWSA-ML relative to every other
algorithm) and saves it as `Output&Results/benchmark_results_nsga2_summary.txt` and `..._summary.csv`.
It also prints where the files are and the Python commands below.

**3b. Or run it in batches** (same output, useful for splitting across sessions or machines — each batch computes its own workflows' hypervolume reference point independently, so results are identical to a single full run):
```bash
java -cp "bin:lib/*" org.workflowsim.examples.planning.LIWSABenchmarkExample \
  "Montage_25,Montage_50,Montage_100" "Output&Results/my_run.csv"
```

**3c. Or run several batches in parallel** (process-level parallelism — see `run_parallel.sh` and the note below on why this is process-level, not thread-level):
```bash
./run_parallel.sh full       # full benchmark, 5 batches
./run_parallel.sh ablation   # density ablation, 5 batches
./run_parallel.sh lambda     # lambda sensitivity sweep
./run_parallel.sh theta      # theta sensitivity sweep
./run_parallel.sh naive      # OLS-vs-naive-features ablation
```

**4. Run the density ablation** (`LIWSA` vs `LIWSA-NoDensity`, all 20 instances):
```bash
java -cp "bin:lib/*" org.workflowsim.examples.planning.LIWSABenchmarkExample \
  "" "Output&Results/ablation_results.csv" "ablation"
```

**5. Run a sensitivity sweep or the naive-features ablation** (5 representative workflows, one per family, by default):
```bash
java -cp "bin:lib/*" org.workflowsim.examples.planning.SensitivityAblationExample lambda
java -cp "bin:lib/*" org.workflowsim.examples.planning.SensitivityAblationExample theta
java -cp "bin:lib/*" org.workflowsim.examples.planning.SensitivityAblationExample naive
```

**6. See the summary and the figures** (Python 3 with `pip install pandas matplotlib numpy`; run from the repository root, keep the quotes):
```bash
python generate_figures.py                                          # default file: benchmark_results_nsga2.csv
python generate_figures.py "Output&Results/benchmark_results_nsga2.csv"
python generate_figures.py --all                                    # every known results file in Output&Results
python generate_figures.py lambda_results.csv --sweep "LIWSA_L"
python generate_figures.py naive_results.csv --pair "LIWSA-ML,LIWSA-ML-Naive"
python summarize_results.py "Output&Results/benchmark_results_nsga2.csv"   # summary table only
```
`generate_figures.py` prints the same all-algorithm summary and saves the figures as PDFs in `Output&Results/figures/`
(hypervolume, makespan and cost per workflow and algorithm; sweeps; ablation pairs). Use `python` or `python3`,
whichever your system has. Hypervolume is plotted on a **log-scaled** y-axis by default — do not change this back to linear without splitting small- and large-scale instances into separate panels, or small-scale bars will visually disappear next to 1000-task instances (this happened once already; see the paper's Fig. 2 for the fix).

**7. Run the HEFT baseline standalone:**
```bash
java -cp "bin:lib/*" org.workflowsim.examples.planning.HEFTBenchmark
```

### On parallelism and reproducibility

`run_parallel.sh` runs independent batches as **separate JVM processes**, not threads within one JVM. This is a hard requirement, not a stylistic choice: CloudSim keeps its entity list and simulation clock in `private static` fields (global, JVM-wide state), so two `CloudSim.init()`/`startSimulation()` cycles running concurrently *on threads in the same JVM* would silently corrupt each other's state. Separate processes each get their own JVM and their own copy of that static state, so this is safe. Every individual (workflow, algorithm, seed) run's own output is unaffected by parallelism either way — its Random instance and CloudSim lifecycle are already fully self-contained — so running in parallel changes wall-clock time only, never any reported result.

---

## 📐 Algorithm Parameters

| Parameter | Value | Scope |
|-----------|------:|-------|
| Population size `P` | 30 | MLEAO, LIWSA, NSGA-II, LIWSA-ML |
| Generations `T_max` | 100 | MLEAO, LIWSA, NSGA-II, LIWSA-ML |
| Random seeds | 5 (1–5) | MLEAO, LIWSA, NSGA-II, LIWSA-ML |
| Neighbourhood radius `τ` | self-calibrated | LIWSA, LIWSA-ML |
| Phase-mixing weight `λ` | 0.5 (swept 0.1–0.9, see above) | LIWSA, LIWSA-ML |
| Kernel parameters `F, L` | 3.0, 0.3 | LIWSA, LIWSA-ML |
| Solitary resampling prob. `η` | 0.5 | LIWSA, LIWSA-ML |
| Copy scale `α` | 1.2 | LIWSA, LIWSA-ML |
| Min elite `δ_min` | 3 | LIWSA, LIWSA-ML |
| Mutation rate `µ` | 0.02 | MLEAO, LIWSA, LIWSA-ML |
| NSGA-II crossover prob. `p_c` | 0.9 (uniform) | NSGA-II only |
| NSGA-II mutation prob. `p_m` | 1/n (random-reset) | NSGA-II only |
| OLS training samples `Nₛ` | 400 | LIWSA-ML only |
| OLS seed genotypes `Nₚ` | 4 | LIWSA-ML only |
| Softmax temperature `θ` | 0.5 (swept 0.1–0.9, see above) | LIWSA-ML only |
| `CONFIG_DENSITY_ABLATION` | `false` (set `true` to ablate) | LIWSA only |
| `CONFIG_NAIVE_FEATURES` | `false` (set `true` to ablate) | LIWSA-ML only |

None of these were tuned via a held-out validation set distinct from the 20 evaluation instances; see the paper's Methodology section for the honest account of this limitation and the sensitivity/ablation results above as a partial check.

---

## 📚 Workflow Benchmark Suite

20 instances from the [Pegasus Workflow Gallery](https://pegasus.isi.edu/), across 5 scientific families at 4 scale points each (24–1000 tasks):

| Family | Scale Points | Type | Characteristic |
|--------|-------------|------|----------------|
| Montage | 25, 50, 100, 1000 | Compute-bound | Wide, flat DAG; astronomical image mosaic |
| CyberShake | 30, 50, 100, 1000 | Compute-bound | Wide, flat DAG; seismic hazard simulation |
| Sipht | 30, 60, 100, 1000 | Chain-heavy | Deep sequential chains; critical-path sensitive |
| Epigenomics | 24, 46, 100, 997 | Data-intensive | Inter-task transfers up to 5.3 GB |
| Inspiral | 30, 50, 100, 1000 | Data-intensive | Gravitational wave detection; multi-GB file transfers |

The sensitivity sweep and OLS-vs-naive ablation (above) use the 100-task scale point from each family as a representative, structurally-diverse subset, to keep sweep cost manageable while still testing across five different DAG topologies.

---

## 📄 Paper

> **Density-Adaptive Locust Swarm Optimisation with Simulation-Trained OLS Warm-Start for Pareto-Based Cloud Workflow Scheduling**  
> Dr. Mohammed Alaa Ala'anzy  
> *IEEE Transactions on Cloud Computing* (submitted)

Full numerical results for all 20 workflow instances, plus the ablation and sensitivity sweep CSVs, are available in the [`Output&Results/`](https://github.com/Al3nzy/WorkflowSim_LocustModeling/tree/master/Output%26Results) directory.

---

## 📜 License

Copyright 2025–2026 SDU University, Kazakhstan.  
Licensed under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0).

---

<p align="center">
  Built on <a href="https://github.com/WorkflowSim/WorkflowSim-1.0">WorkflowSim 1.1.0</a> and <a href="https://github.com/Cloudslab/cloudsim">CloudSim 3.0</a>.<br>
  Benchmark traces from the <a href="https://pegasus.isi.edu/workflow_gallery/">Pegasus Workflow Management System Gallery</a>.
</p>
