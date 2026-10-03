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
 *
 * V5.0.7221 — THE TARGET WAS 5% AND THE MANDATE IS 2x TO 5x.
 *
 * Operator, 5.0.7220: "remember the $50 to $1,000,000 mentality and the
 * daily 2x to 5x wallet growth targets." This file declared itself the
 * "SINGLE SOURCE OF TRUTH for the growth-vs-protection constants that every
 * trader, learner and meta-cognition module must read", and its daily
 * target read 5.0 — forty to a hundred times below the stated mandate.
 * Doctrine-as-code that contradicts the doctrine is worse than no file.
 *
 *   Days at 2x daily (200%):  ~14.3  (2^14.3 ≈ 20,000)
 *   Days at 5x daily (500%):  ~6.2   (5^6.2 ≈ 20,000)
 *   Days at the OLD 5%:       ~204   (1.05^204 ≈ 20,000)
 *
 * V5.0.7223 — CONVENTION, STATED ONCE SO IT IS NEVER MISREAD AGAIN.
 * Operator: "the growth targets are 2x or 200% - 5x 500% daily!!!"
 * The *_PCT constants below are the TARGET WALLET MULTIPLE expressed as a
 * percentage of the day-start wallet: 200% = 2x = wallet doubles, 500% = 5x.
 * They are NOT "growth over start" (7221 wrongly wrote 100%/400% under that
 * reading). To turn a constant into a multiple: PCT / 100.0. To turn it into
 * growth-over-start: PCT / 100.0 - 1.0. Weekly = 2^7 = 128x = 12,800%.
 *
 * The lower bound of the mandate is what is encoded: 2x. It is a LOWER
 * BOUND — any lane strategy that would trade in a way that expects less than
 * this per day is misaligned with the mission.
 *
 * WHAT THIS CHANGES AT RUNTIME: NOTHING, AND THAT IS ITS OWN FINDING.
 * Before editing a constant in a live-money bot I grepped every consumer.
 * DAILY_COMPOUNDING_TARGET_PCT is read by statusLine() and by
 * isAlignedWithDailyTarget(), and isAlignedWithDailyTarget() has ZERO
 * CALLERS in the module. No sizer, no compounding ladder, no learner reads
 * the daily growth target. The "single source of truth" is consumed by a
 * report line. So correcting it cannot ripple anywhere — and the growth
 * mandate has never actually been wired into anything that sizes a trade.
 * That gap is stated on the report now rather than papered over here; wiring
 * a 2x/day target into sizing is a decision for the operator, not a side
 * effect of fixing a comment.
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

    /** Compounding growth targets (lower bounds — actual EV should exceed).
     *  V5.0.7221 — the operator's mandate is 2x to 5x per day. The lower
     *  bound is encoded. The prior 5.0 / 30.0 are kept as named history so a
     *  reader of an older snapshot knows what it was measured against.
     *  V5.0.7223 — unit is TARGET MULTIPLE AS PERCENT OF DAY-START WALLET
     *  (200% = 2x, 500% = 5x). See the header for the convention. */
    const val DAILY_COMPOUNDING_TARGET_PCT: Double = 200.0     // 2x
    const val DAILY_COMPOUNDING_STRETCH_PCT_7221: Double = 500.0   // 5x
    const val WEEKLY_COMPOUNDING_TARGET_PCT: Double = 12_800.0   // 2^7 = 128x, the daily floor compounded
    const val LEGACY_DAILY_TARGET_PCT_PRE_7221: Double = 5.0
    const val LEGACY_WEEKLY_TARGET_PCT_PRE_7221: Double = 30.0

    /** V5.0.7223 — the same targets as plain multiples, so no consumer ever
     *  has to remember the percentage convention. 2.0 = wallet doubles. */
    const val DAILY_TARGET_MULTIPLE_7223: Double = DAILY_COMPOUNDING_TARGET_PCT / 100.0
    const val DAILY_STRETCH_MULTIPLE_7223: Double = DAILY_COMPOUNDING_STRETCH_PCT_7221 / 100.0

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
        realizedRoiMultiple >= 1.0 + (DAILY_TARGET_MULTIPLE_7223 - 1.0) / 10.0
    // ↑ V5.0.7223 — daily growth-over-start is (multiple - 1), i.e. 2x → +100%;
    //   divide by 10 because a trade is expected to contribute ~10% of the
    //   daily target on its own (10 trades/day baseline) → ≥ 1.10 per trade.
    //   Under the 7221 misencoding this read 1.10 by accident; now it reads
    //   1.10 by construction. Zero callers — documented, not load-bearing.

    /** True if a trade counts as "losing behaviour" per the creed —
     *  i.e., any realized SOL delta ≤ 0. Break-even is NOT positive. */
    fun isLosingBehaviour(realizedSolDelta: Double): Boolean = realizedSolDelta <= 0.0

    /** Formats the creed for the pipeline health dump. */
    fun statusLine(): String =
        "targetDaily=${DAILY_COMPOUNDING_TARGET_PCT}%(${DAILY_TARGET_MULTIPLE_7223}x) " +
            "stretch=${DAILY_COMPOUNDING_STRETCH_PCT_7221}%(${DAILY_STRETCH_MULTIPLE_7223}x) " +
            "targetWeekly=${WEEKLY_COMPOUNDING_TARGET_PCT}%(128x) unit=multipleOfDayStart " +
            "maxDD_D=${DAILY_MAX_DRAWDOWN_PCT}% maxDD_W=${WEEKLY_MAX_DRAWDOWN_PCT}% " +
            "maxLossStreak=$MAX_CONSECUTIVE_LOSSES minEV=${MIN_EV_PER_TRADE_MULTIPLE}x " +
            // V5.0.7221 — the honest part. These constants are read by this line
            // and by isAlignedWithDailyTarget(), which nothing calls. The growth
            // target is not wired into any sizer, ladder or learner. Said here
            // so nobody reads a big number and assumes the bot is chasing it.
            "| consumers=statusLine_only growthTargetWiredToSizing=false " +
            "legacyPre7221=${LEGACY_DAILY_TARGET_PCT_PRE_7221}%/${LEGACY_WEEKLY_TARGET_PCT_PRE_7221}%"
}
