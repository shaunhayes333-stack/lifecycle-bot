package com.lifecyclebot.engine.learning

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.1321 — Train-First Learning Policy router.
 *
 * Replaces FinalDecisionGate's binary `allowed` / `blockReason` shape
 * with explicit routing verdicts.
 */
object FdgRouteVerdict {

    enum class Verdict(val tag: String) {
        ALLOW_NORMAL          ("ALLOW_NORMAL"),
        ALLOW_REDUCED_SIZE    ("ALLOW_REDUCED_SIZE"),
        ALLOW_PAPER_MICRO     ("ALLOW_PAPER_MICRO"),
        ROUTE_SHADOW_TRACK    ("ROUTE_SHADOW_TRACK"),
        ROUTE_TRAIN_ONLY      ("ROUTE_TRAIN_ONLY"),
        BLOCK_INVALID_DATA    ("BLOCK_INVALID_DATA"),
        BLOCK_HARD_SAFETY     ("BLOCK_HARD_SAFETY"),
        BLOCK_MODE_AUTHORITY  ("BLOCK_MODE_AUTHORITY"),
        BLOCK_DUPLICATE       ("BLOCK_DUPLICATE"),
        BLOCK_OPERATOR_DISABLED("BLOCK_OPERATOR_DISABLED");

        val executable: Boolean
            get() = this == ALLOW_NORMAL || this == ALLOW_REDUCED_SIZE || this == ALLOW_PAPER_MICRO

        val trainable: Boolean
            get() = this != BLOCK_INVALID_DATA && this != BLOCK_OPERATOR_DISABLED
    }

    private val counts = ConcurrentHashMap<String, AtomicLong>()
    private val laneVerdictCounts = ConcurrentHashMap<String, AtomicLong>()

    fun decide(
        lane: String,
        scoreBand: String,
        hardReason: String? = null,
        modeAuthorityBlock: Boolean = false,
        operatorDisabled: Boolean = false,
        duplicate: Boolean = false,
        invalidData: Boolean = false,
    ): Verdict {
        if (invalidData) return Verdict.BLOCK_INVALID_DATA
        if (operatorDisabled) return Verdict.BLOCK_OPERATOR_DISABLED
        if (modeAuthorityBlock) return Verdict.BLOCK_MODE_AUTHORITY
        if (duplicate) return Verdict.BLOCK_DUPLICATE
        if (hardReason != null && hardReason.isNotBlank()) return Verdict.BLOCK_HARD_SAFETY

        val policy = LanePolicy.effectiveState(lane, scoreBand)
        // V5.9.1325 — TRAIN-FIRST DOCTRINE ENFORCEMENT.
        // Operator mandate: "v3/fdg is the final authority. never stop trading.
        // 1000+ quality trades a day. learn the right way." Any policy state
        // that is NOT a hard-safety failure must keep producing trades (at micro
        // size) so the bot keeps learning. SHADOW_TRACK_ONLY and TRAIN_ONLY_NO_OPEN
        // previously returned non-executable verdicts → caller set blockReason →
        // bot choked (only 6 executions at 722 trades). Collapse them to
        // ALLOW_PAPER_MICRO so the train-first invariant ("trainability ≠ executability,
        // but bot never stops trading") holds.
        return when (policy) {
            LanePolicy.State.NORMAL_EXECUTION       -> Verdict.ALLOW_NORMAL
            LanePolicy.State.PROMOTION_CANDIDATE    -> Verdict.ALLOW_NORMAL
            LanePolicy.State.REDUCED_SIZE_EXECUTION -> Verdict.ALLOW_REDUCED_SIZE
            LanePolicy.State.DEMOTION_CANDIDATE     -> Verdict.ALLOW_REDUCED_SIZE
            LanePolicy.State.PAPER_MICRO_EXECUTION  -> Verdict.ALLOW_PAPER_MICRO
            LanePolicy.State.RETRAINING             -> Verdict.ALLOW_PAPER_MICRO
            LanePolicy.State.SHADOW_TRACK_ONLY      -> Verdict.ALLOW_PAPER_MICRO  // V5.9.1325: never stop trading
            LanePolicy.State.TRAIN_ONLY_NO_OPEN     -> Verdict.ALLOW_PAPER_MICRO  // V5.9.1325: never stop trading
            LanePolicy.State.INVALID_UNTRADEABLE    -> Verdict.BLOCK_INVALID_DATA
        }
    }

    fun record(verdict: Verdict, lane: String) {
        bump(counts, verdict.tag)
        bump(laneVerdictCounts, "${verdict.tag}|${lane.uppercase().take(24)}")
        try {
            PipelineHealthCollector.labelInc("ROUTE_${verdict.tag}")
            PipelineHealthCollector.labelInc("ROUTE_${verdict.tag}|${lane.uppercase().take(24)}")
        } catch (_: Throwable) {}
    }

    fun sizeMultiplier(verdict: Verdict, lane: String, scoreBand: String): Double {
        if (!verdict.executable) return 0.0
        val base = LanePolicy.effectiveExecutionWeight(lane, scoreBand)
        return when (verdict) {
            Verdict.ALLOW_NORMAL          -> base.coerceAtLeast(0.85)
            Verdict.ALLOW_REDUCED_SIZE    -> base.coerceIn(0.30, 0.70)
            Verdict.ALLOW_PAPER_MICRO     -> base.coerceIn(0.05, 0.15)
            else                          -> 0.0
        }
    }

