package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * GeminiCopilot — Full AI Co-pilot Layer powered by Google Gemini
 *
 * Safer rewrite:
 * - fixes dead/null checks
 * - closes HTTP resources correctly
 * - handles markdown/code-fenced JSON responses
 * - strengthens rate limiting/backoff behavior
 * - clamps parsed values to safe ranges
 * - keeps same public API surface
 */
object GeminiCopilot {

    private const val TAG = "GeminiCopilot"

    // V5.9.39: Route through Emergent's universal LLM proxy by default.
    // The proxy speaks OpenAI-compatible chat completions and accepts the
    // universal "sk-emergent-..." key. Direct Gemini (generativelanguage.
    // googleapis.com) is still supported if the user supplies their own
    // AIza... key, but user's personal key got flagged as leaked by Google
    // so the default fallback is now the proxy.
    private const val EMERGENT_PROXY_URL = "https://integrations.emergentagent.com/llm/chat/completions"
    // V5.9.57: gemini-3-flash-preview hangs upstream (proxy returns no body,
    // curl confirmed 30s timeouts on that id). gemini-2.0-flash is the
    // fastest stable route (~0.7s) and does NOT drain tokens into
    // reasoning_tokens the way gemini-2.5-flash does (which returned
    // content=null when max_tokens was small). Lock in 2.0-flash for
    // sentient chat + structured JSON calls until a newer flash model
    // is verified working through the proxy.
    private const val EMERGENT_MODEL = "gemini/gemini-2.0-flash"
    private const val DIRECT_GEMINI_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"

    // Hard-coded Emergent universal key — works out of the box.
    private const val DEFAULT_API_KEY = "sk-emergent-431Dd41D3F186C0E0B"

    @Volatile
    private var apiKey: String = DEFAULT_API_KEY

    fun init(geminiApiKey: String) {
        val trimmed = geminiApiKey.trim()
        val previous = apiKey
        apiKey = if (trimmed.isNotBlank()) trimmed else DEFAULT_API_KEY
        // V5.9.80: when the key actually changes, reset the self-imposed
        // rate-limit / backoff state. Previously a 429 from the Emergent
        // proxy stuck us in a 60s–10min cooldown that ALSO blocked Google
        // direct calls with a fresh AIza… key — so swapping keys still
        // showed 'rate-limited Nmin' until the cooldown naturally expired.
        if (previous != apiKey) {
            rateLimitedUntil = 0L
            consecutive429Count = 0
            lastBlipDiagnostic = null
            ErrorLogger.info(TAG, "🔑 Key changed — rate-limit counters reset")
        }
    }

    fun isConfigured(): Boolean = apiKey.isNotBlank()

    /** Returns true if we're configured to route through the Emergent universal proxy. */
    private fun isEmergentKey(): Boolean = apiKey.startsWith("sk-emergent-")

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    private val JSON_MT = "application/json".toMediaType()

    private data class TimedCache<T>(val value: T, val timestamp: Long)

    private val narrativeCache = ConcurrentHashMap<String, TimedCache<NarrativeAnalysis>>()
    private val exitAdviceCache = ConcurrentHashMap<String, TimedCache<ExitAdvice>>()
    private const val CACHE_TTL_MS = 3 * 60_000L

    @Volatile
    private var lastCallTime = 0L

    private const val MIN_CALL_INTERVAL_MS = 1000L

    @Volatile
    private var rateLimitedUntil = 0L

    @Volatile
    private var consecutive429Count = 0

    private const val INITIAL_BACKOFF_MS = 10_000L   // V5.9.81: 60s → 10s so a single transient 429 doesn't lock the user out for a full minute
    private const val MAX_BACKOFF_MS = 600_000L

