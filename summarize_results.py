#!/usr/bin/env python3
"""
summarize_results.py -- prints a summary of every algorithm in a results CSV.

Same tables the Java programs print at the end of a run:
  1. every numeric column averaged per algorithm,
  2. hypervolume score (100 = best algorithm on that workflow), wins, average rank,
  3. per-workflow mean hypervolume,
  4. LIWSA-ML relative to every other algorithm (if LIWSA-ML is in the file).

Run from the repository root (quote the path: the folder name contains '&'):

    python summarize_results.py                                   # default benchmark file
    python summarize_results.py "Output&Results/benchmark_results_nsga2.csv"
    python summarize_results.py "Output&Results/simulated_front_eval.csv" --hv hv_simulated

The text is also saved next to the CSV as <name>_summary_py.txt.
"""
import argparse
import os
import sys

import pandas as pd

OUTPUT_DIR = "Output&Results"          # must match ResultsPaths.OUTPUT_DIR in the Java code
DEFAULT_CSV = os.path.join(OUTPUT_DIR, "benchmark_results_nsga2.csv")
ID_COLUMNS = {"workflow", "algorithm", "config", "run", "seed"}


def resolve(path):
    """Bare file names are looked up inside the output folder."""
    if os.path.exists(path):
        return path
    alt = os.path.join(OUTPUT_DIR, path)
    return alt if os.path.exists(alt) else path


def summarize(csv_path, hv_column=None, use_median=False):
    df = pd.read_csv(csv_path)
    alg_col = next((c for c in ("algorithm", "config", "run") if c in df.columns), None)
    if alg_col is None:
        raise SystemExit(f"{csv_path}: no 'algorithm', 'config' or 'run' column")
    wf_col = "workflow" if "workflow" in df.columns else None
    keys = [c for c in (wf_col, alg_col, "seed" if "seed" in df.columns else None) if c]
    df = df.drop_duplicates(subset=keys, keep="last")        # last row wins, as in the Java summary

    metrics = [c for c in df.columns
               if c not in ID_COLUMNS and pd.api.types.is_numeric_dtype(df[c])]
    hv_cols = [c for c in metrics if c.lower().startswith(("hv", "hypervolume"))]
    hv = hv_column if hv_column in hv_cols else (hv_cols[0] if hv_cols else None)
    algs = list(dict.fromkeys(df[alg_col]))
    workflows = list(dict.fromkeys(df[wf_col])) if wf_col else ["-"]
    agg = "median" if use_median else "mean"

    out = []
    bar = "=" * 100
    out += [bar, f"RESULTS SUMMARY  ({csv_path})",
            f"{len(workflows)} workflow(s), {len(algs)} algorithm(s), {len(df)} runs; "
            f"values are the {agg} over workflows and seeds.", bar]

    table = df.groupby(alg_col, sort=False)[metrics].agg(agg)
    table.insert(0, "runs", df.groupby(alg_col, sort=False).size())
    if hv:
        per_wf = df.groupby([wf_col, alg_col])[hv].mean().unstack() if wf_col else None
        if per_wf is not None:
            best = per_wf.max(axis=1)
            table["HVscore"] = (per_wf.div(best, axis=0) * 100).mean().reindex(table.index)
            table["wins"] = (per_wf.eq(best, axis=0)).sum().reindex(table.index)
            table["avgRank"] = per_wf.rank(axis=1, ascending=False).mean().reindex(table.index)
    show = table.copy()
    for c in show.columns:
        if c.endswith("_ms"):
            show[c.replace("_ms", "_s")] = show[c] / 1000.0
            show = show.drop(columns=c)
    out.append(show.to_string(float_format=lambda v: f"{v:.3f}" if abs(v) < 10000 else f"{v:.0f}"))
    if hv and wf_col:
        out.append(f"HVscore = {hv} as % of the best algorithm on each workflow, averaged over workflows.")

    if hv and wf_col and len(workflows) > 1:
        out += ["", f"Mean {hv} per workflow", per_wf.reindex(workflows).to_string(float_format=lambda v: f"{v:.1f}")]

    if hv and wf_col and "LIWSA-ML" in algs:
        out += ["", f"LIWSA-ML relative {hv} difference per workflow (positive = LIWSA-ML higher)",
                f"{'vs':<18}{'mean %':>10}{'median %':>10}{'workflows won':>16}"]
        for b in algs:
            if b == "LIWSA-ML":
                continue
            rel = ((per_wf["LIWSA-ML"] / per_wf[b] - 1) * 100).dropna()
            if len(rel):
                out.append(f"{b:<18}{rel.mean():>+10.2f}{rel.median():>+10.2f}{int((rel > 0).sum()):>10d} / {len(rel)}")
    out.append(bar)
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("csv_path", nargs="?", default=DEFAULT_CSV)
    ap.add_argument("--hv", default=None, help="hypervolume column that drives the score (default: the first)")
    ap.add_argument("--median", action="store_true", help="use the median instead of the mean")
    args = ap.parse_args()
    path = resolve(args.csv_path)
    if not os.path.exists(path):
        sys.exit(f"File not found: {path}\nRun the Java benchmark first, or pass the path of a results CSV.")
    text = summarize(path, args.hv, args.median)
    print(text)
    out = os.path.splitext(path)[0] + "_summary_py.txt"
    with open(out, "w", encoding="utf-8") as f:
        f.write(text + "\n")
    print(f"Summary saved to: {out}")


if __name__ == "__main__":
    main()
