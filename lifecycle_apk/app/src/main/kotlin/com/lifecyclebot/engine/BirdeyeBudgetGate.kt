package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.945 — BirdeyeBudgetGate.
 * V5.9.952 — LOCKDOWN MODE + scanner-lane throttle.
 *
 * GLOBAL DAILY CU BUDGET CIRCUIT BREAKER for Birdeye.
 *
 * THE PROBLEM
 *   Operator burned 5M monthly Starter quota in 19 days. Root cause:
 *   5 of 7 Birdeye call sites bypass this gate entirely. The 4 scanner-
 *   side endpoints (trending/meme/markets/new_listing) fire every 8s
 *   from the scanner loop = ~43K calls/day = ~1.3M CU/day uncounted.
 *
 * THE FIX (V5.9.952)
 *   - Add LOCKDOWN tier: when monthly burn hits >80%, only emergency
 *     paths (rug-check on OPEN positions) get through.
 *   - Add SCANNER_LANE throttle: when daily burn hits >60% OR monthly
 *     burn hits >75%, scanner-side Birdeye calls drop from every-8s
 *     to once-every-5min.
 *   - Add canAffordScannerLane() — gates the 4 scanner endpoints.
 *   - Add canAffordSafety() — always returns true unless full lockdown.
 *
 * DOCTRINE
 *   #87.13 — "Bot staying alive is precondition for ALL doctrine".
 *   #87.23 — "FREE-SOURCE-FIRST + PAID-AS-ESCALATION". 90% of scanner-
 *     side intake data is duplicated by free sources. Birdeye scanner
 *     lanes are LUXURY, not necessity.
 */
object BirdeyeBudgetGate {
    private const val TAG = "BirdeyeBudget"
    private const val DEFAULT_DAILY_CAP = 150_000L

    // V5.0.3977 — monthly soft caps. Birdeye dashboard now shows 6M CU/month.
    private const val MONTHLY_CAP = 6_000_000L
    private const val MONTHLY_LOCKDOWN_PCT = 0.80
    private const val MONTHLY_SCANNER_THROTTLE_PCT = 0.75
    private const val DAILY_SCANNER_THROTTLE_PCT = 0.60
    private const val DAILY_SCANNER_HARD_STOP_PCT = 0.90

    // V5.0.3977 — provider conservation is NOT a permanent compile-time state.
    // Operator's Birdeye dashboard (2026-06-20) shows ~3.97K / 6.00M CU used, so
    // the old hardcoded true value was stale and falsely paused non-emergency
    // Birdeye, starving metadata/provider proof. Real protection remains via
    // daily/monthly counters, scanner throttles, and isLockedDown().
    private const val EMERGENCY_CONSERVATION_MODE = false
    private const val EMERGENCY_DAILY_CAP = 2_500L       // ~100 calls/day max
    private const val EMERGENCY_OPEN_POS_CALLS_PER_HOUR = 12L

    @Volatile private var dayKey: Long = currentDayKey()
    @Volatile private var monthKey: Long = currentMonthKey()
    private val callsToday = AtomicLong(0L)
    private val cuToday = AtomicLong(0L)
    private val cuThisMonth = AtomicLong(0L)

    @Volatile private var lastThrottleLogMs = 0L
    @Volatile private var lastLockdownLogMs = 0L
    @Volatile private var dailyCap: Long = DEFAULT_DAILY_CAP

    @Volatile private var lastScannerLaneTickMs = 0L
    private const val SCANNER_THROTTLED_INTERVAL_MS = 300_000L  // 5 minutes

    fun setDailyCap(cap: Long) {
        dailyCap = cap.coerceAtLeast(0L)
        ErrorLogger.info(TAG, "daily CU cap set to $cap")
    }

    /** V5.9.1129 — deterministic tests for hard budget lockdown invariant. */
    fun resetForTests(dailyCapOverride: Long = DEFAULT_DAILY_CAP) {
        dayKey = currentDayKey()
        monthKey = currentMonthKey()
        callsToday.set(0L)
        cuToday.set(0L)
        cuThisMonth.set(0L)
        dailyCap = dailyCapOverride.coerceAtLeast(0L)
        lastThrottleLogMs = 0L
        lastLockdownLogMs = 0L
        lastScannerLaneTickMs = 0L
        hourlyEmergencyCalls.clear()
    }

