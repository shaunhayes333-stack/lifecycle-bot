#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/com/lifecyclebot"
TEST = ROOT / "app/src/test/kotlin/com/lifecyclebot/engine/Aate6711CausalLearningAuthorityTest.kt"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one source match, found {count}")
    path.write_text(text.replace(old, new, 1))


# P0-1 — stop account-level replay drift from blanket-excluding every clean close.
purity = SRC / "engine/truth/EconomicPurityGate6504.kt"
replace_once(
    purity,
    """        val excluded = local || invariantBroken || historical || unreconciledPaperAccount6692
        if (excluded) {
            exclusions.incrementAndGet()
            if (unreconciledPaperAccount6692) globalPaperExclusions6692.incrementAndGet()
""",
    """        // V5.0.6711 — PER-TERMINAL PURITY AUTHORITY.
        // Account-level replay drift is an accounting/rebuild alarm, NOT proof
        // that this exact committed terminal event is impure. Blanket-gating on
        // it excluded every PAPER close from every learner whenever any unrelated
        // historical replay delta existed. Exact terminal proof + per-mint /
        // per-position quarantines remain authoritative below.
        val excluded = local || invariantBroken || historical
        if (unreconciledPaperAccount6692) {
            globalPaperExclusions6692.incrementAndGet()
            if (emit) {
                try {
                    PipelineHealthCollector.labelInc("ECONOMIC_PURITY_GLOBAL_PAPER_DIVERGENCE_TELEMETRY_6711")
                } catch (_: Throwable) {}
            }
        }
        if (excluded) {
            exclusions.incrementAndGet()
""",
    "GLOBAL_PAPER_BLANKET_LEARNING_EXCLUSION_6711",
)

# P0-2 — bind FINALIZE/LEARN telemetry to the actual open causal record.
funnel = SRC / "engine/truth/MemeExecutionFunnelReceivers6625.kt"
replace_once(
    funnel,
    """    fun latestKey6647(mint: String, lane: String): CausalKey? = records.values
        .asSequence()
        .filter { it.key.mint == mint && it.key.lane.equals(lane, true) }
        .maxByOrNull { record -> synchronized(record) { record.stages.values.maxOrNull() ?: 0L } }
        ?.key
    fun statusLine(): String = "records=${records.size}"
""",
    """    fun latestKey6647(mint: String, lane: String): CausalKey? = records.values
        .asSequence()
        .filter { it.key.mint == mint && it.key.lane.equals(lane, true) }
        .maxByOrNull { record -> synchronized(record) { record.stages.values.maxOrNull() ?: 0L } }
        ?.key

    // V5.0.6711 — terminal causality must attach to the OPEN attempt, not
    // whichever same-mint scan happened most recently after the position opened.
    fun latestUnfinalizedOpenKey6711(mint: String, lane: String): CausalKey? = records.values
        .asSequence()
        .filter { it.key.mint == mint && it.key.lane.equals(lane, true) }
        .filter { record -> synchronized(record) { Stage.OPEN in record.stages && Stage.FINALIZE !in record.stages } }
        .maxByOrNull { record -> synchronized(record) { record.stages[Stage.OPEN] ?: 0L } }
        ?.key

    fun latestFinalizedUnlearnedKey6711(mint: String, lane: String): CausalKey? = records.values
        .asSequence()
        .filter { it.key.mint == mint && it.key.lane.equals(lane, true) }
        .filter { record -> synchronized(record) { Stage.FINALIZE in record.stages && Stage.LEARN !in record.stages } }
        .maxByOrNull { record -> synchronized(record) { record.stages[Stage.FINALIZE] ?: 0L } }
        ?.key

    fun statusLine(): String = "records=${records.size}"
""",
    "CAUSAL_OPEN_TERMINAL_BINDING_6711",
)

