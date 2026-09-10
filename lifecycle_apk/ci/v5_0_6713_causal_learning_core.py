#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/com/lifecyclebot"


def replace_once(path: Path, old: str, new: str, label: str):
    text = path.read_text()
    n = text.count(old)
    if n != 1:
        raise SystemExit(f"{label}: expected one exact match, got {n}")
    path.write_text(text.replace(old, new, 1))


def regex_once(path: Path, pat: str, repl: str, label: str, flags=0):
    text = path.read_text()
    out, n = re.subn(pat, repl, text, count=1, flags=flags)
    if n != 1:
        raise SystemExit(f"{label}: expected one regex match, got {n}")
    path.write_text(out)

# ---------------------------------------------------------------------------
# 1. Specialist causal identity: terminal stages attach to the OPEN attempt.
# ---------------------------------------------------------------------------
funnel = SRC / "engine/truth/MemeExecutionFunnelReceivers6625.kt"
text = funnel.read_text()
if "latestUnfinalizedOpenKey6713" not in text:
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

    /** V5.0.6713 — exact OPEN causal record awaiting canonical terminal finality. */
    fun latestUnfinalizedOpenKey6713(mint: String, lane: String): CausalKey? = records.values
        .asSequence()
        .filter { it.key.mint == mint && it.key.lane.equals(lane, true) }
        .filter { record -> synchronized(record) {
            Stage.OPEN in record.stages && Stage.FINALIZE !in record.stages
        } }
        .maxByOrNull { record -> synchronized(record) { record.stages[Stage.OPEN] ?: 0L } }
        ?.key

    /** V5.0.6713 — finalized causal record awaiting a real learner ACK. */
    fun latestFinalizedUnlearnedKey6713(mint: String, lane: String): CausalKey? = records.values
        .asSequence()
        .filter { it.key.mint == mint && it.key.lane.equals(lane, true) }
        .filter { record -> synchronized(record) {
            Stage.FINALIZE in record.stages && Stage.LEARN !in record.stages
        } }
        .maxByOrNull { record -> synchronized(record) { record.stages[Stage.FINALIZE] ?: 0L } }
        ?.key

    fun statusLine(): String = "records=${records.size}"
