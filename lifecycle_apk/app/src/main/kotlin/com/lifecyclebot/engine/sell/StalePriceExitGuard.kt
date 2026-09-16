package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ForensicLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6825 — STALE / DARK PRICE EXIT INTEGRITY GUARD.
 *
 * A stale or missing quote is not economic evidence of a loss. It may mark a
 * position as needing a refresh, but it must not by itself manufacture a
 * terminal sell or a trainable losing outcome.
 *
 * This guard therefore enforces:
 *   • stale-only observations HOLD/DEFER and never arm a terminal exit;
 *   • a terminal stale-price escape requires independent evidence that is safe
 *     to act on (fresh validated mark, confirmed-zero balance, or confirmed
 *     dead/unroutable route);
 *   • absurd/non-finite PnL math is quarantined;
 *   • stale/dark price alone can never assert pnl=-100%.
 */
object StalePriceExitGuard {

    /** A PnL multiple above this is treated as corrupt/untrustworthy. */
    const val ABSURD_GAIN_MULTIPLE = 1000.0   // +100,000%

    // mint -> wallclock when the single validated escape intent was armed.
    private val armedRugEscape = ConcurrentHashMap<String, Long>()
    // mints currently flagged as having a stale/dark price.
    private val staleActive = ConcurrentHashMap<String, Long>()

    private const val ARM_TTL_MS = 300_000L

    /** True if ANY mint currently has a stale/dark mark condition active. */
    fun anyActive(): Boolean {
        val now = System.currentTimeMillis()
        staleActive.entries.removeIf { now - it.value > ARM_TTL_MS }
        return staleActive.isNotEmpty()
    }

    fun markStale(mint: String) {
        if (mint.isBlank()) return
        staleActive[mint] = System.currentTimeMillis()
    }

    fun clearStale(mint: String) {
        staleActive.remove(mint)
        armedRugEscape.remove(mint)
    }

    /**
     * Compatibility entry point used by older callers.
     *
     * IMPORTANT: stale price alone is no longer authority to liquidate. The old
     * implementation returned true once per TTL, which allowed a quote outage to
     * synthesize PAPER_STALE_* terminal losses. We keep the function so existing
     * callers compile, but it now records the stale condition and returns false.
     *
     * Call [armValidatedRugEscape] only after independent terminal evidence exists.
     */
    fun armOnceRugEscape(mint: String, symbol: String): Boolean {
        if (mint.isBlank()) return false
        markStale(mint)
        try {
            ForensicLogger.lifecycle(
                "STALE_PRICE_EXIT_DEFERRED_6825",
                "mint=${mint.take(10)} symbol=$symbol reason=stale_only action=refresh_hold_no_terminal"
            )
        } catch (_: Throwable) {}
        return false
    }

    /**
     * Arm exactly one stale-price escape only when a second authority proves that
     * terminal action is legitimate.
     *
     * Evidence accepted:
     *  - balanceConfirmedZero: authoritative balance says the asset is gone;
     *  - freshPriceVerified: a fresh validated mark exists and can price the exit;
     *  - routeConfirmedDead: routing independently proves the token is dead/unroutable.
     */
    fun armValidatedRugEscape(
        mint: String,
        symbol: String,
        balanceConfirmedZero: Boolean,
        freshPriceVerified: Boolean,
        routeConfirmedDead: Boolean,
    ): Boolean {
        if (mint.isBlank()) return false
        markStale(mint)

        val validatedEvidence = balanceConfirmedZero || freshPriceVerified || routeConfirmedDead
        if (!validatedEvidence) {
            try {
                ForensicLogger.lifecycle(
                    "STALE_PRICE_EXIT_DEFERRED_6825",
                    "mint=${mint.take(10)} symbol=$symbol reason=no_independent_evidence action=refresh_hold_no_terminal"
                )
            } catch (_: Throwable) {}
            return false
        }

        val now = System.currentTimeMillis()
        val prev = armedRugEscape[mint]
        if (prev != null && (now - prev) < ARM_TTL_MS) return false

        val raced = armedRugEscape.putIfAbsent(mint, now)
        if (raced != null && (now - raced) < ARM_TTL_MS) return false
        armedRugEscape[mint] = now

        try {
            ForensicLogger.lifecycle(
                "STALE_PRICE_VALIDATED_ESCAPE_ARMED_6825",
                "mint=${mint.take(10)} symbol=$symbol balanceZero=$balanceConfirmedZero freshMark=$freshPriceVerified routeDead=$routeConfirmedDead"
            )
        } catch (_: Throwable) {}
        return true
    }

    /**
     * Decide whether computed gain math is trustworthy. Quarantined prices must
     * not drive exits, catastrophe cooldowns, or learner rewards.
     */
    fun isGainTrustworthy(
        mint: String,
        entryPrice: Double,
        lastPrice: Double,
        gainMultiple: Double,
    ): Boolean {
        val bad = !gainMultiple.isFinite() ||
            gainMultiple > ABSURD_GAIN_MULTIPLE ||
            entryPrice <= 0.0 ||
            lastPrice <= 0.0

        if (bad) {
            markStale(mint)
            try {
                ForensicLogger.lifecycle(
                    "STALE_PRICE_QUARANTINED",
                    "mint=${mint.take(10)} entryPrice=$entryPrice lastPrice=$lastPrice gainMultiple=$gainMultiple " +
                        "reason=absurd_or_nonfinite action=do_not_trade_or_learn"
                )
            } catch (_: Throwable) {}
            return false
        }
        return true
    }

    /**
     * A stale/dark price may NEVER be reported as a confirmed -100% loss. Only an
     * authoritative zero balance or a verified fresh price may prove total loss.
     */
    fun canAssertTotalLoss(balanceConfirmedZero: Boolean, freshPriceVerified: Boolean): Boolean =
        balanceConfirmedZero || freshPriceVerified
}
