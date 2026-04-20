package com.lifecyclebot.engine

import com.lifecyclebot.data.MentionEvent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayList
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class LlmSentimentEngine(
    private val groqApiKey: String = ""
) {

    data class LlmSentiment(
        val score: Double,
        val confidence: Double,
        val reasoning: String,
        val bullishSignals: List<String>,
        val bearishSignals: List<String>,
        val riskFlags: List<String>,
        val hardBlock: Boolean,
        val blockReason: String,
        val source: String
    )

    private data class CacheEntry(
        val result: LlmSentiment,
        val timestampMs: Long
    )

    companion object {
        private const val TAG = "LlmSentiment"
        private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val MODEL = "llama-3.1-8b-instant"
        private const val MAX_EVENTS = 20
        private const val MAX_EVENT_CHARS = 120
        private const val MAX_BUNDLE_CHARS = 800
        private const val MAX_REASONING_CHARS = 220
        private const val DEFAULT_CONFIDENCE = 50.0
        private const val MAX_COMPLETION_TOKENS = 400
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val cacheTtlMs = 5L * 60_000L

    @Volatile
    private var lastCallMs: Long = 0L

    private val throttleLock = Any()
    private val minCallIntervalMs = 2_100L

    fun score(
        symbol: String,
        mintAddress: String,
        events: List<MentionEvent>
    ): LlmSentiment? {
        if (groqApiKey.isBlank()) return null
        if (events.isEmpty()) return null

        return try {
            val sanitizedEvents = events
                .sortedByDescending { it.ts }
                .take(MAX_EVENTS)

            if (sanitizedEvents.isEmpty()) {
                null
            } else {
                val cacheKey = buildCacheKey(symbol, mintAddress, sanitizedEvents)
                val now = System.currentTimeMillis()

                val cached = cache[cacheKey]
                if (cached != null && now - cached.timestampMs < cacheTtlMs) {
                    return cached.result
                }

                val textBundle = buildTextBundle(sanitizedEvents)
                if (textBundle.isBlank()) {
                    null
                } else {
                    val result = callGroq(symbol, mintAddress, textBundle)
                    if (result != null) {
                        cache[cacheKey] = CacheEntry(result, now)
                        cleanupCacheIfNeeded(now)
                    }
                    result
                }
            }
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "score failed: ${e.message}")
            null
        }
    }

    private fun buildCacheKey(
        symbol: String,
        mintAddress: String,
        events: List<MentionEvent>
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

        val upperSymbol = symbol.toUpperCase(Locale.US)
        return upperSymbol + "_" + mintAddress + "_" + count + "_" + firstTs + "_" + lastTs
    }

    private fun buildTextBundle(events: List<MentionEvent>): String {
        val sb = StringBuilder()

        for (event in events) {
            val source = (event.source?.toString() ?: "UNKNOWN")
                .toUpperCase(Locale.US)

            val rawText = event.text?.toString() ?: ""
            val text = rawText
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(MAX_EVENT_CHARS)

            if (text.isBlank()) continue

            val candidate = "[$source] $text"
            val projectedLen = if (sb.length == 0) {
                candidate.length
            } else {
                sb.length + 1 + candidate.length
            }

            if (projectedLen > MAX_BUNDLE_CHARS) break

            if (sb.length > 0) sb.append('\n')
            sb.append(candidate)
        }

        return sb.toString()
    }

    private fun callGroq(
        symbol: String,
        mint: String,
        textBundle: String
    ): LlmSentiment? {
        throttle()

        val payload = JSONObject()
        payload.put("model", MODEL)
        payload.put("temperature", 0.1)
        payload.put("max_completion_tokens", MAX_COMPLETION_TOKENS)
        payload.put("response_format", JSONObject().put("type", "json_object"))

        val messages = JSONArray()
        messages.put(
            JSONObject()
                .put("role", "system")
                .put("content", systemPrompt())
        )
        messages.put(
            JSONObject()
                .put("role", "user")
                .put("content", buildPrompt(symbol, mint, textBundle))
        )
        payload.put("messages", messages)

        return try {
            val request = Request.Builder()
                .url(GROQ_URL)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .header("Authorization", "Bearer $groqApiKey")
                .header("Content-Type", "application/json")
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty().take(300)
                    ErrorLogger.warn(TAG, "Groq HTTP ${response.code}: $errorBody")
                    return@use null
                }

                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    ErrorLogger.warn(TAG, "Groq empty body")
                    return@use null
                }

                val root = JSONObject(body)
                val choices = root.optJSONArray("choices")
                val firstChoice = if (choices != null && choices.length() > 0) {
                    choices.optJSONObject(0)
                } else {
                    null
                }

                val message = firstChoice?.optJSONObject("message")
                val content = message?.optString("content", "")?.trim().orEmpty()

                if (content.isBlank()) {
                    ErrorLogger.warn(TAG, "Groq missing content")
                    return@use null
                }

                parseLlmResponse(extractJsonObject(content))
            }
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "Groq call failed: ${e.message}")
            null
        }
    }

    private fun parseLlmResponse(jsonText: String): LlmSentiment? {
        return try {
            val j = JSONObject(jsonText)

            val bullish = toSafeStringList(j.optJSONArray("bullish_signals"), 8)
            val bearish = toSafeStringList(j.optJSONArray("bearish_signals"), 8)
            val risks = toSafeStringList(j.optJSONArray("risk_flags"), 8)

            val hardBlock = j.optBoolean("hard_block", false)
            val rawBlockReason = j.optString("block_reason", "").trim()

            LlmSentiment(
                score = safeDouble(j, "score", 0.0).coerceIn(-100.0, 100.0),
                confidence = safeDouble(j, "confidence", DEFAULT_CONFIDENCE).coerceIn(0.0, 100.0),
                reasoning = j.optString("reasoning", "")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(MAX_REASONING_CHARS),
                bullishSignals = bullish,
                bearishSignals = bearish,
                riskFlags = risks,
                hardBlock = hardBlock,
                blockReason = if (hardBlock) rawBlockReason.take(160) else "",
                source = "llm"
            )
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "parse failed: ${e.message}")
            null
        }
    }

    private fun safeDouble(obj: JSONObject, key: String, fallback: Double): Double {
        return try {
            if (!obj.has(key) || obj.isNull(key)) return fallback

            val value = obj.opt(key)
            when (value) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull() ?: fallback
                else -> fallback
            }
        } catch (_: Exception) {
            fallback
        }
    }

    private fun toSafeStringList(array: JSONArray?, limit: Int): List<String> {
        if (array == null || array.length() == 0) return emptyList()

        val out = ArrayList<String>()
        val capped = if (array.length() < limit) array.length() else limit

        for (i in 0 until capped) {
            val value = array.optString(i, "").trim()
            if (value.isNotBlank()) {
                out.add(value.take(80))
            }
        }

        return out
    }

    private fun throttle() {
        synchronized(throttleLock) {
            val now = System.currentTimeMillis()
            val elapsed = now - lastCallMs
            val waitMs = minCallIntervalMs - elapsed

            if (waitMs > 0L) {
                try {
                    Thread.sleep(waitMs)
                } catch (_: InterruptedException) {
                }
            }

            lastCallMs = System.currentTimeMillis()
        }
    }

    private fun cleanupCacheIfNeeded(now: Long) {
        if (cache.size <= 200) return

        val keysToRemove = ArrayList<String>()
        for ((key, value) in cache) {
            if (now - value.timestampMs > cacheTtlMs) {
                keysToRemove.add(key)
            }
        }

        for (key in keysToRemove) {
            cache.remove(key)
        }
    }

    private fun extractJsonObject(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed
        }

        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')

        return if (firstBrace >= 0 && lastBrace > firstBrace) {
            trimmed.substring(firstBrace, lastBrace + 1)
        } else {
            trimmed
        }
    }

    private fun systemPrompt(): String {
        return """
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
- Do not wrap the JSON in code fences
""".trimIndent()
    }

    private fun buildPrompt(
        symbol: String,
        mint: String,
        text: String
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