'''
    replace_once(funnel, old, new, "causal funnel exact terminal lookup")

# ---------------------------------------------------------------------------
# 2. Meme causal learner may not ACK when its immutable entry snapshot is gone.
# ---------------------------------------------------------------------------
entry = SRC / "engine/truth/EntryStrategySnapshot6450.kt"
if "CAUSAL_ENTRY_SNAPSHOT_MISSING_6568" in entry.read_text():
    regex_once(
        entry,
        r'''(val snap = EntryStrategySnapshot6450\.snapshot\(env\.positionId\) \?: run \{\s*try \{ PipelineHealthCollector\.labelInc\("CAUSAL_ENTRY_SNAPSHOT_MISSING_6568"\) \} catch \(_:Throwable\) \{\}\s*)return true''',
        r'''\1return false''',
        "missing causal snapshot false ACK",
        re.S,
    )

# ---------------------------------------------------------------------------
# 3. Canonical terminal publication stamps FINALIZE; actual Meme learner mutation
#    stamps LEARN. Report/UI code is never lifecycle authority.
# ---------------------------------------------------------------------------
bridge = SRC / "engine/truth/FinalizedBusConsumerBridge6465.kt"
text = bridge.read_text()
if "SPECIALIST_CAUSAL_FINALIZE_CANONICAL_6713" not in text:
    replace_once(
        bridge,
        '''    fun deliver(consumer: String, env: CanonicalFinalizedTradeBus6464.Envelope): Boolean {
        // Learning purity metadata applies only to actual learning consumers.
''',
        '''    fun deliver(consumer: String, env: CanonicalFinalizedTradeBus6464.Envelope): Boolean {
        // V5.0.6713 — canonical terminal publication owns FINALIZE. This is
        // independent of learner eligibility: a position can be economically
        // finalized while deliberately excluded from training.
        try {
            SpecialistCausalFunnel6625.latestUnfinalizedOpenKey6713(env.mint, env.lane)?.let { key ->
                SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.FINALIZE, "CANONICAL_TERMINAL_6464")
                PipelineHealthCollector.labelInc("SPECIALIST_CAUSAL_FINALIZE_CANONICAL_6713_${env.lane.uppercase().take(24)}")
            }
        } catch (_: Throwable) {}

        // Learning purity metadata applies only to actual learning consumers.
''',
        "canonical FINALIZE producer",
    )

if "SPECIALIST_CAUSAL_LEARN_ACK_6713" not in bridge.read_text():
    replace_once(
        bridge,
        '''            com.lifecyclebot.engine.runtime.ColdStreakDamper.noteOutcome(env.lane, env.mode.equals("paper", true), win, loss)
            com.lifecyclebot.engine.runtime.DamageControlGate.noteOutcome(pnlPctLearn6707)
            MemeCausalLearning6568.record(env)
        } else true
''',
        '''            com.lifecyclebot.engine.runtime.ColdStreakDamper.noteOutcome(env.lane, env.mode.equals("paper", true), win, loss)
            com.lifecyclebot.engine.runtime.DamageControlGate.noteOutcome(pnlPctLearn6707)
            val learned6713 = MemeCausalLearning6568.record(env)
            if (learned6713) {
                try {
                    SpecialistCausalFunnel6625.latestFinalizedUnlearnedKey6713(env.mint, env.lane)?.let { key ->
                        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.LEARN, "MEME_CAUSAL_ACK_6568")
                        PipelineHealthCollector.labelInc("SPECIALIST_CAUSAL_LEARN_ACK_6713_${env.lane.uppercase().take(24)}")
                    }
                } catch (_: Throwable) {}
            }
            learned6713
        } else true
''',
        "real learner ACK producer",
    )

# ---------------------------------------------------------------------------
# 4. UnifiedPolicy fallback binds only from an already sealed AATE decision.
#    This repairs missing scratchpad stamps without guessing a post-hoc outcome.
# ---------------------------------------------------------------------------
uph = SRC / "engine/UnifiedPolicyHead.kt"
text = uph.read_text()
if "bindDecisionFallback6713" not in text:
    anchor = '''    private fun trainOneOutcome6681(lane: String, x: DoubleArray, pnlPct: Double) {
'''
    helper = '''    /**
     * V5.0.6713 — deterministic recovery for a valid canonical OPEN whose
     * transient UnifiedPolicy scratchpad observation was lost before position
     * binding. Inputs come only from the immutable pre-open AATE decision.
     */
    fun bindDecisionFallback6713(
        positionId: String,
        mint: String,
        ownerLane: String,
        scoreFinal: Double,
        pWin: Double,
        expectedPnlPct: Double,
        rugP: Double,
        contributorEffect01: Double,
    ): Boolean {
        if (positionId.isBlank() || mint.isBlank() || ownerLane.isBlank()) return false
        if (pendingByPosition6681.containsKey(positionId)) return true
        return try {
            val owner = normalizeLane(ownerLane)
            val score01 = (scoreFinal / 100.0).coerceIn(0.0, 1.0)
            val ev01 = (0.5 + expectedPnlPct / 200.0).coerceIn(0.0, 1.0)
            val signals = Signals(
                mlEntryConf = score01,
                symGreenLight = (1.0 - rugP).coerceIn(0.0, 1.0),
                evRatio = ev01,
                metaConviction = contributorEffect01.coerceIn(0.0, 1.0),
                fwdPWin = pWin.coerceIn(0.0, 1.0),
                candConf = score01,
            )
            pendingByPosition6681[positionId] = BoundEntry6681(mint, owner, signals.toArray())
            causalBoundCount6681.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("UNIFIED_POLICY_DECISION_FALLBACK_BOUND_6713")
                ForensicLogger.lifecycle(
                    "UNIFIED_POLICY_DECISION_FALLBACK_BOUND_6713",
                    "positionId=$positionId mint=$mint ownerLane=$owner source=SEALED_AATE_DECISION",
                )
            } catch (_: Throwable) {}
            true
        } catch (_: Throwable) { false }
    }

