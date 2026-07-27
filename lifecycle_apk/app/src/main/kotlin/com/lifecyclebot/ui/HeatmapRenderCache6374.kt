package com.lifecyclebot.ui

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6374 — HEATMAP RENDER CACHE (ANR fix).
 *
 * Operator directive (verbatim): "investigate the anr issues as well. after
 * running for 5 hours there was thousands and the bot was stuck".
 *
 * Snapshot pre-freeze rolling main-thread sample showed the top blocking
 * call site was:
 *   at com.lifecyclebot.ui.MainActivity.renderWrRecoveryHeatmap(SourceFile:193)
 *
 * That function ran on the MAIN thread every updateUi() tick (typically
 * every 1-2s) and did 6 synchronous SQLite reads against TradeHistoryStore
 * (5 × rollingWinRatePctSlice + 1 × rollingWinRatePct + getLifetimeStats)
 * PLUS FreeRangeMode.phaseTargetWr — even though the underlying data
 * moves at trade-close cadence (~seconds to minutes apart). Over a 5h
 * session that is ~9000-18000 redundant SQLite passes, each holding the
 * UI thread for 100-250ms on device — cumulative frozen-frame time is
 * what triggers the "thousands of ANRs" the operator saw.
 *
 * Fix: computation runs on Dispatchers.Default at a bounded cadence
 * (MIN_REFRESH_MS); the main thread reads whatever CharSequence is in
 * the [AtomicReference] cache and does a single setText.
 *
 * Doctrine: fluid/learnt cadence. The MIN_REFRESH_MS is the DEFAULT; the
 * on-board learning/UI layer can dial it via [setMinRefreshMs] without
 * touching the render surface. NEVER blocks — if a refresh is in-flight
 * we just return the cached value.
 */
object HeatmapRenderCache6374 {

    private const val DEFAULT_MIN_REFRESH_MS: Long = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cached = AtomicReference<CharSequence?>(null)
    private val lastComputedAt = AtomicLong(0L)
    private val minRefreshMs = AtomicLong(DEFAULT_MIN_REFRESH_MS)
    private val computing = AtomicBoolean(false)

    /**
     * Returns the currently-cached heatmap text (may be null on first call
     * before background computation completes) and asynchronously kicks off
     * a refresh if the cache is older than [minRefreshMs].
     *
     * Safe to call from the main thread — never blocks on IO/SQLite.
     */
    fun get(): CharSequence? {
        val now = System.currentTimeMillis()
        val age = now - lastComputedAt.get()
        if (age >= minRefreshMs.get() && computing.compareAndSet(false, true)) {
            scope.launch {
                try {
                    val built = withContext(Dispatchers.Default) { computeHeatmap() }
                    cached.set(built)
                    lastComputedAt.set(System.currentTimeMillis())
                } catch (_: Throwable) {
                    // Never crash on background render — cache retains last good value.
                } finally {
                    computing.set(false)
                }
            }
        }
        return cached.get()
    }

    /** Fluid tuning hook. */
    fun setMinRefreshMs(ms: Long) {
        minRefreshMs.set(ms.coerceIn(2_000L, 300_000L))
    }

    fun currentMinRefreshMs(): Long = minRefreshMs.get()
    fun lastComputedAt(): Long = lastComputedAt.get()
    fun hasCache(): Boolean = cached.get() != null

    /** Test-only reset. */
    internal fun resetForTest() {
        cached.set(null)
        lastComputedAt.set(0L)
        minRefreshMs.set(DEFAULT_MIN_REFRESH_MS)
        computing.set(false)
    }

    private fun computeHeatmap(): CharSequence {
        val store = com.lifecyclebot.engine.TradeHistoryStore
        val stats = store.getLifetimeStats()
        val totalSettled = (stats.totalWins + stats.totalLosses).toInt()
        val phaseTarget = try {
            com.lifecyclebot.engine.FreeRangeMode.phaseTargetWr(totalSettled)
        } catch (_: Throwable) { 30.0 }

        val sliceWidth = 50
        val sliceCount = 5
        val builder = SpannableStringBuilder("WR SLICES: ")
        val rolling = store.rollingWinRatePct(sliceWidth)
        for (i in 0 until sliceCount) {
            val pct = store.rollingWinRatePctSlice(offset = i * sliceWidth, width = sliceWidth)
            val color = when {
                pct < 0 -> 0xFF4B5563.toInt()
                phaseTarget <= 0 -> 0xFF4B5563.toInt()
                pct >= phaseTarget -> 0xFF10B981.toInt()
                pct >= phaseTarget * 0.85 -> 0xFFF59E0B.toInt()
                else -> 0xFFEF4444.toInt()
            }
            val start = builder.length
            builder.append("▰")
            builder.setSpan(
                ForegroundColorSpan(color),
                start, builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        val rollLabel = if (rolling >= 0) "%.0f%%".format(rolling) else "—"
        val targetLabel = if (phaseTarget > 0) "${phaseTarget.toInt()}%" else "—"
        builder.append("  roll50=$rollLabel / target=$targetLabel")
        return builder
    }
}
