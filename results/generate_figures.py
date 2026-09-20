#!/usr/bin/env python3
"""

USE this code to run all the figures generation for the results CSVs produced by the WorkflowSim_LocustModeling benchmark.
# Define the correct Python path executable shortcut
$py = "C:\Users\User\AppData\Local\Python\pythoncore-3.14-64\python.exe"

# 1. Standard Benchmark Figures (Generates 9 figures total)
& $py results/generate_figures.py results/benchmark_results.csv
& $py results/generate_figures.py results/benchmark_results_nsga2.csv
& $py results/generate_figures.py results/benchmark_results_5algo_legacy.csv

# 2. Parameter Sensitivity Sweeps (Generates 2 figures total)
& $py results/generate_figures.py results/lambda_results.csv --sweep LIWSA_L
& $py results/generate_figures.py results/theta_results.csv --sweep LIWSAML_T

# 3. Ablation and Naive Pairs (Generates 2 figures total)
& $py results/generate_figures.py results/naive_results.csv --pair LIWSA-ML,LIWSA-ML-Naive
& $py results/generate_figures.py results/ablation_results.csv --pair LIWSA,LIWSA-NoDensity



generate_figures.py -- general-purpose figure generator for
WorkflowSim_LocustModeling results CSVs.

Reads any CSV produced by ResultsCsvWriter (the standard benchmark
schema: workflow,algorithm,seed,makespan,cost,pareto_front_size,
hypervolume,avg_utilization_pct,fairness_index,speedup,
search_wallclock_ms,sim_wallclock_ms) and generates a standard set of
comparison figures, saved as PDFs next to the input CSV under a
'figures/' subdirectory.

Usage:
    python3 generate_figures.py results/benchmark_results.csv
    python3 generate_figures.py results/lambda_results.csv --sweep lambda
    python3 generate_figures.py results/naive_results.csv --pair LIWSA-ML,LIWSA-ML-Naive

Modes:
  (default)      Bar chart of mean hypervolume per workflow per algorithm,
                 log-scaled y-axis (safe for scales spanning orders of
                 magnitude, e.g. 25-task to 1000-task instances in one
                 figure -- see note below).
  --sweep NAME   For sensitivity-sweep CSVs where the algorithm column
                 encodes a swept parameter value (e.g. "LIWSA_L0.30"):
                 line plot of mean hypervolume vs. the swept value, one
                 line per workflow.
  --pair A,B     For two-way ablation CSVs (e.g. LIWSA-ML vs
                 LIWSA-ML-Naive): grouped bar chart of hypervolume and
                 makespan side by side, one panel per workflow.

Note on scale: a linear y-axis will make small-scale instances look like
zero next to 1000-task instances in the same panel. This script defaults
to a log-scaled y-axis for hypervolume (always positive, spans orders of
magnitude) and to per-workflow panels for makespan/cost (which can be
negative, so log scale does not apply) -- do not change the hypervolume
axis back to linear without splitting small/large scales into separate
panels, or the small-scale bars will disappear again.
"""
import argparse
import os
import sys

import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

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


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('csv_path', help='Path to a results CSV (ResultsCsvWriter schema)')
    parser.add_argument('--sweep', metavar='PREFIX', default=None,
                         help='Sweep mode: algorithm-name prefix before the swept numeric value, e.g. "LIWSA_L"')
    parser.add_argument('--pair', metavar='A,B', default=None,
                         help='Pair mode: two algorithm names to compare directly, e.g. "LIWSA-ML,LIWSA-ML-Naive"')
    parser.add_argument('--outdir', default=None, help='Output directory (default: <csv_dir>/figures)')
    args = parser.parse_args()

    df = pd.read_csv(args.csv_path)
    basename = os.path.splitext(os.path.basename(args.csv_path))[0]
    outdir = args.outdir or os.path.join(os.path.dirname(args.csv_path) or '.', 'figures')
    os.makedirs(outdir, exist_ok=True)

    if args.sweep:
        sweep_mode(df, outdir, basename, args.sweep)
    elif args.pair:
        a, b = args.pair.split(',')
        pair_mode(df, outdir, basename, a.strip(), b.strip())
    else:
        default_mode(df, outdir, basename)


if __name__ == '__main__':
    main()
