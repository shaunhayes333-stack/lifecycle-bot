package com.lifecyclebot.engine

/**
 * V5.9.408 — FREE-RANGE LEARNING MODE
 *
 * Single source of truth queried by every guard / gate / cap in the
 * codebase to decide whether the bot is currently in "wide-open"
 * maximum-learning-exposure mode.
 *
 * User intent (from the operator):
 *   "remove cool-downs, blocks, sizing caps, distrust pause. make it
 *    wide open until 3000 trades for maximum learning exposure.
 *    increase the adjustment ability from trade 50 onwards but keep the
 *    light-to-full free-range control to 3000 and beyond. only if
 *    winrate is about 50% and bot is super profitable otherwise
 *    continue until 5000."
 *
 * Implementation:
 *   - Free-range is UNCONDITIONALLY on for the first 3000 lifetime sells.
 *   - Between trade 3000 and 5000, free-range EXITS only if the bot is
 *     decisively healthy: lifetime WR ≥ 50 % AND realized PnL ≥ +5 SOL
 *     (our "super profitable" bar).  If not healthy, stay wide-open.
 *   - At or above trade 5000 free-range is force-OFF — the bot now has
 *     enough data for normal guards to do their job.
 *
 *   - adjustmentStrength() returns the meta-learning nudge scale that
 *     LLM tuners and SentienceHooks multiply their parameter changes
 *     by.  0.0 for trade 0-49 (pure discovery, no tuning), linear ramp
 *     from 0.05 @ trade 50 → 1.0 @ trade 3000, then 1.0 forever.
 *
 * Thread-safety: all reads are O(1) snapshots of TradeHistoryStore.
 * Fail-open: every call is wrapped in try/catch and defaults to
 * wide-open so a corrupt history cannot accidentally clamp the bot.
 */
object FreeRangeMode {

    private const val TAG = "FreeRangeMode"

    // ── Thresholds ──────────────────────────────────────────────────
    private const val WIDE_OPEN_FLOOR_TRADES = 3000    // always wide-open below this
    private const val WIDE_OPEN_CEIL_TRADES  = 5000    // force-off at/above this
    private const val GRADUATE_WIN_RATE_PCT  = 50.0    // needed to graduate between 3000-5000
    private const val GRADUATE_MIN_PNL_SOL   = 5.0     // "super profitable" bar
    private const val TUNER_RAMP_START       = 50      // adjustments begin here
    private const val TUNER_RAMP_END         = 3000    // adjustments reach full strength here

    // V5.9.421 — EMERGENCY-GRADUATE thresholds.
    // User approved: the 3000-trade floor was causing meme-lane to bleed at
    // 10% WR / 668L / 715S for 1462 trades straight. If the bot is clearly
    // drowning (≥500 trades AND WR <15%), force free-range OFF so the
    // normal guards, rug filter, and loss-streak brakes come back online
    // BEFORE we hit 3000 trades. This preserves the original operator
    // intent ("wide open for learning") while bounding the downside.
    private const val EMERGENCY_MIN_TRADES   = 500
    private const val EMERGENCY_MAX_WR_PCT   = 15.0

    // ── Operator override (UI "KILL FREE-RANGE" button can flip this) ─
    @Volatile private var operatorForceOff: Boolean = false
    @Volatile private var operatorForceOn:  Boolean = false

    /** Manually take the bot out of free-range even if trade count < 3000. */
    fun forceOff() { operatorForceOff = true; operatorForceOn = false }
    /** Manually hold free-range on even past 5000 trades. */
    fun forceOn()  { operatorForceOn  = true; operatorForceOff = false }
    fun clearOverride() { operatorForceOff = false; operatorForceOn = false }

    /**
     * Main gate. True = every guard is bypassed and the bot takes every
     * signal it can legally open.
     */
    fun isWideOpen(): Boolean {
        if (operatorForceOn)  return true
        if (operatorForceOff) return false
        return try {
            val snap = TradeHistoryStore.getLifetimeStats()
            val trades = snap.totalSells
            // V5.9.421 — Emergency-graduate. Must fire BEFORE the floor check
            // or we'd be stuck below 3000 with a catastrophic WR forever.
            if (trades >= EMERGENCY_MIN_TRADES && snap.winRate < EMERGENCY_MAX_WR_PCT) {
                return false
            }
            when {
                trades < WIDE_OPEN_FLOOR_TRADES  -> true
                trades >= WIDE_OPEN_CEIL_TRADES  -> false
                // 3000 <= trades < 5000 — graduate only if super healthy
                else -> !(snap.winRate >= GRADUATE_WIN_RATE_PCT &&
                          snap.realizedPnlSol >= GRADUATE_MIN_PNL_SOL)
            }
        } catch (_: Throwable) {
            // Fail-open: a broken trade store should NOT clamp the bot.
            true
        }
    }

    /**
     * V5.9.421 — true iff the emergency-graduate rule is what's keeping
     * us out of wide-open mode (as opposed to the 3000-floor / 5000-ceil
     * logic). Exposed so callers (UI badges, post-mortem logs, the
     * RugPreFilter paper-mode bypass) can distinguish "bot graduated
     * voluntarily" from "bot was forced to stop by the panic brake".
     */
    fun emergencyGraduated(): Boolean {
        if (operatorForceOn || operatorForceOff) return false
        return try {
            val snap = TradeHistoryStore.getLifetimeStats()
            snap.totalSells >= EMERGENCY_MIN_TRADES && snap.winRate < EMERGENCY_MAX_WR_PCT
        } catch (_: Throwable) { false }
    }

    /**
     * Scale multiplier for LLM/Sentience parameter tuning. 0.0 = no
     * tuning at all (pure exploration), 1.0 = full tuning authority.
     * Always returns a value in [0.0, 1.0].
     */
    fun adjustmentStrength(): Double {
        return try {
            val trades = TradeHistoryStore.getLifetimeStats().totalSells
            when {
                trades < TUNER_RAMP_START -> 0.0
                trades >= TUNER_RAMP_END  -> 1.0
                else -> {
                    val span = (TUNER_RAMP_END - TUNER_RAMP_START).toDouble()
                    val progress = (trades - TUNER_RAMP_START).coerceAtLeast(0) / span
                    (0.05 + progress * 0.95).coerceIn(0.0, 1.0)
                }
            }
        } catch (_: Throwable) {
            0.0
        }
    }

    /**
     * Human-readable status string for the UI / logs. Never throws.
     */
    fun statusLine(): String {
        return try {
            val snap = TradeHistoryStore.getLifetimeStats()
            val trades = snap.totalSells
            val wr = snap.winRate.let { "%.1f".format(it) }
            val pnl = snap.realizedPnlSol.let { "%.2f".format(it) }
            val mode = if (isWideOpen()) "🔓 FREE-RANGE" else "🔒 DISCIPLINED"
            val tuner = "%.0f".format(adjustmentStrength() * 100)
            "$mode · $trades trades · WR=${wr}% · PnL=${pnl}◎ · tuner=${tuner}%"
        } catch (_: Throwable) {
            "🔓 FREE-RANGE · (history unavailable)"
        }
    }
}