bridge = SRC / "engine/truth/FinalizedBusConsumerBridge6465.kt"
replace_once(
    bridge,
    """    fun deliver(consumer: String, env: CanonicalFinalizedTradeBus6464.Envelope): Boolean {
        // Learning purity metadata applies only to actual learning consumers.
""",
    """    fun deliver(consumer: String, env: CanonicalFinalizedTradeBus6464.Envelope): Boolean {
        // V5.0.6711 — canonical terminal publication is the FINALIZE authority
        // for the specialist causal chain. Stamp the still-open execution record
        // before any learner-specific exclusion decision; FINALIZE means the
        // economic position closed, while LEARN below means a clean learner ACK.
        try {
            SpecialistCausalFunnel6625.latestUnfinalizedOpenKey6711(env.mint, env.lane)?.let { key ->
                SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.FINALIZE, "CANONICAL_FINALIZED_6711")
                PipelineHealthCollector.labelInc("SPECIALIST_CANONICAL_FINALIZE_BOUND_6711")
            }
        } catch (_: Throwable) {}

        // Learning purity metadata applies only to actual learning consumers.
""",
    "FINALIZE_CAUSAL_STAMP_6711",
)
replace_once(
    bridge,
    """            MemeCausalLearning6568.record(env)
        } else true
""",
    """            val learned6711 = MemeCausalLearning6568.record(env)
            if (learned6711) {
                try {
                    SpecialistCausalFunnel6625.latestFinalizedUnlearnedKey6711(env.mint, env.lane)?.let { key ->
                        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.LEARN, "CANONICAL_LEARNING_DELIVERED_6711")
                        PipelineHealthCollector.labelInc("SPECIALIST_CANONICAL_LEARNING_BOUND_6711")
                    }
                } catch (_: Throwable) {}
            }
            learned6711
        } else true
""",
    "LEARN_CAUSAL_STAMP_6711",
)

# P0-3 — authoritative WR-collapse guard, independent of the legacy soft/probe stack.
guard = SRC / "engine/CanonicalAdaptiveEntryGuard6711.kt"
guard.write_text(r'''package com.lifecyclebot.engine

/**
 * V5.0.6711 — canonical adaptive entry authority.
 *
 * Discovery, shadow scoring and hypothesis generation remain live. Only new
 * canonical capital is filtered while the last-50 terminal cohort is clearly
 * failing. This guard deliberately sits outside AntiChoke/soft-probe policy so
 * catastrophic WR cannot relax its own quality gate.
 */
object CanonicalAdaptiveEntryGuard6711 {
    data class Verdict(
        val block: Boolean,
        val reason: String,
        val rollingWrPct: Double,
        val decisiveCloses: Int,
        val requiredScore: Double,
    )

    private data class Snapshot(val atMs: Long, val rollingWrPct: Double, val decisive: Int)
    @Volatile private var cached = Snapshot(0L, -1.0, 0)
    private const val CACHE_MS = 3_000L

    private fun snapshot(): Snapshot {
        val now = System.currentTimeMillis()
        val c = cached
        if (now - c.atMs <= CACHE_MS) return c
        val next = try {
            val life = TradeHistoryStore.getLifetimeStats()
            Snapshot(now, TradeHistoryStore.rollingWinRatePct(50), life.totalSells)
        } catch (_: Throwable) {
            Snapshot(now, -1.0, 0)
        }
        cached = next
        return next
    }

    fun evaluate(quality: String, entryScore: Double): Verdict {
        val s = snapshot()
        if (s.decisive < 20 || !s.rollingWrPct.isFinite() || s.rollingWrPct < 0.0) {
            return Verdict(false, "CANONICAL_WR_WARMUP_6711", s.rollingWrPct, s.decisive, 0.0)
        }

        val requiredScore = when {
            s.rollingWrPct < 20.0 -> 65.0
            s.rollingWrPct < 30.0 -> 60.0
            s.rollingWrPct < 40.0 -> 55.0
            else -> 0.0
        }
        if (requiredScore <= 0.0) {
            return Verdict(false, "CANONICAL_WR_RECOVERED_6711", s.rollingWrPct, s.decisive, 0.0)
        }

        val highGrade = quality.equals("A", true) || quality.equals("A+", true)
        val pass = highGrade && entryScore.isFinite() && entryScore >= requiredScore
        val reason = if (pass) {
            "CANONICAL_WR_COLLAPSE_HIGH_GRADE_PASS_6711"
        } else {
            "CANONICAL_WR_COLLAPSE_AUTHORITY_6711 roll=${"%.1f".format(s.rollingWrPct)} n=${s.decisive} quality=$quality score=${"%.1f".format(entryScore)} required=${requiredScore.toInt()}"
        }
        return Verdict(!pass, reason, s.rollingWrPct, s.decisive, requiredScore)
    }
}
''')

