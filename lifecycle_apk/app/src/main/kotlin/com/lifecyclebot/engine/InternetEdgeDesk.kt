package com.lifecyclebot.engine

import com.lifecyclebot.util.AppDispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.0.3856 — InternetEdgeDesk.
 *
 * Uses the configured LLM as a background research desk / internet scout. This is
 * deliberately NOT a hot-path oracle: no FDG calls, no executor calls, no scanner
 * blocking, no synchronous entry/exit dependency. It refreshes a compact market
 * brief on AppDispatchers.sideEffect, then ToolkitSignalSheet reads only the cached
 * snapshot as a soft style/setup bias.
 */
object InternetEdgeDesk {
    data class Brief(
        val atMs: Long = 0L,
        val confidence: Double = 0.0,
        val riskMode: String = "unknown",
        val summary: String = "not refreshed yet",
        val setupBias: Map<String, Double> = emptyMap(),
        val laneBias: Map<String, Double> = emptyMap(),
        val watchThemes: List<String> = emptyList(),
        val avoidThemes: List<String> = emptyList(),
        val source: String = "default",
    )

    private const val TTL_MS = 20L * 60_000L
    private const val MIN_REFRESH_GAP_MS = 5L * 60_000L
    private val inFlight = AtomicBoolean(false)

    @Volatile private var lastRefreshAttemptMs: Long = 0L
    @Volatile private var cached: Brief = Brief()

    fun snapshot(): Brief = cached

    fun setupScoreBias(setupName: String): Double = try {
        val b = cached
        if (System.currentTimeMillis() - b.atMs > TTL_MS) 0.0
        else (b.setupBias[setupName.uppercase(Locale.US)] ?: 0.0).coerceIn(-8.0, 8.0)
    } catch (_: Throwable) { 0.0 }

    fun summaryLine(): String {
        val b = cached
        val ageMin = if (b.atMs > 0L) ((System.currentTimeMillis() - b.atMs) / 60_000L).coerceAtLeast(0L) else -1L
        val topSetups = b.setupBias.entries.sortedByDescending { kotlin.math.abs(it.value) }.take(5)
            .joinToString(" ") { "${it.key.lowercase(Locale.US)}=${it.value.fmt1()}" }
            .ifBlank { "none" }
        return "InternetEdge: source=${b.source} age=${ageMin}m conf=${b.confidence.fmt1()} risk=${b.riskMode} setups=[$topSetups] summary=${b.summary.take(180)}"
    }

    fun refreshAsync(trigger: String, context: String = "") {
        val now = System.currentTimeMillis()
        if (now - lastRefreshAttemptMs < MIN_REFRESH_GAP_MS) return
        if (!inFlight.compareAndSet(false, true)) return
        lastRefreshAttemptMs = now
        GlobalScope.launch(AppDispatchers.sideEffect) {
            try {
                if (!GeminiCopilot.isConfigured() || GeminiCopilot.isAIDegraded()) {
                    try { PipelineHealthCollector.labelInc("INTERNET_EDGE_SKIPPED_LLM_UNAVAILABLE") } catch (_: Throwable) {}
                    return@launch
                }
                val response = GeminiCopilot.rawText(
                    userPrompt = buildPrompt(trigger, context),
                    systemPrompt = "You are AATE's internet research desk. If live web/search is available, use it. Return ONLY compact JSON. Never recommend blocking trades; provide soft style biases only.",
                    temperature = 0.15,
                    maxTokens = 700,
                ) ?: return@launch
                val brief = parseBrief(response)
                if (brief != null) {
                    val src = if (brief.source == "llm_text") "llm_text" else "llm_internet"
                    cached = brief.copy(atMs = System.currentTimeMillis(), source = src)
                    try { PipelineHealthCollector.labelInc("INTERNET_EDGE_REFRESHED") } catch (_: Throwable) {}
                } else {
                    try { PipelineHealthCollector.labelInc("INTERNET_EDGE_PARSE_FAILED") } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {
                try { PipelineHealthCollector.labelInc("INTERNET_EDGE_REFRESH_FAILED") } catch (_: Throwable) {}
            } finally {
                inFlight.set(false)
            }
        }
    }

    private fun buildPrompt(trigger: String, context: String): String = """
Trigger: $trigger
Current bot context:
${context.take(1800)}

Research/use current internet context if available: crypto market tone, Solana/meme liquidity, social narratives, macro/regulatory risk, exchange/news shocks, likely scam/honeypot themes, and attention rotations.

Return JSON exactly:
{
  "confidence": 0-100,
  "riskMode": "risk_on|neutral|risk_off|hostile",
  "summary": "one compact sentence",
  "watchThemes": ["theme"],
  "avoidThemes": ["theme"],
  "setupBias": {"CHART_BREAKOUT": 2.0, "DEGEN_MICRO_SNIPE": -2.0},
  "laneBias": {"MOONSHOT": 1.0, "SHITCOIN": -1.0}
}
Bias values must be soft, -8..+8 max. No hard blocks.
""".trimIndent()

    private fun parseBrief(raw: String): Brief? = try {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) {
            Brief(
                confidence = 15.0,
                riskMode = "neutral",
                summary = raw.replace(Regex("\\s+"), " ").take(260),
                source = "llm_text"
            )
        } else {
            val jsonText = raw.substring(start, end + 1)
            val obj = JSONObject(jsonText)
            Brief(
                confidence = obj.optDouble("confidence", 0.0).coerceIn(0.0, 100.0),
                riskMode = obj.optString("riskMode", "unknown").take(32),
                summary = obj.optString("summary", "").replace(Regex("\\s+"), " ").take(260),
                watchThemes = obj.optJSONArray("watchThemes")?.toList(8) ?: emptyList(),
                avoidThemes = obj.optJSONArray("avoidThemes")?.toList(8) ?: emptyList(),
                setupBias = obj.optJSONObject("setupBias")?.toBiasMap() ?: emptyMap(),
                laneBias = obj.optJSONObject("laneBias")?.toBiasMap() ?: emptyMap(),
            )
        }
    } catch (_: Throwable) { null }

    private fun JSONObject.toBiasMap(): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        val it = keys()
        while (it.hasNext() && out.size < 24) {
            val rawKey = it.next()
            val k = rawKey.uppercase(Locale.US).take(48)
            out[k] = optDouble(rawKey, 0.0).coerceIn(-8.0, 8.0)
        }
        return out
    }

    private fun org.json.JSONArray.toList(limit: Int): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until length().coerceAtMost(limit)) out += optString(i, "").take(48)
        return out.filter { it.isNotBlank() }
    }

    private fun Double.fmt1(): String = String.format(Locale.US, "%.1f", this)
}
