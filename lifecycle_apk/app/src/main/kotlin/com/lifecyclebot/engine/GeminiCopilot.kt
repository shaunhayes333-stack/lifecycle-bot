package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.data.Trade
import com.lifecyclebot.data.Candle
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * GeminiCopilot — Full AI Co-pilot Layer powered by Google Gemini
 * 
 * Features:
 *   1. Narrative/Scam Detection - Analyze token names and social signals for pump & dump
 *   2. Trade Reasoning - Generate human-readable trade explanations
 *   3. Market Sentiment Analysis - Broader market context analysis
 *   4. Smart Exit Advisor - AI-suggested optimal exit timing
 *   5. Risk Assessment - Multi-factor risk scoring
 * 
 * Uses Gemini 2.0 Flash for fast, cost-effective inference.
 * Rate limited to avoid quota issues.
 */
object GeminiCopilot {
    
    // API key loaded from user config - no hardcoded keys!
    private var apiKey: String = ""
    private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"
    
    /**
     * Initialize with API key from config
     */
    fun init(geminiApiKey: String) {
        apiKey = geminiApiKey
    }
    
    /**
     * Check if API key is configured
     */
    fun isConfigured(): Boolean = apiKey.isNotBlank()
    
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val JSON_MT = "application/json".toMediaType()
    
    // Cache to avoid redundant API calls
    private val narrativeCache = ConcurrentHashMap<String, Pair<NarrativeAnalysis, Long>>()
    private val exitAdviceCache = ConcurrentHashMap<String, Pair<ExitAdvice, Long>>()
    private val CACHE_TTL_MS = 3 * 60_000L  // 3 minutes
    
    // Rate limiting
    private var lastCallTime = 0L
    private const val MIN_CALL_INTERVAL_MS = 1000L  // 1 second between calls
    
    // ════════════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ════════════════════════════════════════════════════════════════════════════
    
    data class NarrativeAnalysis(
        val isScam: Boolean,
        val scamConfidence: Double,        // 0-100
        val scamType: String,              // "rug_pull", "honeypot", "pump_dump", "none"
        val narrativeType: String,         // "meme", "utility", "ai", "gaming", "defi", "unknown"
        val viralPotential: Double,        // 0-100
        val redFlags: List<String>,
        val greenFlags: List<String>,
        val reasoning: String,
        val recommendation: String,        // "BUY", "AVOID", "WATCH"
    )
    
    data class TradeReasoning(
        val action: String,                // "BUY", "SELL", "HOLD", "SKIP"
        val confidence: Double,            // 0-100
        val primaryReason: String,
        val supportingFactors: List<String>,
        val riskFactors: List<String>,
        val humanSummary: String,          // Natural language explanation
    )
    
    data class MarketSentiment(
        val overallSentiment: String,      // "BULLISH", "BEARISH", "NEUTRAL", "FEAR", "GREED"
        val sentimentScore: Double,        // -100 to +100
        val memeSeasonActive: Boolean,
        val topNarratives: List<String>,
        val marketRisks: List<String>,
        val reasoning: String,
    )
    
    data class ExitAdvice(
        val shouldExit: Boolean,
        val exitUrgency: String,           // "IMMEDIATE", "SOON", "HOLD", "RIDE"
        val suggestedExitPct: Double,      // What % to exit (0-100)
        val targetPrice: Double,           // Suggested target if holding
        val stopLossPrice: Double,         // Suggested stop loss
        val reasoning: String,
        val timeHorizon: String,           // "minutes", "hours", "days"
        val confidenceScore: Double,       // 0-100
    )
    
    data class RiskAssessment(
        val overallRisk: String,           // "LOW", "MEDIUM", "HIGH", "EXTREME"
        val riskScore: Double,             // 0-100
        val liquidityRisk: String,
        val volatilityRisk: String,
        val rugPullRisk: String,
        val marketRisk: String,
        val topRisks: List<String>,
        val mitigationSuggestions: List<String>,
    )
    
