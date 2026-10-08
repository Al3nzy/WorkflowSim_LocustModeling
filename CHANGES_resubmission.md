# Changes for the resubmission (this release)

Result-affecting changes
- `LIWSABenchmarkExample.java`: HEFT and Min-Min are now scored for the hypervolume with the same planning-level
  decoder as the four population-based algorithms (default; `-Dliwsa.planningLevelBaselines=false` restores the
  simulator-measured point). Applies to the main benchmark and to the density ablation.
- `SensitivityAblationExample.java`: same common evaluator for the OLS-vs-naive, lambda and theta runs.
- Population-algorithm results (makespan, cost, front size) are bit-identical to the previous release; only
  hypervolume values change (HEFT/Min-Min points, and the shared reference point).

New / extended tools
- `EqualTimeBenchmark.java`: matched wall-clock comparison of LIWSA-ML and NSGA-II (warm-up run, per-workflow
  calibration of NSGA-II's generation count to within 3% of LIWSA-ML's total search time).
- `RunPaperExperiments.java`: now also runs the component variants A and B (`paper_variant_A/B.csv`) and the
  matched wall-clock experiment (`paper_equal_time.csv`).
- `paper_statistics.py` (new): recomputes every hypervolume statistic quoted in the paper into `paper_statistics.txt`.
- `make_paper_figures.py`: figures drawn at printed size (readable at 6-7 pt); hypervolume figure now shows
  hypervolume as a percentage of the best algorithm per workflow.
- `run` (Code Ocean entry point): compiles, runs `RunPaperExperiments`, then `paper_statistics.py`,
  `make_paper_figures.py`, `generate_figures.py --all` and `summarize_results.py`, and copies everything to /results.
- `Output&Results/`: regenerated results of the final run (stale files from earlier conventions removed).
- `README.md`: Key Results section brought in line with the manuscript.

Notes
- Absolute search times depend on the machine (the shipped results were produced on a single-core container).
- `paper_pricing.csv` was produced before the common-evaluator change and scores HEFT by its simulator point;
  only the LIWSA-ML / NSGA-II ratio in that table is used in the paper.
