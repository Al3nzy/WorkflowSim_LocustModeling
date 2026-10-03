#!/usr/bin/env python3
r"""
generate_figures.py -- figures (and a summary table) for the WorkflowSim_LocustModeling results.

Reads any CSV written by the Java programs (workflow,algorithm,seed,makespan,cost,
pareto_front_size,hypervolume,avg_utilization_pct,fairness_index,speedup,
search_wallclock_ms,sim_wallclock_ms), prints the all-algorithm summary and saves the
figures as PDFs in  Output&Results/figures/ .

All results live in the folder  Output&Results  (CSV files, *_summary.txt/csv, logs/,
figures/). Run everything from the repository root and QUOTE the path, because the
folder name contains an ampersand:

    python generate_figures.py                                    # default: benchmark_results_nsga2.csv
    python generate_figures.py "Output&Results/benchmark_results_nsga2.csv"
    python generate_figures.py benchmark_results_nsga2.csv        # bare name = inside Output&Results
    python generate_figures.py --all                              # every known results file found

    # single special-purpose files:
    python generate_figures.py lambda_results.csv --sweep LIWSA_L
    python generate_figures.py theta_results.csv  --sweep LIWSAML_T
    python generate_figures.py naive_results.csv  --pair LIWSA-ML,LIWSA-ML-Naive
    python generate_figures.py ablation_results.csv --pair LIWSA,LIWSA-NoDensity

Requires:  pip install pandas matplotlib numpy

Modes:
  (default)      Bar chart of mean hypervolume per workflow per algorithm (log y-axis, because
                 25-task and 1000-task instances span orders of magnitude), plus makespan and
                 cost bar charts.
  --sweep NAME   Sensitivity sweeps where the algorithm column encodes a swept value
                 (e.g. "LIWSA_L0.30"): hypervolume vs the swept value, one line per workflow.
  --pair A,B     Two-way ablations: hypervolume and makespan side by side per workflow.
  --all          Runs the right mode for every known file that exists in Output&Results.
  --no-summary   Skip printing the summary table.

Do not switch the hypervolume axis back to linear without splitting small and large scales
into separate panels, or the small-scale bars disappear.
"""
import argparse
import os
import sys

import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

OUTPUT_DIR = "Output&Results"          # must match ResultsPaths.OUTPUT_DIR in the Java code
DEFAULT_CSV = os.path.join(OUTPUT_DIR, "benchmark_results_nsga2.csv")

# (file, mode, argument) for --all
KNOWN_FILES = [
    ("benchmark_results_nsga2.csv", None, None),
    ("benchmark_results.csv", None, None),
    ("benchmark_results_parallel.csv", None, None),
    ("benchmark_results_5algo_legacy.csv", None, None),
    ("lambda_results.csv", "sweep", "LIWSA_L"),
    ("theta_results.csv", "sweep", "LIWSAML_T"),
    ("naive_results.csv", "pair", "LIWSA-ML,LIWSA-ML-Naive"),
    ("ablation_results.csv", "pair", "LIWSA,LIWSA-NoDensity"),
]

ALGO_STYLE = {
    'HEFT':     {'color': '#c0392b', 'hatch': ''},
    'Min-Min':  {'color': '#e67e22', 'hatch': 'xx'},
    'MLEAO':    {'color': '#2980b9', 'hatch': '..'},
    'LIWSA':    {'color': '#27ae60', 'hatch': '//'},
    'NSGA-II':  {'color': '#8e44ad', 'hatch': '\\\\'},
    'LIWSA-ML': {'color': '#16a085', 'hatch': '++'},
}
FALLBACK_COLORS = ['#7f8c8d', '#34495e', '#d35400', '#8e44ad', '#16a085', '#2c3e50']


def style_for(name, idx):
    if name in ALGO_STYLE:
        return ALGO_STYLE[name]
    return {'color': FALLBACK_COLORS[idx % len(FALLBACK_COLORS)], 'hatch': ''}