    // ════════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ════════════════════════════════════════════════════════════════════════════
    
    /**
     * Analyze token name, symbol, and description for scam/narrative signals.
     * Returns cached result if available.
     */
    fun analyzeNarrative(
        symbol: String,
        name: String,
        description: String = "",
        socialMentions: List<String> = emptyList(),
    ): NarrativeAnalysis? {
        val cacheKey = "${symbol}_${name.hashCode()}"
        val cached = narrativeCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.second < CACHE_TTL_MS) {
            return cached.first
        }
        
        val prompt = buildNarrativePrompt(symbol, name, description, socialMentions)
        val response = callGemini(prompt, NARRATIVE_SYSTEM_PROMPT) ?: return null
        val result = parseNarrativeResponse(response) ?: return null
        
        narrativeCache[cacheKey] = result to System.currentTimeMillis()
        ErrorLogger.info("GeminiCopilot", "🤖 Narrative: $symbol → ${result.recommendation} (scam=${result.scamConfidence.toInt()}%)")
        return result
    }
    
    /**
     * Generate human-readable reasoning for a trade decision.
     */
    fun explainTrade(
        ts: TokenState,
        action: String,
        entryScore: Double,
        exitScore: Double,
        aiLayers: Map<String, String>,  // layer name → verdict
    ): TradeReasoning? {
        val prompt = buildTradeReasoningPrompt(ts, action, entryScore, exitScore, aiLayers)
        val response = callGemini(prompt, TRADE_REASONING_SYSTEM_PROMPT) ?: return null
        return parseTradeReasoningResponse(response)
    }
    
    /**
     * Analyze broader market sentiment from recent token activity.
     */
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
    
    /**
     * Get AI advice on whether to exit a position and how.
     */
    fun getExitAdvice(
        ts: TokenState,
        currentPnlPct: Double,
        holdTimeMinutes: Double,
        peakPnlPct: Double,
        recentPriceAction: List<Double>,  // last 10 prices
    ): ExitAdvice? {
        val cacheKey = "${ts.mint}_${currentPnlPct.toInt()}_${holdTimeMinutes.toInt()}"
        val cached = exitAdviceCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.second < CACHE_TTL_MS) {
            return cached.first
        }
        
        val prompt = buildExitAdvicePrompt(ts, currentPnlPct, holdTimeMinutes, peakPnlPct, recentPriceAction)
        val response = callGemini(prompt, EXIT_ADVISOR_SYSTEM_PROMPT) ?: return null
        val result = parseExitAdviceResponse(response) ?: return null
        
        exitAdviceCache[cacheKey] = result to System.currentTimeMillis()
        ErrorLogger.info("GeminiCopilot", "🤖 Exit: ${ts.symbol} → ${result.exitUrgency} (conf=${result.confidenceScore.toInt()}%)")
        return result
    }
    
    /**
     * Comprehensive risk assessment for a token.
     */
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
     * Quick scam check - fast path for obvious scams.
     * Returns true if likely scam, false if probably safe, null if uncertain.
     */
    fun quickScamCheck(symbol: String, name: String): Boolean? {
        // Check for obvious scam patterns first (no API call needed)
        val scamPatterns = listOf(
            "airdrop", "presale", "guaranteed", "1000x", "free money",
            "elon", "musk", "doge killer", "shiba killer", "moon guaranteed",
            "get rich", "millionaire", "lambo", "next bitcoin"
        )
        val lowerName = name.lowercase()
        val lowerSymbol = symbol.lowercase()
        
        for (pattern in scamPatterns) {
            if (lowerName.contains(pattern) || lowerSymbol.contains(pattern)) {
                ErrorLogger.info("GeminiCopilot", "🚨 Quick scam pattern: $symbol contains '$pattern'")
                return true
            }
        }
        
        // Check for copy-cat tokens
        val copyCatPatterns = listOf(
            Regex("(?i)(pepe|doge|shib|bonk|wif).*2"),
            Regex("(?i)real.*(pepe|doge|shib)"),
            Regex("(?i)(pepe|doge|shib).*real"),
            Regex("(?i)official.*(pepe|doge|shib)"),
        )
        for (pattern in copyCatPatterns) {
            if (pattern.containsMatchIn(name) || pattern.containsMatchIn(symbol)) {
                ErrorLogger.info("GeminiCopilot", "🚨 Copy-cat pattern: $symbol")
                return true
            }
        }
        
        return null  // Need deeper analysis
    }
    
    // ════════════════════════════════════════════════════════════════════════════
    // GEMINI API CALL
    // ════════════════════════════════════════════════════════════════════════════
    
    private fun callGemini(userPrompt: String, systemPrompt: String): String? {
        // Rate limiting
        val now = System.currentTimeMillis()
        val elapsed = now - lastCallTime
        if (elapsed < MIN_CALL_INTERVAL_MS) {
            Thread.sleep(MIN_CALL_INTERVAL_MS - elapsed)
        }
        lastCallTime = System.currentTimeMillis()
        
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
                put("temperature", 0.2)  // Low for consistent structured output
                put("maxOutputTokens", 1024)
                put("responseMimeType", "application/json")
            })
        }
        
        return try {
            if (!isConfigured()) {
                ErrorLogger.debug("GeminiCopilot", "API key not configured, skipping")
                return null
            }
            
            val url = "$GEMINI_URL?key=$apiKey"
            val req = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON_MT))
                .header("Content-Type", "application/json")
                .build()
            
            val resp = http.newCall(req).execute()
            if (!resp.isSuccessful) {
                ErrorLogger.warn("GeminiCopilot", "API error: ${resp.code}")
                return null
            }
            
            val body = resp.body?.string() ?: return null
            val json = JSONObject(body)
            
            // Extract text from Gemini response structure
            json.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
        } catch (e: Exception) {
            ErrorLogger.warn("GeminiCopilot", "Call failed: ${e.message}")
            null
        }
    }
    
    // ════════════════════════════════════════════════════════════════════════════
    // PROMPTS
    // ════════════════════════════════════════════════════════════════════════════
    
    private val NARRATIVE_SYSTEM_PROMPT = """
You are a Solana meme coin analyst specializing in detecting scams and evaluating token narratives.
Respond ONLY with valid JSON matching this exact structure:
{
  "is_scam": boolean,
  "scam_confidence": number (0-100),
  "scam_type": "rug_pull" | "honeypot" | "pump_dump" | "none",
  "narrative_type": "meme" | "utility" | "ai" | "gaming" | "defi" | "unknown",
  "viral_potential": number (0-100),
  "red_flags": ["string", ...],
  "green_flags": ["string", ...],
  "reasoning": "string (1-2 sentences)",
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
  "confidence": number (0-100),
  "primary_reason": "string",
  "supporting_factors": ["string", ...],
  "risk_factors": ["string", ...],
  "human_summary": "string (2-3 sentences explaining the trade to a human)"
}
Be concise and actionable. Focus on key factors that drove the decision.
""".trimIndent()
    
    private val MARKET_SENTIMENT_SYSTEM_PROMPT = """
You are a crypto market analyst assessing overall meme coin market conditions.
Respond ONLY with valid JSON:
{
  "overall_sentiment": "BULLISH" | "BEARISH" | "NEUTRAL" | "FEAR" | "GREED",
  "sentiment_score": number (-100 to +100),
  "meme_season_active": boolean,
  "top_narratives": ["string", ...],
  "market_risks": ["string", ...],
  "reasoning": "string (1-2 sentences)"
}
Consider: recent win rates, token activity levels, market trends.
""".trimIndent()
    
    private val EXIT_ADVISOR_SYSTEM_PROMPT = """
You are a trading exit specialist for meme coins. Your job is to advise when and how to exit positions.
Respond ONLY with valid JSON:
{
  "should_exit": boolean,
  "exit_urgency": "IMMEDIATE" | "SOON" | "HOLD" | "RIDE",
  "suggested_exit_pct": number (0-100, how much of position to exit),
  "target_price": number (if holding, what price to target),
  "stop_loss_price": number (suggested stop loss),
  "reasoning": "string (1-2 sentences)",
  "time_horizon": "minutes" | "hours" | "days",
  "confidence_score": number (0-100)
}
Consider: current P&L, peak P&L (round-trip risk), hold time, recent price action (momentum).
Meme coins move fast - be decisive. Lock profits, don't be greedy.
""".trimIndent()
    
    private val RISK_ASSESSMENT_SYSTEM_PROMPT = """
You are a risk analyst for meme coin trading.
Respond ONLY with valid JSON:
{
  "overall_risk": "LOW" | "MEDIUM" | "HIGH" | "EXTREME",
  "risk_score": number (0-100),
  "liquidity_risk": "LOW" | "MEDIUM" | "HIGH",
  "volatility_risk": "LOW" | "MEDIUM" | "HIGH",
  "rug_pull_risk": "LOW" | "MEDIUM" | "HIGH",
  "market_risk": "LOW" | "MEDIUM" | "HIGH",
  "top_risks": ["string", ...],
  "mitigation_suggestions": ["string", ...]
}
Consider: rugcheck score, holder concentration, liquidity depth, token age.
""".trimIndent()
    
    // ════════════════════════════════════════════════════════════════════════════
    // PROMPT BUILDERS
    // ════════════════════════════════════════════════════════════════════════════
    
    private fun buildNarrativePrompt(
        symbol: String, name: String, description: String, socialMentions: List<String>
    ): String {
        val social = if (socialMentions.isNotEmpty()) {
            "\nRecent social mentions:\n${socialMentions.take(5).joinToString("\n") { "- $it" }}"
        } else ""
        
        return """
Analyze this Solana meme coin:
Symbol: $symbol
Name: $name
Description: ${description.take(200).ifEmpty { "None provided" }}
$social

Evaluate for scam signals and viral potential.
""".trimIndent()
    }
    
    private fun buildTradeReasoningPrompt(
        ts: TokenState, action: String, entryScore: Double, exitScore: Double, aiLayers: Map<String, String>
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
        winRate: Double, tokens: List<String>, avgHold: Double, trend: String
    ): String = """
Market Conditions Analysis:
Recent Win Rate: ${winRate.toInt()}%
Recent Tokens Traded: ${tokens.take(10).joinToString(", ")}
Average Hold Time: ${avgHold.toInt()} minutes
Market Trend: $trend

Assess overall meme coin market sentiment.
""".trimIndent()
    
    private fun buildExitAdvicePrompt(
        ts: TokenState, pnl: Double, holdTime: Double, peak: Double, prices: List<Double>
    ): String {
        val priceStr = prices.takeLast(10).joinToString(" → ") { it.toString().take(8) }
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
        ts: TokenState, rugcheck: Int, topHolder: Double, liquidity: Double, age: Int
    ): String = """
Risk Assessment for: ${ts.symbol}
RugCheck Score: $rugcheck/100
Top Holder %: ${topHolder.toInt()}%
Liquidity USD: $${liquidity.toInt()}
Token Age: $age minutes
Current Price: ${ts.lastPrice}
24h Volume: ${ts.history.lastOrNull()?.volume24h ?: 0}

Assess all risk factors.
""".trimIndent()
    
    // ════════════════════════════════════════════════════════════════════════════
    // RESPONSE PARSERS
    // ════════════════════════════════════════════════════════════════════════════
    
    private fun parseNarrativeResponse(json: String): NarrativeAnalysis? {
        return try {
            val j = JSONObject(json.trim())
            NarrativeAnalysis(
                isScam = j.optBoolean("is_scam", false),
                scamConfidence = j.optDouble("scam_confidence", 0.0),
                scamType = j.optString("scam_type", "none"),
                narrativeType = j.optString("narrative_type", "unknown"),
                viralPotential = j.optDouble("viral_potential", 50.0),
                redFlags = j.optJSONArray("red_flags")?.toStringList() ?: emptyList(),
                greenFlags = j.optJSONArray("green_flags")?.toStringList() ?: emptyList(),
                reasoning = j.optString("reasoning", ""),
                recommendation = j.optString("recommendation", "WATCH"),
            )
        } catch (e: Exception) {
            ErrorLogger.debug("GeminiCopilot", "Parse narrative failed: ${e.message}")
            null
        }
    }
    
    private fun parseTradeReasoningResponse(json: String): TradeReasoning? {
        return try {
            val j = JSONObject(json.trim())
            TradeReasoning(
                action = j.optString("action", "HOLD"),
                confidence = j.optDouble("confidence", 50.0),
                primaryReason = j.optString("primary_reason", ""),
                supportingFactors = j.optJSONArray("supporting_factors")?.toStringList() ?: emptyList(),
                riskFactors = j.optJSONArray("risk_factors")?.toStringList() ?: emptyList(),
                humanSummary = j.optString("human_summary", ""),
            )
        } catch (_: Exception) { null }
    }
    
    private fun parseMarketSentimentResponse(json: String): MarketSentiment? {
        return try {
            val j = JSONObject(json.trim())
            MarketSentiment(
                overallSentiment = j.optString("overall_sentiment", "NEUTRAL"),
                sentimentScore = j.optDouble("sentiment_score", 0.0),
                memeSeasonActive = j.optBoolean("meme_season_active", false),
                topNarratives = j.optJSONArray("top_narratives")?.toStringList() ?: emptyList(),
                marketRisks = j.optJSONArray("market_risks")?.toStringList() ?: emptyList(),
                reasoning = j.optString("reasoning", ""),
            )
        } catch (_: Exception) { null }
    }
    
    private fun parseExitAdviceResponse(json: String): ExitAdvice? {
        return try {
            val j = JSONObject(json.trim())
            ExitAdvice(
                shouldExit = j.optBoolean("should_exit", false),
                exitUrgency = j.optString("exit_urgency", "HOLD"),
                suggestedExitPct = j.optDouble("suggested_exit_pct", 0.0),
                targetPrice = j.optDouble("target_price", 0.0),
                stopLossPrice = j.optDouble("stop_loss_price", 0.0),
                reasoning = j.optString("reasoning", ""),
                timeHorizon = j.optString("time_horizon", "hours"),
                confidenceScore = j.optDouble("confidence_score", 50.0),
            )
        } catch (_: Exception) { null }
    }
    
    private fun parseRiskAssessmentResponse(json: String): RiskAssessment? {
        return try {
            val j = JSONObject(json.trim())
            RiskAssessment(
                overallRisk = j.optString("overall_risk", "MEDIUM"),
                riskScore = j.optDouble("risk_score", 50.0),
                liquidityRisk = j.optString("liquidity_risk", "MEDIUM"),
                volatilityRisk = j.optString("volatility_risk", "MEDIUM"),
                rugPullRisk = j.optString("rug_pull_risk", "MEDIUM"),
                marketRisk = j.optString("market_risk", "MEDIUM"),
                topRisks = j.optJSONArray("top_risks")?.toStringList() ?: emptyList(),
                mitigationSuggestions = j.optJSONArray("mitigation_suggestions")?.toStringList() ?: emptyList(),
            )
        } catch (_: Exception) { null }
    }
    
    // Helper extension
    private fun JSONArray.toStringList(): List<String> {
        return (0 until length()).mapNotNull { optString(it) }
    }
}
