package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * LAYER TRANSITION MANAGER - "RIDE THE WAVE" v1.0
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * This manager handles smooth transitions between trading layers as tokens
 * grow through different market cap tiers. The goal is to:
 * 
 * 🚀 RIDE A WINNER FROM $5K TO $100M+ 🚀
 * 
 * Example journey:
 * - Token launches at $5K mcap → ShitCoin Layer catches it
 * - Grows to $500K → Transitions to V3 Quality Layer  
 * - Hits $1M → Transitions to Blue Chip Layer
 * - Hits $10M+ → Continue riding in Blue Chip with wider targets
 * 
 * KEY RULES:
 * 1. Transitions only happen on the WAY UP (not down)
 * 2. Position is preserved across transitions
 * 3. Take profit/stop loss are adjusted for new layer
 * 4. Original entry price is maintained for P&L tracking
 * 
 * LAYER BOUNDARIES:
 * - ShitCoin: <$500K mcap, <6h age
 * - ShitCoin Express: <$300K mcap, momentum >5%
 * - V3 Quality: $20K - $5M mcap (quality setups)
 * - Dip Hunter: $50K - $5M mcap (dipped tokens)
 * - Blue Chip: >$1M mcap (established tokens)
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object LayerTransitionManager {
    
    private const val TAG = "LayerTransition"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LAYER BOUNDARIES (in USD)
    // V4.1: Adjusted ranges - ShitCoin was way too ranged at $500K
    // ═══════════════════════════════════════════════════════════════════════════
    
    // ShitCoin Layer - true micro caps (new launches, pump.fun, etc.)
    const val SHITCOIN_MAX_MCAP = 30_000.0    // V4.1: Was $500K - way too high!
    const val SHITCOIN_MAX_AGE_HOURS = 6.0
    
    // Express Layer - momentum plays on micro caps
    const val EXPRESS_MAX_MCAP = 30_000.0     // V4.1: Was $300K
    const val EXPRESS_MIN_MOMENTUM = 5.0
    
    // V3 Quality Layer - established low caps ($30K - $1M)
    const val V3_MIN_MCAP = 30_000.0          // V4.1: Was $20K
    const val V3_MAX_MCAP = 5_000_000.0
    
    // Blue Chip Layer - established tokens (>$1M)
    const val BLUECHIP_MIN_MCAP = 1_000_000.0
    
    // Transition thresholds (with buffer to prevent oscillation)
    const val TRANSITION_BUFFER_PCT = 10.0  // 10% buffer before transitioning
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LAYER DEFINITIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class TradingLayer(
        val emoji: String,
        val displayName: String,
        val minMcap: Double,
        val maxMcap: Double,
        val defaultTP: Double,
        val defaultSL: Double,
        val maxHoldMins: Int,
    ) {
        SHITCOIN("💩", "ShitCoin", 0.0, 30_000.0, 25.0, 10.0, 15),
        EXPRESS("💩🚂", "Express", 0.0, 30_000.0, 30.0, 8.0, 10),
        V3_QUALITY("🎯", "V3 Quality", 30_000.0, 1_000_000.0, 35.0, 12.0, 60),
        DIP_HUNTER("📉", "DipHunter", 50_000.0, 5_000_000.0, 20.0, 15.0, 360),
        BLUE_CHIP("🔵", "BlueChip", 1_000_000.0, Double.MAX_VALUE, 40.0, 15.0, 120),
        TREASURY("💰", "Treasury", 0.0, Double.MAX_VALUE, 15.0, 8.0, 30),
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE TRACKING
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Track which layer each position is currently in
    private val positionLayers = ConcurrentHashMap<String, LayerState>()
    
    data class LayerState(
        val mint: String,
        val symbol: String,
        var currentLayer: TradingLayer,
        var entryLayer: TradingLayer,
        var entryMcap: Double,
        var entryPrice: Double,
        var highestMcap: Double,
        var transitionCount: Int = 0,
        val transitionHistory: MutableList<TransitionRecord> = mutableListOf(),
    )
    
    data class TransitionRecord(
        val fromLayer: TradingLayer,
        val toLayer: TradingLayer,
        val mcapAtTransition: Double,
        val priceAtTransition: Double,
        val timestamp: Long,
        val pnlAtTransition: Double,
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun init() {
        ErrorLogger.info(TAG, "🔄 Layer Transition Manager initialized")
        ErrorLogger.info(TAG, "   ShitCoin: <\$${(SHITCOIN_MAX_MCAP/1000).toInt()}K")
        ErrorLogger.info(TAG, "   V3 Quality: \$${(V3_MIN_MCAP/1000).toInt()}K - \$${(V3_MAX_MCAP/1_000_000).toInt()}M")
        ErrorLogger.info(TAG, "   Blue Chip: >\$${(BLUECHIP_MIN_MCAP/1_000_000).toInt()}M")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LAYER DETERMINATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Determine the appropriate trading layer for a token based on market cap
     */
    fun determineLayer(
        marketCapUsd: Double,
        tokenAgeHours: Double,
        momentum: Double = 0.0,
        isQualitySetup: Boolean = false,
    ): TradingLayer {
        return when {
            // Express: Super hot momentum plays <$300K
            marketCapUsd <= EXPRESS_MAX_MCAP && momentum >= EXPRESS_MIN_MOMENTUM -> TradingLayer.EXPRESS
            
            // ShitCoin: Fresh micro caps <$500K, <6h
            marketCapUsd <= SHITCOIN_MAX_MCAP && tokenAgeHours <= SHITCOIN_MAX_AGE_HOURS -> TradingLayer.SHITCOIN
            
            // Blue Chip: Established tokens >$1M
            marketCapUsd >= BLUECHIP_MIN_MCAP -> TradingLayer.BLUE_CHIP
            
            // V3 Quality: $20K-$5M with quality setups
            marketCapUsd in V3_MIN_MCAP..V3_MAX_MCAP -> TradingLayer.V3_QUALITY
            
            // Fallback to V3 Quality
            else -> TradingLayer.V3_QUALITY
        }
    }
    
    /**
     * Check if a token qualifies for V3 Quality layer (low cap but quality)
     */
    fun isV3QualityCandidate(
        marketCapUsd: Double,
        tokenAgeHours: Double,
        liquidityUsd: Double,
        buyPressurePct: Double,
        holderConcentration: Double,
    ): Boolean {
        // Must be in V3 range
        if (marketCapUsd < V3_MIN_MCAP || marketCapUsd > V3_MAX_MCAP) return false
        
        // Quality checks
        val hasGoodLiquidity = liquidityUsd >= marketCapUsd * 0.10  // 10%+ liq ratio
        val hasGoodBuyPressure = buyPressurePct >= 45.0
        val hasGoodDistribution = holderConcentration <= 40.0  // Top holder <40%
        val hasAge = tokenAgeHours >= 0.5  // At least 30 mins old
        
        // Need at least 3 of 4 quality signals
        val qualitySignals = listOf(hasGoodLiquidity, hasGoodBuyPressure, hasGoodDistribution, hasAge)
        return qualitySignals.count { it } >= 3
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // POSITION TRACKING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Register a new position with its initial layer
     */
    fun registerPosition(
        mint: String,
        symbol: String,
        layer: TradingLayer,
        entryMcap: Double,
        entryPrice: Double,
    ) {
        positionLayers[mint] = LayerState(
            mint = mint,
            symbol = symbol,
            currentLayer = layer,
            entryLayer = layer,
            entryMcap = entryMcap,
            entryPrice = entryPrice,
            highestMcap = entryMcap,
        )
        
        ErrorLogger.info(TAG, "📝 Registered: $symbol | ${layer.emoji} ${layer.displayName} | " +
            "mcap=\$${(entryMcap/1000).fmt(1)}K")
    }
    
    /**
     * Get current layer for a position
     */
    fun getCurrentLayer(mint: String): TradingLayer? {
        return positionLayers[mint]?.currentLayer
    }
    
    /**
     * Get layer state for a position
     */
    fun getLayerState(mint: String): LayerState? {
        return positionLayers[mint]
    }
    
    /**
     * Remove position when closed
     */
    fun removePosition(mint: String) {
        positionLayers.remove(mint)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TRANSITION LOGIC - Only transitions UP!
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class TransitionSignal(
        val shouldTransition: Boolean,
        val fromLayer: TradingLayer,
        val toLayer: TradingLayer,
        val reason: String,
        val newTakeProfit: Double,
        val newStopLoss: Double,
    )
    
    /**
     * Check if a position should transition to a higher layer
     * ONLY transitions on the WAY UP - never down!
     */
    fun checkTransition(
        mint: String,
        currentMcap: Double,
        currentPrice: Double,
    ): TransitionSignal {
        val state = positionLayers[mint] ?: return TransitionSignal(
            false, TradingLayer.V3_QUALITY, TradingLayer.V3_QUALITY, "NOT_TRACKED", 0.0, 0.0
        )
        
        val currentLayer = state.currentLayer
        
        // Update highest mcap
        if (currentMcap > state.highestMcap) {
            state.highestMcap = currentMcap
        }
        
        // Calculate P&L at current price
        val pnlPct = (currentPrice - state.entryPrice) / state.entryPrice * 100
        
        // ─── CHECK FOR UPWARD TRANSITION ───
        
        // Only transition if we're in profit AND mcap is rising
        if (pnlPct <= 0 || currentMcap <= state.entryMcap * 1.1) {
            return TransitionSignal(false, currentLayer, currentLayer, "NOT_PROFITABLE", 0.0, 0.0)
        }
        
        // Check for transition to higher layer
        val newLayer = when (currentLayer) {
            TradingLayer.SHITCOIN, TradingLayer.EXPRESS -> {
                // ShitCoin/Express → V3 Quality when hitting $500K+
                if (currentMcap >= SHITCOIN_MAX_MCAP * (1 + TRANSITION_BUFFER_PCT/100)) {
                    if (currentMcap >= BLUECHIP_MIN_MCAP * (1 + TRANSITION_BUFFER_PCT/100)) {
                        TradingLayer.BLUE_CHIP  // Skip V3 if already at Blue Chip level
                    } else {
                        TradingLayer.V3_QUALITY
                    }
                } else null
            }
            
            TradingLayer.V3_QUALITY -> {
                // V3 Quality → Blue Chip when hitting $1M+
                if (currentMcap >= BLUECHIP_MIN_MCAP * (1 + TRANSITION_BUFFER_PCT/100)) {
                    TradingLayer.BLUE_CHIP
                } else null
            }
            
            TradingLayer.DIP_HUNTER -> {
                // Dip Hunter → Blue Chip when recovering past $1M
                if (currentMcap >= BLUECHIP_MIN_MCAP * (1 + TRANSITION_BUFFER_PCT/100)) {
                    TradingLayer.BLUE_CHIP
                } else null
            }
            
            // Treasury and Blue Chip don't transition up
            else -> null
        }
        
        if (newLayer != null && newLayer != currentLayer) {
            // Record transition
            val record = TransitionRecord(
                fromLayer = currentLayer,
                toLayer = newLayer,
                mcapAtTransition = currentMcap,
                priceAtTransition = currentPrice,
                timestamp = System.currentTimeMillis(),
                pnlAtTransition = pnlPct,
            )
            state.transitionHistory.add(record)
            state.transitionCount++
            state.currentLayer = newLayer
            
            ErrorLogger.info(TAG, "🚀 TRANSITION: ${state.symbol} | " +
                "${currentLayer.emoji} → ${newLayer.emoji} | " +
                "mcap=\$${(currentMcap/1000).fmt(1)}K | " +
                "P&L=+${pnlPct.fmt(1)}% | " +
                "Transition #${state.transitionCount}")
            
            return TransitionSignal(
                shouldTransition = true,
                fromLayer = currentLayer,
                toLayer = newLayer,
                reason = "MCAP_BREAKOUT: \$${(currentMcap/1000).toInt()}K",
                newTakeProfit = newLayer.defaultTP,
                newStopLoss = newLayer.defaultSL,
            )
        }
        
        return TransitionSignal(false, currentLayer, currentLayer, "NO_TRANSITION", 0.0, 0.0)
    }
    
    /**
     * Get adjusted exit targets based on layer transition
     * As positions transition up, targets get more generous
     */
    fun getAdjustedTargets(
        mint: String,
        baseTakeProfit: Double,
        baseStopLoss: Double,
    ): Pair<Double, Double> {
        val state = positionLayers[mint] ?: return Pair(baseTakeProfit, baseStopLoss)
        
        // Each transition adds to targets
        val transitionBonus = state.transitionCount * 10.0  // +10% per transition
        
        val adjustedTP = baseTakeProfit + transitionBonus
        // Stop loss gets TIGHTER as we transition up (protect gains)
        val adjustedSL = baseStopLoss * (1.0 - state.transitionCount * 0.1).coerceAtLeast(0.5)
        
        return Pair(adjustedTP, adjustedSL)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATS
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class TransitionStats(
        val totalPositions: Int,
        val positionsByLayer: Map<TradingLayer, Int>,
        val totalTransitions: Int,
        val multiTransitionPositions: Int,  // Positions that went through 2+ layers
    )
    
    fun getStats(): TransitionStats {
        val positions = positionLayers.values.toList()
        
        return TransitionStats(
            totalPositions = positions.size,
            positionsByLayer = positions.groupBy { it.currentLayer }.mapValues { it.value.size },
            totalTransitions = positions.sumOf { it.transitionCount },
            multiTransitionPositions = positions.count { it.transitionCount >= 2 },
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun Double.fmt(decimals: Int): String = String.format("%.${decimals}f", this)
}