fdg = SRC / "engine/FinalDecisionGate.kt"
replace_once(
    fdg,
    """        val isCGrade = candidate.setupQuality == "C" || candidate.setupQuality == "D"
""",
    """        // V5.0.6711 — authoritative catastrophic-WR boundary. This is an
        // EARLY RETURN by design: legacy bootstrap, AntiChoke and dust-probe
        // patches below cannot turn a failing canonical cohort back into a buy.
        val canonicalAdaptiveGuard6711 = try {
            CanonicalAdaptiveEntryGuard6711.evaluate(candidate.setupQuality, candidate.entryScore)
        } catch (_: Throwable) { null }
        if (canonicalAdaptiveGuard6711?.block == true) {
            try {
                PipelineHealthCollector.labelInc("FDG_CANONICAL_WR_COLLAPSE_BLOCK_6711")
                ForensicLogger.lifecycle(
                    "FDG_CANONICAL_WR_COLLAPSE_BLOCK_6711",
                    "mint=${ts.mint.take(10)} lane=${tradingModeTag?.name ?: "STANDARD"} ${canonicalAdaptiveGuard6711.reason}",
                )
            } catch (_: Throwable) {}
            return FinalDecision(
                shouldTrade = false,
                mode = mode,
                approvalClass = ApprovalClass.BLOCKED,
                quality = candidate.setupQuality,
                confidence = candidate.aiConfidence,
                edge = EdgeVerdict.SKIP,
                blockReason = canonicalAdaptiveGuard6711.reason,
                blockLevel = BlockLevel.CONFIDENCE,
                sizeSol = 0.0,
                tags = tags + "canonical_wr_authority_6711",
                mint = ts.mint,
                symbol = ts.symbol,
                approvalReason = canonicalAdaptiveGuard6711.reason,
                gateChecks = checks + GateCheck("canonical_wr_authority_6711", false, canonicalAdaptiveGuard6711.reason),
            )
        }

        val isCGrade = candidate.setupQuality == "C" || candidate.setupQuality == "D"
""",
    "FDG_AUTHORITATIVE_WR_EARLY_RETURN_6711",
)

TEST.write_text(r'''package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source-authority regression locks for V5.0.6711. */
class Aate6711CausalLearningAuthorityTest {
    @Test
    fun `global paper replay drift cannot blanket exclude exact closes`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/EconomicPurityGate6504.kt").readText()
        assertTrue(src.contains("val excluded = local || invariantBroken || historical"))
        assertFalse(src.contains("val excluded = local || invariantBroken || historical || unreconciledPaperAccount6692"))
        assertTrue(src.contains("ECONOMIC_PURITY_GLOBAL_PAPER_DIVERGENCE_TELEMETRY_6711"))
    }

    @Test
    fun `canonical finalize and learn stages are actually wired`() {
        val bridge = File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedBusConsumerBridge6465.kt").readText()
        val funnel = File("src/main/kotlin/com/lifecyclebot/engine/truth/MemeExecutionFunnelReceivers6625.kt").readText()
        assertTrue(bridge.contains("Stage.FINALIZE"))
        assertTrue(bridge.contains("Stage.LEARN"))
        assertTrue(bridge.contains("SPECIALIST_CANONICAL_LEARNING_BOUND_6711"))
        assertTrue(funnel.contains("latestUnfinalizedOpenKey6711"))
        assertTrue(funnel.contains("latestFinalizedUnlearnedKey6711"))
    }

    @Test
    fun `catastrophic WR authority precedes legacy soft probe stack`() {
        val fdg = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        val guard = File("src/main/kotlin/com/lifecyclebot/engine/CanonicalAdaptiveEntryGuard6711.kt").readText()
        val authority = fdg.indexOf("FDG_CANONICAL_WR_COLLAPSE_BLOCK_6711")
        val softStack = fdg.indexOf("val isCGrade = candidate.setupQuality")
        assertTrue(authority >= 0 && softStack >= 0 && authority < softStack)
        assertTrue(fdg.contains("return FinalDecision("))
        assertTrue(guard.contains("s.rollingWrPct < 20.0 -> 65.0"))
        assertTrue(guard.contains("s.rollingWrPct < 30.0 -> 60.0"))
        assertTrue(guard.contains("s.rollingWrPct < 40.0 -> 55.0"))
    }
}
''')

print("V5.0.6711 causal learning + WR authority source repair staged")
