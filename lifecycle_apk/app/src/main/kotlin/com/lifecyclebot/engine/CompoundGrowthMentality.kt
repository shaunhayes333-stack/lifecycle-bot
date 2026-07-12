package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * COMPOUND GROWTH MENTALITY — V5.0.6238
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Operator directive:
 *   "the overall mentality must be compound and growth, while trading smart
 *    and somewhat defensively in paper and live."
 *
 * This is NOT a gate. It is a bot-wide ADVISORY MENTALITY STATE that the
 * LLM / super-AGI / SSI / meta-cog / sentience layers all read as a shared
 * bias signal. Each brain applies it in its own way — sentience frames the
 * monologue, LLM biases risk-mode, meta-cog nudges variance against
 * catastrophic drawdown, size shapers respect the defensive floor, position
 * holders press winners further.
 *
 * DERIVED SIGNALS:
 *   • biasGrowth       [0..1]  — how aggressively to press winners
 *   • biasDefensive    [0..1]  — how firmly to guard against giveback
 *   • compoundFactor   [0.7..1.5]  — win-realisation-driven sizing multiplier
 *   • ddReserveFactor  [0.55..1.0] — trim in current drawdown, protect equity
 *   • mentalityTag     String  — one-word snapshot for UI/logs
 *
 * The two biases are NEVER both zero — the mentality is *always* growth-
 * flavoured, with the defensive tilt scaling with drawdown and losing
 * streak.
 *
 * DEPENDENCY MODEL:
 *   • ReportingHub (or any well-informed caller) invokes updateTruth() with
 *     the freshest WR / drawdown / streak / corpus figures on each tick.
 *   • Any brain layer calls snapshot() from anywhere without needing a
 *     Context — the mentality state is computed lazily from the last
 *     updateTruth() input.
 *   • Fail-open: if updateTruth() has never been called, the snapshot
 *     returns a neutral growth-tilted default so callers are never blocked.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object CompoundGrowthMentality {

    data class MentalityState(
        val biasGrowth: Double,
        val biasDefensive: Double,
        val compoundFactor: Double,
        val ddReserveFactor: Double,
        val mentalityTag: String,
        val currentWR: Double,          // 0..1
        val currentDDPct: Double,       // 0..100
        val consecutiveLosses: Int,
        val winCorpusSize: Int,
        val computedAtMs: Long,
    )

    private data class Truth(
        val wr: Double,
        val ddPct: Double,
        val consecLosses: Int,
        val corpus: Int,
        val ts: Long,
    )

    // Neutral bootstrap truth — growth-leaning so we never block a fresh bot.
    private val lastTruth = AtomicReference(Truth(wr = 0.50, ddPct = 0.0, consecLosses = 0, corpus = 0, ts = 0L))
    private val cached = AtomicReference<MentalityState?>(null)

    /** Fed from ReportingHub (or any well-informed caller) each tick. */
    fun updateTruth(wr: Double, ddPct: Double, consecutiveLosses: Int, winCorpusSize: Int) {
        lastTruth.set(Truth(
            wr = wr.coerceIn(0.0, 1.0),
            ddPct = ddPct.coerceIn(0.0, 100.0),
            consecLosses = consecutiveLosses.coerceAtLeast(0),
            corpus = winCorpusSize.coerceAtLeast(0),
            ts = System.currentTimeMillis(),
        ))
        cached.set(null) // force recompute on next snapshot()
    }

    /** The single source of truth. Any brain layer calls this. */
    fun snapshot(): MentalityState {
        cached.get()?.let { return it }
        val fresh = compute(lastTruth.get())
        cached.set(fresh)
        return fresh
    }

    private fun compute(t: Truth): MentalityState {
        val wr = t.wr
        val ddPct = t.ddPct
        val consecLosses = t.consecLosses
        val corpus = t.corpus

        // V5.0.6244 — RECLAIM_MODE. Prior logic slammed the bot into
        // DEFENSIVE_HOLD after a natural 26.7% pullback from an all-time
        // high even though the underlying engine WR was 49% and PF 7.0.
        // Result: compound×=0.70, growth=0.06 — the healthy engine got
        // brake-locked. Reclaim mode softens the defensive tilt when the
        // engine's actually still winning through a normal drawdown so
        // the pullback becomes a re-compound instead of a stall.
        val isReclaimMode = wr >= 0.45 && ddPct >= 10.0 && ddPct <= 40.0 && corpus >= 20 && consecLosses <= 4

        // ── Bias axes ────────────────────────────────────────────────────────
        val growthFromWR = ((wr - 0.30) / 0.60).coerceIn(0.0, 1.0)    // WR 30% → 0.0 ; WR 90% → 1.0
        val growthFromCorpus = min(1.0, corpus / 50.0)                // 50 winning fingerprints → full corpus press
        val ddDenom = if (isReclaimMode) 60.0 else 30.0               // reclaim halves the DD penalty
        val growthPenaltyDD = 1.0 - min(1.0, ddPct / ddDenom)
        val growthPenaltyStreak = 1.0 - min(1.0, consecLosses / 8.0)   // 8 consec losses wipes growth press
        val biasGrowthRaw = ((0.60 * growthFromWR) + (0.20 * growthFromCorpus) + 0.20)
            .coerceIn(0.0, 1.0) * growthPenaltyDD * growthPenaltyStreak
        val biasGrowth = if (isReclaimMode) max(0.30, biasGrowthRaw) else biasGrowthRaw

        val defensiveFromDD = min(1.0, ddPct / 20.0)
        val defensiveFromStreak = min(1.0, consecLosses / 5.0)
        val defensiveFloor = 0.30                                     // always ≥30% defensive tilt
        val biasDefensiveRaw = max(defensiveFloor, max(defensiveFromDD, defensiveFromStreak))
            .coerceIn(0.0, 1.0)
        // In reclaim mode we cap defensive so it never crushes the growth press.
        val biasDefensive = if (isReclaimMode) min(0.70, biasDefensiveRaw) else biasDefensiveRaw

        // ── Compound factor: press winners when the edge is proven ───────────
        val compoundFactorRaw = when {
            wr >= 0.70 && ddPct < 10.0 && corpus >= 20 -> 1.50
            wr >= 0.60 && ddPct < 15.0                 -> 1.25
            wr >= 0.50 && ddPct < 20.0                 -> 1.10
            wr >= 0.35 && ddPct < 25.0                 -> 1.00
            else                                       -> 0.70
        }
        // Reclaim floor: healthy engine on a normal drawdown never drops below 0.85.
        val compoundFactor = if (isReclaimMode) max(0.85, compoundFactorRaw) else compoundFactorRaw

        // ── Drawdown reserve: how much to trim while under water ─────────────
        val ddReserveFactor = (1.0 - (ddPct / 60.0)).coerceIn(0.55, 1.0)

        val mentalityTag = when {
            biasGrowth >= 0.75 && biasDefensive <= 0.40 -> "COMPOUND_PRESS"
            isReclaimMode                               -> "RECLAIM_MODE"
            biasGrowth >= 0.55 && biasDefensive <= 0.55 -> "GROWTH_BIAS"
            biasGrowth >= 0.35                          -> "STEADY_COMPOUND"
            biasDefensive >= 0.70                       -> "DEFENSIVE_HOLD"
            else                                        -> "CAUTIOUS_LEARN"
        }

        return MentalityState(
            biasGrowth = biasGrowth,
            biasDefensive = biasDefensive,
            compoundFactor = compoundFactor,
            ddReserveFactor = ddReserveFactor,
            mentalityTag = mentalityTag,
            currentWR = wr,
            currentDDPct = ddPct,
            consecutiveLosses = consecLosses,
            winCorpusSize = corpus,
            computedAtMs = System.currentTimeMillis(),
        )
    }

    /** Advisory size multiplier all sizing stacks can multiply into their product. */
    fun sizeAdvisory(): Double {
        val s = snapshot()
        return (s.compoundFactor * s.ddReserveFactor).coerceIn(0.5, 2.0)
    }

    /** Advisory tick for AGI/LLM personality layers. Range [-1..+1]. */
    fun agiBias(): Double {
        val s = snapshot()
        return (s.biasGrowth - s.biasDefensive).coerceIn(-1.0, 1.0)
    }

    fun statusLine(): String {
        val s = snapshot()
        return "V5.0.6238_MENTALITY: ${s.mentalityTag} growth=${"%.2f".format(s.biasGrowth)} " +
            "defensive=${"%.2f".format(s.biasDefensive)} compound×=${"%.2f".format(s.compoundFactor)} " +
            "ddReserve=${"%.2f".format(s.ddReserveFactor)} · " +
            "wr=${"%.1f".format(s.currentWR * 100)}% dd=${"%.1f".format(s.currentDDPct)}% " +
            "streakL=${s.consecutiveLosses} corpus=${s.winCorpusSize}"
    }
}
