#!/usr/bin/env python3
"""
make_paper_figures.py -- regenerates the six result figures of the paper from the final results CSV.

    python make_paper_figures.py                                  # reads Output&Results/paper_main.csv
    python make_paper_figures.py "Output&Results/paper_main.csv" --outdir figs

Writes (PDF): hypervolume_families, makespan_vs_heft, cost_vs_heft, makespan_cost_scatter, speedup,
utilization_fairness. Quote the path: the folder name contains an ampersand.
Requires: pip install pandas matplotlib numpy
"""
import argparse
import os
import re

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

OUTPUT_DIR = "Output&Results"
ALGOS = ['HEFT', 'Min-Min', 'MLEAO', 'LIWSA', 'NSGA-II', 'LIWSA-ML']
STYLE = {
    'HEFT': ('#c0392b', ''), 'Min-Min': ('#e67e22', 'xx'), 'MLEAO': ('#2980b9', '..'),
    'LIWSA': ('#27ae60', '//'), 'NSGA-II': ('#8e44ad', '\\\\'), 'LIWSA-ML': ('#16a085', '++'),
}
MARK = {'HEFT': '^', 'Min-Min': 's', 'MLEAO': 'D', 'LIWSA': 'o', 'NSGA-II': 'P', 'LIWSA-ML': '*'}
FAMS = ['Montage', 'CyberShake', 'Sipht', 'Epigenomics', 'Inspiral']
plt.rcParams.update({'font.size': 6.5, 'axes.titlesize': 7, 'axes.labelsize': 6.5, 'legend.fontsize': 6.5,
                     'xtick.labelsize': 6, 'ytick.labelsize': 6, 'hatch.linewidth': 0.5})
# Figure sizes are the printed sizes (7.2 in = IEEE two-column width, 3.5 in = one column), so the text is
# about 6-7 pt in the paper and no rescaling is needed.


def size(w):
    return int(re.search(r'_(\d+)$', w).group(1))


def fam(w):
    return w.rsplit('_', 1)[0]


def wf_of(df, f, large):
    ws = sorted({w for w in df.workflow if fam(w) == f and ((size(w) >= 900) == large)}, key=size)
    return ws


def legend(fig, algos, y=-0.02):
    hs = [plt.Rectangle((0, 0), 1, 1, facecolor=STYLE[a][0], hatch=STYLE[a][1], edgecolor='black', lw=0.4) for a in algos]
    fig.legend(hs, algos, loc='lower center', ncol=len(algos), frameon=False, bbox_to_anchor=(0.5, y))


def bars(ax, x, vals_by_alg, algos, width=None):
    width = width or 0.8 / len(algos)
    for i, a in enumerate(algos):
        c, h = STYLE[a]
        ax.bar(x + (i - (len(algos) - 1) / 2) * width, vals_by_alg[a], width, color=c, hatch=h,
               edgecolor='black', linewidth=0.4)


def fig_hv(m, out):
    hv = m.groupby(['workflow', 'algorithm']).hypervolume.mean().unstack()
    hv = hv.div(hv.max(axis=1), axis=0) * 100          # percentage of the best algorithm on each workflow
    fig, axes = plt.subplots(1, 5, figsize=(7.2, 1.95), sharey=True)
    for ax, f in zip(axes, FAMS):
        ws = wf_of(m, f, False) + wf_of(m, f, True)
        x = np.arange(len(ws))
        bars(ax, x, {a: hv.loc[ws, a].values for a in ALGOS}, ALGOS)
        ax.set_ylim(0, 105)
        ax.set_xticks(x); ax.set_xticklabels([str(size(w)) for w in ws])
        ax.set_title(f); ax.set_xlabel('tasks')
        ax.grid(axis='y', lw=0.3, alpha=0.5)
    axes[0].set_ylabel('Hypervolume (% of best)')
    legend(fig, ALGOS, y=-0.04); fig.tight_layout(rect=(0, 0.07, 1, 1), w_pad=0.4)
    fig.savefig(out, bbox_inches='tight'); plt.close(fig)


def fig_vs_heft(m, metric, ylabel, out):
    mean = m.groupby(['workflow', 'algorithm'])[metric].mean().unstack()
    algos = [a for a in ALGOS if a != 'HEFT']
    fig, axes = plt.subplots(2, 5, figsize=(7.2, 2.6))
    for j, f in enumerate(FAMS):
        for i, large in enumerate([False, True]):
            ax = axes[i][j]; ws = wf_of(m, f, large); x = np.arange(len(ws))
            rel = {a: ((mean.loc[ws, a] / mean.loc[ws, 'HEFT'] - 1) * 100).values for a in algos}
            bars(ax, x, rel, algos)
            ax.axhline(0, color='black', lw=0.6)
            ax.set_xticks(x); ax.set_xticklabels([str(size(w)) for w in ws])
            if i == 0:
                ax.set_title(f)
            ax.set_xlabel('tasks'); ax.grid(axis='y', lw=0.3, alpha=0.5)
            if j == 0:
                ax.set_ylabel(f"{ylabel}\n({'~1000 tasks' if large else '24-100 tasks'})")
    legend(fig, algos, y=-0.03); fig.tight_layout(rect=(0, 0.05, 1, 1), w_pad=0.4, h_pad=0.6)
    fig.savefig(out, bbox_inches='tight'); plt.close(fig)


