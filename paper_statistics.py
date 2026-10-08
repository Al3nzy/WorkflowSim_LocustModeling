#!/usr/bin/env python3
"""Recomputes every hypervolume statistic quoted in the paper from the CSV files in Output&Results
and writes them to paper_statistics.txt (also printed). Run after RunPaperExperiments:

    python3 paper_statistics.py [--dir "Output&Results"]

Sections: aggregate table, instance-level paired tests (Wilcoxon, Holm), scale split, component analysis
(variants A, B, C), density and OLS ablations, lambda/theta sweeps, matched wall-clock comparison.
Sections whose input file is missing (or too small, e.g. in the QUICK smoke test) are skipped."""
import argparse, os, re, sys, io
import numpy as np, pandas as pd
from scipy.stats import wilcoxon

ap = argparse.ArgumentParser(); ap.add_argument('--dir', default='Output&Results'); a = ap.parse_args()
D = a.dir; buf = io.StringIO()
def P(*x):
    print(*x); print(*x, file=buf)
def load(f):
    p = os.path.join(D, f); return pd.read_csv(p) if os.path.exists(p) else None
def inst(df, alg, col='hypervolume'):
    order = list(dict.fromkeys(df.workflow)); return df[df.algorithm == alg].groupby('workflow')[col].mean().loc[order]
def big(w): return w.endswith('1000') or w.endswith('997')
def rel(x, y): return (x - y) / y * 100
def holm(ps):
    idx = np.argsort(ps); m = len(ps); out = np.zeros(m); prev = 0
    for r, i in enumerate(idx):
        prev = max(prev, min(1, (m - r) * ps[i])); out[i] = prev
    return out
def rb(x):
    x = x[x != 0]
    if len(x) == 0: return 0.0
    r = np.abs(x).rank(); p, n = r[x > 0].sum(), r[x < 0].sum(); return (p - n) / (p + n)
def wp(x, y):
    try: return wilcoxon(x, y).pvalue
    except Exception: return float('nan')
ALGS = ['HEFT', 'Min-Min', 'MLEAO', 'LIWSA', 'LIWSA-ML', 'NSGA-II']

C = load('paper_main.csv')
if C is not None:
    order = list(dict.fromkeys(C.workflow)); sm = [w for w in order if not big(w)]; lg = [w for w in order if big(w)]
    hv = pd.DataFrame({al: inst(C, al) for al in ALGS})
    P('== Aggregate (hypervolume, one planning-level evaluator for all six algorithms) ==')
    sc = (hv.div(hv.max(axis=1), axis=0) * 100).mean(); wn = hv.eq(hv.max(axis=1), axis=0).sum(); rk = hv.rank(axis=1, ascending=False).mean()
    fs = C.groupby('algorithm').pareto_front_size.mean(); tm = C.groupby('algorithm').search_wallclock_ms.mean() / 1000
    for al in ALGS: P('%-9s score %5.1f  best %2d  rank %.2f  front %5.1f  search time %.3f s' % (al, sc[al], wn[al], rk[al], fs[al], tm[al] if al not in ('HEFT', 'Min-Min') else float('nan')))
    P('\n== LIWSA-ML relative to each algorithm (per-instance mean hypervolume) ==')
    bs = [b for b in ['HEFT', 'Min-Min', 'MLEAO', 'LIWSA', 'NSGA-II']]
    pv = [wp(hv['LIWSA-ML'], hv[b]) for b in bs]
    ph = holm(np.array(pv)) if all(np.isfinite(pv)) else pv
    for b, p0, ph0 in zip(bs, pv, ph):
        d = rel(hv['LIWSA-ML'], hv[b])
        P('%-8s mean %+7.2f%% median %+7.2f%% IQR [%+.1f, %+.1f] higher %2d/%d  r=%.2f  p=%.4g  p_Holm=%.4g' % (b, d.mean(), d.median(), np.percentile(d, 25), np.percentile(d, 75), (d > 0).sum(), len(d), rb(hv['LIWSA-ML'] - hv[b]), p0, ph0))
    P('\n== Scale split: LIWSA-ML vs NSGA-II ==')
    d = rel(hv['LIWSA-ML'], hv['NSGA-II'])
    if sm: P('small (%d): mean %+.2f%% higher %d  p=%.3g' % (len(sm), d[sm].mean(), (d[sm] > 0).sum(), wp(hv.loc[sm, 'LIWSA-ML'], hv.loc[sm, 'NSGA-II'])))
    if lg: P('large (%d): mean %+.2f%% range [%.1f, %.1f] higher %d' % (len(lg), d[lg].mean(), d[lg].min(), d[lg].max(), (d[lg] > 0).sum()))
    t = C.pivot_table(index='workflow', columns='algorithm', values='search_wallclock_ms', aggfunc='mean')
    P('search time LIWSA-ML / NSGA-II: overall %.2fx' % (t['LIWSA-ML'].mean() / t['NSGA-II'].mean()))
    s = C[C.algorithm.isin(['LIWSA-ML', 'NSGA-II'])].pivot_table(index=['workflow', 'seed'], columns='algorithm', values='hypervolume')
    P('seed-level: LIWSA-ML higher in %d of %d paired runs' % ((s['LIWSA-ML'] > s['NSGA-II']).sum(), len(s)))

