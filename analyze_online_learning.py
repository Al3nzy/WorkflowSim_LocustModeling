r"""
analyze_online_learning.py -- compares the LIWSA-ML variants and NSGA-II from the raw
RegressionHarness outputs stored in Output&Results/online_learning_eval/.

    python analyze_online_learning.py                                   # held-out 10 instances
    python analyze_online_learning.py "Output&Results/online_learning_eval/dev_5_worst_instances"

Prints, per comparison, the mean/median relative hypervolume difference, instances won,
seed-level wins and the instance-level Wilcoxon p-value. Needs: pip install scipy
"""
import re,statistics as st,glob,os,sys
from scipy import stats
def parse(path):
    L=open(path).read().splitlines(); runs=[]
    for i,l in enumerate(L):
        m=re.match(r'RUN (\S+) seed=(\d+)',l)
        if m: runs.append((m.group(1),int(m.group(2)),[tuple(map(float,p.split(','))) for p in re.findall(r'\(([^)]*)\)',L[i+1])]))
    return runs
def nd(pts):
    pts=sorted(set(pts)); return [p for p in pts if not any(q[0]<=p[0] and q[1]<=p[1] and q!=p for q in pts)]
def hv(pts,ref):
    pts=nd(pts); h=0
    for k,(m,c) in enumerate(pts):
        nm=pts[k+1][0] if k+1<len(pts) else ref[0]; h+=(nm-m)*(ref[1]-c)
    return h
def load(d):
    out={}
    for f in sorted(glob.glob(d+'/*.txt')):
        runs=parse(f); allp=[p for r in runs for p in r[2]]
        ref=(1.2*max(p[0] for p in allp),1.2*max(p[1] for p in allp))
        out[os.path.basename(f)[:-4]]={a:[hv(r[2],ref) for r in sorted([r for r in runs if r[0]==a],key=lambda r:r[1])] for a in set(r[0] for r in runs)}
    return out
def report(d,pairs):
    D=load(d); names=list(D)
    print(f'{len(names)} instances: {names}')
    for a,b in pairs:
        rel=[(st.mean(D[n][a])/st.mean(D[n][b])-1)*100 for n in names]
        seedwins=sum(x>y for n in names for x,y in zip(D[n][a],D[n][b])); seedtot=sum(len(D[n][a]) for n in names)
        try: p=stats.wilcoxon([st.mean(D[n][a]) for n in names],[st.mean(D[n][b]) for n in names]).pvalue
        except Exception: p=float('nan')
        print(f'  {a:9s} vs {b:9s}: mean {st.mean(rel):+6.2f}%  median {st.median(rel):+6.2f}%  instances won {sum(r>0 for r in rel)}/{len(rel)}  seed-level wins {seedwins}/{seedtot}  Wilcoxon(instance) p={p:.3f}')
        if len(names)<=6: print('     per-instance:',{n:round(r,2) for n,r in zip(names,rel)})
if __name__=='__main__':
    report(sys.argv[1] if len(sys.argv) > 1 else 'Output&Results/online_learning_eval/heldout_10_instances',[('LIWSAMLO','LIWSAML'),('LIWSAMLAO','LIWSAMLA'),('LIWSAMLA','NSGAII'),('LIWSAMLAO','NSGAII'),('LIWSAMLO','NSGAII'),('LIWSAML','NSGAII')])