def fig_scatter(m, out):
    fig, axes = plt.subplots(1, 2, figsize=(3.5, 2.7))
    for ax, w, t in [(axes[0], 'Epigenomics_997', 'Epigenomics_997'),
                     (axes[1], 'CyberShake_100', 'CyberShake_100')]:
        d = m[m.workflow == w]
        for a in ALGOS:
            s = d[d.algorithm == a]
            c = STYLE[a][0]
            ax.scatter(s.makespan, s.cost, marker=MARK[a], s=16 if a != 'LIWSA-ML' else 30, color=c,
                       edgecolor='black', linewidth=0.5, label=a, zorder=3)
        ax.set_title(t); ax.set_xlabel('Makespan (s)'); ax.set_ylabel('Cost')
        ax.ticklabel_format(style='sci', scilimits=(-2, 4)); ax.grid(lw=0.3, alpha=0.5)
    h, l = axes[0].get_legend_handles_labels()
    fig.legend(h, l, loc='lower center', ncol=3, frameon=False, bbox_to_anchor=(0.5, -0.02))
    fig.tight_layout(rect=(0, 0.14, 1, 1), w_pad=0.6); fig.savefig(out, bbox_inches='tight'); plt.close(fig)


def fig_speedup(m, out):
    sp = m.groupby(['workflow', 'algorithm']).speedup.mean().unstack()
    ws = [w for f in FAMS for w in wf_of(m, f, True)]
    fig, ax = plt.subplots(figsize=(7.2, 2.5)); x = np.arange(len(ws))
    bars(ax, x, {a: sp.loc[ws, a].values for a in ALGOS}, ALGOS)
    ax.set_xticks(x); ax.set_xticklabels([f"{fam(w)}\n{size(w)}" for w in ws])
    ax.set_ylabel('Speedup vs sequential execution'); ax.set_title('Scheduling speedup on large workflow instances')
    ax.grid(axis='y', lw=0.3, alpha=0.5)
    legend(fig, ALGOS, y=-0.04); fig.tight_layout(rect=(0, 0.06, 1, 1))
    fig.savefig(out, bbox_inches='tight'); plt.close(fig)


def fig_util(m, out):
    ut = m.groupby(['workflow', 'algorithm']).avg_utilization_pct.mean().unstack()
    fa = m.groupby(['workflow', 'algorithm']).fairness_index.mean().unstack()
    ws = ['Montage_100', 'CyberShake_50', 'Sipht_100', 'Epigenomics_997', 'Inspiral_1000']
    fig, axes = plt.subplots(1, 2, figsize=(7.2, 2.5)); x = np.arange(len(ws))
    for ax, M, t, yl in [(axes[0], ut, 'VM resource utilisation', 'Avg utilisation (%)'),
                         (axes[1], fa, "Load-balancing fairness (descriptive)", "Fairness index")]:
        bars(ax, x, {a: M.loc[ws, a].values for a in ALGOS}, ALGOS)
        ax.set_xticks(x); ax.set_xticklabels([f"{fam(w)}\n{size(w)}" for w in ws])
        ax.set_title(t); ax.set_ylabel(yl); ax.grid(axis='y', lw=0.3, alpha=0.5)
    legend(fig, ALGOS, y=-0.04); fig.tight_layout(rect=(0, 0.06, 1, 1))
    fig.savefig(out, bbox_inches='tight'); plt.close(fig)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('csv', nargs='?', default=os.path.join(OUTPUT_DIR, 'paper_main.csv'))
    ap.add_argument('--outdir', default='figs')
    a = ap.parse_args()
    m = pd.read_csv(a.csv)
    os.makedirs(a.outdir, exist_ok=True)
    P = lambda n: os.path.join(a.outdir, n)
    jobs = [
        ('hypervolume_families.pdf', lambda f: fig_hv(m, f)),
        ('makespan_vs_heft.pdf', lambda f: fig_vs_heft(m, 'makespan', 'Makespan vs HEFT (%)', f)),
        ('cost_vs_heft.pdf', lambda f: fig_vs_heft(m, 'cost', 'Cost vs HEFT (%)', f)),
        ('makespan_cost_scatter.pdf', lambda f: fig_scatter(m, f)),
        ('speedup.pdf', lambda f: fig_speedup(m, f)),
        ('utilization_fairness.pdf', lambda f: fig_util(m, f)),
    ]
    for name, job in jobs:
        try:
            job(P(name))
        except (KeyError, ValueError, IndexError) as exc:       # e.g. a quick run that lacks the needed workflows
            print(f'Skipped {name}: the results file does not contain the workflows it needs ({exc.__class__.__name__})')
            plt.close('all')
    print('Figures written to', a.outdir)


if __name__ == '__main__':
    main()