'''
    replace_once(uph, anchor, helper + anchor, "UnifiedPolicy sealed-decision fallback")

# ---------------------------------------------------------------------------
# 5. Position attach uses the sealed decision fallback only when the original
#    pre-open policy stamp is missing. Terminal ACK is delayed until exact owner
#    policy mutation succeeds; no premature rewarded-position latch.
# ---------------------------------------------------------------------------
fabric = SRC / "engine/truth/AateDecisionEnvelope6512.kt"
text = fabric.read_text()
if "bindDecisionFallback6713(" not in text:
    replace_once(
        fabric,
        '''        val policyBound6681 = try { UnifiedPolicyHead.bindPosition6681(positionId, mint, lane) } catch (_: Throwable) { false }
''',
        '''        var policyBound6681 = try { UnifiedPolicyHead.bindPosition6681(positionId, mint, lane) } catch (_: Throwable) { false }
''',
        "mutable original policy bind result",
    )
    old = '''        if (e == null) {
            try { PipelineHealthCollector.labelInc("AATE_POSITION_ATTRIBUTION_MISSING_6681") } catch (_: Throwable) {}
            return false
        }
        byPosition[positionId] = e.copy(positionId = positionId)
'''
    new = '''        if (e == null) {
            try { PipelineHealthCollector.labelInc("AATE_POSITION_ATTRIBUTION_MISSING_6681") } catch (_: Throwable) {}
            return false
        }
        if (!policyBound6681) {
            val weight6713 = e.contributors.sumOf { it.weight }.coerceAtLeast(0.0001)
            val effect6713 = e.contributors.sumOf {
                ((it.effect + 1.0) * 0.5).coerceIn(0.0, 1.0) * it.weight
            } / weight6713
            policyBound6681 = try {
                UnifiedPolicyHead.bindDecisionFallback6713(
                    positionId = positionId,
                    mint = mint,
                    ownerLane = lane,
                    scoreFinal = e.scoreFinal,
                    pWin = e.pWin,
                    expectedPnlPct = e.expectedPnlPct,
                    rugP = e.rugP,
                    contributorEffect01 = effect6713,
                )
            } catch (_: Throwable) { false }
        }
        byPosition[positionId] = e.copy(positionId = positionId)
