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
  <b>Density-Adaptive Locust Swarm Optimisation with Self-Supervised OLS Warm-Start<br>for Pareto-Optimal Cloud Workflow Scheduling</b><br>
  <i>Dr. Mohammed Alaa Ala'anzy — SDU University, Kazakhstan</i>
</p>

---

> *Desert locusts don't follow a timer. They respond to crowding. So does LIWSA.*

When individual locusts sense neighbours around them, they shift from solitary foraging to collective swarming — not because a clock told them to, but because of local density. **LIWSA** brings this exact mechanism into cloud workflow scheduling: each candidate schedule measures its own neighbourhood crowding at every generation and decides its own phase probability. No weight. No global clock. No scalar aggregation of makespan vs cost.

The result: a **true Pareto front** of scheduling options — not one solution, but a menu of makespan-vs-cost trade-offs — produced entirely inside WorkflowSim with zero external dependencies.

> **Update (this revision):** added a standard NSGA-II baseline and a controlled ablation of the density-adaptive mixing mechanism, in response to peer review. Reported honestly: NSGA-II matches LIWSA-ML's Pareto-front quality at equal search budget and does so 1.4×–13.5× faster; the ablation finds LIWSA's richer fronts (vs. MLEAO) come from having *some* probabilistic phase mixing, not specifically from that mixing being density-driven. See `Response_to_Reviewers.pdf` in the paper repo for the full account, and the updated results table below.

---

## 📁 Repository Structure

```
WorkflowSim_LocustModeling/
├── sources/org/workflowsim/planning/
│   └── NSGAIIPlanningAlgorithm.java         ← Standard NSGA-II baseline (new)
└── examples/org/workflowsim/examples/planning/
    ├── LIWSAPlanningAlgorithmExample.java      ← LIWSA single-run example
    ├── LIWSAMLPlanningAlgorithmExample.java    ← LIWSA-ML single-run example
    ├── LIWSABenchmarkExample.java              ← Full 20-instance benchmark driver
    ├── MLEAOPlanningAlgorithmExample.java      ← MLEAO baseline example
    ├── HEFTPlanningAlgorithmExample1.java      ← HEFT baseline example
    ├── DHEFTPlanningAlgorithmExample1.java     ← DHEFT variant example
    ├── HEFTBenchmark.java                      ← HEFT benchmark with metrics
    ├── ParetoMetrics.java                      ← 2D hypervolume calculator
    ├── ResultsCsvWriter.java                   ← Shared CSV output writer
    └── RunMetricsCalculator.java               ← Shared metrics (all algorithms)
```

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

---

## 📊 Key Results (20 Pegasus Benchmark Instances, 5 Families, 5 Seeds Each)

| Algorithm | Mean Hypervolume vs HEFT | Pareto Front Size | Search Wall-Clock (1000-task, relative to MLEAO) |
|-----------|-------------------------:|:------------------:|:----------------------------------------:|
| HEFT | baseline | 1 | — (no search phase) |
| Min-Min | −3.4% avg | 1 | — (no search phase) |
| MLEAO | +159.7% avg | 1–26 (mean 6.25) | 1.0× |
| LIWSA | +174.2% avg | 1–30 (mean 13.75) | 9.1× |
| **NSGA-II** | **+181.9% avg** | **1–30 (mean 26.84)** | **0.8×** |
| **LIWSA-ML** | **+182.9% avg** | **1–30 (mean 13.25)** | **13.2×** |

LIWSA-ML beats HEFT, Min-Min, and MLEAO clearly and consistently (mean hypervolume gain +9.1% over MLEAO). Against the added NSGA-II baseline at matched search budget, encoding, decoder, and warm-start seeds, the picture is closer: NSGA-II wins mean hypervolume on 14/20 instances to LIWSA-ML's 5 (margins narrow, ~1.4% on average, not significant at n=5), while running 1.4×–13.5× faster depending on workflow size — traced to the O(P²n) cost of LIWSA's density-driven solitary-phase voting vs. NSGA-II's O(P²+Pn) operators. Full account in the paper's Results §IV-F and the Response to Reviewers.

On **data-intensive workflows** (Epigenomics, Inspiral at ~1000 tasks), LIWSA-ML simultaneously reduces makespan and cost versus HEFT (e.g. Epigenomics_997: −78.5% makespan, −10.0% cost) — true Pareto dominance, not a trade-off, and a pattern all four population-based algorithms (MLEAO, LIWSA, NSGA-II, LIWSA-ML) share to some degree since it stems from a structural HEFT weakness on large file transfers, not from any one algorithm's search strategy specifically.

---

## 🔧 Shared Infrastructure

All four algorithm drivers (`LIWSAPlanningAlgorithmExample`, `LIWSAMLPlanningAlgorithmExample`, `MLEAOPlanningAlgorithmExample`, `LIWSABenchmarkExample`) share the same supporting classes, so results are directly comparable without reconciling different column layouts or metric definitions:

