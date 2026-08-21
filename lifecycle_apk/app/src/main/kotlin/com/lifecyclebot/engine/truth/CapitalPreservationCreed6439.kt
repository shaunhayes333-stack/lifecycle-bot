package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6439 — CAPITAL PRESERVATION CREED (doctrine as code).
 *
 * OPERATOR DIRECTIVE (V5.0.6438):
 *   "Their priority should be wallet growth while protecting capital
 *    inline with the daily and weekly compounding targets and the
 *    $50 to a $1,000,000 mindset!!! Bad behaviour and consistently
 *    buying into losers, the wallet balance shrinking, bad trading
 *    logic and strategy should NEVER be seen or recognised as good
 *    behaviour or the right way to trade or the right mentality."
 *
 * This module is the SINGLE SOURCE OF TRUTH for the growth-vs-protection
 * constants that every trader, learner and meta-cognition module must
 * read. It intentionally exposes no setters — the values are the creed.
 *
 * $50 → $1,000,000 mindset math
 * ──────────────────────────────
 *   Ratio needed:      20,000x
 *   Days at 5% daily:  ~204 (1.05^204 ≈ 20,000)
 *   Days at 10% daily: ~104
 *   Weeks at 30%:      ~38 (1.30^38 ≈ 20,000)
 *
 * So the daily compounding target is set to 5% (aggressive but survivable)
 * and the weekly compounding target to 30%. Both targets are LOWER
 * BOUNDS — any lane strategy that would trade in a way that expects less
 * than these returns per unit-of-risk is misaligned with the mission.
 *
 * Capital protection floors
 * ─────────────────────────
 *   Daily max drawdown:  8% of session-start balance
 *   Weekly max drawdown: 18% of week-start balance
 *   Max consecutive losses before cool-down: 3
 *   Min expected value per trade (unit: multiple of risk): 1.15x
 *
 * These floors are HARD. When they trip:
 *   • DAILY_LOSS_LIMIT_TRIPPED_6439 → no new BUYs until 00:00 UTC roll
 *   • WEEKLY_LOSS_LIMIT_TRIPPED_6439 → no new BUYs until week roll
 *   • LOSING_STREAK_TRIPPED_6439 → LosingStreakReflex6439 cools down
 */
object CapitalPreservationCreed6439 {
    private val finalizedLosingByPosition6486 = ConcurrentHashMap<String, Boolean>()

    /** V5.0.6486 — retain the creed verdict for each canonical terminal identity. */
    fun recordFinalized6486(positionId: String, realizedSolDelta: Double): Boolean {
        if (positionId.isBlank()) return false
        finalizedLosingByPosition6486[positionId] = isLosingBehaviour(realizedSolDelta)
        try { PipelineHealthCollector.labelInc("CAPITAL_CREED_FINALIZED_CONSUMED_6486") } catch (_: Throwable) {}
        return true
    }

    fun finalizedVerdict6486(positionId: String): Boolean? = finalizedLosingByPosition6486[positionId]

    /** Compounding growth targets (lower bounds — actual EV should exceed). */
    const val DAILY_COMPOUNDING_TARGET_PCT: Double = 5.0
    const val WEEKLY_COMPOUNDING_TARGET_PCT: Double = 30.0

    /** Hard drawdown ceilings (percentage of the period-start balance). */
    const val DAILY_MAX_DRAWDOWN_PCT: Double = 8.0
    const val WEEKLY_MAX_DRAWDOWN_PCT: Double = 18.0

    /** Streak protection. */
    const val MAX_CONSECUTIVE_LOSSES: Int = 3
    const val CONSECUTIVE_LOSS_COOLDOWN_MS: Long = 30L * 60L * 1000L   // 30 min

    /** Minimum expected-value multiple (per unit of risked capital). */
    const val MIN_EV_PER_TRADE_MULTIPLE: Double = 1.15

    /** True if the given realized ROI (unit: multiple, e.g. 1.08 = +8%) is
     *  aligned with daily compounding target. Used by the reward shaper so
     *  break-even trades stop counting as "good behaviour". */
    fun isAlignedWithDailyTarget(realizedRoiMultiple: Double): Boolean =
        realizedRoiMultiple >= 1.0 + (DAILY_COMPOUNDING_TARGET_PCT / 100.0) / 10.0
    // ↑ divide by 10 because a trade is expected to contribute ~10% of the
    //   daily target on its own (10 trades/day baseline).

    /** True if a trade counts as "losing behaviour" per the creed —
     *  i.e., any realized SOL delta ≤ 0. Break-even is NOT positive. */
    fun isLosingBehaviour(realizedSolDelta: Double): Boolean = realizedSolDelta <= 0.0

    /** Formats the creed for the pipeline health dump. */
    fun statusLine(): String =
        "targetDaily=${DAILY_COMPOUNDING_TARGET_PCT}% targetWeekly=${WEEKLY_COMPOUNDING_TARGET_PCT}% " +
            "maxDD_D=${DAILY_MAX_DRAWDOWN_PCT}% maxDD_W=${WEEKLY_MAX_DRAWDOWN_PCT}% " +
            "maxLossStreak=$MAX_CONSECUTIVE_LOSSES minEV=${MIN_EV_PER_TRADE_MULTIPLE}x"
}
