package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * SessionSafetyHalt — V5.9.1050
 * ═══════════════════════════════════════════════════════════════════════
 *
 * V5.9.1049 had a fatal flaw: once the halt latched, it was permanent for
 * the session. Since FluidLearning WR counters persist across restarts, the
 * halt re-engaged on the first buy of every new session until the user
 * manually waited long enough for WR to recover — i.e. the bot was
 * permanently bricked during bootstrap.
 *
 * V5.9.1050 fixes: timed cooldown instead of permanent latch, raised
 * threshold 50→200 (bootstrap needs sample size per PERFORMANCE_DOCTRINE),
 * lowered floor 25%→12% (bootstrap 20-35% WR is healthy per doctrine).
 *
 * When the halt fires: new paper buys are blocked for COOLDOWN_MS (3 min).
 * After cooldown, halt auto-releases and the counter keeps accumulating.
 * If WR is still below floor at the next check, it re-latches for another
 * cooldown — so the bot keeps trying to learn rather than permanently dying.
 *
 * Exits and live trades are NEVER blocked.
 */
object SessionSafetyHalt {

    private const val TAG = "SessionSafetyHalt"

    /** New PAPER entries pause after this many session trades while bleeding. */
    const val PAPER_SESSION_THRESHOLD: Int = 200

    /** A session is "bleeding" when WR drops below this once threshold hit. */
    const val PAPER_HALT_WR_PCT: Double = 12.0

    /** Cooldown after halt engages: 3 minutes, then auto-release. */
    const val COOLDOWN_MS: Long = 3 * 60_000L

    private val paperBuyCount  = AtomicInteger(0)
    private val haltUntilMs    = AtomicLong(0L)
    @Volatile private var haltReason: String = ""

    fun recordPaperBuy() { paperBuyCount.incrementAndGet() }

    fun resetSession() {
        paperBuyCount.set(0)
        haltUntilMs.set(0L)
        haltReason = ""
        ErrorLogger.info(TAG, "🔄 Session halt counters reset")
    }

    /**
     * Returns true if new paper entries should be blocked.
     * After COOLDOWN_MS the halt auto-releases — bot resumes trading.
     */
    fun shouldBlockPaperEntry(recentWinRatePct: Double): Boolean {
        val now = System.currentTimeMillis()
        val until = haltUntilMs.get()
        // Currently in cooldown?
        if (until > 0L && now < until) return true
        // Cooldown expired — auto-release
        if (until > 0L && now >= until) {
            haltUntilMs.set(0L)
            haltReason = ""
            try {
                ForensicLogger.lifecycle("SESSION_SAFETY_HALT_RELEASED",
                    "trades=${paperBuyCount.get()} wrPct=${"%.1f".format(recentWinRatePct)}")
            } catch (_: Throwable) {}
            ErrorLogger.info(TAG, "🟢 SESSION_SAFETY_HALT cooldown expired — resuming paper buys")
        }
        val count = paperBuyCount.get()
        if (count < PAPER_SESSION_THRESHOLD) return false
        // Threshold reached — engage timed cooldown if WR below floor
        if (recentWinRatePct < PAPER_HALT_WR_PCT) {
            val newUntil = now + COOLDOWN_MS
            if (haltUntilMs.compareAndSet(0L, newUntil) || haltUntilMs.get() < now) {
                haltUntilMs.set(newUntil)
                haltReason = "paper trades=$count, WR=${"%.1f".format(recentWinRatePct)}% (< $PAPER_HALT_WR_PCT%) — cooldown ${COOLDOWN_MS/60_000}min"
                ErrorLogger.warn(TAG, "🛑 SESSION_SAFETY_HALT engaged (cooldown): $haltReason")
                try {
                    ForensicLogger.lifecycle("SESSION_SAFETY_HALT_ENGAGED",
                        "trades=$count wrPct=${"%.1f".format(recentWinRatePct)} threshold=$PAPER_SESSION_THRESHOLD wrFloor=$PAPER_HALT_WR_PCT cooldownMs=$COOLDOWN_MS")
                } catch (_: Throwable) {}
            }
            return true
        }
        return false
    }

    fun isHalted(): Boolean = haltUntilMs.get() > System.currentTimeMillis()
    fun haltReason(): String = haltReason
    fun paperBuyCount(): Int = paperBuyCount.get()
}
