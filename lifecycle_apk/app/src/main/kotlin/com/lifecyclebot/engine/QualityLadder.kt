package com.lifecyclebot.engine

/**
 * V5.9.716 — QUALITY LADDER (early-activation rebuild)
 *
 * Previously only activated at 5000 trades (phaseCap returned 0 before
 * 5000). This meant coaching and size guidance were completely silent for
 * the entire bootstrap / learning period, allowing the WR hole to deepen
 * unchecked.
 *
 * V5.9.716 change: phaseCap now mirrors the FreeRangeMode phase schedule.
 * The ladder starts coaching at 500 trades and scales to full tiers by
 * 5000, giving the AI quality feedback early enough to actually course-
 * correct WR before the earnedCeiling trap locks in.
 *
 * KEY CONSTRAINT: Tiers ONLY modulate size via sizeMultiplier() — they
 * NEVER completely zero out a lane. sizeMultiplier() floors at 0.50.
 * Tier 0 = 1.00×, Tier 5 = 0.50×, linear between.
 *
 * Tiers:
 *   0 — wide open. All guards off. Size ×1.00.
 *   1 — TRIPLE_DANGER hard-block (2-5 AM + cold narrative + thin liq).
 *       Size ×0.90.
 *   2 — Tier 1 + HardRugPreFilter fires even in paper mode. Size ×0.80.
 *   3 — Tier 2 + MemeLossStreakGuard active + entry cooldowns respected.
 *       Size ×0.70.
 *   4 — Tier 3 + StrategyTrustAI distrust-pause respected.  Size ×0.60.
 *   5 — Tier 4 + strict live-mode thresholds. Size ×0.50.
 */
object QualityLadder {

    private const val TAG = "QualityLadder"

    // ── Public API ───────────────────────────────────────────────────
    fun tier(): Int = computeTier()

    fun targetWr(): Double = try {
        FreeRangeMode.phaseTargetWr(TradeHistoryStore.getLifetimeStats().totalSells)
    } catch (_: Throwable) { 0.0 }

    /** Size multiplier 0.50–1.00. Never zeros a lane. */
    fun sizeMultiplier(): Double {
        val t = tier()
        return (1.0 - t * 0.10).coerceIn(0.5, 1.0)
    }

    fun statusLine(): String = try {
        val snap   = TradeHistoryStore.getLifetimeStats()
        val trades = snap.totalSells
        val actual = snap.winRate
        val target = FreeRangeMode.phaseTargetWr(trades)
        val t      = computeTier()
        val icon   = when (t) {
            0    -> "🔓"
            1, 2 -> "🟡"
            3, 4 -> "🟠"
            else -> "🔴"
        }
        "$icon TIER $t · $trades trades · WR=${"%.1f".format(actual)}% " +
            "(target ${"%.1f".format(target)}%) · size×${"%.2f".format(sizeMultiplier())}"
    } catch (_: Throwable) {
        "🔓 TIER 0 · (history unavailable)"
    }

    // V5.9.462 compat — public for UI callers
    fun targetWrForTrades(trades: Int): Double = FreeRangeMode.phaseTargetWr(trades)

    // ── Internals ────────────────────────────────────────────────────
    /**
     * V5.9.716 — phaseCap now activates at 500 trades (not 5000).
     *
     * Phase 0  (  0– 500): cap=0, coaching only, no size reduction
     * Phase 1  (500–1000): cap=1, mild tier ceiling
     * Phase 2  (1000–3000): cap=3, mid tiers available
     * Phase 3  (3000–5000): cap=4, deep tiers available
     * Phase 4  (5000+):     cap=5, full ladder active
     *
     * At every phase: tier only activates when actualWR < targetWR.
     * If the bot is on target → tier 0 regardless of cap.
     */
    private fun phaseCap(trades: Int): Int = when {
        trades < 500  -> 0   // pure exploration — never penalise
        trades < 1000 -> 1   // soft max: tier 1 (lose-streak brakes)
        trades < 3000 -> 3   // mid max: up to tier 3
        trades < 5000 -> 4   // full max: up to tier 4
        else          -> 5   // master: all tiers active
    }

    private fun gapTier(gapPp: Double): Int = when {
        gapPp < 5.0  -> 1
        gapPp < 10.0 -> 2
        gapPp < 15.0 -> 3
        gapPp < 25.0 -> 4
        else         -> 5
    }

    private fun computeTier(): Int {
        return try {
            val snap   = TradeHistoryStore.getLifetimeStats()
            val trades = snap.totalSells
            val cap    = phaseCap(trades)
            if (cap == 0) return 0          // exploration — no tiers
            val target = FreeRangeMode.phaseTargetWr(trades)
            val actual = snap.winRate
            if (actual >= target) return 0  // on-target → freedom
            kotlin.math.min(gapTier(target - actual), cap)
        } catch (_: Throwable) {
            0  // fail-open
        }
    }
}
