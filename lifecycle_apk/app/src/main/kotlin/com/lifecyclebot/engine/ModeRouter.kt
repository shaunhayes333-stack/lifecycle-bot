package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.data.Candle

/**
 * ModeRouter — Intelligent Trade Classification System
 * 
 * Instead of asking "Is this token a buy?", we ask "What KIND of trade is this?"
 * Each trade type gets routed to its specialized mode with:
 *   - Mode-specific entry thresholds
 *   - Mode-specific position sizing
 *   - Mode-specific stop/take-profit logic
 *   - Mode-specific timeout rules
 * 
 * Trade Classifications:
 *   - FRESH_LAUNCH: New pairs under strict age threshold
 *   - BREAKOUT_CONTINUATION: Proven strength, compressing before next leg
 *   - REVERSAL_RECLAIM: Dumped tokens reclaiming structure
 *   - WHALE_ACCUMULATION: Smart money loading before expansion
 *   - GRADUATION: Tokens transitioning from chaos to structure
 *   - TREND_PULLBACK: Established uptrend with orderly pullback
 *   - SENTIMENT_IGNITION: Social/narrative burst before price reflects
 */
object ModeRouter {
    
    private const val TAG = "ModeRouter"
    
    // ═══════════════════════════════════════════════════════════════════
    // TRADE CLASSIFICATION
    // ═══════════════════════════════════════════════════════════════════
    
    enum class TradeType(
        val emoji: String,
        val label: String,
        val maxSizePct: Double,      // Max % of bankroll for this type
        val defaultStopPct: Double,  // Default stop loss %
        val defaultTpPct: Double,    // Default take profit % (first partial)
        val maxHoldMins: Int,        // Timeout in minutes
        val riskTier: Int,           // 1=safest, 5=riskiest
    ) {
        FRESH_LAUNCH(
            emoji = "🚀",
            label = "Fresh Launch",
            maxSizePct = 2.0,        // Tiny size - highest risk
            defaultStopPct = 25.0,   // Wide stop for volatility
            defaultTpPct = 50.0,     // Fast partials
            maxHoldMins = 15,        // Short timeout
            riskTier = 5,
        ),
        BREAKOUT_CONTINUATION(
            emoji = "📊",
            label = "Breakout",
            maxSizePct = 8.0,        // Medium-large - proven strength
            defaultStopPct = 12.0,   // Tighter stop below structure
            defaultTpPct = 25.0,     // Let it run
            maxHoldMins = 120,       // Longer hold
            riskTier = 2,
        ),
        REVERSAL_RECLAIM(
            emoji = "🔄",
            label = "Reversal",
            maxSizePct = 5.0,        // Medium size
            defaultStopPct = 15.0,   // Medium stop
            defaultTpPct = 30.0,     // Take first target quicker
            maxHoldMins = 60,        // Don't overstay
            riskTier = 3,
        ),
        WHALE_ACCUMULATION(
            emoji = "🐋",
            label = "Whale Follow",
            maxSizePct = 6.0,        // Medium size
            defaultStopPct = 18.0,   // Exit if accumulation band fails
            defaultTpPct = 40.0,     // Partial on expansion
            maxHoldMins = 180,       // Patient
            riskTier = 3,
        ),
        GRADUATION(
            emoji = "🎓",
            label = "Graduation",
            maxSizePct = 5.0,        // Medium size
            defaultStopPct = 20.0,   // Event-driven volatility
            defaultTpPct = 35.0,     
            maxHoldMins = 90,        
            riskTier = 3,
        ),
        TREND_PULLBACK(
            emoji = "📈",
            label = "Trend Pullback",
            maxSizePct = 10.0,       // Largest - most reliable
            defaultStopPct = 10.0,   // Tightest stop relative to structure
            defaultTpPct = 20.0,     // Trail under higher lows
            maxHoldMins = 240,       // Widest patience
            riskTier = 1,
        ),
        SENTIMENT_IGNITION(
            emoji = "🔥",
            label = "Sentiment",
            maxSizePct = 4.0,        // Smaller - narrative driven
            defaultStopPct = 20.0,   
            defaultTpPct = 40.0,     
            maxHoldMins = 45,        // Narrative fades fast
            riskTier = 4,
        ),
        UNKNOWN(
            emoji = "❓",
            label = "Unknown",
            maxSizePct = 3.0,
            defaultStopPct = 15.0,
            defaultTpPct = 25.0,
            maxHoldMins = 60,
            riskTier = 3,
        ),
    }
    
