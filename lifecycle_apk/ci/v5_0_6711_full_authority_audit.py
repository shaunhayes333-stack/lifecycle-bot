#!/usr/bin/env python3
from pathlib import Path
import re
from collections import defaultdict

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin"
OUT = ROOT / "audits/v5_0_6711_full_authority_audit.md"

files = sorted(SRC.rglob("*.kt"))
texts = {p: p.read_text(errors="ignore") for p in files}


def rel(p): return str(p.relative_to(ROOT))
def hits(pattern, flags=0):
    rx = re.compile(pattern, flags)
    rows=[]
    for p,t in texts.items():
        for i,line in enumerate(t.splitlines(),1):
            if rx.search(line): rows.append((rel(p),i,line.strip()))
    return rows

def section(title, rows, limit=500):
    out=[f"\n## {title}\n", f"Count: **{len(rows)}**\n"]
    for p,i,l in rows[:limit]: out.append(f"- `{p}:{i}` — `{l[:300]}`")
    if len(rows)>limit: out.append(f"- … {len(rows)-limit} more")
    return out

lines=["# V5.0.6711 Full Runtime Authority / Patch-Rot Audit\n",
       "Generated from the complete checked-out Kotlin source. This report maps source authority, overlays, bypasses and causal wiring; it is not a string-presence regression test.\n"]

# 1. Duplicate authority definitions / legacy-new parallel stacks.
defs=defaultdict(list)
for p,t in texts.items():
    for i,line in enumerate(t.splitlines(),1):
        m=re.search(r"\b(object|class)\s+([A-Za-z0-9_]+)", line)
        if m: defs[m.group(2)].append((rel(p),i,line.strip()))
dups=[]
for name,rows in sorted(defs.items()):
    if len(rows)>1:
        for p,i,l in rows: dups.append((p,i,f"{name}: {l}"))
lines += section("Duplicate class/object definitions (parallel-authority risk)", dups)

queries={
"Legacy FinalDecisionGate callsites": r"\bFinalDecisionGate\.(evaluate|canExecute)|FinalDecisionGate\.evaluate",
"V3 FinalDecisionEngine construction/calls": r"\bFinalDecisionEngine\b|finalDecisionEngine\.decide|\.decide\(",
"ExecutableOpenGate authority callsites": r"\bExecutableOpenGate\b|PREFDG_ENTRY_AUTHORITY_6487|EXEC_OPEN_ALLOWED|ExecutionIntent\(",
"TradeAuthorizer callsites": r"\bTradeAuthorizer\b|authorize.*(?:Buy|Open|Trade)|EXEC_GATE",
"WR recovery/collapse authority": r"WrRecoveryPartial|rollingCollapse|WR_ROLL50|WR_BELOW|rollingWinRatePct|phaseTargetWr",
"Anti-choke/starvation/adaptive relaxation": r"AntiChoke|anti.?choke|starvation|adaptiveRelax|bypass.*confidence|BOOTSTRAP_OVERRIDE|SOFT_PROBE|PROBE_ONLY",
"Fresh-launch / dust / WAIT override promotions": r"fresh.*probe|DUST_PROBE|ZERO_SIGNAL_PROBE|WAIT.*OVERRIDE|LANE_BUY_INTENT_OVERRIDES|PRE_RUNNER|EXECUTE_SMALL",
"Brain + cross-talk contributors": r"AICrossTalk|CrossTalkFusion|BrainConsensus|UnifiedPolicyHead|ForwardOutcomeModel|StrategyHypothesis|MathematicalEdge|LiveProbabilityEngine|AateDecisionFabric|MetaCognition|Superbrain|Sentience",
"Canonical position binding": r"attachPosition\(|bindPosition6681\(|UNIFIED_POLICY_POSITION_BOUND|positionOpened|CANONICAL.*OPEN",
"Canonical terminal buses": r"CanonicalTradeFinalizedBus6450|CanonicalFinalizedTradeBus6464|CANONICAL_TRADE_FINAL|deliverToConsumers|FinalizedBusConsumerBridge",
"Learning eligibility / purity / quarantine": r"PaperLearningEligibility6519|EconomicPurityGate6504|RewardPurityGate6441|LearningQuarantine|CANONICAL_PERFORMANCE_QUARANTINE|FINALIZED_LEARNING_INELIGIBLE",
"Policy outcome credit": r"recordOutcome6681\(|onFinalized\(|MemeCausalLearning6568\.record|LaneExitTuner\.recordClose|ColdStreakDamper\.noteOutcome|DamageControlGate\.noteOutcome",
"Replay/accounting authority": r"JournalEconomicReplay6619|FillLotLedger|CanonicalPaperTransaction6486|PAPER_REPLAY|LEDGER_VS_JOURNAL|qtyMismatch|ORPHAN|RECONCIL",
"Paper open/close mutation paths": r"PAPER_(BUY|SELL|CLOSE)|paperBuy|paperSell|paperClose|recordBuy|recordSell|closePosition|markClosed",
"Exit/finality authority": r"ExitCoordinator|requestSell|MemeSellFinality|Universal.*SL|CATASTROPHIC|TRAILING_STOP|TAKE_PROFIT|SELL_CONFIRMED|SELL_FINALIZED",
"Occupancy/slot/turnover": r"SlotHealth|slotHealth|MintOccupancy|turnover|EXEC_DEFERRED_SLOT_HEALTH|SAME_MINT|PendingIntentBacklog",
"Provider routing / fallback": r"ApiHealthMonitor|Birdeye|DexScreener|DexPaprika|CoinGecko|PumpFun|Jupiter|Helius|circuit.*provider|fallback|BACKOFF",
"Main-thread UI heavy reads": r"MainActivity|renderOpenPositions|renderBlueChipPositions|snapshotAtomic6643|runBlocking|Dispatchers\.Main|Looper\.getMainLooper|UiOffMainAudit",
}
for title,pat in queries.items():
    lines += section(title, hits(pat, re.I))

