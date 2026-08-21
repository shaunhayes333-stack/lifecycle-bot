package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/** V5.0.6479 — background-only Gemini exit advice cache. */
object AsyncGeminiExitAdviceCache6479 {
    data class Entry(val advice: GeminiCopilot.ExitAdvice?, val updatedAtMs: Long)
    private const val TTL_MS = 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ConcurrentHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private fun key(mint: String, pnlPct: Double, peakPct: Double): String =
        "$mint|p${floor(pnlPct / 5.0).toInt()}|h${floor(peakPct / 5.0).toInt()}"

    fun cachedOrRequest(
        ts: TokenState,
        currentPnlPct: Double,
        holdTimeMinutes: Double,
        peakPnlPct: Double,
        recentPriceAction: List<Double>,
    ): GeminiCopilot.ExitAdvice? {
        val k = key(ts.mint, currentPnlPct, peakPnlPct)
        val now = System.currentTimeMillis()
        val cached = cache[k]?.takeIf { now - it.updatedAtMs <= TTL_MS }
        if (cached == null && inFlight.add(k)) {
            scope.launch {
                try {
                    val advice = GeminiCopilot.getExitAdvice(ts, currentPnlPct, holdTimeMinutes, peakPnlPct, recentPriceAction)
                    cache[k] = Entry(advice, System.currentTimeMillis())
                    try { PipelineHealthCollector.labelInc("GEMINI_EXIT_CACHE_REFRESHED_6479") } catch (_: Throwable) {}
                } catch (_: Throwable) {
                    try { PipelineHealthCollector.labelInc("GEMINI_EXIT_CACHE_REFRESH_FAILED_6479") } catch (_: Throwable) {}
                } finally { inFlight.remove(k) }
            }
        }
        if (cached == null) try { PipelineHealthCollector.labelInc("GEMINI_EXIT_CACHE_MISS_NEUTRAL_6479") } catch (_: Throwable) {}
        return cached?.advice
    }
}
