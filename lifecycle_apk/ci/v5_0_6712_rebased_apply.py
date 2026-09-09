#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

# ---------------------------------------------------------------------------
# 1) Current EconomicPurityGate6504 shape.
# ---------------------------------------------------------------------------
purity = ROOT / "app/src/main/kotlin/com/lifecyclebot/engine/truth/EconomicPurityGate6504.kt"
t = purity.read_text()
if "ECONOMIC_PURITY_ACCOUNT_REPLAY_DIVERGENCE_TELEMETRY_6712" not in t:
    pat = r'''        val unreconciledPaperAccount6692 = try \{.*?        return excluded'''
    repl = '''        // V5.0.6712 §PER_EVENT_PURITY_AUTHORITY — account replay drift is
        // reconciliation telemetry, never a blanket veto on unrelated clean terminals.
        val accountReplayDivergence6712 = try {
            com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() &&
                kotlin.math.abs(JournalEconomicReplay6619.latestLedgerDivergenceSol()) > 0.001
        } catch (_: Throwable) { false }
        if (accountReplayDivergence6712) {
            try { PipelineHealthCollector.labelInc("ECONOMIC_PURITY_ACCOUNT_REPLAY_DIVERGENCE_TELEMETRY_6712") } catch (_: Throwable) {}
        }
        val excluded = local || invariantBroken || historical
        if (excluded) {
            exclusions.incrementAndGet()
            if (emit) {
                try {
                    ForensicLogger.lifecycle(
                        "ECONOMIC_PURITY_EXCLUSION_6504",
                        "mint=${mint.take(10)} local=$local invariant=$invariantBroken historical=$historical accountReplayDiverged=$accountReplayDivergence6712",
                    )
                    PipelineHealthCollector.labelInc("ECONOMIC_PURITY_EXCLUSION_6504")
                } catch (_: Throwable) {}
            }
        }
        return excluded'''
    t2, n = re.subn(pat, repl, t, count=1, flags=re.S)
    if n != 1:
        raise SystemExit(f"rebased purity patch expected 1 block, got {n}")
    purity.write_text(t2)
    print("patched current EconomicPurityGate6504")

# ---------------------------------------------------------------------------
# 2) Current SpecialistCausalFunnel6625 shape uses synchronized stage maps.
#    Add exact lifecycle lookups without inventing a parallel identity.
# ---------------------------------------------------------------------------
funnel = ROOT / "app/src/main/kotlin/com/lifecyclebot/engine/truth/MemeExecutionFunnelReceivers6625.kt"
f = funnel.read_text()
if "latestUnfinalizedOpenKey6712" not in f:
    old = '''    fun latestKey6647(mint: String, lane: String): CausalKey? = records.values
        .asSequence()
        .filter { it.key.mint == mint && it.key.lane.equals(lane, true) }
        .maxByOrNull { record -> synchronized(record) { record.stages.values.maxOrNull() ?: 0L } }
        ?.key
    fun statusLine(): String = "records=${records.size}"
'''
    new = '''    fun latestKey6647(mint: String, lane: String): CausalKey? = records.values
        .asSequence()
        .filter { it.key.mint == mint && it.key.lane.equals(lane, true) }
        .maxByOrNull { record -> synchronized(record) { record.stages.values.maxOrNull() ?: 0L } }
        ?.key

    /** V5.0.6712 — canonical terminal bridge lookup: exact OPEN not yet finalized. */
    fun latestUnfinalizedOpenKey6712(mint: String, lane: String): CausalKey? = records.values
        .asSequence()
        .filter { it.key.mint == mint && it.key.lane.equals(lane, true) }
        .filter { record -> synchronized(record) {
            Stage.OPEN in record.stages && Stage.FINALIZE !in record.stages
        } }
        .maxByOrNull { record -> synchronized(record) { record.stages.values.maxOrNull() ?: 0L } }
        ?.key

    /** V5.0.6712 — learner ACK lookup: FINALIZE exists, LEARN not yet acknowledged. */
    fun latestFinalizedUnlearnedKey6712(mint: String, lane: String): CausalKey? = records.values
        .asSequence()
        .filter { it.key.mint == mint && it.key.lane.equals(lane, true) }
        .filter { record -> synchronized(record) {
            Stage.FINALIZE in record.stages && Stage.LEARN !in record.stages
        } }
        .maxByOrNull { record -> synchronized(record) { record.stages.values.maxOrNull() ?: 0L } }
        ?.key

    fun statusLine(): String = "records=${records.size}"
'''
    if f.count(old) != 1:
        raise SystemExit(f"rebased causal funnel expected exact current tail once, got {f.count(old)}")
    funnel.write_text(f.replace(old, new, 1))
    print("patched current SpecialistCausalFunnel6625")

# Execute the staged consolidation script with sections 1-2 removed; every
# remaining section keeps its own source-drift assertion.
repair_path = ROOT / "ci/v5_0_6712_patch_rot_consolidation.py"
src = repair_path.read_text()
a = src.index("# 1) PER-EVENT ECONOMIC PURITY")
c = src.index("# 3) MEME CAUSAL LEARNER")
src = src[:a] + "# 1-2 applied by rebased wrapper above.\n" + src[c:]
exec(compile(src, str(repair_path), "exec"), {"__file__": str(repair_path), "__name__": "__main__"})
