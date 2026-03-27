package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/**
 * LiquidityBucketRouter — Routes Tokens to Appropriate Modes by Liquidity Tier
 * 
 * Liquidity bucket determines which mode family a token belongs to:
 *   - MICRO:  $0 - $10K  → MICROCAP_PROBE mode
 *   - SMALL:  $10K - $50K → PRE_PUMP, BREAKOUT, PULLBACK
 *   - MEDIUM: $50K - $200K → ROTATION, TREND
 *   - LARGE:  $200K+ → HIGH_LIQUIDITY_ROTATION
 * 
 * A $775k liq token should NEVER be treated like a $3k liq token.
 */
object LiquidityBucketRouter {
    
    private const val TAG = "LiqBucket"
    
    enum class LiquidityBucket(
        val minLiq: Double,
        val maxLiq: Double,
        val label: String,
        val emoji: String,
        val maxSizeMultiplier: Double,  // Relative to base position size
        val riskTolerance: Double,      // 0-1, how much risk is acceptable
    ) {
        MICRO(0.0, 10_000.0, "Micro Cap", "🔬", 0.3, 0.2),
        SMALL(10_000.0, 50_000.0, "Small Cap", "🔸", 0.6, 0.5),
        MEDIUM(50_000.0, 200_000.0, "Medium Cap", "🔶", 0.8, 0.7),
        LARGE(200_000.0, Double.MAX_VALUE, "Large Cap", "💎", 1.0, 0.9),
    }
    
    data class BucketResult(
        val bucket: LiquidityBucket,
        val recommendedModes: List<ModeRouter.TradeType>,
        val maxSizeMultiplier: Double,
        val notes: List<String>,
    )
    
    // ═══════════════════════════════════════════════════════════════════
    // BUCKET CLASSIFICATION
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Classify token into liquidity bucket and get recommended modes.
     */
    fun classify(ts: TokenState): BucketResult {
        val liq = ts.lastLiquidityUsd
        val hist = ts.history.toList()
        val notes = mutableListOf<String>()
        
        val bucket = when {
            liq < LiquidityBucket.MICRO.maxLiq -> LiquidityBucket.MICRO
            liq < LiquidityBucket.SMALL.maxLiq -> LiquidityBucket.SMALL
            liq < LiquidityBucket.MEDIUM.maxLiq -> LiquidityBucket.MEDIUM
            else -> LiquidityBucket.LARGE
        }
        
        notes.add("Liq: $${(liq / 1000).toInt()}K → ${bucket.label}")
        
        // ─────────────────────────────────────────────────────────────────
        // RECOMMENDED MODES BY BUCKET
        // ─────────────────────────────────────────────────────────────────
        val recommendedModes = when (bucket) {
            LiquidityBucket.MICRO -> listOf(
                ModeRouter.TradeType.FRESH_LAUNCH,
                ModeRouter.TradeType.SENTIMENT_IGNITION,
            ).also { notes.add("⚠️ Micro: smallest size, fastest stops") }
            
            LiquidityBucket.SMALL -> listOf(
                ModeRouter.TradeType.FRESH_LAUNCH,
                ModeRouter.TradeType.BREAKOUT_CONTINUATION,
                ModeRouter.TradeType.REVERSAL_RECLAIM,
                ModeRouter.TradeType.WHALE_ACCUMULATION,
                ModeRouter.TradeType.GRADUATION,
            ).also { notes.add("Small: moderate size, standard risk") }
            
            LiquidityBucket.MEDIUM -> listOf(
                ModeRouter.TradeType.BREAKOUT_CONTINUATION,
                ModeRouter.TradeType.TREND_PULLBACK,
                ModeRouter.TradeType.WHALE_ACCUMULATION,
                ModeRouter.TradeType.GRADUATION,
            ).also { notes.add("Medium: good size, patient exits") }
            
            LiquidityBucket.LARGE -> listOf(
                ModeRouter.TradeType.TREND_PULLBACK,
                ModeRouter.TradeType.BREAKOUT_CONTINUATION,
            ).also { notes.add("💎 Large: scalable size, rotation style") }
        }
        
        // ─────────────────────────────────────────────────────────────────
        // ADJUST SIZE MULTIPLIER BASED ON LIQUIDITY TREND
        // ─────────────────────────────────────────────────────────────────
        var sizeMultiplier = bucket.maxSizeMultiplier
        
        // If liquidity is growing, allow slightly larger size
        if (hist.size >= 5) {
            val volumes = hist.takeLast(5).map { it.vol }
            val recentVol = volumes.takeLast(2).average()
            val priorVol = volumes.take(3).average()
            
            if (priorVol > 0 && recentVol > priorVol * 1.2) {
                sizeMultiplier *= 1.15
                notes.add("📈 Liq growing: +15% size")
            } else if (priorVol > 0 && recentVol < priorVol * 0.7) {
                sizeMultiplier *= 0.7
                notes.add("📉 Liq shrinking: -30% size")
            }
        }
        
        return BucketResult(
            bucket = bucket,
            recommendedModes = recommendedModes,
            maxSizeMultiplier = sizeMultiplier.coerceIn(0.1, 1.5),
            notes = notes,
        )
    }
    
    /**
     * Get the emoji for a liquidity bucket.
     */
    fun getEmoji(liq: Double): String {
        return when {
            liq < LiquidityBucket.MICRO.maxLiq -> LiquidityBucket.MICRO.emoji
            liq < LiquidityBucket.SMALL.maxLiq -> LiquidityBucket.SMALL.emoji
            liq < LiquidityBucket.MEDIUM.maxLiq -> LiquidityBucket.MEDIUM.emoji
            else -> LiquidityBucket.LARGE.emoji
        }
    }
    
    /**
     * Check if a trade type is appropriate for the token's liquidity bucket.
     */
    fun isModeAppropriate(ts: TokenState, tradeType: ModeRouter.TradeType): Boolean {
        val result = classify(ts)
        return tradeType in result.recommendedModes
    }
    
    /**
     * Get position size multiplier for a token.
     */
    fun getSizeMultiplier(ts: TokenState): Double {
        return classify(ts).maxSizeMultiplier
    }
    
    /**
     * Log bucket classification.
     */
    fun logClassification(ts: TokenState, result: BucketResult) {
        if (result.bucket in listOf(LiquidityBucket.MICRO, LiquidityBucket.LARGE)) {
            ErrorLogger.info(TAG, "${result.bucket.emoji} ${ts.symbol}: ${result.bucket.label} | " +
                "size=${(result.maxSizeMultiplier * 100).toInt()}% | ${result.notes.last()}")
        }
    }
}
