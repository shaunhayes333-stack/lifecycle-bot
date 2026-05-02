package com.lifecyclebot.engine

/**
 * V5.9.422 — QUALITY LADDER
 *
 * Operator intent:
 *   "scaling to 35% winrate to 3000 trades then 50-85% after 5000 but
 *    still keep volume … it should all be fluid anyway via the pipeline"
 *
 * Replaces the binary `FreeRangeMode.emergencyGraduated()` panic-brake
 * with a progressive tier system that scales defensive guards AND size
 * in lockstep with how the bot is actually performing vs its expected
 * win-rate for its current trade-count band.
 *
 *   Trade band         Target WR            Max tier
 *   ──────────────────────────────────────────────────
 *   0 – 500            0 % (discovery)      0 (wide open)
 *   500 – 1500         15 % → 25 %          2
 *   1500 – 3000        25 % → 35 %          3
 *   3000 – 4000        35 % → 50 %          4
 *   4000 – 5000        50 % → 70 %          5
 *   5000 – 7000        70 % → 85 %          5
 *   7000+              85 %                 5
 *
 * Tiers:
 *   0 — wide open (free-range). No guards fire. Size ×1.00.
 *   1 — TRIPLE_DANGER hard-block (2-5 AM + cold narrative + thin liq).
 *       Size ×0.90.
 *   2 — Tier 1 + HardRugPreFilter fires even in paper mode. Size ×0.80.
 *   3 — Tier 2 + MemeLossStreakGuard active + entry cooldowns respected.
 *       Size ×0.70.
 *   4 — Tier 3 + StrategyTrustAI distrust-pause respected.  Size ×0.60.
 *   5 — Tier 4 + strict live-mode thresholds across every guard.
 *       Size ×0.50.
 *
 * Tier activates ONLY when actualWR < targetWR. If the bot is
 * performing at/above the ladder expectation for its phase it stays on
 * Tier 0 — performance earns freedom. Volume is preserved at every
 * tier (size floor 0.01 SOL paper / 0.05 live is unchanged — the
 * ladder multiplier narrows around the base size but never zeroes it).
 *
 * Thread-safety: all reads are O(1) snapshots of TradeHistoryStore.
 * Fail-open: every call wrapped in try/catch → Tier 0 (wide open) so a
 * corrupt history cannot accidentally clamp the bot.
 */
object QualityLadder {

    private const val TAG = "QualityLadder"

    // ── Public API ──────────────────────────────────────────────────

    /** Current defensive tier 0..5. 0 = wide open. */
    fun tier(): Int = computeTier()

    /** Target win-rate percentage (0-100) for the current trade count. */
    fun targetWr(): Double = try {
        targetWrForTrades(TradeHistoryStore.getLifetimeStats().totalSells)
    } catch (_: Throwable) { 0.0 }

    /** Size multiplier the pipeline applies to proposed entry size. */
    fun sizeMultiplier(): Double {
        val t = tier()
        // Linear: Tier 0 → 1.00, Tier 5 → 0.50
        return (1.0 - t * 0.10).coerceIn(0.5, 1.0)
    }

    /** Human-readable one-liner for the UI / logs. Never throws. */
    fun statusLine(): String = try {
        val snap = TradeHistoryStore.getLifetimeStats()
        val trades = snap.totalSells
        val actual = snap.winRate
        val target = targetWrForTrades(trades)
        val t = computeTier()
        val icon = when (t) {
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

    // ── Internals ───────────────────────────────────────────────────

    internal fun targetWrForTrades(trades: Int): Double = when {
        trades < 500  -> 0.0
        trades < 1500 -> lerp(15.0, 25.0, (trades - 500).toDouble() / 1000.0)
        trades < 3000 -> lerp(25.0, 35.0, (trades - 1500).toDouble() / 1500.0)
        trades < 4000 -> lerp(35.0, 50.0, (trades - 3000).toDouble() / 1000.0)
        trades < 5000 -> lerp(50.0, 70.0, (trades - 4000).toDouble() / 1000.0)
        trades < 7000 -> lerp(70.0, 85.0, (trades - 5000).toDouble() / 2000.0)
        else          -> 85.0
    }

    private fun phaseCap(trades: Int): Int = when {
        trades < 500  -> 0
        trades < 1500 -> 2
        trades < 3000 -> 3
        trades < 4000 -> 4
        else          -> 5
    }

    private fun gapTier(gapPp: Double): Int = when {
        gapPp < 5.0  -> 1
        gapPp < 10.0 -> 2
        gapPp < 15.0 -> 3
        gapPp < 25.0 -> 4
        else         -> 5
    }

    private fun computeTier(): Int = try {
        val snap = TradeHistoryStore.getLifetimeStats()
        val trades = snap.totalSells
        if (trades < 500) return 0
        val target = targetWrForTrades(trades)
        val actual = snap.winRate
        if (actual >= target) return 0                        // earned freedom
        val cap = phaseCap(trades)
        kotlin.math.min(gapTier(target - actual), cap)
    } catch (_: Throwable) {
        0  // fail-open
    }

    private fun lerp(a: Double, b: Double, t: Double): Double =
        a + (b - a) * t.coerceIn(0.0, 1.0)
}
