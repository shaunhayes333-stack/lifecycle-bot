#!/usr/bin/env python3
"""V5.0.6918 — triage every never-called public function into actionable tiers.

Emits a persistent ledger (ci/UNWIRED_LEDGER.tsv) so progress is trackable
across builds instead of rediscovered each session.

Tiers:
  A_PREDICT   decision inputs: expectancy/probability/score/pattern/prediction
  B_RISK      safety/risk/rug/blacklist/veto signals
  C_EXIT      exit/hold/trail/partial/profit signals
  D_DISPLAY   display/export/format/emoji/UI-only -> ignore, not a defect
  E_INFILE    only ever needed inside its own file -> false positive
  F_DEAD      no semantic hook anywhere -> deletion candidate
"""
import os,re,sys
from collections import defaultdict
ROOT="lifecycle_apk/app/src/main/kotlin/com/lifecyclebot"
GOD={"engine/Executor.kt","engine/BotService.kt","ui/MainActivity.kt",
     "engine/FinalDecisionGate.kt","engine/SolanaMarketScanner.kt"}
files=[]
for dp,_,fns in os.walk(ROOT):
    for fn in fns:
        if fn.endswith(".kt"): files.append(os.path.join(dp,fn))
raw={p:open(p,encoding="utf-8",errors="replace").read() for p in files}
def co(t):
    t=re.sub(r'/\*.*?\*/',' ',t,flags=re.S); t=re.sub(r'//[^\n]*',' ',t)
    t=re.sub(r'"""(?:.|\n)*?"""',' "" ',t); t=re.sub(r'"(?:\\.|[^"\\\n])*"',' "" ',t); return t
CALL=re.compile(r'\b([A-Za-z_][A-Za-z0-9_]{5,})\s*\(')
calls=defaultdict(set); selfcalls=defaultdict(int)
code={p:co(t) for p,t in raw.items()}
for p,c in code.items():
    for m in CALL.finditer(c): calls[m.group(1)].add(p)
OBJ=re.compile(r'^(?:internal\s+)?(?:object|class|data class|enum class)\s+([A-Za-z0-9_]+)',re.M)
FUNLINE=re.compile(r'^\s{0,8}(?:@\w+\s+)*(?:public\s+|internal\s+)?(?:suspend\s+)?fun\s+(?:<[^>]+>\s+)?([A-Za-z0-9_]+)\s*\(')
SKIP={"toString","equals","hashCode","invoke","resetForTest","clearForTest"}

A=re.compile(r'(expectanc|posterior|probabilit|predict|forecast|score|winrate|win_rate|accuracy|edge|conviction|pattern|momentum|alpha|signal|reputation|reliab|quality|boost|ranking|recommend|bestmode|consensus|confidence)',re.I)
B=re.compile(r'(risk|rug|blacklist|toxic|danger|veto|safety|fraud|honeypot|exhaust|breach|guard|suppress|block)',re.I)
C=re.compile(r'(exit|hold|trail|partial|profit|stop|tp|sell|drawdown|peak|patience)',re.I)
D=re.compile(r'(display|format|emoji|label|render|string|report|export|snapshot|header|text|panel|card|row|cell|pill|chart|view|icon|summary|diagnos|status)',re.I)

rows=[]
for p,t in raw.items():
    rel=p.replace(ROOT+"/","")
    m=OBJ.search(t)
    owner=m.group(1) if m else rel.split("/")[-1][:-3]
    for line in t.splitlines():
        if re.match(r'^\s{0,8}private\s',line) or 'override' in line: continue
        fm=FUNLINE.match(line)
        if not fm: continue
        fn=fm.group(1)
        if len(fn)<6 or fn in SKIP: continue
        ext=calls.get(fn,set())-{p}
        if ext: continue                      # has an external caller — fine
        # classify
        if rel in GOD: tier="E_INFILE"
        elif D.search(fn): tier="D_DISPLAY"
        elif A.search(fn): tier="A_PREDICT"
        elif B.search(fn): tier="B_RISK"
        elif C.search(fn): tier="C_EXIT"
        else: tier="F_DEAD"
        rows.append((tier,owner,fn,rel))
rows.sort()
counts=defaultdict(int)
for tier,_,_,_ in rows: counts[tier]+=1
out=os.path.join("ci","UNWIRED_LEDGER.tsv")
with open(out,"w") as f:
    f.write("tier\towner\tfunction\tfile\n")
    for r in rows: f.write("\t".join(r)+"\n")
print(f"total never-called public funs: {len(rows)}")
for k in sorted(counts): print(f"  {k:12s} {counts[k]:5d}")
print(f"\nledger -> {out}")
print("\nTIER A (decision inputs) — top owners by count:")
own=defaultdict(list)
for tier,o,fn,rel in rows:
    if tier=="A_PREDICT": own[o].append(fn)
for o,fs in sorted(own.items(), key=lambda kv:-len(kv[1]))[:18]:
    print(f"  {o:34s} {len(fs):3d}  {', '.join(fs[:6])}{' …' if len(fs)>6 else ''}")
