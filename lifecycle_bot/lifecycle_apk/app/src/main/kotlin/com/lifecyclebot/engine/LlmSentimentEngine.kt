package com.lifecyclebot.engine

import com.lifecyclebot.data.MentionEvent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * LLM Sentiment Engine — free via Groq API.
 *
 * Groq free tier:
 *   Model:  llama-3.1-8b-instant
 *   Limit:  30 requests/min, 14,400 req/day — more than enough
 *   Speed:  ~200 tokens/sec (extremely fast)
 *   Cost:   $0
 *
 * Get a free key at: https://console.groq.com (no credit card needed)
 *
 * How it works:
 *   1. Batch recent tweets + Telegram messages into chunks of ~800 chars
 *   2. Send to Groq with a structured prompt asking for JSON sentiment
 *   3. Parse the JSON response: score, reasoning, key signals, risk flags
 *   4. Cache result for 5 minutes to avoid hammering the API
 *
 * Falls back to keyword scoring (SentimentAnalyzer) if no key configured
 * or if Groq is unavailable.
 *
 * Why Groq over Claude API here:
 *   - Claude API costs money per token
 *   - Groq llama-3.1-8b is genuinely free at this volume
 *   - For structured sentiment on short crypto texts it's more than adequate
 */
class LlmSentimentEngine(private val groqApiKey: String = "") {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val JSON_MT = "application/json".toMediaType()
    private val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
    private val MODEL    = "llama-3.1-8b-instant"

    // Cache: cacheKey → (result, timestamp)
    private val cache = mutableMapOf<String, Pair<LlmSentiment, Long>>()
    private val CACHE_TTL_MS = 5 * 60_000L

    data class LlmSentiment(
        val score: Double,              // -100 to +100
        val confidence: Double,         // 0-100
        val reasoning: String,
        val bullishSignals: List<String>,
        val bearishSignals: List<String>,
        val riskFlags: List<String>,    // e.g. "coordinated pump", "fake volume"
        val hardBlock: Boolean,
        val blockReason: String,
        val source: String,             // "llm" | "keyword_fallback"
    )

    // ── public interface ──────────────────────────────────────────────

    /**
     * Score a batch of mention events using the LLM.
     * Returns null if key not configured — caller should fall back to keyword scoring.
     */
    fun score(
        symbol: String,
        mintAddress: String,
        events: List<MentionEvent>,
    ): LlmSentiment? {
        if (groqApiKey.isBlank()) return null
        if (events.isEmpty()) return null

        val cacheKey = "${mintAddress}_${events.size}_${events.lastOrNull()?.ts}"
        val cached   = cache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.second < CACHE_TTL_MS) {
            return cached.first
        }

        // Build text bundle — take most recent 20 events, max 800 chars total
        val textBundle = events
            .sortedByDescending { it.ts }
            .take(20)
            .joinToString("\n") { "[${it.source.uppercase()}] ${it.text.take(120)}" }
            .take(800)

        val result = callGroq(symbol, mintAddress, textBundle) ?: return null
        cache[cacheKey] = result to System.currentTimeMillis()
        return result
    }

    // ── Groq API call ─────────────────────────────────────────────────

    private fun callGroq(symbol: String, mint: String, textBundle: String): LlmSentiment? {
        val prompt = buildPrompt(symbol, mint, textBundle)

        val payload = JSONObject().apply {
            put("model", MODEL)
            put("temperature", 0.1)    // low temp = consistent structured output
            put("max_tokens", 400)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", SYSTEM_PROMPT)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        return try {
            val req = Request.Builder()
                .url(GROQ_URL)
                .post(payload.toString().toRequestBody(JSON_MT))
                .header("Authorization", "Bearer $groqApiKey")
                .header("Content-Type", "application/json")
                .build()

            val resp = http.newCall(req).execute()
            if (!resp.isSuccessful) return null

            val body    = resp.body?.string() ?: return null
            val content = JSONObject(body)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "") ?: return null

            parseLlmResponse(content)
        } catch (_: Exception) { null }
    }

    private fun parseLlmResponse(json: String): LlmSentiment? {
        return try {
            val j = JSONObject(json.trim())

            val bullish = j.optJSONArray("bullish_signals")
                ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList()
            val bearish = j.optJSONArray("bearish_signals")
                ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList()
            val risks   = j.optJSONArray("risk_flags")
                ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList()

            val hardBlock   = j.optBoolean("hard_block", false)
            val blockReason = j.optString("block_reason", "")

            LlmSentiment(
                score          = j.optDouble("score", 0.0).coerceIn(-100.0, 100.0),
                confidence     = j.optDouble("confidence", 50.0).coerceIn(0.0, 100.0),
                reasoning      = j.optString("reasoning", "").take(200),
                bullishSignals = bullish,
                bearishSignals = bearish,
                riskFlags      = risks,
                hardBlock      = hardBlock,
                blockReason    = blockReason,
                source         = "llm",
            )
        } catch (_: Exception) { null }
    }

    // ── prompts ───────────────────────────────────────────────────────

    private val SYSTEM_PROMPT = """
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
}