# 2. High-risk contradictory overlays in actual execution-adjacent files.
risk_files=[p for p in files if any(k in p.name for k in ["Decision","Gate","Authorizer","Executor","BotService","Sizer","Learning","Exit","Paper","Replay","Truth","CrossTalk","Policy"])]
over=[]
rx_overlay=re.compile(r"\b(bypass|soft.?allow|soft.?probe|probe|relax|override|fallback|restore|reopen|force|suppress|shadow)\b", re.I)
for p in risk_files:
    for i,line in enumerate(texts[p].splitlines(),1):
        if rx_overlay.search(line): over.append((rel(p),i,line.strip()))
lines += section("Execution-adjacent bypass/relax/restore overlays", over, 1000)

# 3. Version strata around authority files: evidence of accumulated patch stacking.
strata=[]
for p in risk_files:
    tags=re.findall(r"V5\.(?:0|9)\.\d+[A-Za-z0-9_-]*", texts[p], re.I)
    if tags:
        uniq=[]
        for x in tags:
            if x not in uniq: uniq.append(x)
        if len(uniq)>=8:
            strata.append((rel(p),1,f"{len(uniq)} strata: {' → '.join(uniq[-30:])}"))
lines += section("Files with heavy version/patch stacking", sorted(strata,key=lambda x:int(x[2].split()[0]), reverse=True), 300)

# 4. Concrete invariants / red flags discovered structurally.
checks=[]
def addcheck(name, ok, detail): checks.append((name,ok,detail))
alltxt="\n".join(texts.values())
addcheck("Global paper replay divergence participates in EconomicPurity exclusion",
         "local || invariantBroken || historical || unreconciledPaperAccount6692" not in alltxt,
         "Must be telemetry/account rebuild authority, not blanket per-terminal learner veto")
addcheck("Specialist FINALIZE has runtime producer outside report/UI",
         any("Stage.FINALIZE" in l and "ToolkitSignalSheet" not in p and "CausalAuthorityRepair" not in p for p,i,l in hits(r"Stage\.FINALIZE")),
         "FINALIZE must be stamped by canonical terminal publication")
addcheck("Specialist LEARN has runtime producer outside report/UI",
         any("Stage.LEARN" in l and "ToolkitSignalSheet" not in p and "CausalAuthorityRepair" not in p for p,i,l in hits(r"Stage\.LEARN")),
         "LEARN must be stamped only after actual learner ACK")
addcheck("UnifiedPolicy position bind exists", "bindPosition6681(" in alltxt, "Every canonical OPEN path must call it")
addcheck("UnifiedPolicy outcome credit exists", "recordOutcome6681(" in alltxt, "Every eligible terminal must call it exactly once")
addcheck("Legacy FDG marked deprecated", "@Deprecated" in texts.get(SRC/"com/lifecyclebot/engine/FinalDecisionGate.kt", ""), "Deprecated authority must not independently contradict V3/final open gate")
addcheck("Canonical final open firewall exists", "object ExecutableOpenGate" in alltxt, "Use as one final capital authority")

lines.append("\n## Structural invariant summary\n")
for n,ok,d in checks:
    lines.append(f"- {'✅' if ok else '❌'} **{n}** — {d}")

# 5. Candidate consolidation map.
lines += ["\n## Consolidation target\n",
"1. **Discovery/data/brains remain broad and live.** Scanner, token feeds, specialist models, cross-talk and hypothesis engines may contribute evidence and shadow outcomes.\n",
"2. **One immutable decision envelope.** V3/brains/cross-talk produce evidence; no contributor directly creates capital authority.\n",
"3. **One final capital admission authority.** ExecutableOpenGate/TradeAuthorizer must consume the immutable decision plus safety, route, WR/adaptive policy and capital state; later anti-choke/probe code cannot override a DENY.\n",
"4. **One OPEN identity.** Every successful canonical open binds positionId → exact decision/policy/specialist causal key.\n",
"5. **One terminal source.** CanonicalTradeFinalizedBus6450 → CanonicalFinalizedTradeBus6464 with exact terminal economic proof.\n",
"6. **Per-event learning purity.** Historical/account replay divergence triggers rebuild/telemetry, never blanket exclusion of unrelated clean terminal events.\n",
"7. **One learner ACK path.** Clean terminal event reaches owner learner + cross-talk/strategy consumers once, then stamps LEARN on the original OPEN key.\n",
"8. **Replay/admin rows stay forensic only.** Rebuild contaminated performance from immutable lots/events; do not train on repaired/synthetic economics.\n",
"9. **Exit and occupancy finality are idempotent.** Sell-confirmed terminal releases locks/slots exactly once.\n",
"10. **UI renders cached snapshots only.** No ledger/replay/DB recomputation on Main.\n"]

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text("\n".join(lines))
print(f"wrote {OUT} ({len(lines)} lines assembled)")
