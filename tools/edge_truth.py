#!/usr/bin/env python3
"""
AATE edge-truth readout — scores the bot's SELECTION, not its win rate.

Win rate lies on this strategy: a 1.7% runner tail can carry a positive mean
while the median trade loses. This prints, per lane, the numbers that don't:
median net, runner rate, tail-share of P&L, blind-at-decision rate, and the
at-high admit rate (the exact leak 8640 targets).

Usage:
    python3 tools/edge_truth.py aate_edge_dataset_*.jsonl[.txt]
"""
import json, sys, statistics as st
from collections import defaultdict

MEME_LANES = {"SHITCOIN","MOONSHOT","EXPRESS","PROJECT_SNIPER","MANIPULATED",
              "DIP_HUNTER","QUALITY","BLUECHIP","CORE","CYCLIC"}

def num(v):
    return v if isinstance(v,(int,float)) else None

def load(path):
    rows=[]
    with open(path) as f:
        for line in f:
            line=line.strip()
            if not line: continue
            try: r=json.loads(line)
            except json.JSONDecodeError: continue
            if "net" in r and "lane" in r: rows.append(r)
    return rows

def lane_stats(rows):
    nets=[num(r["net"]) for r in rows if num(r["net"]) is not None]
    if not nets: return None
    wins=[n for n in nets if n>0]; losses=[n for n in nets if n<=0]
    gains=sum(wins); drops=sum(-n for n in losses)
    pf = gains/drops if drops>0 else float("inf")
    runners=[n for n in nets if n>=100]
    # tail share: fraction of all positive P&L from the single best trade
    tail = (max(wins)/gains*100) if wins else 0.0
    # blind-at-decision: pxPeak/runup not observed
    blind=sum(1 for r in rows if num(r.get("pxPeak")) is None or num(r.get("runup")) is None)
    # at-high while observed (the 8582 condition)
    athigh=sum(1 for r in rows
               if num(r.get("pxPeak")) is not None and num(r.get("runup")) is not None
               and r["pxPeak"]>=0.97 and r["runup"]>=5.0)
    return dict(n=len(nets), median=st.median(nets), wr=len(wins)/len(nets)*100,
                pf=pf, run=len(runners)/len(nets)*100, tail=tail,
                blind=blind/len(rows)*100, athigh=athigh/len(rows)*100)

def main(path):
    rows=load(path)
    adm=[r for r in rows if r.get("admitted") in (True,1)]
    ref=[r for r in rows if r.get("admitted") in (False,0)]
    print(f"\n{path}\n{len(rows)} rows | admitted {len(adm)} | refused {len(ref)}\n")
    hdr=f"{'lane':16} {'n':>5} {'medNet':>8} {'wr':>5} {'PF':>6} {'run%':>5} {'tail%':>6} {'blind%':>7} {'atHigh%':>8}"
    print("ADMITTED by lane (does the gate pick winners?)")
    print(hdr); print("-"*len(hdr))
    bylane=defaultdict(list)
    for r in adm: bylane[r["lane"]].append(r)
    for lane in sorted(bylane, key=lambda l: -len(bylane[l])):
        s=lane_stats(bylane[lane])
        if not s or s["n"]<5: continue
        pf=f"{s['pf']:.2f}" if s['pf']!=float('inf') else " inf"
        print(f"{lane:16} {s['n']:>5} {s['median']:>+7.1f}% {s['wr']:>4.0f}% {pf:>6} "
              f"{s['run']:>4.1f}% {s['tail']:>5.0f}% {s['blind']:>6.0f}% {s['athigh']:>7.0f}%")
    a=lane_stats(adm); r=lane_stats(ref)
    print("-"*len(hdr))
    print(f"{'ALL ADMITTED':16} {a['n']:>5} {a['median']:>+7.1f}% {a['wr']:>4.0f}% "
          f"{a['pf']:.2f} {a['run']:>4.1f}% {a['tail']:>5.0f}% {a['blind']:>6.0f}% {a['athigh']:>7.0f}%")
    print(f"{'ALL REFUSED':16} {r['n']:>5} {r['median']:>+7.1f}% {r['wr']:>4.0f}% "
          f"{r['pf']:.2f} {r['run']:>4.1f}%")
    print("\nread: if ADMITTED median <= REFUSED median, the gate is not selecting.")
    print("      if tail% is high, P&L is one lottery ticket — a single coin.")
    print("      blind%/atHigh% are the 8640 leak: entries made without seeing the curve.\n")

if __name__=="__main__":
    if len(sys.argv)<2: sys.exit(__doc__)
    for p in sys.argv[1:]: main(p)
