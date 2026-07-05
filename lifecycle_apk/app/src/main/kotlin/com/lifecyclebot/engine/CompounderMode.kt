package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6121 — CompounderMode
 *
 * OPERATOR DIRECTIVE: "$100 to a million should be piss easy for the AATE
 * memetrader. Daily 2x-5x compounding target."
 *
 * WHAT THIS DOES
 * ══════════════
 *
 * Detects rolling GREEN STREAKS (consecutive winning live closes) and
 * scales the next entry's size accordingly. When the bot is HOT, it must
 * press the advantage. When it goes cold, it must halve down. This is
 * the Kelly-inspired compounding lever.
 *
 * Streak size multiplier ladder:
 *   • 0-2 green closes:  1.00×    (no bonus, warm-up)
 *   • 3-4 green closes:  1.15×    (hot hand emerging)
 *   • 5-6 green closes:  1.35×    (compounding zone)
 *   • 7-9 green closes:  1.60×    (max-press zone)
 *   • 10+ green closes:  1.85×    (kelly-max cap)
 *
 * Streak resets on the FIRST red close. Streak is measured from the
 * most recent 12 live SELL rows.
 *
 * COOLDOWN GUARD: if the streak breaks and pnl of the losing close is
 * < -20%, apply a 0.60× damper on the NEXT entry (single-shot). This is
 * the "just got smoked, don't chase" protection.
 *
 * DOCTRINE #86 — fail-open: any error yields 1.0 multiplier.
 *
 * INTEGRATION POINT: FinalDecisionGate calls `sizeMultiplier(recentSells)`
 * as the last sizing shape before the final coerce. Non-persistent —
 * computed fresh from journal on every call (uses TradeHistoryStore
 * inside, so it's already correct after restart).
 */
object CompounderMode {

    // ── Tunables ────────────────────────────────────────────────────────
    private const val LOOKBACK_WINDOW = 12
    private const val WIN_PNL_THRESHOLD = 5.0        // > +5% pnl = win
    private const val COOLDOWN_LOSS_THRESHOLD = -20.0
    private const val COOLDOWN_DAMPER = 0.60

    // ── State ───────────────────────────────────────────────────────────
    private val cooldownActive = AtomicInteger(0)      // 1 = next entry gets damper
    private val lastRecomputedMs = AtomicLong(0L)
    private const val RECOMPUTE_INTERVAL_MS = 5_000L    // cheap read; recompute at most every 5s

    @Volatile private var lastStreak: Int = 0
    @Volatile private var lastMultiplier: Double = 1.0

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Compute the size multiplier for the next entry based on the current
     * green streak. `recentSells` is expected in descending-time order.
     *
     * @return multiplier in [0.60, 1.85]; 1.0 when neutral.
     */
    fun sizeMultiplier(recentSells: List<Trade>): Double {
        return try {
            val now = System.currentTimeMillis()
            if (now - lastRecomputedMs.get() < RECOMPUTE_INTERVAL_MS) return lastMultiplier

            val window = recentSells.take(LOOKBACK_WINDOW)
            if (window.isEmpty()) {
                lastStreak = 0
                lastMultiplier = 1.0
                lastRecomputedMs.set(now)
                return 1.0
            }

            // Count consecutive wins from the head (most recent first).
            var streak = 0
            for (t in window) {
                if (t.pnlPct > WIN_PNL_THRESHOLD) streak += 1 else break
            }

            // Latest close: if it was a big red loss, arm cooldown.
            val head = window.first()
            if (head.pnlPct < COOLDOWN_LOSS_THRESHOLD) {
                cooldownActive.set(1)
            }

            val streakMult = when {
                streak >= 10 -> 1.85
                streak >= 7  -> 1.60
                streak >= 5  -> 1.35
                streak >= 3  -> 1.15
                else         -> 1.00
            }

            // Cooldown consumes on any next call (single-shot).
            val cooldownMult = if (cooldownActive.compareAndSet(1, 0)) COOLDOWN_DAMPER else 1.0

            val finalMult = (streakMult * cooldownMult).coerceIn(0.50, 1.90)

            if (finalMult != lastMultiplier && streak != lastStreak) {
                try {
                    ForensicLogger.lifecycle(
                        "COMPOUNDER_MODE_6121",
                        "streak=$streak mult=${"%.2f".format(finalMult)} " +
                        "(streakMult=${"%.2f".format(streakMult)} cooldownMult=${"%.2f".format(cooldownMult)})",
                    )
                    if (finalMult > 1.0) PipelineHealthCollector.labelInc("COMPOUNDER_BOOST_6121")
                    if (finalMult < 1.0) PipelineHealthCollector.labelInc("COMPOUNDER_COOLDOWN_6121")
                } catch (_: Throwable) {}
            }

            lastStreak = streak
            lastMultiplier = finalMult
            lastRecomputedMs.set(now)
            finalMult
        } catch (_: Throwable) { 1.0 }
    }

    fun snapshot(): Pair<Int, Double> = lastStreak to lastMultiplier
}
