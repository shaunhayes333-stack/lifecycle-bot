package com.lifecyclebot.engine

/**
 * UnifiedNarrativeAI - Single LLM signal combining Groq + Gemini
 * 
 * Problem: Having two separate LLM layers (Groq for narrative, Gemini for scam detection)
 * creates correlated signals. Both analyze token names/descriptions.
 * 
 * Solution: Merge into ONE signal that uses:
 * - Groq for fast initial screening (cheap, fast)
 * - Gemini for deeper analysis when Groq is uncertain (more accurate)
 * 
 * Output: Single narrative score for OrthogonalSignals.NARRATIVE category
 */
object UnifiedNarrativeAI {
    
    data class NarrativeResult(
        val score: Double,              // 0-100 (50 = neutral)
        val confidence: Double,         // 0-100
        val isScam: Boolean,
        val scamConfidence: Double,
        val narrativeType: String,      // "meme", "ai", "political", "defi", etc.
        val viralPotential: Double,     // 0-100
        val source: String,             // "groq", "gemini", "combined"
        val reasoning: String,
    )
    
    // Cache to avoid redundant calls
    private val cache = mutableMapOf<String, Pair<NarrativeResult, Long>>()
    private const val CACHE_TTL_MS = 5 * 60_000L  // 5 minutes
    
    /**
     * Get unified narrative score for a token.
     * Uses tiered approach: Groq first, Gemini if uncertain.
     */
    fun analyze(
        symbol: String,
        name: String,
        description: String = "",
    ): NarrativeResult {
        val cacheKey = "${symbol}_${name.hashCode()}"
        
        // Check cache
        cache[cacheKey]?.let { (result, timestamp) ->
            if (System.currentTimeMillis() - timestamp < CACHE_TTL_MS) {
                return result
            }
        }
        
        // TIER 1: Quick pattern-based scam check (no API call)
        val quickScam = GeminiCopilot.quickScamCheck(symbol, name)
        if (quickScam == true) {
            val result = NarrativeResult(
                score = 10.0,  // Very bearish
                confidence = 85.0,
                isScam = true,
                scamConfidence = 90.0,
                narrativeType = "scam_pattern",
                viralPotential = 0.0,
                source = "pattern",
                reasoning = "Matched known scam pattern in name/symbol",
            )
            cache[cacheKey] = result to System.currentTimeMillis()
            return result
        }
        
        // TIER 2: Try Groq first (faster, cheaper)
        val groqResult = tryGroqAnalysis(symbol, name, description)
        
        // If Groq is confident (>70%), use it
        if (groqResult != null && groqResult.confidence >= 70.0) {
            cache[cacheKey] = groqResult to System.currentTimeMillis()
            return groqResult
        }
        
        // TIER 3: Groq uncertain or failed - use Gemini for deeper analysis
        val geminiResult = tryGeminiAnalysis(symbol, name, description)
        
        // If both available, combine them
        val finalResult = when {
            groqResult != null && geminiResult != null -> combineResults(groqResult, geminiResult)
            geminiResult != null -> geminiResult
            groqResult != null -> groqResult
            else -> getDefaultResult(symbol)
        }
        
        cache[cacheKey] = finalResult to System.currentTimeMillis()
        return finalResult
    }
    
    /**
     * Try Groq-based analysis (using existing LlmSentimentEngine)
     * Note: LlmSentimentEngine is a class that requires instantiation.
     * For now, we skip Groq and rely on Gemini for narrative analysis.
     */
    private fun tryGroqAnalysis(symbol: String, name: String, description: String): NarrativeResult? {
        // LlmSentimentEngine requires instantiation with API key and is designed
        // for different use case (scoring with text bundle). 
        // For unified narrative, we'll rely primarily on Gemini which is already
        // integrated as an object singleton.
        // 
        // TODO: If Groq integration is needed, instantiate LlmSentimentEngine
        // with the API key from BotConfig and adapt the call.
        return null
    }
    
    /**
     * Try Gemini-based analysis (using GeminiCopilot)
     */
    private fun tryGeminiAnalysis(symbol: String, name: String, description: String): NarrativeResult? {
        return try {
            val analysis = GeminiCopilot.analyzeNarrative(
                symbol = symbol,
                name = name,
                description = description,
                socialMentions = emptyList(),
            )
            
            if (analysis != null) {
                // Convert Gemini result to unified format
                val score = when (analysis.recommendation) {
                    "BUY" -> 70.0 + (analysis.viralPotential * 0.3)
                    "WATCH" -> 50.0
                    "AVOID" -> 30.0 - (analysis.scamConfidence * 0.2)
                    else -> 50.0
                }.coerceIn(0.0, 100.0)
                
                NarrativeResult(
                    score = score,
                    confidence = if (analysis.isScam) 85.0 else 65.0,
                    isScam = analysis.isScam,
                    scamConfidence = analysis.scamConfidence,
                    narrativeType = analysis.narrativeType,
                    viralPotential = analysis.viralPotential,
                    source = "gemini",
                    reasoning = analysis.reasoning,
                )
            } else null
        } catch (e: Exception) {
            ErrorLogger.debug("UnifiedNarrativeAI", "Gemini failed: ${e.message}")
            null
        }
    }
    
    /**
     * Combine Groq and Gemini results intelligently
     */
    private fun combineResults(groq: NarrativeResult, gemini: NarrativeResult): NarrativeResult {
        // If they agree on scam status, high confidence
        val bothSayScam = groq.isScam && gemini.isScam
        val bothSaySafe = !groq.isScam && !gemini.isScam
        
        val combinedConfidence = when {
            bothSayScam -> 95.0  // Both agree it's a scam
            bothSaySafe -> 80.0  // Both agree it's safe
            else -> 50.0         // Disagreement = low confidence
        }
        
        // Weight by individual confidence
        val groqWeight = groq.confidence / (groq.confidence + gemini.confidence)
        val geminiWeight = gemini.confidence / (groq.confidence + gemini.confidence)
        
        val combinedScore = (groq.score * groqWeight) + (gemini.score * geminiWeight)
        val combinedScamConf = (groq.scamConfidence * groqWeight) + (gemini.scamConfidence * geminiWeight)
        val combinedViral = (groq.viralPotential * groqWeight) + (gemini.viralPotential * geminiWeight)
        
        return NarrativeResult(
            score = combinedScore,
            confidence = combinedConfidence,
            isScam = bothSayScam || combinedScamConf > 70,
            scamConfidence = combinedScamConf,
            narrativeType = gemini.narrativeType,  // Gemini usually better at this
            viralPotential = combinedViral,
            source = "combined",
            reasoning = "Groq: ${groq.reasoning.take(50)} | Gemini: ${gemini.reasoning.take(50)}",
        )
    }
    
    /**
     * Default result when both LLMs fail
     */
    private fun getDefaultResult(symbol: String): NarrativeResult {
        return NarrativeResult(
            score = 50.0,  // Neutral
            confidence = 20.0,  // Very low confidence
            isScam = false,
            scamConfidence = 0.0,
            narrativeType = "unknown",
            viralPotential = 50.0,
            source = "default",
            reasoning = "No LLM analysis available for $symbol",
        )
    }
    
    /**
     * Clear cache (call when memory pressure)
     */
    fun clearCache() {
        cache.clear()
    }
}
