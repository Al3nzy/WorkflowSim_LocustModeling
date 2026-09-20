#!/usr/bin/env bash
#
# run_parallel.sh -- runs several independent WorkflowSim benchmark
# batches concurrently, each in its own JVM process.
#
# WHY PROCESS-LEVEL, NOT THREAD-LEVEL PARALLELISM:
# CloudSim (sources/org/cloudbus/cloudsim/core/CloudSim.java) keeps its
# entity list and simulation clock in `private static` fields -- global,
# JVM-wide state, not per-simulation state. Running two
# CloudSim.init()/startSimulation() cycles concurrently on different
# threads *within the same JVM* would have both threads reading and
# writing the same static fields, silently corrupting both runs. This
# is a real constraint of the underlying CloudSim/WorkflowSim libraries,
# not a limitation specific to this codebase, and it rules out an
# in-process thread pool (java.util.concurrent.ForkJoinPool,
# IntStream.parallel(), etc.) for anything that calls runPlanning().
#
# Each workflow-to-workflow run is already fully independent (its own
# Random instance, its own CloudSim.init() call), so running several as
# SEPARATE OS PROCESSES is safe: each process gets its own JVM and its
# own copy of every static field. This script does exactly that, using
# one process per workflow-family batch, which is how the full 20-workflow
# sweep was actually run during development (see README.md).
#
# USAGE:
#   ./run_parallel.sh                 # full 20-workflow benchmark, 5 batches in parallel
#   ./run_parallel.sh ablation        # density ablation, all 20 workflows, 5 batches
#   ./run_parallel.sh lambda          # lambda sensitivity sweep
#   ./run_parallel.sh theta           # theta sensitivity sweep
#   ./run_parallel.sh naive           # OLS-vs-naive-features ablation
#
# Requires: `javac`/`java` on PATH, compiled classes in bin/ (run
# build.sh or the javac command in README.md first).
#
# Each batch's own stdout/stderr goes to results/logs/<batch>.log; the
# script waits for all batches to finish before exiting. On a machine
# with fewer cores than batches, the OS scheduler will time-slice them;
# this is still correct, just not faster than running sequentially.

set -euo pipefail
cd "$(dirname "$0")/.."   # repo root, assuming this script lives in results/

MODE="${1:-full}"
mkdir -p results/logs
CP="bin:lib/*"
MAIN=org.workflowsim.examples.planning.LIWSABenchmarkExample
SENS=org.workflowsim.examples.planning.SensitivityAblationExample

run_batch() {
  local label="$1"; shift
  echo "Starting batch: $label"
  java -cp "$CP" "$@" > "results/logs/${label}.log" 2>&1 &
}

case "$MODE" in
  full)
    rm -f results/benchmark_results_parallel.csv
    run_batch montage   "$MAIN" "Montage_25,Montage_50,Montage_100,Montage_1000"       "results/benchmark_results_parallel.csv"
    run_batch cybershake "$MAIN" "CyberShake_30,CyberShake_50,CyberShake_100,CyberShake_1000" "results/benchmark_results_parallel.csv"
    run_batch sipht      "$MAIN" "Sipht_30,Sipht_60,Sipht_100,Sipht_1000"              "results/benchmark_results_parallel.csv"
    run_batch epigenomics "$MAIN" "Epigenomics_24,Epigenomics_46,Epigenomics_100,Epigenomics_997" "results/benchmark_results_parallel.csv"
    run_batch inspiral   "$MAIN" "Inspiral_30,Inspiral_50,Inspiral_100,Inspiral_1000"  "results/benchmark_results_parallel.csv"
    ;;
  ablation)
    rm -f results/ablation_results_parallel.csv
    run_batch abl_montage    "$MAIN" "Montage_25,Montage_50,Montage_100,Montage_1000"       "results/ablation_results_parallel.csv" "ablation"
    run_batch abl_cybershake "$MAIN" "CyberShake_30,CyberShake_50,CyberShake_100,CyberShake_1000" "results/ablation_results_parallel.csv" "ablation"
    run_batch abl_sipht      "$MAIN" "Sipht_30,Sipht_60,Sipht_100,Sipht_1000"              "results/ablation_results_parallel.csv" "ablation"
    run_batch abl_epigenomics "$MAIN" "Epigenomics_24,Epigenomics_46,Epigenomics_100,Epigenomics_997" "results/ablation_results_parallel.csv" "ablation"
    run_batch abl_inspiral   "$MAIN" "Inspiral_30,Inspiral_50,Inspiral_100,Inspiral_1000"  "results/ablation_results_parallel.csv" "ablation"
    ;;
  lambda|theta|naive)
    # These are already fast enough single-process (well under a minute
    # for all 5 representative workflows), but can be split one-workflow-
    # per-process too if a wider workflow set is used:
    rm -f "results/${MODE}_results_parallel.csv"
    for wf in Montage_100 CyberShake_100 Sipht_100 Epigenomics_100 Inspiral_100; do
      run_batch "${MODE}_${wf}" "$SENS" "$MODE" "$wf" "results/${MODE}_results_parallel.csv"
    done
    ;;
  *)
    echo "Unknown mode: $MODE (expected: full, ablation, lambda, theta, naive)" >&2
    exit 1
    ;;
esac

wait
echo "All batches complete. Logs in results/logs/, combined CSV in results/."
