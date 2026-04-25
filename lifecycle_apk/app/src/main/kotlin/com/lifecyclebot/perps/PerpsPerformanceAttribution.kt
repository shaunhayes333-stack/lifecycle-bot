package com.lifecyclebot.perps

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 📊 PERFORMANCE ATTRIBUTION - V5.7.5
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Tracks which AI layers contribute most to trading P&L.
 * 
 * PURPOSE:
 * ─────────────────────────────────────────────────────────────────────────────
 *   • Identify best-performing layers → Increase their weight
 *   • Identify worst-performing layers → Decrease weight or disable
 *   • Understand what's working and what's not
 * 
 * TRACKING:
 * ─────────────────────────────────────────────────────────────────────────────
 *   For each trade, we record:
 *     • Which layers were BULLISH vs BEARISH
 *     • The final outcome (WIN/LOSS/PNL%)
 *     • Layer confidence at entry time
 *   
 *   Attribution: If a layer was BULLISH on a winning LONG, it gets credit
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object PerpsPerformanceAttribution {
    
    private const val TAG = "📊Attribution"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA STRUCTURES
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class LayerVote(
        val layerName: String,
        val direction: PerpsDirection,  // LONG or SHORT
        val confidence: Int,            // 0-100
        val score: Int,                 // Raw score from layer
    )
    
    data class TradeAttribution(
        val tradeId: String,
        val market: PerpsMarket,
        val direction: PerpsDirection,
        val layerVotes: List<LayerVote>,
        val entryPrice: Double,
        val exitPrice: Double?,
        val pnlPct: Double?,
        val isWin: Boolean?,
        val entryTime: Long,
        val exitTime: Long?,
    )
    
    // Per-layer statistics
    data class LayerStats(
        val layerName: String,
        var totalTrades: Int = 0,
        var correctPredictions: Int = 0,
        var wrongPredictions: Int = 0,
        var totalPnlContribution: Double = 0.0,  // Sum of PnL% when layer was correct
        var avgConfidenceWhenCorrect: Double = 0.0,
        var avgConfidenceWhenWrong: Double = 0.0,
    ) {
        val accuracy: Double get() = if (totalTrades > 0) correctPredictions.toDouble() / totalTrades * 100 else 0.0
        val avgPnlContribution: Double get() = if (correctPredictions > 0) totalPnlContribution / correctPredictions else 0.0
        val valueScore: Double get() = accuracy * avgPnlContribution / 100  // Combines accuracy and magnitude
    }
    
    // Storage
    private val tradeAttributions = ConcurrentHashMap<String, TradeAttribution>()
    private val layerStats = ConcurrentHashMap<String, LayerStats>()
    private val totalTradesAttributed = AtomicInteger(0)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // RECORDING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Record entry with layer votes
     */
    fun recordEntry(
        tradeId: String,
        market: PerpsMarket,
        direction: PerpsDirection,
        entryPrice: Double,
        layerVotes: List<LayerVote>,
    ) {
        val attribution = TradeAttribution(
            tradeId = tradeId,
            market = market,
            direction = direction,
            layerVotes = layerVotes,
            entryPrice = entryPrice,
            exitPrice = null,
            pnlPct = null,
            isWin = null,
            entryTime = System.currentTimeMillis(),
            exitTime = null,
        )
        
        tradeAttributions[tradeId] = attribution
        
        ErrorLogger.debug(TAG, "📊 Entry recorded: $tradeId | ${market.symbol} ${direction.symbol} | ${layerVotes.size} layer votes")
    }
    
    /**
     * Record exit and calculate attribution
     */
    fun recordExit(
        tradeId: String,
        exitPrice: Double,
        pnlPct: Double,
    ) {
        val attribution = tradeAttributions[tradeId] ?: return
        
        val isWin = pnlPct >= 1.0  // V5.9.225: unified 1% threshold
        
        // Update attribution record
        tradeAttributions[tradeId] = attribution.copy(
            exitPrice = exitPrice,
            pnlPct = pnlPct,
            isWin = isWin,
            exitTime = System.currentTimeMillis(),
        )
        
        // Calculate layer contributions
        calculateLayerContributions(attribution.copy(pnlPct = pnlPct, isWin = isWin))
        
        totalTradesAttributed.incrementAndGet()
        
        ErrorLogger.info(TAG, "📊 Exit recorded: $tradeId | PnL=${pnlPct.fmt(1)}% | ${if (isWin) "WIN" else "LOSS"}")
    }
    
    /**
     * Calculate which layers contributed to win/loss
     */
    private fun calculateLayerContributions(attribution: TradeAttribution) {
        val pnl = attribution.pnlPct ?: return
        val isWin = attribution.isWin ?: return
        val tradeDirection = attribution.direction
        
        for (vote in attribution.layerVotes) {
            val stats = layerStats.getOrPut(vote.layerName) { LayerStats(vote.layerName) }
            
            // Did this layer vote in the same direction as the trade?
            val agreedWithTrade = vote.direction == tradeDirection
            
            // Was the layer's prediction correct?
            // If layer agreed with trade and trade won → correct
            // If layer disagreed with trade and trade lost → also correct (layer was right to be cautious)
            val wasCorrect = (agreedWithTrade && isWin) || (!agreedWithTrade && !isWin)
            
            synchronized(stats) {
                stats.totalTrades++
                
                if (wasCorrect) {
                    stats.correctPredictions++
                    stats.totalPnlContribution += kotlin.math.abs(pnl)
                    stats.avgConfidenceWhenCorrect = 
                        (stats.avgConfidenceWhenCorrect * (stats.correctPredictions - 1) + vote.confidence) / stats.correctPredictions
                } else {
                    stats.wrongPredictions++
                    stats.avgConfidenceWhenWrong = 
                        (stats.avgConfidenceWhenWrong * (stats.wrongPredictions - 1) + vote.confidence) / stats.wrongPredictions.coerceAtLeast(1)
                }
            }
            
            layerStats[vote.layerName] = stats
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // QUERIES
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get all layer statistics sorted by performance
     */
    fun getLayerRankings(sortBy: SortCriteria = SortCriteria.VALUE_SCORE): List<LayerStats> {
        return layerStats.values.sortedByDescending { 
            when (sortBy) {
                SortCriteria.ACCURACY -> it.accuracy
                SortCriteria.PNL_CONTRIBUTION -> it.avgPnlContribution
                SortCriteria.VALUE_SCORE -> it.valueScore
                SortCriteria.TOTAL_TRADES -> it.totalTrades.toDouble()
            }
        }
    }
    
    enum class SortCriteria {
        ACCURACY,
        PNL_CONTRIBUTION,
        VALUE_SCORE,
        TOTAL_TRADES,
    }
    
    /**
     * Get stats for a specific layer
     */
    fun getLayerStats(layerName: String): LayerStats? = layerStats[layerName]
    
    /**
     * Get top N performing layers
     */
    fun getTopLayers(n: Int = 5): List<LayerStats> {
        return getLayerRankings().take(n)
    }
    
    /**
     * Get bottom N performing layers
     */
    fun getBottomLayers(n: Int = 5): List<LayerStats> {
        return getLayerRankings().reversed().take(n)
    }
    
    /**
     * Get layers that need attention (low accuracy)
     */
    fun getLayersNeedingAttention(accuracyThreshold: Double = 45.0): List<LayerStats> {
        return layerStats.values
            .filter { it.totalTrades >= 10 && it.accuracy < accuracyThreshold }
            .sortedBy { it.accuracy }
    }
    
    /**
     * Get suggested weight adjustments
     */
    data class WeightAdjustment(
        val layerName: String,
        val currentAccuracy: Double,
        val suggestedMultiplier: Double,  // 0.5 = reduce weight, 1.5 = increase weight
        val reason: String,
    )
    
    fun getSuggestedWeightAdjustments(): List<WeightAdjustment> {
        val adjustments = mutableListOf<WeightAdjustment>()
        
        for (stats in layerStats.values) {
            if (stats.totalTrades < 20) continue  // Need enough data
            
            val multiplier = when {
                stats.accuracy >= 65 -> 1.3   // High performer
                stats.accuracy >= 55 -> 1.1   // Good performer
                stats.accuracy >= 45 -> 1.0   // Average
                stats.accuracy >= 35 -> 0.8   // Below average
                else -> 0.5                    // Poor performer
            }
            
            if (multiplier != 1.0) {
                val reason = when {
                    multiplier > 1 -> "🟢 Strong accuracy: ${stats.accuracy.fmt(1)}%"
                    multiplier < 1 -> "🔴 Weak accuracy: ${stats.accuracy.fmt(1)}%"
                    else -> "Average"
                }
                
                adjustments.add(WeightAdjustment(
                    layerName = stats.layerName,
                    currentAccuracy = stats.accuracy,
                    suggestedMultiplier = multiplier,
                    reason = reason,
                ))
            }
        }
        
        return adjustments.sortedByDescending { kotlin.math.abs(it.suggestedMultiplier - 1.0) }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SUMMARY
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class AttributionSummary(
        val totalTrades: Int,
        val topLayer: LayerStats?,
        val bottomLayer: LayerStats?,
        val avgLayerAccuracy: Double,
        val layersNeedingAttention: Int,
    )
    
    fun getSummary(): AttributionSummary {
        val rankings = getLayerRankings()
        val needAttention = getLayersNeedingAttention()
        
        return AttributionSummary(
            totalTrades = totalTradesAttributed.get(),
            topLayer = rankings.firstOrNull(),
            bottomLayer = rankings.lastOrNull(),
            avgLayerAccuracy = rankings.map { it.accuracy }.average().takeIf { !it.isNaN() } ?: 50.0,
            layersNeedingAttention = needAttention.size,
        )
    }
    
    /**
     * Get formatted summary for UI
     */
    fun getFormattedSummary(): String {
        val summary = getSummary()
        val top3 = getTopLayers(3)
        val bottom3 = getBottomLayers(3)
        
        return buildString {
            appendLine("📊 PERFORMANCE ATTRIBUTION")
            appendLine("═".repeat(40))
            appendLine("Trades Analyzed: ${summary.totalTrades}")
            appendLine("Avg Layer Accuracy: ${summary.avgLayerAccuracy.fmt(1)}%")
            appendLine()
            appendLine("🏆 TOP PERFORMERS:")
            for (layer in top3) {
                appendLine("  ${layer.layerName}: ${layer.accuracy.fmt(1)}% | +${layer.avgPnlContribution.fmt(1)}%")
            }
            appendLine()
            appendLine("⚠️ NEEDS IMPROVEMENT:")
            for (layer in bottom3) {
                appendLine("  ${layer.layerName}: ${layer.accuracy.fmt(1)}%")
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITIES
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun reset() {
        tradeAttributions.clear()
        layerStats.clear()
        totalTradesAttributed.set(0)
        ErrorLogger.info(TAG, "📊 Attribution data reset")
    }
    
    private fun Double.fmt(decimals: Int): String = "%.${decimals}f".format(this)
}
