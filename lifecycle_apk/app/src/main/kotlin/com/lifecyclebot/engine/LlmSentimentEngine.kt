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

/**
 * LLM Sentiment Engine — Groq-backed structured sentiment scoring.
 *
 * Safe design goals:
 * - Never crashes decision flow
 * - Returns null on API failure so caller can fall back
 * - Caches results briefly to avoid hammering Groq
 * - Hard clamps all scores
 * - Avoids Kotlin stdlib ambiguity issues that can break CI builds
 */
class LlmSentimentEngine(
    private val groqApiKey: String = "",
) {

    data class LlmSentiment(
        val score: Double,              // -100 to +100
        val confidence: Double,         // 0 to 100
        val reasoning: String,
        val bullishSignals: List<String>,
        val bearishSignals: List<String>,
        val riskFlags: List<String>,
        val hardBlock: Boolean,
        val blockReason: String,
        val source: String,             // "llm" | "keyword_fallback"
    )

    private data class CacheEntry(
        val result: LlmSentiment,
        val timestampMs: Long,
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val cacheTtlMs = 5 * 60_000L

    @Volatile
    private var lastCallMs: Long = 0L

    private val minCallIntervalMs = 1_200L

    companion object {
        private const val TAG = "LlmSentiment"
        private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val MODEL = "llama-3.1-8b-instant"
        private const val MAX_EVENTS = 20
        private const val MAX_EVENT_CHARS = 120
        private const val MAX_BUNDLE_CHARS = 800
        private const val MAX_REASONING_CHARS = 220
        private const val DEFAULT_CONFIDENCE = 50.0
    }

    /**
     * Main scoring entrypoint.
     * Returns null if API key missing, no events, or remote call fails.
     */
    fun score(
        symbol: String,
        mintAddress: String,
        events: List<MentionEvent>,
    ): LlmSentiment? {
        if (groqApiKey.isBlank()) return null
        if (events.isEmpty()) return null

        return try {
            val sanitizedEvents = events
                .sortedByDescending { it.ts }
                .take(MAX_EVENTS)

            if (sanitizedEvents.isEmpty()) return null

            val cacheKey = buildCacheKey(symbol, mintAddress, sanitizedEvents)
            val now = System.currentTimeMillis()

            val cached = cache[cacheKey]
            if (cached != null && now - cached.timestampMs < cacheTtlMs) {
                return cached.result
            }

            val textBundle = buildTextBundle(sanitizedEvents)
            if (textBundle.isBlank()) return null

            val result = callGroq(
                symbol = symbol,
                mint = mintAddress,
                textBundle = textBundle,
            ) ?: return null

            cache[cacheKey] = CacheEntry(result, now)
            cleanupCacheIfNeeded(now)
            result
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "score failed: ${e.message}")
            null
        }
    }

    private fun buildCacheKey(
        symbol: String,
        mintAddress: String,
        events: List<MentionEvent>,
    ): String {
        var firstTs = Long.MAX_VALUE
        var lastTs = Long.MIN_VALUE
        var count = 0

        for (event in events) {
            val ts = event.ts
            if (ts < firstTs) firstTs = ts
            if (ts > lastTs) lastTs = ts
            count++
        }

        if (count == 0) {
            firstTs = 0L
            lastTs = 0L
        }

        val upperSymbol = symbol.uppercase()
        return upperSymbol + "_" + mintAddress + "_" + count + "_" + firstTs + "_" + lastTs
    }

    private fun buildTextBundle(events: List<MentionEvent>): String {
        val sb = StringBuilder()

        for (event in events) {
            val source = event.source.uppercase()
            val text = event.text
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(MAX_EVENT_CHARS)

            if (text.isBlank()) continue

            val candidate = "[$source] $text"
            val projectedLen = if (sb.isEmpty()) {
                candidate.length
            } else {
                sb.length + 1 + candidate.length
            }

            if (projectedLen > MAX_BUNDLE_CHARS) {
                break
            }

            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(candidate)
        }

        return sb.toString()
    }

    private fun callGroq(
        symbol: String,
        mint: String,
        textBundle: String,
    ): LlmSentiment? {
        throttle()

        val payload = JSONObject().apply {
            put("model", MODEL)
            put("temperature", 0.1)
            put("max_tokens", 400)
            put("response_format", JSONObject().put("type", "json_object"))
            put(
                "messages",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("role", "system")
                            put("content", systemPrompt())
                        }
                    )
                    put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", buildPrompt(symbol, mint, textBundle))
                        }
                    )
                }
            )
        }

        return try {
            val request = Request.Builder()
                .url(GROQ_URL)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .header("Authorization", "Bearer $groqApiKey")
                .header("Content-Type", "application/json")
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    ErrorLogger.warn(TAG, "Groq HTTP ${response.code}")
                    return null
                }

                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    ErrorLogger.warn(TAG, "Groq empty body")
                    return null
                }

                val content = JSONObject(body)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.trim()
                    .orEmpty()

                if (content.isBlank()) {
                    ErrorLogger.warn(TAG, "Groq missing content")
                    return null
                }

                parseLlmResponse(content)
            }
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "Groq call failed: ${e.message}")
            null
        }
    }

    private fun parseLlmResponse(jsonText: String): LlmSentiment? {
        return try {
            val j = JSONObject(jsonText)

            val bullish = j.optJSONArray("bullish_signals").toSafeStringList(limit = 8)
            val bearish = j.optJSONArray("bearish_signals").toSafeStringList(limit = 8)
            val risks = j.optJSONArray("risk_flags").toSafeStringList(limit = 8)

            val hardBlock = j.optBoolean("hard_block", false)
            val rawBlockReason = j.optString("block_reason", "").trim()

            LlmSentiment(
                score = j.optDouble("score", 0.0).coerceIn(-100.0, 100.0),
                confidence = j.optDouble("confidence", DEFAULT_CONFIDENCE).coerceIn(0.0, 100.0),
                reasoning = j.optString("reasoning", "")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(MAX_REASONING_CHARS),
                bullishSignals = bullish,
                bearishSignals = bearish,
                riskFlags = risks,
                hardBlock = hardBlock,
                blockReason = if (hardBlock) rawBlockReason.take(160) else "",
                source = "llm",
            )
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "parse failed: ${e.message}")
            null
        }
    }

    private fun JSONArray?.toSafeStringList(limit: Int): List<String> {
        if (this == null || this.length() == 0) return emptyList()

        val out = ArrayList<String>()
        val capped = if (this.length() < limit) this.length() else limit

        for (i in 0 until capped) {
            val value = this.optString(i).trim()
            if (value.isNotBlank()) {
                out.add(value.take(80))
            }
        }

        return out
    }

    private fun throttle() {
        val now = System.currentTimeMillis()
        val waitMs = minCallIntervalMs - (now - lastCallMs)

        if (waitMs > 0L) {
            try {
                Thread.sleep(waitMs)
            } catch (_: InterruptedException) {
            }
        }

        lastCallMs = System.currentTimeMillis()
    }

    private fun cleanupCacheIfNeeded(now: Long) {
        if (cache.size <= 200) return
        cache.entries.removeIf { now - it.value.timestampMs > cacheTtlMs }
    }

    private fun systemPrompt(): String = """
You are a Solana meme coin trading analyst. Analyze recent social chatter and return ONLY valid JSON in this exact structure:
{
  "score": <number from -100 to 100>,
  "confidence": <number from 0 to 100>,
  "reasoning": "<short summary>",
  "bullish_signals": ["<signal>", "..."],
  "bearish_signals": ["<signal>", "..."],
  "risk_flags": ["<flag>", "..."],
  "hard_block": <true or false>,
  "block_reason": "<reason if blocked, else empty string>"
}

Interpretation rules:
- Positive score = bullish sentiment
- Negative score = bearish sentiment
- hard_block = true only for very strong scam/rug/honeypot style evidence
- Keep reasoning concise
- Do not include markdown
""".trimIndent()

    private fun buildPrompt(
        symbol: String,
        mint: String,
        text: String,
    ): String {
        val shortMint = if (mint.length > 8) mint.take(8) else mint
        return """
Token: $symbol ($shortMint…)

Recent social activity:
$text

Analyze the sentiment, identify bullish and bearish signals, list risk flags, and decide whether this deserves a hard block.
""".trimIndent()
    }
}