A, B = load('paper_variant_A.csv'), load('paper_variant_B.csv')
if C is not None and A is not None and B is not None:
    P('\n== Component analysis: hypervolume relative to NSGA-II within each variant run (%) ==')
    def r2(df, al): return rel(inst(df, al), inst(df, 'NSGA-II')).loc[order]
    rows = [('LIWSA', r2(A, 'LIWSA')), ('LIWSA + archive', r2(B, 'LIWSA')), ('LIWSA-ML', r2(A, 'LIWSA-ML')), ('LIWSA-ML + archive', r2(B, 'LIWSA-ML')), ('LIWSA-ML + archive + online', r2(C, 'LIWSA-ML'))]
    for n, d in rows: P('%-28s mean %+5.1f median %+5.1f higher %2d  24-100 tasks %+5.1f  ~1000 tasks %+5.1f' % (n, d.mean(), d.median(), (d > 0).sum(), d[sm].mean() if sm else float('nan'), d[lg].mean() if lg else float('nan')))
    for (i, j, nm) in [(2, 3, 'archive on LIWSA-ML'), (3, 4, 'online learning'), (0, 1, 'archive on LIWSA')]:
        x = rows[j][1] - rows[i][1]; P('%s: %+.2f points, improved %d/%d, p=%.4g' % (nm, x.mean(), (x > 0).sum(), len(x), wp(rows[j][1], rows[i][1])))
    dev = ['Inspiral_30', 'Montage_50', 'Epigenomics_24', 'Epigenomics_100', 'Inspiral_50']
    held = [w for w in order if not big(w) and w not in dev]
    x = rows[4][1] - rows[2][1]
    for nm, ws in (('development subset', dev), ('check subset', held)):
        ws = [w for w in ws if w in x.index]
        if ws: P('final design vs published LIWSA-ML, relative to NSGA-II, %s: %+.2f points, improved %d/%d' % (nm, x[ws].mean(), (x[ws] > 0).sum(), len(ws)))

Dn = load('paper_density_ablation.csv')
if Dn is not None:
    h = pd.DataFrame({'L': inst(Dn, 'LIWSA'), 'N': inst(Dn, 'LIWSA-NoDensity')}); d = rel(h.L, h.N)
    P('\n== Density ablation (LIWSA vs LIWSA-NoDensity) ==\nmean %+.3f%% higher with density %d/%d  p=%.3g' % (d.mean(), (d > 0).sum(), len(d), wp(h.L, h.N)))
