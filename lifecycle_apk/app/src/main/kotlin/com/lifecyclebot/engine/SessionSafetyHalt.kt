package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * SessionSafetyHalt — V5.9.1049
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Operator directive (verbatim): "it should be stopping trading after 50
 * trades either mate!!!" — referring to a paper session bleeding -1.4 SOL
 * with a 12.5% win rate after 161 journal records (snapshot V5.9.1040).
 *
 * Hard guard layered ON TOP of TradingCopilot / EMERGENCY_BRAKE. When the
 * bot has executed PAPER_SESSION_THRESHOLD entries this session AND the
 * sell-window win-rate is below PAPER_HALT_WR_PCT, new paper buys are
 * refused. Exits and live trades are NEVER blocked by this halt — only
 * fresh paper entries. Halt persists until the bot is stopped + started
 * (clears on session reset).
 *
 * This is intentionally SIMPLE — it is the "circuit breaker that always
 * works" beneath the AI brain.  Bot can still recover by restarting.
 */
object SessionSafetyHalt {

    private const val TAG = "SessionSafetyHalt"

    /** New PAPER entries are refused after this many trades in a bleeding session. */
    const val PAPER_SESSION_THRESHOLD: Int = 50

    /** A session is "bleeding" when WR drops below this once threshold hit. */
    const val PAPER_HALT_WR_PCT: Double = 25.0

    private val paperBuyCount = AtomicInteger(0)
    private val haltLatched   = AtomicBoolean(false)
    @Volatile private var haltReason: String = ""

    /**
     * Record a paper buy execution.  Idempotent — caller responsibility
     * to call once per fired entry.
     */
    fun recordPaperBuy() {
        paperBuyCount.incrementAndGet()
    }

    /**
     * Reset on bot start / new session.
     */
    fun resetSession() {
        paperBuyCount.set(0)
        haltLatched.set(false)
        haltReason = ""
        ErrorLogger.info(TAG, "🔄 Session halt counters reset")
    }

    /**
     * Check whether new paper entries should be refused.  Cheap — atomic
     * read of the latch; the WR computation only happens BEFORE the latch
     * trips (i.e. once threshold is reached).
     *
     * @param recentWinRatePct the rolling-window sell win-rate in percent
     *                         (0..100). Pass the same number shown in
     *                         the Performance Analytics card.
     */
    fun shouldBlockPaperEntry(recentWinRatePct: Double): Boolean {
        if (haltLatched.get()) return true

        val count = paperBuyCount.get()
        if (count < PAPER_SESSION_THRESHOLD) return false

        // Threshold reached — engage the latch if WR is below floor.
        if (recentWinRatePct < PAPER_HALT_WR_PCT) {
            if (haltLatched.compareAndSet(false, true)) {
                haltReason = "paper trades=$count, WR=${"%.1f".format(recentWinRatePct)}% (< $PAPER_HALT_WR_PCT%)"
                ErrorLogger.warn(TAG, "🛑 SESSION_SAFETY_HALT engaged: $haltReason")
                try {
                    ForensicLogger.lifecycle(
                        "SESSION_SAFETY_HALT_ENGAGED",
                        "trades=$count wrPct=${"%.1f".format(recentWinRatePct)} threshold=$PAPER_SESSION_THRESHOLD wrFloor=$PAPER_HALT_WR_PCT",
                    )
                } catch (_: Throwable) {}
            }
            return true
        }
        return false
    }

    fun isHalted(): Boolean = haltLatched.get()
    fun haltReason(): String = haltReason
    fun paperBuyCount(): Int = paperBuyCount.get()
}
