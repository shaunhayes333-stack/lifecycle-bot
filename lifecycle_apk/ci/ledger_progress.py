import os,re,csv,collections,json
"""
V5.0.6940 — wiring progress against ci/UNWIRED_LEDGER.tsv.

Reports how many of the 360 MAJOR ledger entries (A_PREDICT, B_RISK, C_EXIT)
now have a real external caller. Informational only — never fails the build —
because this is a programme tracker, not a correctness gate.

A caller means actual call syntax, qualified by the owner: `Owner.fn(`, or
`.fn(` in a file that also names the Owner. An earlier version of this counted
any file that merely mentioned the identifier and reported 100% complete,
which was nonsense: names like `verdict` or `isRisky` appear as local
variables everywhere. Comments are stripped so a mention in prose is not a
call.
"""
import os,sys
def _root():
    here=os.path.dirname(os.path.abspath(__file__))
    for b in (os.path.dirname(here),here,os.getcwd()):
        for c in (os.path.join(b,"app","src","main","kotlin"),
                  os.path.join(b,"lifecycle_apk","app","src","main","kotlin")):
            if os.path.isdir(c): return b,c
    return ".",os.path.join("app","src","main","kotlin")
BASE,ROOT=_root()
files={}
for dp,_,fs in os.walk(ROOT):
    for fn in fs:
        if fn.endswith(".kt"):
            p=os.path.join(dp,fn)
            t=open(p,encoding="utf-8",errors="replace").read()
            files[p]=re.sub(r'//[^\n]*','',re.sub(r'/\*.*?\*/','',t,flags=re.S))
LEDGER=os.path.join(BASE,"ci","UNWIRED_LEDGER.tsv")
if not os.path.exists(LEDGER):
    LEDGER=os.path.join(os.path.dirname(BASE),"ci","UNWIRED_LEDGER.tsv")
rows=list(csv.DictReader(open(LEDGER),delimiter="\t"))
major=[r for r in rows if r["tier"] in ("A_PREDICT","B_RISK","C_EXIT")]
wired=collections.Counter(); left=collections.defaultdict(list)
for r in major:
    fn,own_cls=r["function"],r["owner"]
    own=os.path.normpath(os.path.join(ROOT,r["file"]))
    # A real call is qualified by the owner: Owner.fn(  — or the file imports the
    # owner AND calls .fn( on something. Require the owner name to be present.
    qual=re.compile(r'\b'+re.escape(own_cls)+r'\s*(?:\.|\?\.)\s*'+re.escape(fn)+r'\s*\(')
    bare=re.compile(r'(?:\.|\?\.)\s*'+re.escape(fn)+r'\s*\(')
    hit=False
    for p,t in files.items():
        if os.path.normpath(p)==own: continue
        if qual.search(t): hit=True; break
        if own_cls in t and bare.search(t): hit=True; break
    if hit: wired[r["tier"]]+=1
    else: left[r["tier"]].append(f'{own_cls}.{fn}  ({r["file"]})')
print(f"{'tier':<12}{'total':>6}{'wired':>7}{'left':>7}  progress")
tot=w=0
for t in ("A_PREDICT","B_RISK","C_EXIT"):
    n=sum(1 for r in major if r["tier"]==t); k=wired[t]; tot+=n; w+=k
    b=int(20*k/n); print(f"{t:<12}{n:>6}{k:>7}{n-k:>7}  [{'#'*b}{'.'*(20-b)}] {100*k/n:.0f}%")
print(f"{'TOTAL':<12}{tot:>6}{w:>7}{tot-w:>7}  {100*w/tot:.0f}%")
sys.exit(0)
