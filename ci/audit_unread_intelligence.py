#!/usr/bin/env python3
"""Focused: intelligence modules whose ANALYSIS OUTPUTS are never read.
Excludes god-objects (internal helpers legitimately stay in-file) and
keeps only getter/query-shaped functions — the ones whose entire purpose is
to be consumed by a decision path."""
import os,re
from collections import defaultdict
ROOT="lifecycle_apk/app/src/main/kotlin/com/lifecyclebot"
GOD={"engine/Executor.kt","engine/BotService.kt","ui/MainActivity.kt","engine/FinalDecisionGate.kt"}
files=[]
for dp,_,fns in os.walk(ROOT):
    for fn in fns:
        if fn.endswith(".kt"): files.append(os.path.join(dp,fn))
raw={p:open(p,encoding="utf-8",errors="replace").read() for p in files}
def co(t):
    t=re.sub(r'/\*.*?\*/',' ',t,flags=re.S); t=re.sub(r'//[^\n]*',' ',t)
    t=re.sub(r'"""(?:.|\n)*?"""',' "" ',t); t=re.sub(r'"(?:\\.|[^"\\\n])*"',' "" ',t); return t
CALL=re.compile(r'\b([A-Za-z_][A-Za-z0-9_]{5,})\s*\(')
calls=defaultdict(set)
for p,t in raw.items():
    for m in CALL.finditer(co(t)): calls[m.group(1)].add(p)
OBJ=re.compile(r'^(?:internal\s+)?object\s+([A-Za-z0-9_]+)',re.M)
FUNLINE=re.compile(r'^\s{0,8}(?:@\w+\s+)*(?:public\s+|internal\s+)?(?:suspend\s+)?fun\s+(?:<[^>]+>\s+)?([A-Za-z0-9_]+)\s*\(')
# query-shaped = something a decision path would READ
QUERY=re.compile(r'^(get|is|has|should|can|current|compute|calculate|predict|forecast|score|evaluate|analyse|analyze|recommend|query|posterior|expectancy|accuracy|snapshot|report|best|top|rank)',re.I)
SEM=re.compile(r'(AI|Brain|Learn|Scanner|Policy|Memory|Pattern|Predict|Oracle|Intelligence|Education|Curriculum|Sentien|Ssi|Edge|Expect|Tracker|Scorecard|Council|Consensus|Graph|Lab|Hypothesis|Collective)',re.I)
rows=[]
for p,t in raw.items():
    rel=p.replace(ROOT+"/","")
    if rel in GOD: continue
    m=OBJ.search(t)
    if not m: continue
    owner=m.group(1)
    if not SEM.search(owner) and not SEM.search(rel): continue
    funs=[]
    for line in t.splitlines():
        if re.match(r'^\s{0,8}private\s',line) or 'override' in line: continue
        fm=FUNLINE.match(line)
        if fm: funs.append(fm.group(1))
    q=[f for f in sorted(set(funs)) if len(f)>=6 and QUERY.match(f)]
    if not q: continue
    dead=[f for f in q if not (calls.get(f,set())-{p})]
    if dead: rows.append((owner,rel,len(q),dead,len(t.splitlines())))
rows.sort(key=lambda r:(-len(r[3]),-r[4]))
tot=sum(len(r[3]) for r in rows)
print(f"intelligence modules with UNREAD analysis outputs: {len(rows)}")
print(f"TOTAL unread query/analysis functions: {tot}\n")
for owner,rel,nq,dead,lines in rows[:40]:
    print(f"{owner:34s} {len(dead):2d}/{nq:2d} unread  L={lines:5d}  {rel}")
    print(f"     {', '.join(dead[:8])}{' …' if len(dead)>8 else ''}")