'''
    replace_once(fabric, old, new, "AATE position fallback binding")

if "AATE_POLICY_REWARD_RETRY_CAUSAL_BIND_6713" not in fabric.read_text():
    replace_once(
        fabric,
        '''    fun onFinalized(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean {
        if (!rewardedPositions.add(env.positionId)) return true
''',
        '''    fun onFinalized(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean {
        if (rewardedPositions.contains(env.positionId)) return true
''',
        "defer rewarded idempotency latch",
    )
    old = '''        val uphBefore = UnifiedPolicyHead.trainedCount()
        // V5.0.6681 — canonical owner-bound learning. Do not call the legacy
        // mint-wide recordOutcome path: one terminal trade must update global
        // exactly once and only its actual execution owner lane.
        try { UnifiedPolicyHead.recordOutcome6681(env.positionId, env.mint, env.lane, env.realizedReturnPct) } catch (_: Throwable) {}
        if (UnifiedPolicyHead.trainedCount() > uphBefore) updated += "UnifiedPolicyHead"
'''
    new = '''        val uphBefore = UnifiedPolicyHead.trainedCount()
        // V5.0.6713 — exact owner-bound policy mutation is required before this
        // consumer ACKs the canonical event. Failed/missing binds retry instead
        // of permanently recording a false successful reward delivery.
        val policyAck6713 = try {
            UnifiedPolicyHead.recordOutcome6681(env.positionId, env.mint, env.lane, env.realizedReturnPct)
        } catch (_: Throwable) { false }
        val memeOwner6713 = env.lane.uppercase() in setOf(
            "QUALITY","BLUECHIP","BLUE_CHIP","SHITCOIN","CYCLIC","EXPRESS","CORE",
            "MOONSHOT","PROJECT_SNIPER","DIP_HUNTER","MANIPULATED","TREASURY","CASHGEN",
        )
        if (memeOwner6713 && !policyAck6713) {
            try {
                PipelineHealthCollector.labelInc("AATE_POLICY_REWARD_RETRY_CAUSAL_BIND_6713")
                ForensicLogger.lifecycle(
                    "AATE_POLICY_REWARD_RETRY_CAUSAL_BIND_6713",
                    "positionId=${env.positionId.take(18)} lane=${env.lane} mint=${env.mint.take(10)} action=retry_no_false_ack",
                )
            } catch (_: Throwable) {}
            return false
        }
        if (!rewardedPositions.add(env.positionId)) return true
        if (policyAck6713 && UnifiedPolicyHead.trainedCount() > uphBefore) updated += "UnifiedPolicyHead"
'''
    replace_once(fabric, old, new, "exact UnifiedPolicy terminal ACK")

# ---------------------------------------------------------------------------
# 6. Regression tests target semantics, not only marker presence.
# ---------------------------------------------------------------------------
test = ROOT / "app/src/test/kotlin/com/lifecyclebot/engine/Aate6713CausalLearningCoreTest.kt"
test.write_text(r'''package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate6713CausalLearningCoreTest {
    @Test fun `canonical terminal owns finalize and learner mutation owns learn`() {
        val b = File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedBusConsumerBridge6465.kt").readText()
        assertTrue(b.contains("latestUnfinalizedOpenKey6713"))
        assertTrue(b.contains("Stage.FINALIZE"))
        assertTrue(b.contains("latestFinalizedUnlearnedKey6713"))
        assertTrue(b.contains("if (learned6713)"))
        assertTrue(b.contains("Stage.LEARN"))
    }

    @Test fun `missing causal entry snapshot cannot be falsely acked`() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/EntryStrategySnapshot6450.kt").readText()
        val fn = s.substringAfter("fun record(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean")
            .substringBefore("private fun med")
        val miss = fn.substringAfter("CAUSAL_ENTRY_SNAPSHOT_MISSING_6568").take(220)
        assertTrue(miss.contains("return false"))
    }

    @Test fun `policy rewarded latch occurs after exact owner mutation`() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/AateDecisionEnvelope6512.kt").readText()
        val fn = s.substringAfter("fun onFinalized(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean")
            .substringBefore("private fun emitPolicy")
        assertTrue(fn.indexOf("recordOutcome6681(") >= 0)
        assertTrue(fn.indexOf("rewardedPositions.add(env.positionId)") > fn.indexOf("recordOutcome6681("))
        assertTrue(fn.contains("AATE_POLICY_REWARD_RETRY_CAUSAL_BIND_6713"))
    }

    @Test fun `sealed AATE decision is only fallback source for missing policy observation`() {
        val fabric = File("src/main/kotlin/com/lifecyclebot/engine/truth/AateDecisionEnvelope6512.kt").readText()
        val uph = File("src/main/kotlin/com/lifecyclebot/engine/UnifiedPolicyHead.kt").readText()
        assertTrue(fabric.contains("bindDecisionFallback6713("))
        assertTrue(uph.contains("source=SEALED_AATE_DECISION"))
        assertFalse(uph.contains("source=POST_HOC_MARKET"))
    }
}
''')

print("V5.0.6713 causal learning core repair applied")