def default_mode(df, outdir, basename):
    """Bar chart of mean hypervolume per workflow per algorithm, log y-axis."""
    mean_hv = df.groupby(['workflow', 'algorithm'])['hypervolume'].mean().unstack()
    workflows = sorted(mean_hv.index)
    algos = list(mean_hv.columns)

    fig, ax = plt.subplots(figsize=(max(8, 0.5 * len(workflows) * len(algos)), 5))
    x = np.arange(len(workflows))
    width = 0.8 / max(len(algos), 1)
    for i, alg in enumerate(algos):
        st = style_for(alg, i)
        vals = [mean_hv.loc[wf, alg] if wf in mean_hv.index and not pd.isna(mean_hv.loc[wf, alg]) else 0
                for wf in workflows]
        ax.bar(x + (i - len(algos) / 2) * width, vals, width, label=alg,
               color=st['color'], hatch=st['hatch'], edgecolor='black', linewidth=0.3)
    ax.set_yscale('log')
    ax.set_xticks(x)
    ax.set_xticklabels(workflows, rotation=45, ha='right', fontsize=8)
    ax.set_ylabel('Hypervolume (log scale)')
    ax.set_title(f'Hypervolume by workflow and algorithm -- {basename}')
    ax.legend(fontsize=8, ncol=min(len(algos), 6))
    plt.tight_layout()
    out = os.path.join(outdir, f'{basename}_hypervolume.pdf')
    plt.savefig(out, bbox_inches='tight')
    print(f"Saved {out}")

    # Companion: makespan and cost, per-workflow panels (can't log-scale
    # safely if any algorithm's mean is used as a 0% baseline elsewhere,
    # so these are plotted as-is per workflow rather than vs a baseline).
    for metric, label in [('makespan', 'Makespan (s)'), ('cost', 'Cost ($)')]:
        mean_m = df.groupby(['workflow', 'algorithm'])[metric].mean().unstack()
        fig, ax = plt.subplots(figsize=(max(8, 0.5 * len(workflows) * len(algos)), 5))
        for i, alg in enumerate(algos):
            st = style_for(alg, i)
            vals = [mean_m.loc[wf, alg] if wf in mean_m.index and not pd.isna(mean_m.loc[wf, alg]) else 0
                    for wf in workflows]
            ax.bar(x + (i - len(algos) / 2) * width, vals, width, label=alg,
                   color=st['color'], hatch=st['hatch'], edgecolor='black', linewidth=0.3)
        ax.set_xticks(x)
        ax.set_xticklabels(workflows, rotation=45, ha='right', fontsize=8)
        ax.set_ylabel(label)
        ax.set_title(f'{label} by workflow and algorithm -- {basename}')
        ax.legend(fontsize=8, ncol=min(len(algos), 6))
        plt.tight_layout()
        out = os.path.join(outdir, f'{basename}_{metric}.pdf')
        plt.savefig(out, bbox_inches='tight')
        print(f"Saved {out}")


def sweep_mode(df, outdir, basename, param_prefix):
    """Line plot of mean hypervolume vs swept parameter value, one line per workflow."""
    rows = df[df['algorithm'].str.contains(param_prefix, regex=False)].copy()
    if rows.empty:
        print(f"No rows found with algorithm names containing '{param_prefix}'", file=sys.stderr)
        return
    # Extract the numeric value after the prefix, e.g. "LIWSA_L0.30" -> 0.30
    rows['param_value'] = rows['algorithm'].str.extract(r'([0-9]*\.?[0-9]+)$').astype(float)

    piv = rows.groupby(['workflow', 'param_value'])['hypervolume'].mean().unstack()
    fig, ax = plt.subplots(figsize=(7, 5))
    for i, wf in enumerate(piv.index):
        # normalize each workflow's own curve to its mean, so all workflows
        # are visible on one axis regardless of absolute hypervolume scale
        series = piv.loc[wf]
        normalized = series / series.mean() * 100
        ax.plot(series.index, normalized, marker='o', label=wf,
                color=FALLBACK_COLORS[i % len(FALLBACK_COLORS)])
    ax.axhline(100, color='gray', linewidth=0.8, linestyle='--')
    ax.set_xlabel('Parameter value')
    ax.set_ylabel('Hypervolume (% of that workflow\'s own mean)')
    ax.set_title(f'Sensitivity sweep: {param_prefix} -- {basename}')
    ax.legend(fontsize=8)
    plt.tight_layout()
    out = os.path.join(outdir, f'{basename}_sweep_{param_prefix.strip("_")}.pdf')
    plt.savefig(out, bbox_inches='tight')
    print(f"Saved {out}")


