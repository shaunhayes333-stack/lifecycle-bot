package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.945 — BirdeyeBudgetGate.
 *
 * GLOBAL DAILY CU BUDGET CIRCUIT BREAKER for Birdeye.
 *
 * THE PROBLEM
 *   Operator burned 75% of monthly 5M Starter budget in 10 hours.
 *   V5.9.937 cost-analysis assumed 50 admitted mints/hour. After
 *   V5.9.937-942 scanner expansion: ~1500 unique mints/hour × 6
 *   prefetch endpoints × 25 CU avg = ~225K CU/hour = ~5.4M CU/day.
 *
 * THE FIX
 *   Hard daily CU cap. When canAfford() returns false, every Birdeye
 *   prefetch caller short-circuits. Critical safety paths (Stealth-Mint
 *   Monitor on OPEN positions) still fire because they go through their
 *   own dispatch, not this gate.
 *
 *   Budget rolls at UTC midnight via "day key" comparison — no
 *   scheduler needed.
 *
 * CONFIGURATION
 *   Default 150K CU/day = ~4.5M CU/month (10% safety margin under
 *   5M Starter quota).
 *
 * DOCTRINE
 *   #87.13 'Bot staying alive is precondition for ALL doctrine' —
 *     running out of budget mid-month would dark the entire Birdeye
 *     sensor stack and starve FDG of its strongest soft-shapers.
 */
object BirdeyeBudgetGate {
    private const val TAG = "BirdeyeBudget"
    private const val DEFAULT_DAILY_CAP = 150_000L

    @Volatile private var dayKey: Long = currentDayKey()
    private val callsToday = AtomicLong(0L)
    private val cuToday = AtomicLong(0L)

    @Volatile private var lastThrottleLogMs = 0L
    @Volatile private var dailyCap: Long = DEFAULT_DAILY_CAP

    fun setDailyCap(cap: Long) {
        dailyCap = cap.coerceAtLeast(0L)
        ErrorLogger.info(TAG, "daily CU cap set to $cap")
    }

    fun canAfford(estimatedCalls: Int): Boolean {
        rolloverIfNeeded()
        if (dailyCap == 0L) return true
        val estCu = estimatedCalls * 25L
        return (cuToday.get() + estCu) <= dailyCap
    }

    fun recordCalls(calls: Int) {
        rolloverIfNeeded()
        callsToday.addAndGet(calls.toLong())
        cuToday.addAndGet(calls * 25L)
    }

    fun logThrottleIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastThrottleLogMs < 60_000L) return
        lastThrottleLogMs = now
        ErrorLogger.info(
            TAG,
            "BUDGET CAP HIT — prefetches throttled. calls=" + callsToday.get() +
                " cu=" + cuToday.get() + "/" + dailyCap + " (resets at UTC midnight)"
        )
    }

    fun snapshot(): Snapshot {
        rolloverIfNeeded()
        val cu = cuToday.get()
        return Snapshot(
            dayKey = dayKey,
            callsToday = callsToday.get(),
            cuToday = cu,
            dailyCap = dailyCap,
            pctUsed = if (dailyCap > 0) (cu.toDouble() / dailyCap * 100.0) else 0.0,
        )
    }

    data class Snapshot(
        val dayKey: Long,
        val callsToday: Long,
        val cuToday: Long,
        val dailyCap: Long,
        val pctUsed: Double,
    )

    private fun rolloverIfNeeded() {
        val now = currentDayKey()
        if (now != dayKey) {
            synchronized(this) {
                if (now != dayKey) {
                    val prevCalls = callsToday.getAndSet(0L)
                    val prevCu = cuToday.getAndSet(0L)
                    dayKey = now
                    lastThrottleLogMs = 0L
                    ErrorLogger.info(
                        TAG,
                        "UTC day rollover — prev day: calls=" + prevCalls + " cu=" + prevCu + ". Resetting."
                    )
                }
            }
        }
    }

    private fun currentDayKey(): Long = System.currentTimeMillis() / 86_400_000L
}