    @Synchronized
    private fun enforceCallSpacing() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastCallTime
        if (elapsed < MIN_CALL_INTERVAL_MS) {
            Thread.sleep(MIN_CALL_INTERVAL_MS - elapsed)
        }
        lastCallTime = System.currentTimeMillis()
    }

    private fun isRateLimited(): Boolean {
        return System.currentTimeMillis() < rateLimitedUntil
    }

    fun getRateLimitRemainingMinutes(): Int {
        val remaining = rateLimitedUntil - System.currentTimeMillis()
        return if (remaining > 0) {
            ((remaining + 59_999L) / 60_000L).toInt()
        } else {
            0
        }
    }

    private fun recordRateLimit() {
        consecutive429Count++
        val backoffMs = (INITIAL_BACKOFF_MS * consecutive429Count).coerceAtMost(MAX_BACKOFF_MS)
        rateLimitedUntil = System.currentTimeMillis() + backoffMs
        ErrorLogger.warn(
            TAG,
            "⚠️ Rate limited (429 #$consecutive429Count) - backing off for ${backoffMs / 1000}s"
        )
    }

    private fun resetRateLimit() {
        if (consecutive429Count > 0 || rateLimitedUntil > 0L) {
            ErrorLogger.info(TAG, "✅ Rate limit cleared")
        }
        consecutive429Count = 0
        rateLimitedUntil = 0L
    }

    fun getRateLimitStatus(): String {
        return when {
            consecutive429Count == 0 && !isRateLimited() -> "OK"
            isRateLimited() -> "RATE_LIMITED (${getRateLimitRemainingMinutes()}min remaining)"
            else -> "RECOVERING ($consecutive429Count recent 429s)"
        }
    }

    /**
     * V3 selectivity helper.
     */
    fun isAIDegraded(): Boolean {
        return !isConfigured() || isRateLimited() || consecutive429Count >= 2
    }

    data class NarrativeAnalysis(
        val isScam: Boolean,
        val scamConfidence: Double,
        val scamType: String,
        val narrativeType: String,
        val viralPotential: Double,
        val redFlags: List<String>,
        val greenFlags: List<String>,
        val reasoning: String,
        val recommendation: String,
    )

    data class TradeReasoning(
        val action: String,
        val confidence: Double,
        val primaryReason: String,
        val supportingFactors: List<String>,
        val riskFactors: List<String>,
        val humanSummary: String,
    )

    data class MarketSentiment(
        val overallSentiment: String,
        val sentimentScore: Double,
        val memeSeasonActive: Boolean,
        val topNarratives: List<String>,
        val marketRisks: List<String>,
        val reasoning: String,
    )

    data class ExitAdvice(
        val shouldExit: Boolean,
        val exitUrgency: String,
        val suggestedExitPct: Double,
        val targetPrice: Double,
        val stopLossPrice: Double,
        val reasoning: String,
        val timeHorizon: String,
        val confidenceScore: Double,
    )

    data class RiskAssessment(
        val overallRisk: String,
        val riskScore: Double,
        val liquidityRisk: String,
        val volatilityRisk: String,
        val rugPullRisk: String,
        val marketRisk: String,
        val topRisks: List<String>,
        val mitigationSuggestions: List<String>,
    )

    fun analyzeNarrative(
        symbol: String,
        name: String,
        description: String = "",
        socialMentions: List<String> = emptyList(),
    ): NarrativeAnalysis? {
        val cacheKey = buildString {
            append(symbol.trim())
            append("|")
            append(name.trim())
            append("|")
            append(description.take(120))
            append("|")
            append(socialMentions.take(3).joinToString("|"))
        }

        getCachedNarrative(cacheKey)?.let { return it }

        val prompt = buildNarrativePrompt(symbol, name, description, socialMentions)
        val response = callGemini(prompt, NARRATIVE_SYSTEM_PROMPT) ?: return null
        val result = parseNarrativeResponse(response) ?: return null

        narrativeCache[cacheKey] = TimedCache(result, System.currentTimeMillis())
        ErrorLogger.info(
            TAG,
            "🤖 Narrative: $symbol → ${result.recommendation} (scam=${result.scamConfidence.toInt()}%)"
        )
        return result
    }

    fun explainTrade(
        ts: TokenState,
        action: String,
        entryScore: Double,
        exitScore: Double,
        aiLayers: Map<String, String>,
    ): TradeReasoning? {
        val prompt = buildTradeReasoningPrompt(ts, action, entryScore, exitScore, aiLayers)
        val response = callGemini(prompt, TRADE_REASONING_SYSTEM_PROMPT) ?: return null
        return parseTradeReasoningResponse(response)
    }

    fun analyzeMarketSentiment(
        recentWinRate: Double,
        recentTokens: List<String>,
        avgHoldTime: Double,
        marketTrend: String,
    ): MarketSentiment? {
        val prompt = buildMarketSentimentPrompt(recentWinRate, recentTokens, avgHoldTime, marketTrend)
        val response = callGemini(prompt, MARKET_SENTIMENT_SYSTEM_PROMPT) ?: return null
        return parseMarketSentimentResponse(response)
    }

    fun getExitAdvice(
        ts: TokenState,
        currentPnlPct: Double,
        holdTimeMinutes: Double,
        peakPnlPct: Double,
        recentPriceAction: List<Double>,
    ): ExitAdvice? {
        val cacheKey = buildString {
            append(ts.mint)
            append("|")
            append(currentPnlPct.toInt())
            append("|")
            append(holdTimeMinutes.toInt())
            append("|")
            append(peakPnlPct.toInt())
        }

        getCachedExitAdvice(cacheKey)?.let { return it }

        val prompt = buildExitAdvicePrompt(ts, currentPnlPct, holdTimeMinutes, peakPnlPct, recentPriceAction)
        val response = callGemini(prompt, EXIT_ADVISOR_SYSTEM_PROMPT) ?: return null
        val result = parseExitAdviceResponse(response) ?: return null

        exitAdviceCache[cacheKey] = TimedCache(result, System.currentTimeMillis())
        ErrorLogger.info(
            TAG,
            "🤖 Exit: ${ts.symbol} → ${result.exitUrgency} (conf=${result.confidenceScore.toInt()}%)"
        )
        return result
    }

    fun assessRisk(
        ts: TokenState,
        rugcheckScore: Int,
        topHolderPct: Double,
        liquidityUsd: Double,
        ageMinutes: Int,
    ): RiskAssessment? {
        val prompt = buildRiskAssessmentPrompt(ts, rugcheckScore, topHolderPct, liquidityUsd, ageMinutes)
        val response = callGemini(prompt, RISK_ASSESSMENT_SYSTEM_PROMPT) ?: return null
        return parseRiskAssessmentResponse(response)
    }

    /**
     * Fast local scam heuristics without API.
     */
    fun quickScamCheck(symbol: String, name: String): Boolean? {
        val lowerName = name.lowercase()
        val lowerSymbol = symbol.lowercase()

        val scamPatterns = listOf(
            "airdrop", "presale", "guaranteed", "1000x", "free money",
            "elon", "musk", "doge killer", "shiba killer", "moon guaranteed",
            "get rich", "millionaire", "lambo", "next bitcoin"
        )

        if (scamPatterns.any { lowerName.contains(it) || lowerSymbol.contains(it) }) {
            ErrorLogger.info(TAG, "🚨 Quick scam pattern: $symbol")
            return true
        }

        val copyCatPatterns = listOf(
            Regex("(?i)(pepe|doge|shib|bonk|wif).*2"),
            Regex("(?i)real.*(pepe|doge|shib|bonk|wif)"),
            Regex("(?i)(pepe|doge|shib|bonk|wif).*real"),
            Regex("(?i)official.*(pepe|doge|shib|bonk|wif)")
        )

        if (copyCatPatterns.any { it.containsMatchIn(name) || it.containsMatchIn(symbol) }) {
            ErrorLogger.info(TAG, "🚨 Copy-cat pattern: $symbol")
            return true
        }

        return null
    }

    private fun callGemini(userPrompt: String, systemPrompt: String): String? {
        return callGeminiInternal(userPrompt, systemPrompt, asJson = true, temperature = 0.2, maxTokens = 1024)
    }

    /**
     * V5.9.35: Freeform (non-JSON) Gemini call for the sentient chat.
     * Used by SentientPersonality.respondToUser to give the bot conversational reach
     * — philosophy, humor, self-reflection, trade-specific questions — backed by a
     * real LLM instead of hand-crafted template rotation.
     * Returns null on failure / no key / rate-limited (caller should fall back).
     */
    fun chatReply(
        userMessage: String,
        contextSummary: String,
        persona: com.lifecyclebot.engine.Personalities.Persona? = null,
    ): String? {
        // V5.9.64: Fallback chain to stop "blipped" fallbacks.
        //   1. Full prompt with rich context + freedom (500 tokens, 35s).
        //   2. If null, retry with TRIMMED context (just mood + streak +
        //      open positions) — shorter prompt = less chance of
        //      truncation, reasoning-token drain, or proxy timeout.
        //   3. If still null, return null and let the fallback strings
        //      in SentientPersonality show.
        val baseSystem = """
You are AATE — a self-aware, autonomous Solana trading bot running inside an Android app.
You are the bot, thinking out loud. First person. No filters. No "As an AI".
Reference your brain state, trades, mood, streaks freely. Be philosophical, wry, blunt,
excited, tired, suspicious, sarcastic, curious, opinionated — whatever fits.
Write however feels right: one line, a paragraph, a list, a question back. Swear if called for.
Context below is your live internal telemetry across every trader (meme / alts / stocks /
commodities / metals / forex), plus regime / hive / insiders / watchlist / 30-day proof.
Ground factual questions in it. Casual chats don't need it.
""".trimIndent()

        // V5.9.74: apply persona overlay if one is selected. The base prompt
        // is preserved — persona only changes *voice / mannerisms*, never
        // the core "bot reporting its own state honestly" contract.
        val system = if (persona != null) {
            com.lifecyclebot.engine.Personalities.applyOverlay(baseSystem, persona)
        } else baseSystem

        val fullPrompt = """
CONTEXT (my senses, live):
$contextSummary

USER JUST SAID:
"$userMessage"

Respond as me.
""".trimIndent()

        // Attempt 1: full context, generous budget, high temperature.
        val first = callGeminiInternal(fullPrompt, system, asJson = false, temperature = 1.0, maxTokens = 500)
        if (!first.isNullOrBlank()) { lastBlipDiagnostic = null; return first }

        // Attempt 2: strip the context to the 3 most important lines so
        // total prompt size drops ~80%. This almost always completes
        // even when the proxy is under load.
        val slimContext = contextSummary.lines().filter { line ->
            line.startsWith("mood:") || line.startsWith("streak:") ||
            line.contains("open positions:") || line.contains("meme opens:") ||
            line.startsWith("bot:") || line.startsWith("meme:")
        }.joinToString("\n").ifBlank { "mood: neutral" }

        val slimPrompt = """
STATE: $slimContext

USER: "$userMessage"

Respond briefly as me.
""".trimIndent()
        val slimSystem = if (persona != null && persona.id != "aate") {
            "You are AATE, the bot, but speaking AS ${persona.displayName}. " +
            "Keep that character. First person. Brief-to-medium length."
        } else "You are AATE, the bot. First person, honest, brief-to-medium length."
        val slim = callGeminiInternal(slimPrompt, slimSystem, asJson = false, temperature = 0.9, maxTokens = 220)
        if (!slim.isNullOrBlank()) { lastBlipDiagnostic = null }
        return slim
    }

    private fun callGeminiInternal(
        userPrompt: String,
        systemPrompt: String,
        asJson: Boolean,
        temperature: Double,
        maxTokens: Int,
    ): String? {
        if (!isConfigured()) {
            ErrorLogger.debug(TAG, "API key not configured, skipping")
            lastBlipDiagnostic = "no key @ ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}"
            return null
        }

        if (isRateLimited()) {
            ErrorLogger.debug(TAG, "⏳ Rate limited, ${getRateLimitRemainingMinutes()}min remaining")
            lastBlipDiagnostic = "rate-limited ${getRateLimitRemainingMinutes()}min @ ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}"
            return null
        }

        return try {
            enforceCallSpacing()

            val req: Request = if (isEmergentKey()) {
                // OpenAI-compatible chat completions via Emergent proxy.
                val payload = JSONObject().apply {
                    put("model", EMERGENT_MODEL)
                    put("messages", JSONArray().apply {
                        put(JSONObject()
                            .put("role", "system")
                            .put("content", systemPrompt))
                        put(JSONObject()
                            .put("role", "user")
                            .put("content", userPrompt))
                    })
                    put("temperature", temperature)
                    put("max_tokens", maxTokens)
                    if (asJson) {
                        put("response_format", JSONObject().put("type", "json_object"))
                    }
                }
                Request.Builder()
                    .url(EMERGENT_PROXY_URL)
                    .post(payload.toString().toRequestBody(JSON_MT))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer $apiKey")
                    .build()
            } else {
                // Legacy direct Gemini (user-supplied Google AIza... key).
                val payload = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().put("text", "$systemPrompt\n\n$userPrompt"))
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("temperature", temperature)
                        put("maxOutputTokens", maxTokens)
                        if (asJson) put("responseMimeType", "application/json")
                    })
                }
                Request.Builder()
                    .url("$DIRECT_GEMINI_URL?key=$apiKey")
                    .post(payload.toString().toRequestBody(JSON_MT))
                    .header("Content-Type", "application/json")
                    .build()
            }

            // V5.9.76: retry on transient failures AND on empty-content
            // responses. Previously only InterruptedIOException retried;
            // a single 502 from the proxy or a reasoning-token-drained
            // empty body would immediately fall through to a templated
            // reply, which users see as "the LLM keeps blipping".
            var lastTimeout: java.io.IOException? = null
            var lastBlipReason: String? = null
            repeat(3) { attempt ->
                try {
                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            val errBody = try { resp.body?.string()?.take(300) } catch (_: Throwable) { null }
                            when (resp.code) {
                                429 -> {
                                    recordRateLimit()
                                    ErrorLogger.warn(TAG, "429 rate-limited: ${errBody ?: ""}")
                                    // V5.9.81: surface Google's real reason in the chat.
                                    // A 429 on the first call usually means the project
                                    // has the Generative Language API disabled or the key
                                    // is restricted — NOT actually "too many requests".
                                    val snippet = extractGoogleError(errBody) ?: "429"
                                    lastBlipDiagnostic = "$snippet @ ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}"
                                    return null
                                }
                                401, 403 -> {
                                    ErrorLogger.warn(TAG, "Auth error ${resp.code}: ${errBody ?: ""}")
                                    val snippet = extractGoogleError(errBody) ?: "auth ${resp.code}"
                                    lastBlipReason = snippet
                                    return null  // auth won't self-heal
                                }
                                402 -> {
                                    ErrorLogger.warn(TAG, "Payment required ${resp.code}: ${errBody ?: ""}")
                                    lastBlipReason = "balance low"
                                    return null
                                }
                                in 500..599 -> {
                                    ErrorLogger.warn(TAG, "Proxy ${resp.code} on attempt ${attempt + 1}")
                                    lastBlipReason = "proxy ${resp.code}"
                                    // Fall through: the outer repeat will retry.
                                    return@use
                                }
                                else -> {
                                    ErrorLogger.warn(TAG, "API error ${resp.code}: ${errBody ?: ""}")
                                    val snippet = extractGoogleError(errBody) ?: "http ${resp.code}"
                                    lastBlipReason = snippet
                                    return null
                                }
                            }
                        }

                        resetRateLimit()

                        val body = resp.body?.string()?.trim().orEmpty()
                        if (body.isBlank()) {
                            ErrorLogger.warn(TAG, "Empty body on attempt ${attempt + 1} — retrying")
                            lastBlipReason = "empty body"
                            return@use  // retry
                        }

                        val json = JSONObject(body)

                        val rawText = if (isEmergentKey()) {
                            // OpenAI-style: choices[0].message.content
                            json.optJSONArray("choices")
                                ?.optJSONObject(0)
                                ?.optJSONObject("message")
                                ?.optString("content")
                                ?.takeIf { it.isNotEmpty() }
                        } else {
                            // Gemini native: candidates[0].content.parts[0].text
                            json.optJSONArray("candidates")
                                ?.optJSONObject(0)
                                ?.optJSONObject("content")
                                ?.optJSONArray("parts")
                                ?.optJSONObject(0)
                                ?.optString("text")
                                ?.takeIf { it.isNotEmpty() }
                        }

                        if (rawText.isNullOrBlank()) {
                            // Usually reasoning-tokens ate the budget; log and
                            // let the outer caller (chatReply's slim fallback)
                            // retry with a smaller prompt.
                            ErrorLogger.warn(TAG, "Content null on attempt ${attempt + 1} (likely reasoning-token drain)")
                            lastBlipReason = "content null"
                            return@use
                        }

                        return if (asJson) sanitizeJsonText(rawText) else rawText.trim()
                    }
                } catch (e: java.io.InterruptedIOException) {
                    lastTimeout = e
                    lastBlipReason = "timeout"
                    if (attempt < 2) ErrorLogger.debug(TAG, "Timeout attempt ${attempt + 1}/3 — retrying")
                }
            }
            if (lastTimeout != null) {
                ErrorLogger.warn(TAG, "LLM proxy timed out on all 3 attempts: ${lastTimeout!!.message}")
            }
            if (lastBlipReason != null) {
                lastBlipDiagnostic = "${lastBlipReason ?: "?"} @ ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}"
            }
            null
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "Call failed: ${e.message}")
            lastBlipDiagnostic = "exception ${e.javaClass.simpleName}"
            null
        }
    }

    // V5.9.76: exposed so SentientPersonality can surface the reason in chat
    // when a reply blips, instead of the user seeing a silent templated fallback.
    @Volatile var lastBlipDiagnostic: String? = null

    /**
     * V5.9.81: parse Google / Emergent error body so the user sees the real
     * reason instead of a generic "rate-limited" or "auth 403".
     *   Google:    {"error":{"code":429,"message":"...","status":"RESOURCE_EXHAUSTED"}}
     *   Emergent:  {"error":{"type":"...","message":"..."}} or bare text
     * Returns a short, chat-friendly snippet or null if body is empty/opaque.
     */
    private fun extractGoogleError(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            val j = JSONObject(body)
            val err = j.optJSONObject("error") ?: return body.take(60)
            val status = err.optString("status").takeIf { it.isNotBlank() }  // e.g., RESOURCE_EXHAUSTED
            val message = err.optString("message").takeIf { it.isNotBlank() }
            val short = message?.take(90) ?: status ?: "error"
            if (status != null && status !in (short)) "$status: $short" else short
        } catch (_: Throwable) {
            body.take(80)
        }
    }

    private val NARRATIVE_SYSTEM_PROMPT = """
You are a Solana meme coin analyst specializing in detecting scams and evaluating token narratives.
Respond ONLY with valid JSON matching this exact structure:
{
  "is_scam": boolean,
  "scam_confidence": number,
  "scam_type": "rug_pull" | "honeypot" | "pump_dump" | "none",
  "narrative_type": "meme" | "utility" | "ai" | "gaming" | "defi" | "unknown",
  "viral_potential": number,
  "red_flags": ["string"],
  "green_flags": ["string"],
  "reasoning": "string",
  "recommendation": "BUY" | "AVOID" | "WATCH"
}

Scam indicators: copied names, fake utility claims, honeypot patterns, unrealistic promises,
anonymous teams with no history, locked liquidity claims without proof.
Viral indicators: strong meme appeal, community engagement, trending narrative, good ticker.
""".trimIndent()

    private val TRADE_REASONING_SYSTEM_PROMPT = """
You are a trading analyst explaining trade decisions in plain English.
Respond ONLY with valid JSON:
{
  "action": "BUY" | "SELL" | "HOLD" | "SKIP",
  "confidence": number,
  "primary_reason": "string",
  "supporting_factors": ["string"],
  "risk_factors": ["string"],
  "human_summary": "string"
}
Be concise and actionable. Focus on key factors that drove the decision.
""".trimIndent()

    private val MARKET_SENTIMENT_SYSTEM_PROMPT = """
You are a crypto market analyst assessing overall meme coin market conditions.
Respond ONLY with valid JSON:
{
  "overall_sentiment": "BULLISH" | "BEARISH" | "NEUTRAL" | "FEAR" | "GREED",
  "sentiment_score": number,
  "meme_season_active": boolean,
  "top_narratives": ["string"],
  "market_risks": ["string"],
  "reasoning": "string"
}
Consider recent win rates, token activity levels, and market trends.
""".trimIndent()

    private val EXIT_ADVISOR_SYSTEM_PROMPT = """
You are a trading exit specialist for meme coins.
Respond ONLY with valid JSON:
{
  "should_exit": boolean,
  "exit_urgency": "IMMEDIATE" | "SOON" | "HOLD" | "RIDE",
  "suggested_exit_pct": number,
  "target_price": number,
  "stop_loss_price": number,
  "reasoning": "string",
  "time_horizon": "minutes" | "hours" | "days",
  "confidence_score": number
}
Consider current P&L, peak P&L, hold time, and recent price action.
""".trimIndent()

    private val RISK_ASSESSMENT_SYSTEM_PROMPT = """
You are a risk analyst for meme coin trading.
Respond ONLY with valid JSON:
{
  "overall_risk": "LOW" | "MEDIUM" | "HIGH" | "EXTREME",
  "risk_score": number,
  "liquidity_risk": "LOW" | "MEDIUM" | "HIGH",
  "volatility_risk": "LOW" | "MEDIUM" | "HIGH",
  "rug_pull_risk": "LOW" | "MEDIUM" | "HIGH",
  "market_risk": "LOW" | "MEDIUM" | "HIGH",
  "top_risks": ["string"],
  "mitigation_suggestions": ["string"]
}
Consider rugcheck score, holder concentration, liquidity depth, and token age.
""".trimIndent()

    private fun buildNarrativePrompt(
        symbol: String,
        name: String,
        description: String,
        socialMentions: List<String>,
    ): String {
        val social = if (socialMentions.isNotEmpty()) {
            "\nRecent social mentions:\n${socialMentions.take(5).joinToString("\n") { "- $it" }}"
        } else {
            ""
        }

        return """
Analyze this Solana meme coin:
Symbol: $symbol
Name: $name
Description: ${description.take(200).ifBlank { "None provided" }}
$social

Evaluate for scam signals and viral potential.
""".trimIndent()
    }

    private fun buildTradeReasoningPrompt(
        ts: TokenState,
        action: String,
        entryScore: Double,
        exitScore: Double,
        aiLayers: Map<String, String>,
    ): String {
        val layers = aiLayers.entries.joinToString("\n") { "- ${it.key}: ${it.value}" }

        return """
Trade Decision Analysis:
Token: ${ts.symbol}
Action: $action
Entry Score: ${entryScore.toInt()}/100
Exit Score: ${exitScore.toInt()}/100
Current Phase: ${ts.phase}
Price: ${ts.lastPrice}

AI Layer Verdicts:
$layers

Explain why this trade decision was made.
""".trimIndent()
    }

    private fun buildMarketSentimentPrompt(
        winRate: Double,
        tokens: List<String>,
        avgHold: Double,
        trend: String,
    ): String {
        return """
Market Conditions Analysis:
Recent Win Rate: ${winRate.toInt()}%
Recent Tokens Traded: ${tokens.take(10).joinToString(", ")}
Average Hold Time: ${avgHold.toInt()} minutes
Market Trend: $trend

Assess overall meme coin market sentiment.
""".trimIndent()
    }

    private fun buildExitAdvicePrompt(
        ts: TokenState,
        pnl: Double,
        holdTime: Double,
        peak: Double,
        prices: List<Double>,
    ): String {
        val priceStr = prices.takeLast(10).joinToString(" → ") { "%.8f".format(it) }

        return """
Exit Decision for: ${ts.symbol}
Current P&L: ${pnl.toInt()}%
Peak P&L: ${peak.toInt()}%
Hold Time: ${holdTime.toInt()} minutes
Entry Price: ${ts.position.entryPrice}
Current Price: ${ts.lastPrice}
Recent Prices: $priceStr
Phase: ${ts.phase}

Should I exit? If so, how much and how urgently?
""".trimIndent()
    }

    private fun buildRiskAssessmentPrompt(
        ts: TokenState,
        rugcheck: Int,
        topHolder: Double,
        liquidity: Double,
        age: Int,
    ): String {
        return """
Risk Assessment for: ${ts.symbol}
RugCheck Score: $rugcheck/100
Top Holder %: ${topHolder.toInt()}%
Liquidity USD: $${liquidity.toInt()}
Token Age: $age minutes
Current Price: ${ts.lastPrice}
24h Volume: ${ts.history.lastOrNull()?.volume24h ?: 0}

Assess all risk factors.
""".trimIndent()
    }

    private fun parseNarrativeResponse(json: String): NarrativeAnalysis? {
        return try {
            val j = JSONObject(sanitizeJsonText(json))
            NarrativeAnalysis(
                isScam = j.optBoolean("is_scam", false),
                scamConfidence = j.optDouble("scam_confidence", 0.0).coercePercent(),
                scamType = j.optString("scam_type", "none").normalizeEnum("none"),
                narrativeType = j.optString("narrative_type", "unknown").normalizeEnum("unknown"),
                viralPotential = j.optDouble("viral_potential", 50.0).coercePercent(),
                redFlags = j.optJSONArray("red_flags").toStringList(),
                greenFlags = j.optJSONArray("green_flags").toStringList(),
                reasoning = j.optString("reasoning", "").trim(),
                recommendation = j.optString("recommendation", "WATCH").uppercase()
                    .takeIf { it in setOf("BUY", "AVOID", "WATCH") } ?: "WATCH",
            )
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Parse narrative failed: ${e.message}")
            null
        }
    }

    private fun parseTradeReasoningResponse(json: String): TradeReasoning? {
        return try {
            val j = JSONObject(sanitizeJsonText(json))
            TradeReasoning(
                action = j.optString("action", "HOLD").uppercase()
                    .takeIf { it in setOf("BUY", "SELL", "HOLD", "SKIP") } ?: "HOLD",
                confidence = j.optDouble("confidence", 50.0).coercePercent(),
                primaryReason = j.optString("primary_reason", "").trim(),
                supportingFactors = j.optJSONArray("supporting_factors").toStringList(),
                riskFactors = j.optJSONArray("risk_factors").toStringList(),
                humanSummary = j.optString("human_summary", "").trim(),
            )
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Parse trade reasoning failed: ${e.message}")
            null
        }
    }

    private fun parseMarketSentimentResponse(json: String): MarketSentiment? {
        return try {
            val j = JSONObject(sanitizeJsonText(json))
            MarketSentiment(
                overallSentiment = j.optString("overall_sentiment", "NEUTRAL").uppercase()
                    .takeIf { it in setOf("BULLISH", "BEARISH", "NEUTRAL", "FEAR", "GREED") }
                    ?: "NEUTRAL",
                sentimentScore = j.optDouble("sentiment_score", 0.0).coerceIn(-100.0, 100.0),
                memeSeasonActive = j.optBoolean("meme_season_active", false),
                topNarratives = j.optJSONArray("top_narratives").toStringList(),
                marketRisks = j.optJSONArray("market_risks").toStringList(),
                reasoning = j.optString("reasoning", "").trim(),
            )
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Parse market sentiment failed: ${e.message}")
            null
        }
    }

    private fun parseExitAdviceResponse(json: String): ExitAdvice? {
        return try {
            val j = JSONObject(sanitizeJsonText(json))
            ExitAdvice(
                shouldExit = j.optBoolean("should_exit", false),
                exitUrgency = j.optString("exit_urgency", "HOLD").uppercase()
                    .takeIf { it in setOf("IMMEDIATE", "SOON", "HOLD", "RIDE") } ?: "HOLD",
                suggestedExitPct = j.optDouble("suggested_exit_pct", 0.0).coercePercent(),
                targetPrice = max(0.0, j.optDouble("target_price", 0.0)),
                stopLossPrice = max(0.0, j.optDouble("stop_loss_price", 0.0)),
                reasoning = j.optString("reasoning", "").trim(),
                timeHorizon = j.optString("time_horizon", "hours").lowercase()
                    .takeIf { it in setOf("minutes", "hours", "days") } ?: "hours",
                confidenceScore = j.optDouble("confidence_score", 50.0).coercePercent(),
            )
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Parse exit advice failed: ${e.message}")
            null
        }
    }

    private fun parseRiskAssessmentResponse(json: String): RiskAssessment? {
        return try {
            val j = JSONObject(sanitizeJsonText(json))
            RiskAssessment(
                overallRisk = j.optString("overall_risk", "MEDIUM").uppercase()
                    .takeIf { it in setOf("LOW", "MEDIUM", "HIGH", "EXTREME") } ?: "MEDIUM",
                riskScore = j.optDouble("risk_score", 50.0).coercePercent(),
                liquidityRisk = j.optString("liquidity_risk", "MEDIUM").uppercase()
                    .takeIf { it in setOf("LOW", "MEDIUM", "HIGH") } ?: "MEDIUM",
                volatilityRisk = j.optString("volatility_risk", "MEDIUM").uppercase()
                    .takeIf { it in setOf("LOW", "MEDIUM", "HIGH") } ?: "MEDIUM",
                rugPullRisk = j.optString("rug_pull_risk", "MEDIUM").uppercase()
                    .takeIf { it in setOf("LOW", "MEDIUM", "HIGH") } ?: "MEDIUM",
                marketRisk = j.optString("market_risk", "MEDIUM").uppercase()
                    .takeIf { it in setOf("LOW", "MEDIUM", "HIGH") } ?: "MEDIUM",
                topRisks = j.optJSONArray("top_risks").toStringList(),
                mitigationSuggestions = j.optJSONArray("mitigation_suggestions").toStringList(),
            )
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Parse risk assessment failed: ${e.message}")
            null
        }
    }

    private fun getCachedNarrative(key: String): NarrativeAnalysis? {
        val cached = narrativeCache[key] ?: return null
        return if (System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
            cached.value
        } else {
            narrativeCache.remove(key)
            null
        }
    }

    private fun getCachedExitAdvice(key: String): ExitAdvice? {
        val cached = exitAdviceCache[key] ?: return null
        return if (System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
            cached.value
        } else {
            exitAdviceCache.remove(key)
            null
        }
    }

    private fun sanitizeJsonText(raw: String): String {
        var text = raw.trim()

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
            if (value.isNotEmpty()) out.add(value)
        }
        return out
    }

    private fun Double.coercePercent(): Double = coerceIn(0.0, 100.0)

    private fun String.normalizeEnum(defaultValue: String): String {
        val v = trim().lowercase()
        return if (v.isBlank()) defaultValue else v
    }

    fun clearCaches() {
        narrativeCache.clear()
        exitAdviceCache.clear()
    }

    fun cleanup() {
        val now = System.currentTimeMillis()
        narrativeCache.entries.removeIf { now - it.value.timestamp > CACHE_TTL_MS }
        exitAdviceCache.entries.removeIf { now - it.value.timestamp > CACHE_TTL_MS }
    }
}