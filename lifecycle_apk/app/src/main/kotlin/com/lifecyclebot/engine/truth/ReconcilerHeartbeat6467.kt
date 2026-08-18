package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6467 §P0 (items 12-13) — SINGLE RECONCILER HEARTBEAT.
 * All health reports read the same lastQuickStart/lastQuickSuccess/
 * quickPassCount + full equivalents. Long.MAX_VALUE overdue is banned
 * from runtime age exposure (returned as ageMs = -1 sentinel).
 */
object ReconcilerHeartbeat6467 {
    private val lastQuickStart = AtomicLong(0L)
    private val lastQuickSuccess = AtomicLong(0L)
    private val quickPassCount = AtomicLong(0L)
    private val lastFullStart = AtomicLong(0L)
    private val lastFullSuccess = AtomicLong(0L)
    private val fullPassCount = AtomicLong(0L)

    fun onQuickStart() { lastQuickStart.set(System.currentTimeMillis()) }
    fun onQuickSuccess() {
        lastQuickSuccess.set(System.currentTimeMillis()); quickPassCount.incrementAndGet()
        try { PipelineHealthCollector.labelInc("RECONCILER_QUICK_PASS_6467") } catch (_: Throwable) {}
    }
    fun onFullStart() { lastFullStart.set(System.currentTimeMillis()) }
    fun onFullSuccess() {
        lastFullSuccess.set(System.currentTimeMillis()); fullPassCount.incrementAndGet()
        try { PipelineHealthCollector.labelInc("RECONCILER_FULL_PASS_6467") } catch (_: Throwable) {}
    }

    /** Age of last success in ms, or -1 sentinel if uninitialized (never Long.MAX_VALUE). */
    fun quickAgeMs(): Long {
        val last = lastQuickSuccess.get()
        return if (last == 0L) -1L else System.currentTimeMillis() - last
    }
    fun fullAgeMs(): Long {
        val last = lastFullSuccess.get()
        return if (last == 0L) -1L else System.currentTimeMillis() - last
    }
    fun quickPasses(): Long = quickPassCount.get()
    fun fullPasses(): Long = fullPassCount.get()

    fun statusLine(): String {
        val qAge = quickAgeMs(); val fAge = fullAgeMs()
        return "quickPasses=${quickPassCount.get()} quickAgeMs=${if (qAge < 0) "uninit" else qAge.toString()} " +
            "fullPasses=${fullPassCount.get()} fullAgeMs=${if (fAge < 0) "uninit" else fAge.toString()}"
    }
    internal fun resetForTest() {
        lastQuickStart.set(0L); lastQuickSuccess.set(0L); quickPassCount.set(0L)
        lastFullStart.set(0L); lastFullSuccess.set(0L); fullPassCount.set(0L)
    }
}
