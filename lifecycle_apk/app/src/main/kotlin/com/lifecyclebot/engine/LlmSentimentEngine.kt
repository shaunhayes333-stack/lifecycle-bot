package com.lifecyclebot.engine

import com.lifecyclebot.data.MentionEvent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * LlmSentimentEngine — Groq-backed social sentiment layer with safe fallback.
 *
 * Notes:
 * - Thread-safe cache
 * - Proper HTTP resource closing
 * - JSON sanitization for code-fenced responses
 * - Internal keyword fallback available through scoreWithFallback()
 * - Conservative hard-block behavior
 */
class LlmSentimentEngine(
    groqApiKey: String = "",
) {

    private val apiKey = groqApiKey.trim()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()
    private val groqUrl = "https://api.groq.com/openai/v1/chat/completions"
    private val model = "llama-3.1-8b-instant"

    private data class CachedSentiment(
        val result: LlmSentiment,
        val timestamp: Long,
    )

    private val cache = ConcurrentHashMap<String, CachedSentiment>()
    private val cacheTtlMs = 5 * 60_000L

    data class LlmSentiment(
        val score: Double,              // -100 to +100
        val confidence: Double,         // 0-100
        val reasoning: String,
        val bullishSignals: List<String>,
        val bearishSignals: List<String>,
        val riskFlags: List<String>,
        val hardBlock: Boolean,
        val blockReason: String,
        val source: String,             // "llm" | "keyword_fallback"
    )

    /**
     * Original interface:
     * Returns null if API key is missing or LLM call fails.
     */
    fun score(
        symbol: String,
        mintAddress: String,
        events: List<MentionEvent>,
    ): LlmSentiment? {
        if (apiKey.isBlank()) return null
        if (events.isEmpty()) return null

        val normalizedSymbol = symbol.trim().ifBlank { "UNKNOWN" }
        val cacheKey = buildCacheKey(normalizedSymbol, mintAddress, events)

        cache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.timestamp < cacheTtlMs) {
                return cached.result
            } else {
                cache.remove(cacheKey)
            }
        }

        val textBundle = buildTextBundle(events)
        if (textBundle.isBlank()) return null

        val result = callGroq(normalizedSymbol, mintAddress, textBundle) ?: return null
        cache[cacheKey] = CachedSentiment(result, System.currentTimeMillis())
        return result
    }

    /**
     * Safer interface for callers that always want a usable signal.
     * Uses keyword fallback if Groq is unavailable.
     */
    fun scoreWithFallback(
        symbol: String,
        mintAddress: String,
        events: List<MentionEvent>,
    ): LlmSentiment {
        return score(symbol, mintAddress, events)
            ?: keywordFallback(symbol, mintAddress, events)
    }

    fun isConfigured(): Boolean = apiKey.isNotBlank()

    private fun buildCacheKey(
        symbol: String,
        mintAddress: String,
        events: List<MentionEvent>,
    ): String {
        val newestTs = events.maxOfOrNull { it.ts } ?: 0L
        val oldestTs = events.minOfOrNull { it.ts } ?: 0L
        val textFingerprint = events
            .sortedByDescending { it.ts }
            .take(10)
            .joinToString("|") { "${it.source}:${it.text.take(40)}" }
            .hashCode()

        return "$symbol|$mintAddress|${events.size}|$oldestTs|$newestTs|$textFingerprint"
    }

    private fun buildTextBundle(events: List<MentionEvent>): String {
        val builder = StringBuilder()
        for (event in events.sortedByDescending { it.ts }.take(20)) {
            val line = "[${event.source.uppercase()}] ${event.text.replace('\n', ' ').trim().take(120)}"
            if (line.isBlank()) continue
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(line)
            if (builder.length >= 800) break
        }
        return builder.toString().take(800).trim()
    }

    private fun callGroq(
        symbol: String,
        mint: String,
        textBundle: String,
    ): LlmSentiment? {
        val prompt = buildPrompt(symbol, mint, textBundle)

        val payload = JSONObject().apply {
            put("model", model)
            put("temperature", 0.1)
            put("max_tokens", 400)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        return try {
            val request = Request.Builder()
                .url(groqUrl)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    ErrorLogger.warn("LlmSentiment", "Groq HTTP ${response.code} for $symbol")
                    return null
                }

                val body = response.body?.string()?.trim().orEmpty()
                if (body.isBlank()) {
                    ErrorLogger.warn("LlmSentiment", "Empty Groq response for $symbol")
                    return null
                }

                val content = JSONObject(body)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    ?.trim()
                    .orEmpty()

                if (content.isBlank()) {
                    ErrorLogger.warn("LlmSentiment", "Missing Groq message content for $symbol")
                    return null
                }

                parseLlmResponse(content)
            }
        } catch (e: Exception) {
            ErrorLogger.warn("LlmSentiment", "Groq call failed for $symbol: ${e.message}")
            null
        }
    }

    private fun parseLlmResponse(rawJson: String): LlmSentiment? {
        return try {
            val json = sanitizeJson(rawJson)
            val j = JSONObject(json)

            val bullish = j.optJSONArray("bullish_signals").toStringList()
            val bearish = j.optJSONArray("bearish_signals").toStringList()
            val risks = j.optJSONArray("risk_flags").toStringList()

            val score = j.optDouble("score", 0.0).coerceIn(-100.0, 100.0)
            val confidence = j.optDouble("confidence", 50.0).coerceIn(0.0, 100.0)
            val hardBlock = j.optBoolean("hard_block", false)
            val blockReason = j.optString("block_reason", "").trim().take(120)

            LlmSentiment(
                score = score,
                confidence = confidence,
                reasoning = j.optString("reasoning", "").trim().take(220),
                bullishSignals = bullish,
                bearishSignals = bearish,
                riskFlags = risks,
                hardBlock = hardBlock,
                blockReason = if (hardBlock) blockReason else "",
                source = "llm",
            )
        } catch (e: Exception) {
            ErrorLogger.warn("LlmSentiment", "Parse failed: ${e.message}")
            null
        }
    }

    private fun sanitizeJson(input: String): String {
        var text = input.trim()

        if (text.startsWith("```")) {
            text = text
                .removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        }

        val firstBrace = text.indexOf('{')
        val lastBrace = text.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            text = text.substring(firstBrace, lastBrace + 1)
        }

        return text
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val value = optString(i, "").trim()
            if (value.isNotEmpty()) out.add(value.take(60))
        }
        return out
    }

    /**
     * Simple internal fallback.
     * Conservative and intentionally dumb, but stable.
     */
    private fun keywordFallback(
        symbol: String,
        mintAddress: String,
        events: List<MentionEvent>,
    ): LlmSentiment {
        if (events.isEmpty()) {
            return LlmSentiment(
                score = 0.0,
                confidence = 15.0,
                reasoning = "No social data available.",
                bullishSignals = emptyList(),
                bearishSignals = emptyList(),
                riskFlags = emptyList(),
                hardBlock = false,
                blockReason = "",
                source = "keyword_fallback",
            )
        }

        val positiveWords = listOf(
            "send", "moon", "bullish", "breakout", "runner", "gem", "accumulation",
            "strong", "buying", "support", "squeeze", "uptrend", "hold"
        )
        val negativeWords = listOf(
            "dump", "rug", "honeypot", "scam", "sell", "distribution", "draining",
            "fake", "washed", "dead", "exit", "insiders", "bundled"
        )
        val hardBlockWords = listOf(
            "honeypot confirmed", "cant sell", "can't sell", "dev dumped", "rug confirmed"
        )

        var pos = 0
        var neg = 0
        val bullishSignals = mutableListOf<String>()
        val bearishSignals = mutableListOf<String>()
        val riskFlags = mutableListOf<String>()
        var hardBlock = false
        var blockReason = ""

        val sample = events.sortedByDescending { it.ts }.take(20)

        for (event in sample) {
            val text = event.text.lowercase()

            positiveWords.forEach { word ->
                if (text.contains(word)) {
                    pos++
                    if (bullishSignals.size < 5) bullishSignals.add(word)
                }
            }

            negativeWords.forEach { word ->
                if (text.contains(word)) {
                    neg++
                    if (bearishSignals.size < 5) bearishSignals.add(word)
                    if (word in listOf("rug", "honeypot", "fake", "bundled", "insiders") && riskFlags.size < 5) {
                        riskFlags.add(word)
                    }
                }
            }

            val matchedBlock = hardBlockWords.firstOrNull { text.contains(it) }
            if (matchedBlock != null) {
                hardBlock = true
                blockReason = matchedBlock
            }
        }

        val raw = (pos - neg) * 8.0
        val score = raw.coerceIn(-100.0, 100.0)
        val density = min(1.0, (pos + neg).toDouble() / maxOf(sample.size, 1).toDouble())
        val confidence = (25.0 + density * 35.0 + min(20.0, abs(score) * 0.2)).coerceIn(15.0, 80.0)

        return LlmSentiment(
            score = score,
            confidence = confidence,
            reasoning = when {
                hardBlock -> "Keyword fallback detected a hard scam/rug indicator in recent mentions."
                score > 20 -> "Keyword fallback sees more bullish than bearish social language."
                score < -20 -> "Keyword fallback sees more bearish than bullish social language."
                else -> "Keyword fallback sees mixed or weak social sentiment."
            },
            bullishSignals = bullishSignals.distinct(),
            bearishSignals = bearishSignals.distinct(),
            riskFlags = riskFlags.distinct(),
            hardBlock = hardBlock,
            blockReason = blockReason,
            source = "keyword_fallback",
        )
    }

    private val systemPrompt = """
You are a Solana meme coin trading analyst. Analyse social media text about tokens and return ONLY valid JSON with this exact structure:
{
  "score": <number -100 to 100, positive=bullish>,
  "confidence": <number 0-100>,
  "reasoning": "<1-2 sentence summary>",
  "bullish_signals": ["<signal>", ...],
  "bearish_signals": ["<signal>", ...],
  "risk_flags": ["<flag>", ...],
  "hard_block": <true if rug/scam/honeypot detected, else false>,
  "block_reason": "<reason if hard_block is true, else empty string>"
}

Risk flags to watch for: coordinated pump, wash trading, fake volume, honeypot, dev dump warning, insider selling, copied from another token, artificial hype.
Hard block triggers: explicit rug confirmation, honeypot confirmed, dev dumped, can't sell tokens.
""".trimIndent()

    private fun buildPrompt(symbol: String, mint: String, text: String): String = """
Token: $symbol (${mint.take(8)}…)

Recent social media activity:
$text

Analyse the sentiment and risk level of this token based on the above social posts.
""".trimIndent()

    fun cleanup() {
        val now = System.currentTimeMillis()
        cache.entries.removeIf { now - it.value.timestamp > cacheTtlMs }
    }

    fun clearCache() {
        cache.clear()
    }
}