package com.lifecyclebot.engine

import com.lifecyclebot.network.SharedHttpClient

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * NarrativeDetector — Groq-powered scam/narrative detection
 * ════════════════════════════════════════════════════════════════════
 *
 * Uses Groq LLM (llama-3.3-70b-versatile) to analyze:
 *   1. Token name/symbol for scam patterns (ELONMUSK, SAFEMOON2, etc.)
 *   2. Social sentiment from Twitter/Telegram mentions
 *   3. Token metadata/description for red flags
 *
 * Returns a confidence adjustment (-30 to +10) that modifies the
 * FinalDecisionGate confidence score.
 *
 * Scam patterns detected:
 *   - Celebrity impersonation (ELON, TRUMP, etc.)
 *   - "Safe" prefix scams (SAFEMOON, SAFEEARTH)
 *   - Fake versions of popular tokens (SHIB2, DOGE2, PEPE2)
 *   - Honeypot indicators
 *   - Coordinated pump language
 *   - Dev wallet warnings
 */
object NarrativeDetector {

    private val http = SharedHttpClient.builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON_MT = "application/json".toMediaType()
    private val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
    private val MODEL = "llama-3.3-70b-versatile"  // More capable model for narrative detection

    // Cache: mint → (result, timestamp)
    private val cache = mutableMapOf<String, Pair<NarrativeResult, Long>>()
    private val CACHE_TTL_MS = 10 * 60_000L  // 10 min cache

    data class NarrativeResult(
        val confidenceAdjustment: Int,    // -30 to +10 (negative = suspicious)
        val riskLevel: String,            // "LOW", "MEDIUM", "HIGH", "CRITICAL"
        val scamIndicators: List<String>, // Detected red flags
        val positiveSignals: List<String>,// Detected good signs
        val reasoning: String,            // LLM explanation
        val shouldBlock: Boolean,         // Hard block recommendation
        val blockReason: String,          // Why blocked
    )

    /**
     * Analyze a token for narrative/scam risk.
     * Returns confidence adjustment and risk assessment.
     */
    fun analyze(
        symbol: String,
        name: String,
        mintAddress: String,
        description: String = "",
        socialMentions: List<String> = emptyList(),
        groqApiKey: String,
    ): NarrativeResult {
        if (groqApiKey.isBlank()) {
            return fallbackAnalysis(symbol, name, description)
        }

        // Check cache
        val cached = cache[mintAddress]
        if (cached != null && System.currentTimeMillis() - cached.second < CACHE_TTL_MS) {
            return cached.first
        }

        // Build context for LLM
        val socialText = socialMentions
            .take(15)
            .joinToString("\n") { it.take(100) }
            .take(600)

        val result = callGroq(symbol, name, mintAddress, description, socialText, groqApiKey)
            ?: fallbackAnalysis(symbol, name, description)

        cache[mintAddress] = result to System.currentTimeMillis()
        
        ErrorLogger.info("NarrativeAI", 
            "🔍 $symbol: adj=${result.confidenceAdjustment} risk=${result.riskLevel} | ${result.reasoning.take(60)}")
        
        return result
    }