**`RunMetricsCalculator`** — computes makespan, execution cost (using `CostModel.VM` per-second rates), average VM utilisation, Jain's fairness index, and scheduling speedup from the simulator's actual job results. One implementation, used by every driver.

**`ParetoMetrics`** — 2D hypervolume calculator with a shared cross-algorithm reference point. The reference point (1.2× the worst makespan and cost across *all* algorithms and seeds for a given workflow) is computed once and reused — ensuring hypervolume comparisons are meaningful and not inflated by a single algorithm's own bad points.

**`ResultsCsvWriter`** — single CSV schema, flushed to disk after every completed run (not buffered to the end), so a long benchmark interrupted partway through still leaves every completed result safely on disk.

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

**1. Clone and set up WorkflowSim 1.1.0 / CloudSim 3.0** as usual.

**2. Place the planning files** into:
```
examples/org/workflowsim/examples/planning/
```

**3. Run a single LIWSA-ML example:**
```bash
# Set daxPath in LIWSAMLPlanningAlgorithmExample.java, then:
javac -cp .:workflowsim.jar LIWSAMLPlanningAlgorithmExample.java
java  -cp .:workflowsim.jar org.workflowsim.examples.planning.LIWSAMLPlanningAlgorithmExample
```

**4. Run the full benchmark** (all workflows, six algorithms, 5 seeds, CSV output):
```bash
java -cp .:workflowsim.jar org.workflowsim.examples.planning.LIWSABenchmarkExample
# Results written to: results/benchmark_results.csv (~17 min single-threaded)
```

**4b. Or run it in batches** (useful for verification, or splitting across
sessions — each batch computes its own workflows' hypervolume reference
points independently, so results are identical to a single full run):
```bash
java -cp .:workflowsim.jar org.workflowsim.examples.planning.LIWSABenchmarkExample \
  "Montage_25,Montage_50,Montage_100" "results/my_run.csv"
```

**4c. Or run the density ablation** (`LIWSA` vs. `LIWSA-NoDensity`, all 20 instances):
```bash
java -cp .:workflowsim.jar org.workflowsim.examples.planning.LIWSABenchmarkExample \
  "" "results/my_ablation.csv" "ablation"
```

**5. Run the HEFT baseline:**
```bash
java -cp .:workflowsim.jar org.workflowsim.examples.planning.HEFTBenchmark
```

---

## 📐 Algorithm Parameters

| Parameter | Value | Scope |
|-----------|------:|-------|
| Population size `P` | 30 | MLEAO, LIWSA, NSGA-II, LIWSA-ML |
| Generations `T_max` | 100 | MLEAO, LIWSA, NSGA-II, LIWSA-ML |
| Random seeds | 5 (1–5) | MLEAO, LIWSA, NSGA-II, LIWSA-ML |
| Neighbourhood radius `τ` | self-calibrated | LIWSA, LIWSA-ML |
| Phase-mixing weight `λ` | 0.5 | LIWSA, LIWSA-ML |
| Kernel parameters `F, L` | 3.0, 0.3 | LIWSA, LIWSA-ML |
| Copy scale `α` | 1.2 | LIWSA, LIWSA-ML |
| Min elite `δ_min` | 3 | LIWSA, LIWSA-ML |
| Mutation rate `µ` | 0.02 | LIWSA, LIWSA-ML, MLEAO |
| NSGA-II crossover prob. `p_c` | 0.9 (uniform crossover) | NSGA-II only |
| NSGA-II mutation prob. `p_m` | 1/n (random-reset) | NSGA-II only |
| OLS training samples `Nₛ` | 400 | LIWSA-ML only |
| OLS seed genotypes `Nₚ` | 4 | LIWSA-ML only |
| Softmax temperature `θ` | 0.5 | LIWSA-ML only |
| `CONFIG_DENSITY_ABLATION` | `false` (set `true` to ablate) | LIWSA only, new |

None of these were tuned via a held-out validation set distinct from the
20 evaluation instances; see the paper's §III-E and Response to Reviewers
(R3 Q4) for the honest account of this limitation and a partial
overfitting cross-check against the untuned NSGA-II baseline.

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

---

## 📄 Paper

> **Density-Adaptive Locust Swarm Optimisation with Self-Supervised OLS Warm-Start for Pareto-Optimal Cloud Workflow Scheduling**  
> Dr. Mohammed Alaa Ala'anzy  
> *IEEE Transactions on Cloud Computing* (submitted)

Full numerical results for all 20 workflow instances are available in the [`results/`](https://github.com/Al3nzy/WorkflowSim_LocustModeling/tree/master/results) directory.

---

## 📜 License

Copyright 2025–2026 SDU University, Kazakhstan.  
Licensed under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0).

---

<p align="center">
  Built on <a href="https://github.com/WorkflowSim/WorkflowSim-1.0">WorkflowSim 1.1.0</a> and <a href="https://github.com/Cloudslab/cloudsim">CloudSim 3.0</a>.<br>
  Benchmark traces from the <a href="https://pegasus.isi.edu/workflow_gallery/">Pegasus Workflow Management System Gallery</a>.
</p>