    fun canAfford(estimatedCalls: Int): Boolean {
        rolloverIfNeeded()
        if (EMERGENCY_CONSERVATION_MODE) return false
        if (isLockedDown()) return false
        if (dailyCap == 0L) return true
        val estCu = estimatedCalls * 25L
        return (cuToday.get() + estCu) <= dailyCap
    }

    /** Emergency-only allowance for open-position price fallback. */
    fun canAffordOpenPositionEmergency(estimatedCalls: Int = 1): Boolean {
        rolloverIfNeeded()
        if (isProviderLockedDown()) return false
        val estCu = estimatedCalls * 25L
        val cap = if (EMERGENCY_CONSERVATION_MODE) EMERGENCY_DAILY_CAP else dailyCap
        if (cap > 0L && cuToday.get() + estCu > cap) return false
        if (EMERGENCY_CONSERVATION_MODE) {
            val hourKey = System.currentTimeMillis() / 3_600_000L
            val key = "openpos:$hourKey"
            val used = hourlyEmergencyCalls[key] ?: 0L
            if (used + estimatedCalls > EMERGENCY_OPEN_POS_CALLS_PER_HOUR) return false
            hourlyEmergencyCalls[key] = used + estimatedCalls
            hourlyEmergencyCalls.keys.removeIf { it != key }
        }
        return true
    }

    /**
     * V5.9.952 — gates the 4 scanner-side Birdeye endpoints.
     * Throttles them from every-8s to every-5min when burn is high.
     */
    fun canAffordScannerLane(): Boolean {
        rolloverIfNeeded()
        if (EMERGENCY_CONSERVATION_MODE) return false
        if (isLockedDown()) return false
        if (!canAfford(1)) return false

        val dailyPct = if (dailyCap > 0) cuToday.get().toDouble() / dailyCap else 0.0
        val monthlyPct = cuThisMonth.get().toDouble() / MONTHLY_CAP
        // V5.0.4162 — source-balance recovery. The comment contract for this
        // gate says scanner-side Birdeye throttles when burn is high (>60%), but
        // code was throttling at 10% and hard-stopping at 25%. Runtime showed
        // daily=47.7%, which silently removed all Birdeye scanner lanes while
        // MemeTrader needed metadata/source diversity. Keep real budget safety:
        // throttle at 60%, hard-stop only near the daily cap.
        if (dailyPct >= DAILY_SCANNER_HARD_STOP_PCT) return false
        val throttle = dailyPct >= DAILY_SCANNER_THROTTLE_PCT ||
                       monthlyPct >= MONTHLY_SCANNER_THROTTLE_PCT

        if (!throttle) {
            lastScannerLaneTickMs = System.currentTimeMillis()
            return true
        }

        val now = System.currentTimeMillis()
        if (now - lastScannerLaneTickMs >= SCANNER_THROTTLED_INTERVAL_MS) {
            lastScannerLaneTickMs = now
            ErrorLogger.info(
                TAG,
                "scanner-lane throttle ACTIVE (daily=" + "%.0f".format(dailyPct*100) + "% " +
                    "monthly=" + "%.0f".format(monthlyPct*100) + "%) — letting ONE tick through"
            )
            return true
        }
        return false
    }

    /**
     * V5.9.952 — safety-critical calls. Always allow unless full lockdown.
     */
    fun canAffordSafety(): Boolean {
        rolloverIfNeeded()
        if (EMERGENCY_CONSERVATION_MODE) return false
        return !isLockedDown()
    }

    fun recordCalls(calls: Int) {
        rolloverIfNeeded()
        callsToday.addAndGet(calls.toLong())
        val cu = calls * 25L
        cuToday.addAndGet(cu)
        cuThisMonth.addAndGet(cu)
    }

    private val hourlyEmergencyCalls = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun isProviderLockedDown(): Boolean {
        val pct = cuThisMonth.get().toDouble() / MONTHLY_CAP
        return EMERGENCY_CONSERVATION_MODE || pct >= MONTHLY_LOCKDOWN_PCT
    }