    /**
     * Classification result with confidence and reasoning.
     */
    data class Classification(
        val tradeType: TradeType,
        val confidence: Double,      // 0-100
        val signals: List<String>,   // Why this classification
        val subSignals: Map<TradeType, Double>,  // Scores for each type
    )
    
    // ═══════════════════════════════════════════════════════════════════
    // MAIN CLASSIFICATION LOGIC
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Classify a token into the most appropriate trade type.
     * This determines which mode's logic to apply.
     */
    fun classify(ts: TokenState): Classification {
        val signals = mutableListOf<String>()
        val scores = mutableMapOf<TradeType, Double>()
        
        // Initialize all scores
        TradeType.values().forEach { scores[it] = 0.0 }
        
        val hist = ts.history.toList()
        val now = System.currentTimeMillis()
        
        // Calculate token age from history (first candle timestamp)
        val tokenAgeMins = if (hist.isNotEmpty()) {
            (now - hist.first().ts) / 60_000.0
        } else 999.0
        
        // ─────────────────────────────────────────────────────────────────
        // FRESH LAUNCH DETECTION
        // ─────────────────────────────────────────────────────────────────
        if (tokenAgeMins <= 15.0) {
            scores[TradeType.FRESH_LAUNCH] = scores[TradeType.FRESH_LAUNCH]!! + 50.0
            signals.add("FRESH: age=${tokenAgeMins.toInt()}min")
            
            // Bonus for good early structure
            if (ts.lastLiquidityUsd > 3000) {
                scores[TradeType.FRESH_LAUNCH] = scores[TradeType.FRESH_LAUNCH]!! + 15.0
                signals.add("FRESH: good liq $${ts.lastLiquidityUsd.toInt()}")
            }
            
            // Check early buy pressure
            val lastCandle = hist.lastOrNull()
            if (lastCandle != null && lastCandle.buyRatio > 0.55) {
                scores[TradeType.FRESH_LAUNCH] = scores[TradeType.FRESH_LAUNCH]!! + 10.0
            }
        }
        
        // ─────────────────────────────────────────────────────────────────
        // BREAKOUT CONTINUATION DETECTION
        // ─────────────────────────────────────────────────────────────────
        if (hist.size >= 10) {
            val breakoutScore = detectBreakoutContinuation(hist, ts)
            scores[TradeType.BREAKOUT_CONTINUATION] = breakoutScore.score
            if (breakoutScore.score > 30) {
                signals.addAll(breakoutScore.reasons)
            }
        }
        
        // ─────────────────────────────────────────────────────────────────
        // REVERSAL RECLAIM DETECTION
        // ─────────────────────────────────────────────────────────────────
        if (hist.size >= 8) {
            val reversalScore = detectReversalReclaim(hist, ts)
            scores[TradeType.REVERSAL_RECLAIM] = reversalScore.score
            if (reversalScore.score > 30) {
                signals.addAll(reversalScore.reasons)
            }
        }
        
        // ─────────────────────────────────────────────────────────────────
        // WHALE ACCUMULATION DETECTION
        // ─────────────────────────────────────────────────────────────────
        val whaleScore = detectWhaleAccumulation(ts)
        scores[TradeType.WHALE_ACCUMULATION] = whaleScore.score
        if (whaleScore.score > 30) {
            signals.addAll(whaleScore.reasons)
        }
        
        // ─────────────────────────────────────────────────────────────────
        // GRADUATION DETECTION
        // ─────────────────────────────────────────────────────────────────
        val gradScore = detectGraduation(ts, hist)
        scores[TradeType.GRADUATION] = gradScore.score
        if (gradScore.score > 30) {
            signals.addAll(gradScore.reasons)
        }
        
        // ─────────────────────────────────────────────────────────────────
        // TREND PULLBACK DETECTION
        // ─────────────────────────────────────────────────────────────────
        if (hist.size >= 15) {
            val trendScore = detectTrendPullback(hist, ts)
            scores[TradeType.TREND_PULLBACK] = trendScore.score
            if (trendScore.score > 30) {
                signals.addAll(trendScore.reasons)
            }
        }
        
        // ─────────────────────────────────────────────────────────────────
        // SENTIMENT IGNITION DETECTION
        // ─────────────────────────────────────────────────────────────────
        val sentimentScore = detectSentimentIgnition(ts, hist)
        scores[TradeType.SENTIMENT_IGNITION] = sentimentScore.score
        if (sentimentScore.score > 30) {
            signals.addAll(sentimentScore.reasons)
        }
        
        // ─────────────────────────────────────────────────────────────────
        // PICK WINNER
        // ─────────────────────────────────────────────────────────────────
        val bestType = scores.maxByOrNull { it.value }?.key ?: TradeType.UNKNOWN
        val bestScore = scores[bestType] ?: 0.0
        
        // Calculate confidence (how much better is best vs second best)
        val sortedScores = scores.values.sortedDescending()
        val secondBest = if (sortedScores.size > 1) sortedScores[1] else 0.0
        val confidence = when {
            bestScore <= 20 -> 10.0  // Very weak signal
            bestScore - secondBest > 30 -> 90.0  // Clear winner
            bestScore - secondBest > 15 -> 70.0  // Good separation
            bestScore - secondBest > 5 -> 50.0   // Moderate
            else -> 30.0  // Ambiguous
        }
        
        return Classification(
            tradeType = if (bestScore > 25) bestType else TradeType.UNKNOWN,
            confidence = confidence,
            signals = signals,
            subSignals = scores,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // BREAKOUT CONTINUATION DETECTOR
    // ═══════════════════════════════════════════════════════════════════
    
    private data class SubScore(val score: Double, val reasons: List<String>)
    
    private fun detectBreakoutContinuation(hist: List<Candle>, ts: TokenState): SubScore {
        var score = 0.0
        val reasons = mutableListOf<String>()
        
        val prices = hist.map { it.priceUsd }
        val vols = hist.map { it.vol }
        
        // Check for prior impulse leg (significant move up)
        val recentHigh = prices.takeLast(10).maxOrNull() ?: return SubScore(0.0, emptyList())
        val priorLow = prices.dropLast(5).takeLast(10).minOrNull() ?: return SubScore(0.0, emptyList())
        val impulsePct = if (priorLow > 0) ((recentHigh - priorLow) / priorLow) * 100 else 0.0
        
        if (impulsePct > 30) {
            score += 25.0
            reasons.add("BREAKOUT: prior impulse +${impulsePct.toInt()}%")
        }
        
        // Check for higher lows (consolidation, not distribution)
        val last5Lows = prices.takeLast(5)
        val higherLows = last5Lows.zipWithNext { a, b -> b >= a * 0.98 }.count { it }
        if (higherLows >= 3) {
            score += 20.0
            reasons.add("BREAKOUT: higher lows forming")
        }
        
        // Volume contraction during consolidation
        val recentVol = vols.takeLast(3).average()
        val priorVol = vols.dropLast(3).takeLast(5).average()
        if (priorVol > 0 && recentVol < priorVol * 0.7) {
            score += 15.0
            reasons.add("BREAKOUT: vol contracting")
        }
        
        // Liquidity rising or stable
        if (ts.lastLiquidityUsd > 5000) {
            score += 10.0
        }
        
        // Price near local high (ready to break)
        val currentPrice = prices.lastOrNull() ?: 0.0
        if (currentPrice > recentHigh * 0.95) {
            score += 15.0
            reasons.add("BREAKOUT: near local high")
        }
        
        // Holder concentration stable
        val lastCandle = hist.lastOrNull()
        if (lastCandle != null && lastCandle.holderCount > 50) {
            score += 5.0
        }
        
        return SubScore(score, reasons)
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // REVERSAL RECLAIM DETECTOR
    // ═══════════════════════════════════════════════════════════════════
    
    private fun detectReversalReclaim(hist: List<Candle>, ts: TokenState): SubScore {
        var score = 0.0
        val reasons = mutableListOf<String>()
        
        val prices = hist.map { it.priceUsd }
        
        // Check for prior dump
        val recentHigh = prices.dropLast(3).takeLast(8).maxOrNull() ?: return SubScore(0.0, emptyList())
        val recentLow = prices.takeLast(5).minOrNull() ?: return SubScore(0.0, emptyList())
        val dumpPct = if (recentHigh > 0) ((recentHigh - recentLow) / recentHigh) * 100 else 0.0
        
        if (dumpPct > 25) {
            score += 20.0
            reasons.add("REVERSAL: prior dump -${dumpPct.toInt()}%")
        } else {
            return SubScore(0.0, emptyList())  // No meaningful dump
        }
        
        // Check for stabilization (not making new lows)
        val last3Prices = prices.takeLast(3)
        val stabilized = last3Prices.all { it >= recentLow * 0.98 }
        if (stabilized) {
            score += 20.0
            reasons.add("REVERSAL: stabilizing above low")
        }
        
        // Sell pressure weakening (buy ratio recovering)
        val recentCandles = hist.takeLast(3)
        val avgBuyRatio = recentCandles.map { it.buyRatio }.average()
        if (avgBuyRatio > 0.48) {
            score += 15.0
            reasons.add("REVERSAL: sell pressure weakening")
        }
        
        // Wicks getting bought up
        val wicksBought = recentCandles.count { c ->
            c.lowUsd > 0 && c.priceUsd > c.lowUsd * 1.02
        }
        if (wicksBought >= 2) {
            score += 15.0
            reasons.add("REVERSAL: wicks bought")
        }
        
        // Liquidity not draining
        if (ts.lastLiquidityUsd > 2000) {
            score += 10.0
        }
        
        // Check for reclaim of prior support
        val currentPrice = prices.lastOrNull() ?: 0.0
        val midPoint = (recentHigh + recentLow) / 2
        if (currentPrice > midPoint) {
            score += 15.0
            reasons.add("REVERSAL: reclaiming midpoint")
        }
        
        return SubScore(score, reasons)
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // WHALE ACCUMULATION DETECTOR
    // ═══════════════════════════════════════════════════════════════════
    
    private fun detectWhaleAccumulation(ts: TokenState): SubScore {
        var score = 0.0
        val reasons = mutableListOf<String>()
        
        // Use WhaleDetector if available
        try {
            val whaleSignal = WhaleDetector.evaluate(ts.mint, ts)
            
            if (whaleSignal.hasWhaleActivity) {
                score += 30.0
                reasons.add("WHALE: ${whaleSignal.whaleBuys} large buys")
            }
            
            if (whaleSignal.smartMoneyPresent) {
                score += 25.0
                reasons.add("WHALE: smart money detected")
            }
            
            // Velocity score indicates accumulation pace
            if (whaleSignal.velocityScore > 50) {
                score += 15.0
                reasons.add("WHALE: high velocity ${whaleSignal.velocityScore.toInt()}")
            }
            
            // Low concentration = distributed accumulation (better)
            if (whaleSignal.concentration < 40) {
                score += 10.0
            }
        } catch (_: Exception) { }
        
        // Check if price not yet expanded (still in accumulation zone)
        val hist = ts.history.toList()
        if (hist.size >= 5) {
            val prices = hist.map { it.priceUsd }
            val recentHigh = prices.takeLast(10).maxOrNull() ?: 0.0
            val currentPrice = prices.lastOrNull() ?: 0.0
            
            // Still near accumulation zone (not already pumped)
            if (recentHigh > 0 && currentPrice < recentHigh * 1.3) {
                score += 10.0
                reasons.add("WHALE: price not yet expanded")
            }
        }
        
        return SubScore(score, reasons)
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // GRADUATION DETECTOR
    // ═══════════════════════════════════════════════════════════════════
    
    private fun detectGraduation(ts: TokenState, hist: List<Candle>): SubScore {
        var score = 0.0
        val reasons = mutableListOf<String>()
        
        // Check source for graduation indicators
        val source = ts.source.lowercase()
        if (source.contains("pump") || source.contains("grad") || source.contains("migrat")) {
            score += 20.0
            reasons.add("GRAD: source indicates graduation")
        }
        
        // Look for liquidity jump (migration event)
        if (hist.size >= 5) {
            val liqVals = hist.takeLast(10).map { it.vol }  // Using vol as proxy
            val recentLiq = liqVals.takeLast(3).average()
            val priorLiq = liqVals.dropLast(3).take(3).average()
            
            if (priorLiq > 0 && recentLiq > priorLiq * 2) {
                score += 25.0
                reasons.add("GRAD: liquidity jumped ${(recentLiq / priorLiq).toInt()}x")
            }
        }
        
        // Volume remains active post-event
        val lastCandle = hist.lastOrNull()
        if (lastCandle != null && lastCandle.vol > 5000) {
            score += 15.0
            reasons.add("GRAD: active volume post-event")
        }
        
        // Structure surviving (no immediate collapse)
        if (hist.size >= 5) {
            val prices = hist.takeLast(5).map { it.priceUsd }
            val high = prices.maxOrNull() ?: 0.0
            val current = prices.lastOrNull() ?: 0.0
            
            if (high > 0 && current > high * 0.7) {
                score += 15.0
                reasons.add("GRAD: structure surviving")
            }
        }
        
        // Holder count growing
        if (lastCandle != null && lastCandle.holderCount > 100) {
            score += 10.0
        }
        
        return SubScore(score, reasons)
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // TREND PULLBACK DETECTOR
    // ═══════════════════════════════════════════════════════════════════
    
    private fun detectTrendPullback(hist: List<Candle>, ts: TokenState): SubScore {
        var score = 0.0
        val reasons = mutableListOf<String>()
        
        val prices = hist.map { it.priceUsd }
        val vols = hist.map { it.vol }
        
        // Check for established uptrend (series of higher highs)
        val highs = prices.chunked(3).map { it.maxOrNull() ?: 0.0 }
        val higherHighs = highs.zipWithNext { a, b -> b > a }.count { it }
        
        if (higherHighs >= 2) {
            score += 25.0
            reasons.add("TREND: established uptrend")
        } else {
            return SubScore(0.0, emptyList())  // No uptrend
        }
        
        // Check for orderly pullback (not panic selling)
        val recentHigh = prices.takeLast(10).maxOrNull() ?: 0.0
        val currentPrice = prices.lastOrNull() ?: 0.0
        val pullbackPct = if (recentHigh > 0) ((recentHigh - currentPrice) / recentHigh) * 100 else 0.0
        
        if (pullbackPct in 5.0..20.0) {
            score += 25.0
            reasons.add("TREND: orderly pullback -${pullbackPct.toInt()}%")
        }
        
        // Reduced volume during pullback
        val recentVol = vols.takeLast(3).average()
        val priorVol = vols.dropLast(3).takeLast(5).average()
        if (priorVol > 0 && recentVol < priorVol * 0.8) {
            score += 15.0
            reasons.add("TREND: reduced pullback volume")
        }
        
        // Check EMA fan (using simple MA proxy)
        val ema8 = prices.takeLast(8).average()
        val ema21 = prices.takeLast(21).average()
        if (ema8 > ema21 && currentPrice >= ema21 * 0.98) {
            score += 15.0
            reasons.add("TREND: EMA structure bullish")
        }
        
        // Holders stable during pullback
        val lastCandle = hist.lastOrNull()
        if (lastCandle != null && lastCandle.holderCount > 50) {
            score += 5.0
        }
        
        // Liquidity steady
        if (ts.lastLiquidityUsd > 5000) {
            score += 5.0
        }
        
        return SubScore(score, reasons)
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // SENTIMENT IGNITION DETECTOR
    // ═══════════════════════════════════════════════════════════════════
    
    private fun detectSentimentIgnition(ts: TokenState, hist: List<Candle>): SubScore {
        var score = 0.0
        val reasons = mutableListOf<String>()
        
        // Check for profile/social presence (use name and symbol presence as proxy)
        if (ts.name.isNotEmpty() && ts.symbol.isNotEmpty() && ts.name != ts.symbol) {
            score += 10.0
            reasons.add("SENTIMENT: has identity")
        }
        
        // Check source for boost indicators
        val source = ts.source.lowercase()
        if (source.contains("boost") || source.contains("trend")) {
            score += 20.0
            reasons.add("SENTIMENT: boosted source")
        }
        
        // Trending indicators (check from source or meta)
        if (source.contains("coingecko") || source.contains("birdeye_trend")) {
            score += 20.0
            reasons.add("SENTIMENT: trending source")
        }
        
        // Buy pressure rising (sentiment translating to action)
        if (hist.size >= 3) {
            val recentBuyRatios = hist.takeLast(3).map { it.buyRatio }
            val rising = recentBuyRatios.zipWithNext { a, b -> b > a }.count { it }
            if (rising >= 2) {
                score += 15.0
                reasons.add("SENTIMENT: buy pressure rising")
            }
        }
        
        // Chart still early (not fully expanded)
        if (hist.size >= 5) {
            val prices = hist.map { it.priceUsd }
            val low = prices.minOrNull() ?: 0.0
            val current = prices.lastOrNull() ?: 0.0
            val expansion = if (low > 0) ((current - low) / low) * 100 else 0.0
            
            if (expansion < 100) {
                score += 10.0
                reasons.add("SENTIMENT: chart not fully expanded")
            }
        }
        
        // Volume confirming sentiment
        val lastCandle = hist.lastOrNull()
        if (lastCandle != null && lastCandle.vol > 10000) {
            score += 10.0
        }
        
        return SubScore(score, reasons)
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // MODE MAPPING
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Map TradeType to UnifiedModeOrchestrator.ExtendedMode
     */
    fun mapToExtendedMode(tradeType: TradeType): UnifiedModeOrchestrator.ExtendedMode {
        return when (tradeType) {
            TradeType.FRESH_LAUNCH -> UnifiedModeOrchestrator.ExtendedMode.PRESALE_SNIPE
            TradeType.BREAKOUT_CONTINUATION -> UnifiedModeOrchestrator.ExtendedMode.MOMENTUM_SWING
            TradeType.REVERSAL_RECLAIM -> UnifiedModeOrchestrator.ExtendedMode.REVIVAL
            TradeType.WHALE_ACCUMULATION -> UnifiedModeOrchestrator.ExtendedMode.WHALE_FOLLOW
            TradeType.GRADUATION -> UnifiedModeOrchestrator.ExtendedMode.MOONSHOT
            TradeType.TREND_PULLBACK -> UnifiedModeOrchestrator.ExtendedMode.STANDARD
            TradeType.SENTIMENT_IGNITION -> UnifiedModeOrchestrator.ExtendedMode.PUMP_SNIPER
            TradeType.UNKNOWN -> UnifiedModeOrchestrator.ExtendedMode.STANDARD
        }
    }
    
    /**
     * Get the emoji for a trade type classification.
     */
    fun getEmoji(tradeType: TradeType): String = tradeType.emoji
    
    /**
     * Log classification for debugging.
     */
    fun logClassification(ts: TokenState, classification: Classification) {
        if (classification.tradeType != TradeType.UNKNOWN && classification.confidence > 40) {
            ErrorLogger.info(TAG, "${classification.tradeType.emoji} ${ts.symbol} classified as " +
                "${classification.tradeType.label} (${classification.confidence.toInt()}%) | " +
                classification.signals.take(3).joinToString(", "))
        }
    }
}
