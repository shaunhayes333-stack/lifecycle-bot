#!/usr/bin/env python3
"""V5.0.8546 - research the edge dataset: every bucket / family proxy / regime filter, net of cost, no hindsight.

Usage: python3 ci/edge_research_8546.py DIR_OR_FILES...  (aate_edge_dataset_8537_*.jsonl exports; duplicates merged).
Rows flagged "fill":false (V5.0.8546: entries that could not be rebased to a fillable mark) are reported apart.
"""
import json, math, glob, sys, statistics as st
rows={}
for f in [p for a in sys.argv[1:] for p in (glob.glob(a+'/*') if __import__('os').path.isdir(a) else [a])]:
    for line in open(f):
        try: r=json.loads(line)
        except Exception: continue
        if not isinstance(r.get('net'),(int,float)): continue
        rows[(r['t'],r['mint'],r['lane'])]=r
ALL=sorted(rows.values(), key=lambda r:r['t'])
R=[r for r in ALL if r.get('fill', True) is not False]
print(f"unfillable entries (fill=false) reported apart: {len(ALL)-len(R)}")
def num(v): return v if isinstance(v,(int,float)) and math.isfinite(v) else None
def stat(xs):
    xs=[max(-60,min(200,x)) for x in xs]
    n=len(xs)
    if n==0: return "n0"
    m=sum(xs)/n; se=(st.pstdev(xs)/math.sqrt(n)) if n>1 else float('nan')
    w=sum(1 for x in xs if x>0)/n
    gw=sum(x for x in xs if x>0); gl=-sum(x for x in xs if x<0)
    pf=gw/gl if gl>0 else float('inf')
    return f"n{n:4d} mean{m:+7.1f}% ±{se:4.1f} win{w*100:3.0f}% pf{pf:4.2f}"
def show(name, pred):
    xs=[r['net'] for r in R if pred(r)]
    print(f"  {name:58s} {stat(xs)}")
span=(R[-1]['t']-R[0]['t'])/3.6e6
print(f"rows={len(R)} span={span:.1f}h lanes={sorted(set(r['lane'] for r in R))}")
show("ALL", lambda r: True)
show("admitted", lambda r: r.get('admitted') is True)
show("refused", lambda r: r.get('admitted') is False)
print("by lane:")
for l in sorted(set(r['lane'] for r in R)): show(l, lambda r,l=l: r['lane']==l)
print("price vs peak (pxPeak):")
for lo,hi in [(0,0.45),(0.45,0.7),(0.7,0.85),(0.85,0.92),(0.92,0.98),(0.98,1.01)]:
    show(f"pxPeak {lo}-{hi}", lambda r,lo=lo,hi=hi: num(r.get('pxPeak')) is not None and lo<=r['pxPeak']<hi)
print("drawdown from peak (dd %):")
for lo,hi in [(0,5),(5,15),(15,35),(35,55),(55,101)]:
    show(f"dd {lo}-{hi}", lambda r,lo=lo,hi=hi: num(r.get('dd')) is not None and lo<=r['dd']<hi)
print("buy pressure (bp %):")
for lo,hi in [(0,45),(45,55),(55,65),(65,80),(80,101)]:
    show(f"bp {lo}-{hi}", lambda r,lo=lo,hi=hi: num(r.get('bp')) is not None and lo<=r['bp']<hi)
print("5m / 1h change (impulse then pullback):")
show("chg1h>+20 & chg5m in [-15,0]  (pullback after impulse)", lambda r: (num(r.get('chg1h')) or -999)>20 and num(r.get('chg5m')) is not None and -15<=r['chg5m']<=0)
show("chg1h>+20 & chg5m in (0,+8]   (reclaim after impulse)", lambda r: (num(r.get('chg1h')) or -999)>20 and num(r.get('chg5m')) is not None and 0<r['chg5m']<=8)
show("chg5m>+10  (chasing a 5-min spike)", lambda r: (num(r.get('chg5m')) or -999)>10)
show("chg5m>+30", lambda r: (num(r.get('chg5m')) or -999)>30)
show("chg5m < -10 (falling)", lambda r: (num(r.get('chg5m')) or 999)< -10)
print("family proxies (features at decision time only):")
pb=lambda r: num(r.get('dd')) is not None and 15<=r['dd']<=55 and (num(r.get('runup')) or 0)>=25 and (num(r.get('bp')) or 0)>=55
show("PULLBACK_RECLAIM proxy: runup>=25, dd 15-55, bp>=55", pb)
show("  ... + chg5m >= 0 (turning up)", lambda r: pb(r) and (num(r.get('chg5m')) or -1)>=0)
show("NEAR-HIGH entry: pxPeak>=0.92 & runup>=25", lambda r: (num(r.get('pxPeak')) or 0)>=0.92 and (num(r.get('runup')) or 0)>=25)
show("BASE proxy: runup<15, dd<10, |chg5m|<=3, bp>=55", lambda r: (num(r.get('runup')) is not None and r['runup']<15) and (num(r.get('dd')) or 99)<10 and abs(num(r.get('chg5m')) or 99)<=3 and (num(r.get('bp')) or 0)>=55)
show("RANGE_LOW proxy: liq>=100k, age>=1440m, dd 10-40, bp>=50", lambda r: (num(r.get('liq')) or 0)>=100000 and (num(r.get('age')) or 0)>=1440 and 10<=(num(r.get('dd')) or -1)<=40 and (num(r.get('bp')) or 0)>=50)
show("established liq>=100k & age>=1d (all)", lambda r: (num(r.get('liq')) or 0)>=100000 and (num(r.get('age')) or 0)>=1440)
print("by cost:")
for lo,hi in [(0,3),(3,4.5),(4.5,7),(7,100)]:
    show(f"cost {lo}-{hi}%", lambda r,lo=lo,hi=hi: num(r.get('cost')) is not None and lo<=r['cost']<hi)
print("by source:")
srcs={}
for r in R:
    s=(r.get('src') or '').split(',')[0]; srcs.setdefault(s,[]).append(r['net'])
for s,xs in sorted(srcs.items(), key=lambda kv:-len(kv[1]))[:10]: print(f"  {s[:58]:58s} {stat(xs)}")
# regime filter, no hindsight: label of row j known at t_j+5min; regime at t uses rows with t_j+5min <= t within 45 min
print("regime filter (prior 45-min breadth, labels only once matured):")
cold=[];neutral=[];hot=[];thin=[]
for i,r in enumerate(R):
    t=r['t']; prior=[q for q in R if q['t']+300000<=t and t-(q['t']+300000)<=45*60000]
    if len(prior)<40: thin.append(r['net']); continue
    nets=[q['net'] for q in prior]; m=sum(nets)/len(nets)
    # gross proxy: net + cost
    rising=sum(1 for q in prior if q['net']+(num(q.get('cost')) or 0)>0)/len(prior)
    if m< -6 and rising<0.25: cold.append(r['net'])
    elif m>2 and rising>0.45: hot.append(r['net'])
    else: neutral.append(r['net'])
for name,xs in [("COLD",cold),("NEUTRAL",neutral),("HOT",hot),("thin (<40 prior)",thin)]: print(f"  {name:58s} {stat(xs)}")
