package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.495z43 — operator spec item B (last-ditch global trip wire).
 *
 * If the in-line PumpPortal partial-sell guards in Executor.tryPumpPortalSell
 * record N partial-sell attempts in M minutes, this kill-switch globally
 * disables PumpPortal live sells for the rest of the session.
 *
 * Forensics 20260508_071749 showed three back-to-back over-consumption
 * incidents (Winston, Goldie, GREMLIN) within minutes — exactly the
 * pattern this trip-wire is designed to short-circuit.
 *
 * The kill-switch is in-memory only by design. Operator must restart the
 * bot (or call `armForSession()`) to re-enable. That is intentional — a
 * kill-switch that auto-resets is not a kill-switch.
 */
object PumpPortalKillSwitch {

    private const val TAG = "PumpPortalKillSwitch"
    private const val WINDOW_MS = 10 * 60_000L
    private const val THRESHOLD = 3

    private val attempts = ConcurrentHashMap<String, AtomicLong>()  // mint → lastAttemptMs
    private val recentCount = AtomicInteger(0)
    private val firstInWindow = AtomicLong(0L)
    @Volatile private var tripped: Boolean = false
    @Volatile private var trippedAtMs: Long = 0L
    @Volatile private var trippedReason: String = ""

    fun recordPartialAttempt(mint: String, symbol: String, labelTag: String) {
        val now = System.currentTimeMillis()
        attempts[mint] = AtomicLong(now)
        // Sliding window: if first sample is older than WINDOW_MS, reset.
        val first = firstInWindow.get()
        if (first == 0L || now - first > WINDOW_MS) {
            firstInWindow.set(now)
            recentCount.set(1)
        } else {
            val n = recentCount.incrementAndGet()
            if (n >= THRESHOLD && !tripped) {
                tripped = true
                trippedAtMs = now
                trippedReason = "$n partial-sell attempts in last 10m " +
                    "(latest=$symbol mint=${mint.take(8)}… label=$labelTag)"
                ErrorLogger.error(TAG,
                    "🚨🚨🚨 PUMP_PORTAL_KILL_SWITCH_TRIPPED — $trippedReason. " +
                    "Live PumpPortal sells DISABLED for remainder of session.")
            }
        }
    }

    fun isTripped(): Boolean = tripped
    fun trippedReason(): String = trippedReason
    fun trippedAtMs(): Long = trippedAtMs
    fun recentPartialAttempts(): Int = recentCount.get()

    /** Manual reset for tests / operator override. */
    fun armForSession() {
        tripped = false
        trippedAtMs = 0L
        trippedReason = ""
        recentCount.set(0)
        firstInWindow.set(0L)
        attempts.clear()
    }
}
