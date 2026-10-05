package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.PipelineHealthCollector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7809 — EXIT HOT PATH: per-position single-flight units on a pool
 * discovery cannot reach.
 *
 * ROOT CAUSE (5.0.7808, 4 live positions, EXIT_COORDINATOR_STALE_RESET with
 * LOCK_AGE_>=10s and normal-stop trigger -> broadcast far past SLA):
 * BotService's 2 s hot-exit manager called Executor.runManageOnly for each
 * open position INLINE. runManageOnly calls requestSell/doSell, and a live
 * sell is synchronous network work (quote ladder, sign, broadcast, confirm
 * polling with Thread.sleep 0.3-3 s retries). One position's sell therefore
 * held the loop past the 10 s watchdog (the stale reset) and every other
 * position's stop waited behind it in the same walk (the latency). Non-
 * emergency sells were also dispatched on Dispatchers.IO, the pool intake
 * and discovery fill with blocking provider calls.
 *
 * FIX. Each position's manage unit runs on [unitDispatcher] with at most one
 * unit in flight per position key; the hot loop only dispatches, so its
 * heartbeat measures the loop itself. Non-emergency exits get their own pool
 * ([normalExitDispatcher]); emergencies keep EmergencyExitDispatcher7807.
 * Oversell protection is unchanged: CloseLease, the per-mint sell lock and
 * SellExecutionLocks still single-flight the SELL itself.
 *
 * Stale recovery stays as the fallback: a unit in flight longer than
 * [UNIT_STUCK_MS_7809] is assumed wedged (hung socket) and a replacement may
 * start; the sell-side locks keep that from ever becoming a second sale.
 * Field Manual L240, L248.
 */
object ExitHotPath7809 {

    const val UNIT_STUCK_MS_7809 = 30_000L

    private val inFlight7809 = ConcurrentHashMap<String, Long>()
    private val dispatched7809 = AtomicLong(0L)
    private val coalesced7809 = AtomicLong(0L)
    private val stuckReplaced7809 = AtomicLong(0L)
    private val maxUnitMs7809 = AtomicLong(0L)

    private val unitExecutor7809: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r, "AATE-ExitUnit-7809").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 2 }
        }
    private val normalExitExecutor7809: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r, "AATE-NormalExit-7809").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1 }
        }

    /** Pool for per-position hot-exit manage units. */
    val unitDispatcher: CoroutineDispatcher = unitExecutor7809.asCoroutineDispatcher()

    /** Pool for non-emergency exit sells (never Dispatchers.IO). */
    val normalExitDispatcher: CoroutineDispatcher = normalExitExecutor7809.asCoroutineDispatcher()

    /**
     * Claim the unit for [key]. True = caller must run the unit and call [end]
     * with the same [nowMs]. False = a unit for this position is already in
     * flight (younger than [UNIT_STUCK_MS_7809]); this tick is coalesced.
     */
    fun tryBegin(key: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (key.isBlank()) return false
        val prior = inFlight7809.putIfAbsent(key, nowMs)
        if (prior == null) {
            dispatched7809.incrementAndGet()
            return true
        }
        if (nowMs - prior < UNIT_STUCK_MS_7809) {
            coalesced7809.incrementAndGet()
            return false
        }
        if (!inFlight7809.replace(key, prior, nowMs)) return false
        stuckReplaced7809.incrementAndGet()
        dispatched7809.incrementAndGet()
        try { PipelineHealthCollector.labelInc("HOT_EXIT_UNIT_STUCK_REPLACED_7809") } catch (_: Throwable) {}
        return true
    }

    /** Release the claim made at [startedAtMs]; a newer replacement claim is left alone. */
    fun end(key: String, startedAtMs: Long, nowMs: Long = System.currentTimeMillis()) {
        if (key.isBlank()) return
        if (inFlight7809.remove(key, startedAtMs)) {
            val took = (nowMs - startedAtMs).coerceAtLeast(0L)
            maxUnitMs7809.accumulateAndGet(took) { a, b -> maxOf(a, b) }
        }
    }

    fun inFlightCount(): Int = inFlight7809.size

    fun statusLine(nowMs: Long = System.currentTimeMillis()): String {
        val oldest = inFlight7809.values.minOrNull()?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L
        return "hotExitUnits7809 inFlight=${inFlight7809.size} oldestMs=$oldest dispatched=${dispatched7809.get()} " +
            "coalesced=${coalesced7809.get()} stuckReplaced=${stuckReplaced7809.get()} maxUnitMs=${maxUnitMs7809.get()}"
    }

    internal fun resetForTest() {
        inFlight7809.clear()
        dispatched7809.set(0L)
        coalesced7809.set(0L)
        stuckReplaced7809.set(0L)
        maxUnitMs7809.set(0L)
    }
}