    /**
     * V5.9.1129 — executable-entry budget invariant.
     *
     * Do NOT use provider conservation or the app-local daily call budget as a
     * global buy kill-switch or the bot goes to zero volume while free/Dex/Jupiter
     * routes are still viable. Daily exhaustion throttles Birdeye calls via
     * canAfford(); live entries are hard-paused only on monthly/provider exhaustion.
     */
    fun isEntryBudgetLockedDown(): Boolean {
        rolloverIfNeeded()
        val monthlyPct = cuThisMonth.get().toDouble() / MONTHLY_CAP
        return EMERGENCY_CONSERVATION_MODE || monthlyPct >= MONTHLY_LOCKDOWN_PCT
    }

    fun isLockedDown(): Boolean {
        val pct = cuThisMonth.get().toDouble() / MONTHLY_CAP
        val cap = if (EMERGENCY_CONSERVATION_MODE) EMERGENCY_DAILY_CAP else dailyCap
        val dailyPct = if (cap > 0) cuToday.get().toDouble() / cap else 0.0
        val locked = EMERGENCY_CONSERVATION_MODE || pct >= MONTHLY_LOCKDOWN_PCT
        if (locked) {
            val now = System.currentTimeMillis()
            if (now - lastLockdownLogMs > 300_000L) {
                lastLockdownLogMs = now
                ErrorLogger.warn(
                    TAG,
                    "BIRDEYE LOCKDOWN — monthly burn=" + "%.0f".format(pct*100) + "% daily=" + "%.0f".format(dailyPct*100) + "%. " +
                        "All non-safety paths blocked until reset."
                )
            }
        }
        return locked
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
        val monthCu = cuThisMonth.get()
        val effectiveDailyCap = if (EMERGENCY_CONSERVATION_MODE) EMERGENCY_DAILY_CAP else dailyCap
        val monthlyPct = monthCu.toDouble() / MONTHLY_CAP
        val effectiveDailyPct = if (effectiveDailyCap > 0) cu.toDouble() / effectiveDailyCap else 0.0
        val entryLocked = isEntryBudgetLockedDown()
        return Snapshot(
            dayKey = dayKey,
            callsToday = callsToday.get(),
            cuToday = cu,
            dailyCap = effectiveDailyCap,
            pctUsed = effectiveDailyPct * 100.0,
            cuThisMonth = monthCu,
            monthlyCap = MONTHLY_CAP,
            monthlyPctUsed = monthlyPct * 100.0,
            lockedDown = entryLocked,
            providerConservation = EMERGENCY_CONSERVATION_MODE,
            providerLockedDown = EMERGENCY_CONSERVATION_MODE || monthlyPct >= MONTHLY_LOCKDOWN_PCT,
        )
    }

    data class Snapshot(
        val dayKey: Long,
        val callsToday: Long,
        val cuToday: Long,
        val dailyCap: Long,
        val pctUsed: Double,
        val cuThisMonth: Long = 0L,
        val monthlyCap: Long = 6_000_000L,
        val monthlyPctUsed: Double = 0.0,
        val lockedDown: Boolean = false,
        val providerConservation: Boolean = false,
        val providerLockedDown: Boolean = false,
    )

    private fun rolloverIfNeeded() {
        val nowDay = currentDayKey()
        if (nowDay != dayKey) {
            synchronized(this) {
                if (nowDay != dayKey) {
                    val prevCalls = callsToday.getAndSet(0L)
                    val prevCu = cuToday.getAndSet(0L)
                    dayKey = nowDay
                    lastThrottleLogMs = 0L
                    ErrorLogger.info(
                        TAG,
                        "UTC day rollover — prev day: calls=" + prevCalls + " cu=" + prevCu + ". Resetting."
                    )
                }
            }
        }
        val nowMonth = currentMonthKey()
        if (nowMonth != monthKey) {
            synchronized(this) {
                if (nowMonth != monthKey) {
                    val prevMonthCu = cuThisMonth.getAndSet(0L)
                    monthKey = nowMonth
                    lastLockdownLogMs = 0L
                    ErrorLogger.info(
                        TAG,
                        "calendar month rollover — prev month cu=" + prevMonthCu + ". Resetting."
                    )
                }
            }
        }
    }

    private fun currentDayKey(): Long = System.currentTimeMillis() / 86_400_000L
    private fun currentMonthKey(): Long {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        return cal.get(java.util.Calendar.YEAR) * 12L + cal.get(java.util.Calendar.MONTH)
    }
}
