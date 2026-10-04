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
import re
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
    ("paper_main.csv", None, None),
    ("paper_density_ablation.csv", "pair", "LIWSA,LIWSA-NoDensity"),
    ("paper_naive.csv", "pair", "LIWSA-ML,LIWSA-ML-Naive"),
    ("paper_lambda.csv", "sweep", "LIWSA_L"),
    ("paper_theta.csv", "sweep", "LIWSAML_T"),
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


FAMILY_ORDER = ['Montage', 'CyberShake', 'Sipht', 'Epigenomics', 'Inspiral']
LARGE_FROM = 900          # 997/1000-task instances are plotted separately from the 24-100-task ones


def _split_name(wf):
    """'Montage_25' -> ('Montage', 25); names that do not fit the pattern -> (name, 0)."""
    m = re.match(r'^(.*?)_(\d+)$', wf)
    return (m.group(1), int(m.group(2))) if m else (wf, 0)


def _groups(workflows):
    """Returns {'small': [...], 'large': [...]} with workflows ordered by family then size."""
    def key(wf):
        fam, n = _split_name(wf)
        return (FAMILY_ORDER.index(fam) if fam in FAMILY_ORDER else len(FAMILY_ORDER), fam, n)
    ordered = sorted(workflows, key=key)
    return {'small': [w for w in ordered if _split_name(w)[1] < LARGE_FROM],
            'large': [w for w in ordered if _split_name(w)[1] >= LARGE_FROM]}


def _save(fig, outdir, name):
    for ext in ('pdf', 'png'):
        out = os.path.join(outdir, f'{name}.{ext}')
        fig.savefig(out, bbox_inches='tight', dpi=150)
    print(f"Saved {os.path.join(outdir, name)}.pdf / .png")
    plt.close(fig)


def _panel_figure(df, metric, label, workflows, algos, title, outdir, name):
    """One panel per workflow family; bars grouped by instance size; each panel has its own linear y-axis."""
    mean = df.groupby(['workflow', 'algorithm'])[metric].mean().unstack()
    std = df.groupby(['workflow', 'algorithm'])[metric].std().unstack().fillna(0)
    fams = [f for f in FAMILY_ORDER if any(_split_name(w)[0] == f for w in workflows)]
    fams += sorted({_split_name(w)[0] for w in workflows} - set(fams))
    fig, axes = plt.subplots(1, len(fams), figsize=(3.4 * len(fams) + 1.2, 3.8), squeeze=False)
    width = 0.8 / max(len(algos), 1)
    for ax, fam in zip(axes[0], fams):
        wfs = [w for w in workflows if _split_name(w)[0] == fam]
        x = np.arange(len(wfs))
        for i, alg in enumerate(algos):
            st = style_for(alg, i)
            vals = [mean.loc[w, alg] if alg in mean.columns and not pd.isna(mean.loc[w, alg]) else 0 for w in wfs]
            errs = [std.loc[w, alg] if alg in std.columns else 0 for w in wfs]
            ax.bar(x + (i - (len(algos) - 1) / 2) * width, vals, width, yerr=errs, capsize=1.5,
                   error_kw={'elinewidth': 0.6}, label=alg, color=st['color'], hatch=st['hatch'],
                   edgecolor='black', linewidth=0.3)
        ax.set_xticks(x)
        ax.set_xticklabels([str(_split_name(w)[1]) + ' tasks' for w in wfs], fontsize=8)
        ax.set_title(fam, fontsize=10)
        ax.ticklabel_format(axis='y', style='sci', scilimits=(-2, 4))
        ax.tick_params(axis='y', labelsize=8)
        ax.grid(axis='y', linewidth=0.3, alpha=0.5)
    axes[0][0].set_ylabel(label)
    handles, labels = axes[0][0].get_legend_handles_labels()
    fig.legend(handles, labels, loc='upper center', ncol=len(algos), fontsize=8, bbox_to_anchor=(0.5, 1.02))
    fig.suptitle(title, y=1.09, fontsize=11)
    fig.tight_layout()
    _save(fig, outdir, name)


def _relative_heatmap(df, workflows, algos, title, outdir, name):
    """Hypervolume as a percentage of the best algorithm on each workflow (100 = best)."""
    mean = df.groupby(['workflow', 'algorithm'])['hypervolume'].mean().unstack()
    rel = mean.loc[workflows, algos].div(mean.loc[workflows, algos].max(axis=1), axis=0) * 100
    fig, ax = plt.subplots(figsize=(1.0 * len(algos) + 3, 0.34 * len(workflows) + 1.6))
    im = ax.imshow(rel.values, cmap='YlGn', vmin=40, vmax=100, aspect='auto')
    ax.set_xticks(range(len(algos)))
    ax.set_xticklabels(algos, rotation=30, ha='right', fontsize=9)
    ax.set_yticks(range(len(workflows)))
    ax.set_yticklabels(workflows, fontsize=9)
    for r in range(rel.shape[0]):
        best = rel.iloc[r].max()
        for c in range(rel.shape[1]):
            v = rel.iloc[r, c]
            ax.text(c, r, f'{v:.1f}', ha='center', va='center', fontsize=8,
                    fontweight='bold' if v == best else 'normal')
    ax.set_title(title, fontsize=10)
    fig.colorbar(im, ax=ax, fraction=0.04, pad=0.02, label='% of best algorithm')
    fig.tight_layout()
    _save(fig, outdir, name)


def default_mode(df, outdir, basename):
    """Hypervolume, makespan and cost per workflow family, with the 24-100-task instances and the
    997/1000-task instances in separate figures, plus a 'percent of best' heatmap for each group."""
    algos = [a for a in ['HEFT', 'Min-Min', 'MLEAO', 'LIWSA', 'LIWSA-ML', 'NSGA-II'] if a in set(df['algorithm'])]
    algos += [a for a in dict.fromkeys(df['algorithm']) if a not in algos]
    groups = _groups(list(dict.fromkeys(df['workflow'])))
    names = {'small': 'small and medium instances (24-100 tasks)', 'large': 'large instances (997-1000 tasks)'}
    for g, wfs in groups.items():
        if not wfs:
            continue
        for metric, label in [('hypervolume', 'Hypervolume (higher is better)'),
                              ('makespan', 'Makespan [s] (lower is better)'),
                              ('cost', 'Cost (lower is better)')]:
            _panel_figure(df, metric, label, wfs, algos, f'{label.split(" (")[0]} -- {names[g]} -- {basename}',
                          outdir, f'{basename}_{metric}_{g}')
        _relative_heatmap(df, wfs, algos, f'Hypervolume, % of the best algorithm -- {names[g]}',
                          outdir, f'{basename}_hypervolume_relative_{g}')


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