N = load('paper_naive.csv')
if N is not None:
    h = pd.DataFrame({'ML': inst(N, 'LIWSA-ML'), 'Nv': inst(N, 'LIWSA-ML-Naive')}); d = rel(h.ML, h.Nv)
    s = N[N.algorithm.isin(['LIWSA-ML', 'LIWSA-ML-Naive'])].pivot_table(index=['workflow', 'seed'], columns='algorithm', values='hypervolume')
    P('\n== OLS vs naive features ==\nmean %+.2f%% median %+.2f%% higher %d/%d; seed runs %d/%d' % (d.mean(), d.median(), (d > 0).sum(), len(d), (s['LIWSA-ML'] > s['LIWSA-ML-Naive']).sum(), len(s)))
    P(d.round(2).to_dict())
for f, nm in (('paper_lambda.csv', 'lambda'), ('paper_theta.csv', 'theta')):
    L = load(f)
    if L is None: continue
    algs = [x for x in L.algorithm.unique() if x not in ('HEFT', 'Min-Min')]
    Q = L[L.algorithm.isin(algs)].groupby(['workflow', 'algorithm']).hypervolume.mean().unstack()
    Q = Q[sorted(Q.columns, key=lambda z: float(re.findall(r'[\d.]+$', z)[0]))]
    base = [c for c in Q.columns if c.endswith('0.50')][0]; r = (Q.div(Q[base], axis=0) - 1) * 100
    spread = (Q.max(axis=1) - Q.min(axis=1)) / Q.mean(axis=1) * 100
    P('\n== %s sweep ==\nspread across values: mean %.2f%% max %.2f%%; largest |mean change vs default| %.2f%%; largest single-workflow change %.2f%%' % (nm, spread.mean(), spread.max(), r.mean().abs().max(), r.abs().values.max()))

E = load('paper_equal_time.csv')
if E is not None:
    g = E.groupby(['workflow', 'config']).agg(hv=('hypervolume', 'mean'), ms=('search_ms', 'sum'), gen=('generations', 'mean')).reset_index()
    Pv = g.pivot(index='workflow', columns='config', values='hv'); T = g.pivot(index='workflow', columns='config', values='ms'); G = g.pivot(index='workflow', columns='config', values='gen')
    ow = list(dict.fromkeys(E.workflow)); Pv, T, G = Pv.loc[ow], T.loc[ow], G.loc[ow]
    s_ = [w for w in ow if not big(w)]; l_ = [w for w in ow if big(w)]
    L_ = 'LIWSA-ML_100gen'
    P('\n== Matched wall-clock comparison (LIWSA-ML at 100 generations vs NSGA-II) ==')
    P('NSGA-II generations (matched): mean %.0f; total NSGA-II search time / LIWSA-ML search time = %.3f' % (G['NSGA-II_matchedTime'].mean(), T['NSGA-II_matchedTime'].sum() / T[L_].sum()))
    for ref in ('NSGA-II_100gen', 'NSGA-II_matchedTime'):
        x = rel(Pv[L_], Pv[ref])
        P('%-20s all: mean %+.2f%% median %+.2f%% higher %d/%d p=%.3g' % (ref, x.mean(), x.median(), (x > 0).sum(), len(x), wp(Pv[L_], Pv[ref])))
        if s_: P('%-20s 24-100 tasks: mean %+.2f%% higher %d/%d p=%.3g' % ('', x[s_].mean(), (x[s_] > 0).sum(), len(s_), wp(Pv.loc[s_, L_], Pv.loc[s_, ref])))
        if l_: P('%-20s ~1000 tasks: mean %+.2f%% range [%.1f, %.1f] higher %d/%d' % ('', x[l_].mean(), x[l_].min(), x[l_].max(), (x[l_] > 0).sum(), len(l_)))
    imp = rel(Pv['NSGA-II_matchedTime'], Pv['NSGA-II_100gen']); P('NSGA-II gain from the extra generations: mean %+.2f%%' % imp.mean())
with open(os.path.join(D, 'paper_statistics.txt'), 'w') as fh: fh.write(buf.getvalue())