    private fun callGroq(
        symbol: String,
        name: String,
        mint: String,
        description: String,
        socialText: String,
        apiKey: String,
    ): NarrativeResult? {
        val prompt = buildPrompt(symbol, name, mint, description, socialText)

        val payload = JSONObject().apply {
            put("model", MODEL)
            put("temperature", 0.1)
            put("max_tokens", 500)
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
            if (!RateLimiter.allowRequest("groq")) {
                ErrorLogger.debug("NarrativeAI", "Rate limited, using fallback")
                return null
            }

            val req = Request.Builder()
                .url(GROQ_URL)
                .post(payload.toString().toRequestBody(JSON_MT))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .build()

            val resp = http.newCall(req).execute()
            if (!resp.isSuccessful) {
                ErrorLogger.debug("NarrativeAI", "Groq returned ${resp.code}")
                return null
            }

            val body = resp.body?.string() ?: return null
            val content = JSONObject(body)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "") ?: return null

            parseResponse(content)
        } catch (e: Exception) {
            ErrorLogger.debug("NarrativeAI", "Groq call failed: ${e.message}")
            null
        }
    }

    private fun parseResponse(json: String): NarrativeResult? {
        return try {
            val j = JSONObject(json.trim())

            val scamIndicators = j.optJSONArray("scam_indicators")
                ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList()
            val positiveSignals = j.optJSONArray("positive_signals")
                ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList()

            NarrativeResult(
                confidenceAdjustment = j.optInt("confidence_adjustment", 0).coerceIn(-30, 10),
                riskLevel = j.optString("risk_level", "MEDIUM"),
                scamIndicators = scamIndicators,
                positiveSignals = positiveSignals,
                reasoning = j.optString("reasoning", "").take(200),
                shouldBlock = j.optBoolean("should_block", false),
                blockReason = j.optString("block_reason", ""),
            )
        } catch (e: Exception) {
            ErrorLogger.debug("NarrativeAI", "Parse failed: ${e.message}")
            null
        }
    }

    /**
     * Fallback keyword-based analysis when Groq is unavailable.
     */
    private fun fallbackAnalysis(symbol: String, name: String, description: String): NarrativeResult {
        val combined = "$symbol $name $description".lowercase()
        val scamIndicators = mutableListOf<String>()
        var adjustment = 0

        // Celebrity impersonation
        val celebrities = listOf("elon", "trump", "biden", "musk", "zuck", "bezos", "gates")
        celebrities.forEach { celeb ->
            if (combined.contains(celeb)) {
                scamIndicators.add("Celebrity name: $celeb")
                adjustment -= 15
            }
        }

        // "Safe" prefix scams
        if (combined.startsWith("safe") || combined.contains("safemoon") || combined.contains("safeearth")) {
            scamIndicators.add("'Safe' prefix - common scam pattern")
            adjustment -= 20
        }

        // Fake version indicators
        val fakePatterns = listOf("2.0", "v2", "inu2", "classic", "new", "real", "official", "original")
        fakePatterns.forEach { pattern ->
            if (combined.contains(pattern)) {
                scamIndicators.add("Fake version indicator: $pattern")
                adjustment -= 10
            }
        }

        // Pump language
        val pumpWords = listOf("1000x", "moon", "lambo", "guaranteed", "easy money", "next shib", "next doge")
        pumpWords.forEach { word ->
            if (combined.contains(word)) {
                scamIndicators.add("Pump language: $word")
                adjustment -= 5
            }
        }

        // Honeypot indicators
        val honeypotWords = listOf("honeypot", "cant sell", "can't sell", "no sell", "locked")
        honeypotWords.forEach { word ->
            if (combined.contains(word)) {
                scamIndicators.add("Honeypot indicator: $word")
                adjustment -= 25
            }
        }

        val riskLevel = when {
            adjustment <= -25 -> "CRITICAL"
            adjustment <= -15 -> "HIGH"
            adjustment <= -5 -> "MEDIUM"
            else -> "LOW"
        }

        return NarrativeResult(
            confidenceAdjustment = adjustment.coerceIn(-30, 0),
            riskLevel = riskLevel,
            scamIndicators = scamIndicators,
            positiveSignals = emptyList(),
            reasoning = if (scamIndicators.isEmpty()) "No obvious red flags detected" 
                        else "Keyword analysis found ${scamIndicators.size} red flags",
            shouldBlock = adjustment <= -25,
            blockReason = if (adjustment <= -25) "Multiple scam indicators detected" else "",
        )
    }

    private val SYSTEM_PROMPT = """
You are a Solana meme coin scam detector. Analyze tokens for fraud risk and return ONLY valid JSON:
{
  "confidence_adjustment": <-30 to +10, negative=suspicious>,
  "risk_level": "<LOW|MEDIUM|HIGH|CRITICAL>",
  "scam_indicators": ["<indicator>", ...],
  "positive_signals": ["<signal>", ...],
  "reasoning": "<1-2 sentence explanation>",
  "should_block": <true if definite scam, else false>,
  "block_reason": "<reason if blocked>"
}

SCAM PATTERNS TO DETECT:
- Celebrity impersonation (ELONMUSK, TRUMPCOIN)
- "Safe" prefix scams (SAFEMOON clones)
- Fake versions (SHIB2, DOGE2, adding "real"/"official")
- Honeypot language (can't sell, locked liquidity lies)
- Coordinated pump ("1000x guaranteed", "next SHIB")
- Dev wallet warnings
- Copied token mechanics from known rugs

POSITIVE SIGNALS:
- Original creative name/concept
- Active genuine community (not bots)
- Transparent team communication
- Verified contracts
- Organic growth patterns

Confidence adjustment guide:
- +10: Very legitimate, original, active community
- 0: Neutral, nothing suspicious
- -10: Minor concerns
- -20: Significant red flags
- -30: Almost certainly a scam
""".trimIndent()

    private fun buildPrompt(
        symbol: String,
        name: String,
        mint: String,
        description: String,
        socialText: String
    ): String = """
TOKEN ANALYSIS REQUEST:
Symbol: $symbol
Name: $name
Contract: ${mint.take(12)}...

Description/Metadata:
${description.take(300).ifBlank { "(none provided)" }}

Recent Social Activity:
${socialText.ifBlank { "(no social data)" }}

Analyze this token for scam/fraud risk and narrative quality.
""".trimIndent()

    /**
     * Clear cache (call on app restart or periodically)
     */
    fun clearCache() {
        cache.clear()
    }
}