def pair_mode(df, outdir, basename, algo_a, algo_b):
    """Grouped bar chart comparing two algorithm variants directly."""
    sub = df[df['algorithm'].isin([algo_a, algo_b])]
    mean_hv = sub.groupby(['workflow', 'algorithm'])['hypervolume'].mean().unstack()
    workflows = sorted(mean_hv.index)

    fig, axes = plt.subplots(1, 2, figsize=(11, 4.5))
    x = np.arange(len(workflows))
    width = 0.35
    for i, alg in enumerate([algo_a, algo_b]):
        st = style_for(alg, i)
        vals = [mean_hv.loc[wf, alg] for wf in workflows]
        axes[0].bar(x + (i - 0.5) * width, vals, width, label=alg,
                    color=st['color'], hatch=st['hatch'], edgecolor='black', linewidth=0.3)
    axes[0].set_yscale('log')
    axes[0].set_xticks(x)
    axes[0].set_xticklabels(workflows, rotation=45, ha='right', fontsize=8)
    axes[0].set_ylabel('Hypervolume (log scale)')
    axes[0].set_title('Hypervolume')
    axes[0].legend(fontsize=8)

    mean_mk = sub.groupby(['workflow', 'algorithm'])['makespan'].mean().unstack()
    for i, alg in enumerate([algo_a, algo_b]):
        st = style_for(alg, i)
        vals = [mean_mk.loc[wf, alg] for wf in workflows]
        axes[1].bar(x + (i - 0.5) * width, vals, width, label=alg,
                    color=st['color'], hatch=st['hatch'], edgecolor='black', linewidth=0.3)
    axes[1].set_xticks(x)
    axes[1].set_xticklabels(workflows, rotation=45, ha='right', fontsize=8)
    axes[1].set_ylabel('Makespan (s)')
    axes[1].set_title('Makespan')
    axes[1].legend(fontsize=8)

    plt.suptitle(f'{algo_a} vs {algo_b} -- {basename}')
    plt.tight_layout()
    out = os.path.join(outdir, f'{basename}_{algo_a}_vs_{algo_b}.pdf'.replace(' ', '_'))
    plt.savefig(out, bbox_inches='tight')
    print(f"Saved {out}")


def resolve(path):
    """Bare file names are looked up inside the output folder."""
    if os.path.exists(path):
        return path
    alt = os.path.join(OUTPUT_DIR, path)
    return alt if os.path.exists(alt) else path


def run_one(csv_path, sweep=None, pair=None, outdir=None, show_summary=True):
    csv_path = resolve(csv_path)
    if not os.path.exists(csv_path):
        print(f"File not found: {csv_path}\nRun the Java benchmark first (it writes into {OUTPUT_DIR}).", file=sys.stderr)
        return False
    df = pd.read_csv(csv_path)
    basename = os.path.splitext(os.path.basename(csv_path))[0]
    outdir = outdir or os.path.join(OUTPUT_DIR, 'figures')
    os.makedirs(outdir, exist_ok=True)

    if show_summary and not sweep:
        try:
            from summarize_results import summarize
            print(summarize(csv_path))
        except Exception as exc:                       # the figures matter more than the table
            print(f"(summary skipped: {exc})", file=sys.stderr)

    if sweep:
        sweep_mode(df, outdir, basename, sweep)
    elif pair:
        a, b = pair.split(',')
        pair_mode(df, outdir, basename, a.strip(), b.strip())
    else:
        default_mode(df, outdir, basename)
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('csv_path', nargs='?', default=DEFAULT_CSV,
                        help=f'Results CSV (default: {DEFAULT_CSV}); a bare file name is looked up in {OUTPUT_DIR}')
    parser.add_argument('--sweep', metavar='PREFIX', default=None,
                         help='Sweep mode: algorithm-name prefix before the swept numeric value, e.g. "LIWSA_L"')
    parser.add_argument('--pair', metavar='A,B', default=None,
                         help='Pair mode: two algorithm names to compare directly, e.g. "LIWSA-ML,LIWSA-ML-Naive"')
    parser.add_argument('--all', action='store_true', help=f'Process every known results file found in {OUTPUT_DIR}')
    parser.add_argument('--no-summary', action='store_true', help='Do not print the summary table')
    parser.add_argument('--outdir', default=None, help=f'Figure directory (default: {OUTPUT_DIR}/figures)')
    args = parser.parse_args()

    if args.all:
        done = 0
        for name, mode, arg in KNOWN_FILES:
            path = os.path.join(OUTPUT_DIR, name)
            if os.path.exists(path):
                print(f"\n##### {path}")
                done += run_one(path, sweep=arg if mode == 'sweep' else None,
                                pair=arg if mode == 'pair' else None,
                                outdir=args.outdir, show_summary=not args.no_summary)
        print(f"\nDone: {done} results file(s) processed. Figures are in "
              f"{args.outdir or os.path.join(OUTPUT_DIR, 'figures')}")
        return

    if not run_one(args.csv_path, args.sweep, args.pair, args.outdir, not args.no_summary):
        sys.exit(1)
    print(f"\nFigures are in {args.outdir or os.path.join(OUTPUT_DIR, 'figures')}")


if __name__ == '__main__':
    main()