    data class Snapshot(
        val verdictCounts: Map<String, Long>,
        val laneVerdictCounts: Map<String, Long>,
    )

    fun snapshot(): Snapshot = Snapshot(
        verdictCounts = counts.mapValues { it.value.get() },
        laneVerdictCounts = laneVerdictCounts.mapValues { it.value.get() },
    )

    fun resetForTests() {
        counts.clear()
        laneVerdictCounts.clear()
    }

    private fun bump(map: ConcurrentHashMap<String, AtomicLong>, key: String) {
        var c = map[key]
        if (c == null) c = map.computeIfAbsent(key) { AtomicLong(0L) }
        c.incrementAndGet()
    }

    data class RoutedDecision(
        val verdict: Verdict,
        val proceedToOpen: Boolean,
        val sizeMultiplier: Double,
        val routeReasonForLog: String,
    )

    fun routeLearnedDangerBucket(
        lane: String,
        scoreBand: String,
        evidenceLabel: String,
        operatorDisabled: Boolean = false,
    ): RoutedDecision {
        val v = decide(
            lane = lane,
            scoreBand = scoreBand,
            operatorDisabled = operatorDisabled,
        )
        // V5.9.1325 — REGRESSION FIX (Base44/Emergent regression that collapsed WR to 10.3%, -8.43 SOL).
        // Train-First (1321/1322) replaced the danger-bucket HARD VETO with routing that still
        // OPENS at PAPER_MICRO/REDUCED_SIZE. In a DUMP regime that turned proven dead buckets
        // (SHITCOIN|S61+ 39L/0W, MANIPULATED|S61+ 20L/1W, TREASURY|S61+) back into live bleeders.
        // DOCTRINE: soft-shape > veto, BUT proven NET-NEGATIVE bleeders must not keep OPENING in a
        // hostile regime. We DOWNGRADE a proceeding verdict to SHADOW_TRACK (non-opening): the
        // candidate STILL trains (the caller's !proceedToOpen branch writes the NoTradeObservation
        // row), so Train-First's learning universe is 100% preserved — we just stop paying tuition
        // with live/paper capital on buckets we already know are dead.
        var effectiveV = v
        if (v.executable) {
            val regimeDump = try {
                com.lifecyclebot.engine.RegimeDetector.currentRegime() == com.lifecyclebot.engine.RegimeDetector.Regime.DUMP
            } catch (_: Throwable) { false }
            val provenDead = try {
                val band = scoreBand.substringAfterLast('|').ifBlank { scoreBand }
                val midScore = when {
                    band.contains("61") -> 70; band.contains("41") -> 50
                    band.contains("26") -> 33; band.contains("11") -> 18; else -> 5
                }
                val b = com.lifecyclebot.engine.LosingPatternMemory.stats(lane, midScore)
                b.isDangerous && b.meanPnl < 0.0 && b.losses >= 20 && b.wins <= 1
            } catch (_: Throwable) { false }
            if (provenDead || regimeDump) {
                effectiveV = Verdict.ROUTE_SHADOW_TRACK
                try { ErrorLogger.info("FDG", "🛑 DANGER_BUCKET_REGIME_GUARD lane=$lane bucket=$scoreBand regimeDump=$regimeDump provenDead=$provenDead → SHADOW_TRACK (was ${v.tag})") } catch (_: Throwable) {}
            }
        }
        val vFinal = effectiveV
        record(vFinal, lane)
        val mult = sizeMultiplier(vFinal, lane, scoreBand)
        val proceed = vFinal.executable && mult > 0.0
        val log = "LEARNED_DANGER_BUCKET_${routeTagFor(vFinal)}:${evidenceLabel.take(80)}"
        try {
            ErrorLogger.info("FDG", "🧭 $log lane=$lane bucket=$scoreBand size×${"%.2f".format(mult)}")
        } catch (_: Throwable) {}
        return RoutedDecision(
            verdict = vFinal,
            proceedToOpen = proceed,
            sizeMultiplier = mult,
            routeReasonForLog = log,
        )
    }

    private fun routeTagFor(v: Verdict): String = when (v) {
        Verdict.ALLOW_NORMAL         -> "ROUTED_NORMAL"
        Verdict.ALLOW_REDUCED_SIZE   -> "SIZE_DAMPED"
        Verdict.ALLOW_PAPER_MICRO    -> "MICRO_PAPER"
        Verdict.ROUTE_SHADOW_TRACK   -> "SHADOWED"
        Verdict.ROUTE_TRAIN_ONLY     -> "TRAIN_ONLY"
        Verdict.BLOCK_INVALID_DATA   -> "BLOCKED_INVALID_DATA"
        Verdict.BLOCK_HARD_SAFETY    -> "BLOCKED_HARD_SAFETY"
        Verdict.BLOCK_MODE_AUTHORITY -> "BLOCKED_MODE_AUTHORITY"
        Verdict.BLOCK_DUPLICATE      -> "BLOCKED_DUPLICATE"
        Verdict.BLOCK_OPERATOR_DISABLED -> "BLOCKED_OPERATOR_DISABLED"
    }
}
