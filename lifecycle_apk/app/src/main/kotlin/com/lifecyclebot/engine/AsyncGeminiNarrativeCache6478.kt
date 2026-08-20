package com.lifecyclebot.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** V5.0.6478 — background-only Gemini narrative cache. */
object AsyncGeminiNarrativeCache6478 {
    data class Entry(
        val quickScam: Boolean?,
        val analysis: GeminiCopilot.NarrativeAnalysis?,
        val updatedAtMs: Long,
    )

    private const val TTL_MS = 10 * 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ConcurrentHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private fun key(symbol: String, name: String): String =
        "${symbol.trim().uppercase()}|${name.trim().uppercase()}"

    fun cachedOrRequest(symbol: String, name: String): Entry? {
        val k = key(symbol, name)
        val now = System.currentTimeMillis()
        val cached = cache[k]?.takeIf { now - it.updatedAtMs <= TTL_MS }
        if (cached == null && inFlight.add(k)) {
            scope.launch {
                try {
                    val quick = GeminiCopilot.quickScamCheck(symbol, name)
                    val analysis = if (quick == true) null else GeminiCopilot.analyzeNarrative(
                        symbol = symbol, name = name, description = "", socialMentions = emptyList(),
                    )
                    cache[k] = Entry(quick, analysis, System.currentTimeMillis())
                    try { PipelineHealthCollector.labelInc("GEMINI_NARRATIVE_CACHE_REFRESHED_6478") } catch (_: Throwable) {}
                } catch (_: Throwable) {
                    try { PipelineHealthCollector.labelInc("GEMINI_NARRATIVE_CACHE_REFRESH_FAILED_6478") } catch (_: Throwable) {}
                } finally { inFlight.remove(k) }
            }
        }
        if (cached == null) try { PipelineHealthCollector.labelInc("GEMINI_NARRATIVE_CACHE_MISS_NEUTRAL_6478") } catch (_: Throwable) {}
        return cached
    }

    fun statusLine(): String = "cached=${cache.size} inFlight=${inFlight.size}"
    internal fun resetForTest() { cache.clear(); inFlight.clear() }
}
