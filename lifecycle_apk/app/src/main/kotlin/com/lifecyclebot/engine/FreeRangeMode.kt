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
        // V5.9.606 — restore the original operator contract documented above.
        // V5.9.422 accidentally tied free-range directly to QualityLadder.tier(),
        // so at ~3000 trades with WR below target the bot entered Tier 3
        // PROFITABILITY_LOCKED and re-enabled cooldown/volume/loss guards.
        // Result: paper mode still found hundreds of candidates but barely
        // traded. QualityLadder may reduce size / surface readiness, but it
        // must not shut off the learning firehose before 5000 unless the bot
        // is actually healthy enough to graduate.
        return try {
            val snap = TradeHistoryStore.getLifetimeStats()
            val trades = snap.totalSells
            when {
                trades < WIDE_OPEN_FLOOR_TRADES -> true
                trades < WIDE_OPEN_CEIL_TRADES  -> !(snap.winRate >= GRADUATE_WIN_RATE_PCT && snap.realizedPnlSol >= GRADUATE_MIN_PNL_SOL)
                else                            -> false
            }
        } catch (_: Throwable) { true }
    }

    /**
     * V5.9.421 → V5.9.422 — retained as a thin alias over
     * `QualityLadder.tier() >= 1` so existing callers (HardRugPreFilter,
     * BotService triple-danger gate) keep compiling while the ladder
     * takes over. Anything new should consult QualityLadder directly.
     */
    fun emergencyGraduated(): Boolean {
        if (operatorForceOn || operatorForceOff) return false
        return try { QualityLadder.tier() >= 1 } catch (_: Throwable) { false }
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
     * V5.9.422 — defers to QualityLadder for the detailed pipeline view
     * and prepends the legacy free-range icon so existing log scrapers
     * still see "🔓 FREE-RANGE" or "🔒 DISCIPLINED".
     */
    fun statusLine(): String {
        return try {
            val mode = if (isWideOpen()) "🔓 FREE-RANGE" else "🔒 DISCIPLINED"
            val ladder = try { QualityLadder.statusLine() } catch (_: Throwable) { "" }
            if (ladder.isNotBlank()) "$mode · $ladder" else mode
        } catch (_: Throwable) {
            "🔓 FREE-RANGE · (history unavailable)"
        }
    }
}
