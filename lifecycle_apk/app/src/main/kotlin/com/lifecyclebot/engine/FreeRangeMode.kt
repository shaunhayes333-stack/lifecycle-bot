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
    // V5.9.704 — GRADUATED AIR CONTROL (replaces perpetual wide-open)
    //
    // Operator intent: "soft air adjustments after 500 trades pushing to
    // full air control after 5000+ trades 50% WR or better targeting 80%"
    //
    // guardLevel() returns 0–5. Each level activates additional guards:
    //   0  (  0– 500): pure wide-open, zero guards, bot sees everything
    //   1  (500–1000): MemeLossStreakGuard + loss-streak cooldowns active
    //   2  (1000–3000): + ReentryGuard cooldowns + volume floor rises
    //   3  (3000–5000): + HardRugPreFilter active even in paper mode
    //   4  (5000+, WR<50%): all guards active, stays in learning stance
    //   5  (5000+, WR≥50%): full air control, all guards, tightest thresholds
    //
    // isWideOpen() = guardLevel() == 0  (unchanged semantics for callers
    // that need a binary gate check — they still compile and work).
    private const val GUARD_L1_TRADES    = 500    // loss-streak brakes begin
    private const val GUARD_L2_TRADES    = 1000   // reentry cooldowns + volume floor
    private const val GUARD_L3_TRADES    = 3000   // rug filter in paper
    private const val GUARD_L4_TRADES    = 5000   // full air control
    private const val FULL_CTRL_WIN_RATE = 50.0   // WR target to reach full air control
    private const val TUNER_RAMP_START   = 50     // LLM adjustments begin here
    private const val TUNER_RAMP_END     = 3000   // LLM adjustments reach full strength

    // Emergency-graduate kept for backwards compat callers.
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
    /**
     * V5.9.704 — Graduated guard level 0–5.
     * Callers use this to progressively activate guards matching the
     * 500/1000/3000/5000-trade air-control doctrine.
     *
     * Fail-open: returns 0 (wide-open) on any error so a corrupt
     * history cannot accidentally clamp the bot.
     */
    fun guardLevel(): Int {
        if (operatorForceOn)  return 0
        if (operatorForceOff) return 5
        // AntiChoke starvation: drop back to level 0 so every guard relaxes.
        if (AntiChokeManager.isSoftening()) return 0
        return try {
            val snap  = TradeHistoryStore.getLifetimeStats()
            val trades = snap.totalSells
            val wr     = snap.winRate
            when {
                trades < GUARD_L1_TRADES -> 0   // pure exploration
                trades < GUARD_L2_TRADES -> 1   // soft: loss-streak brakes only
                trades < GUARD_L3_TRADES -> 2   // + reentry cooldowns, vol floor rises
                trades < GUARD_L4_TRADES -> 3   // + rug filter in paper
                wr >= FULL_CTRL_WIN_RATE -> 5   // full air control earned
                else                    -> 4   // 5000+ but still learning
            }
        } catch (_: Throwable) { 0 }
    }

    fun isWideOpen(): Boolean {
        // V5.9.704 — isWideOpen() = "no guards whatsoever" = guardLevel 0.
        // Callers that only need a binary check still work correctly.
        // AntiChoke softening and operator overrides are handled inside guardLevel().
        return guardLevel() == 0
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
