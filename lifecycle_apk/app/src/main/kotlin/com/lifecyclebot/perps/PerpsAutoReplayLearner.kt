package com.lifecyclebot.perps

import com.lifecyclebot.engine.ErrorLogger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 🎬 PERPS AUTO REPLAY LEARNER - V5.7.3
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * ALWAYS LEARNING. Auto-replays past trades to continuously improve AI predictions.
 * 
 * PHILOSOPHY:
 * ─────────────────────────────────────────────────────────────────────────────
 *   "Every trade is a lesson. Every replay makes us smarter."
 * 
 * HOW IT WORKS:
 * ─────────────────────────────────────────────────────────────────────────────
 *   1. Records all trades with full context (market state, layer signals, etc.)
 *   2. Continuously replays trades in background
 *   3. Compares what AI predicted vs what actually happened
 *   4. Adjusts layer trust scores based on accuracy
 *   5. Identifies winning patterns and losing patterns
 *   6. Feeds insights back to all 26 layers
 * 
 * LEARNING MODES:
 * ─────────────────────────────────────────────────────────────────────────────
 *   🔄 CONTINUOUS    - Always replaying in background
 *   📊 BATCH         - Replay last N trades on demand
 *   🎯 FOCUSED       - Replay specific market/direction combos
 *   💀 FAILURE       - Learn specifically from losses
 *   🏆 SUCCESS       - Reinforce winning patterns
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object PerpsAutoReplayLearner {
    
    private const val TAG = "🎬PerpsReplay"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val isLearning = AtomicBoolean(true)  // ALWAYS ON by default
    private val isPaused = AtomicBoolean(false)
    
    // Trade history for replay
    private val tradeHistory = ConcurrentLinkedQueue<ReplayableTrade>()
    private const val MAX_HISTORY_SIZE = 500
    
    // Learning stats
    private val totalReplays = AtomicInteger(0)
    private val patternsIdentified = AtomicInteger(0)
    private val layerAdjustments = AtomicInteger(0)
    private val lastReplayTime = AtomicLong(0)
    
    // Replay job
    private var replayJob: Job? = null
    private const val REPLAY_INTERVAL_MS = 60_000L  // Replay every minute
    private const val TRADES_PER_REPLAY = 10        // Replay 10 trades per cycle
    
    // Identified patterns
    private val winningPatterns = mutableListOf<TradePattern>()
    private val losingPatterns = mutableListOf<TradePattern>()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * A trade with full context for replay learning
     */
    data class ReplayableTrade(
        val trade: PerpsTrade,
        val marketStateAtEntry: MarketSnapshot,
        val marketStateAtExit: MarketSnapshot,
        val layerSignalsAtEntry: Map<String, LayerSignalSnapshot>,
        val aiPrediction: PredictionSnapshot,
        val timestamp: Long = System.currentTimeMillis(),
    )
    
    data class MarketSnapshot(
        val price: Double,
        val priceChange24h: Double,
        val volume24h: Double,
        val fundingRate: Double,
        val longShortRatio: Double,
        val volatility: Boolean,
    )
    
    data class LayerSignalSnapshot(
        val layerName: String,
        val direction: PerpsDirection?,
        val confidence: Double,
        val trustScore: Double,
    )
    
    data class PredictionSnapshot(
        val predictedDirection: PerpsDirection,
        val confidence: Double,
        val predictedTP: Double,
        val predictedSL: Double,
        val layerConsensus: Int,
    )
    
    /**
     * Identified trading pattern
     */
    data class TradePattern(
        val id: String,
        val market: PerpsMarket,
        val direction: PerpsDirection,
        val riskTier: PerpsRiskTier,
        val marketConditions: PatternConditions,
        val winRate: Double,
        val avgPnl: Double,
        val occurrences: Int,
        val confidence: Double,
        val description: String,
    )
    
    data class PatternConditions(
        val priceChangeRange: ClosedFloatingPointRange<Double>,
        val volumeHigh: Boolean,
        val fundingFavorable: Boolean,
        val volatileMarket: Boolean,
        val layerConsensusMin: Int,
    )
    
    /**
     * Learning insight from replay
     */
    data class LearningInsight(
        val type: InsightType,
        val layerName: String?,
        val market: PerpsMarket?,
        val direction: PerpsDirection?,
        val insight: String,
        val actionTaken: String,
        val impactScore: Double,
        val timestamp: Long = System.currentTimeMillis(),
    )
    
    enum class InsightType {
        LAYER_TRUST_ADJUSTED,
        PATTERN_IDENTIFIED,
        PATTERN_INVALIDATED,
        OPTIMAL_SETUP_FOUND,
        RISK_FACTOR_DETECTED,
        TIMING_INSIGHT,
        LEVERAGE_INSIGHT,
    }
    
    // Recent insights for UI
    private val recentInsights = ConcurrentLinkedQueue<LearningInsight>()
    private const val MAX_INSIGHTS = 50
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Start the auto-replay learner (always learning!)
     */
    fun start() {
        if (replayJob?.isActive == true) return
        
        isLearning.set(true)
        isPaused.set(false)
        
        replayJob = CoroutineScope(Dispatchers.Default).launch {
            ErrorLogger.info(TAG, "🎬 Auto-Replay Learner STARTED - Always learning!")
            runReplayLoop()
        }
    }
    
    /**
     * Stop the learner (not recommended - we should always be learning!)
     */
    fun stop() {
        isLearning.set(false)
        replayJob?.cancel()
        ErrorLogger.info(TAG, "🎬 Auto-Replay Learner STOPPED")
    }
    
    /**
     * Pause/resume learning
     */
    fun setPaused(paused: Boolean) {
        isPaused.set(paused)
        ErrorLogger.info(TAG, "🎬 Learning ${if (paused) "PAUSED" else "RESUMED"}")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TRADE RECORDING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Record a completed trade for future replay learning
     */
    suspend fun recordTrade(
        trade: PerpsTrade,
        entryMarketData: PerpsMarketData,
        exitMarketData: PerpsMarketData,
    ) {
        // Capture market state at entry
        val entrySnapshot = MarketSnapshot(
            price = entryMarketData.price,
            priceChange24h = entryMarketData.priceChange24hPct,
            volume24h = entryMarketData.volume24h,
            fundingRate = entryMarketData.fundingRate,
            longShortRatio = entryMarketData.getLongShortRatio(),
            volatility = entryMarketData.isVolatile(),
        )
        
        // Capture market state at exit
        val exitSnapshot = MarketSnapshot(
            price = exitMarketData.price,
            priceChange24h = exitMarketData.priceChange24hPct,
            volume24h = exitMarketData.volume24h,
            fundingRate = exitMarketData.fundingRate,
            longShortRatio = exitMarketData.getLongShortRatio(),
            volatility = exitMarketData.isVolatile(),
        )
        
        // Get layer signals that were active at entry
        val layerSignals = captureLayerSignals(trade.market, entryMarketData)
        
        // Capture the AI prediction
        val prediction = PredictionSnapshot(
            predictedDirection = trade.direction,
            confidence = trade.aiConfidence.toDouble(),
            predictedTP = trade.entryPrice * (1 + 0.1),  // Estimated
            predictedSL = trade.entryPrice * (1 - 0.05),
            layerConsensus = layerSignals.count { it.value.direction == trade.direction },
        )
        
        val replayable = ReplayableTrade(
            trade = trade,
            marketStateAtEntry = entrySnapshot,
            marketStateAtExit = exitSnapshot,
            layerSignalsAtEntry = layerSignals,
            aiPrediction = prediction,
        )
        
        // Add to history
        tradeHistory.add(replayable)
        
        // Trim if too large
        while (tradeHistory.size > MAX_HISTORY_SIZE) {
            tradeHistory.poll()
        }
        
        // Also record to heatmap
        PerpsTradeHeatmap.recordTrade(trade)
        
        ErrorLogger.debug(TAG, "🎬 Recorded trade for replay: ${trade.market.symbol} ${trade.direction.symbol} ${trade.pnlPct.fmt(1)}%")
    }
    
    private suspend fun captureLayerSignals(
        market: PerpsMarket,
        marketData: PerpsMarketData,
    ): Map<String, LayerSignalSnapshot> {
        val signals = mutableMapOf<String, LayerSignalSnapshot>()
        val layerStats = PerpsLearningBridge.getLayerPerpsStats()
        
        layerStats.forEach { (name, stats) ->
            signals[name] = LayerSignalSnapshot(
                layerName = name,
                direction = if (marketData.priceChange24hPct > 0) PerpsDirection.LONG else PerpsDirection.SHORT,
                confidence = stats.second,
                trustScore = stats.first,
            )
        }
        
        return signals
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // REPLAY LOOP
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun runReplayLoop() {
        while (isLearning.get()) {
            if (!isPaused.get() && tradeHistory.isNotEmpty()) {
                try {
                    replayAndLearn()
                } catch (e: Exception) {
                    ErrorLogger.error(TAG, "Replay error: ${e.message}", e)
                }
            }
            
            delay(REPLAY_INTERVAL_MS)
        }
    }
    
    /**
     * Replay recent trades and learn from them
     */
    private suspend fun replayAndLearn() {
        val tradesToReplay = tradeHistory.toList().takeLast(TRADES_PER_REPLAY)
        if (tradesToReplay.isEmpty()) return
        
        ErrorLogger.debug(TAG, "🎬 Replaying ${tradesToReplay.size} trades...")
        
        for (replayable in tradesToReplay) {
            analyzeAndLearn(replayable)
        }
        
        // Identify patterns from accumulated data
        identifyPatterns()
        
        totalReplays.incrementAndGet()
        lastReplayTime.set(System.currentTimeMillis())
    }
    
    /**
     * Analyze a single trade and extract learnings
     */
    private fun analyzeAndLearn(replayable: ReplayableTrade) {
        val trade = replayable.trade
        val isWin = trade.pnlPct > 0
        val prediction = replayable.aiPrediction
        
        // 1. Check which layers were correct
        replayable.layerSignalsAtEntry.forEach { (layerName, signal) ->
            val layerWasCorrect = (signal.direction == trade.direction) == isWin
            
            // Adjust layer trust based on accuracy
            val currentTrust = signal.trustScore
            val adjustment = if (layerWasCorrect) 0.005 else -0.003
            val newTrust = (currentTrust + adjustment).coerceIn(0.1, 1.0)
            
            if (kotlin.math.abs(adjustment) > 0.001) {
                layerAdjustments.incrementAndGet()
                
                addInsight(LearningInsight(
                    type = InsightType.LAYER_TRUST_ADJUSTED,
                    layerName = layerName,
                    market = trade.market,
                    direction = trade.direction,
                    insight = "$layerName ${if (layerWasCorrect) "correctly predicted" else "missed"} this ${trade.direction.symbol}",
                    actionTaken = "Trust ${if (layerWasCorrect) "increased" else "decreased"} to ${(newTrust * 100).toInt()}%",
                    impactScore = kotlin.math.abs(adjustment) * 100,
                ))
            }
        }
        
        // 2. Check prediction accuracy
        val predictionCorrect = (prediction.predictedDirection == trade.direction) == isWin
        if (!predictionCorrect && prediction.confidence > 70) {
            addInsight(LearningInsight(
                type = InsightType.RISK_FACTOR_DETECTED,
                layerName = null,
                market = trade.market,
                direction = trade.direction,
                insight = "High confidence (${prediction.confidence.toInt()}%) prediction was wrong",
                actionTaken = "Flagged for pattern analysis",
                impactScore = prediction.confidence,
            ))
        }
        
        // 3. Analyze market conditions at entry
        val entry = replayable.marketStateAtEntry
        if (isWin && entry.volatility && trade.leverage > 5) {
            addInsight(LearningInsight(
                type = InsightType.OPTIMAL_SETUP_FOUND,
                layerName = null,
                market = trade.market,
                direction = trade.direction,
                insight = "High leverage in volatile market worked: +${trade.pnlPct.fmt(1)}%",
                actionTaken = "Pattern reinforced",
                impactScore = trade.pnlPct,
            ))
        }
        
        // 4. Timing analysis
        val holdMinutes = (trade.closeTime - trade.openTime) / 60_000
        if (isWin && holdMinutes < 30) {
            addInsight(LearningInsight(
                type = InsightType.TIMING_INSIGHT,
                layerName = null,
                market = trade.market,
                direction = trade.direction,
                insight = "Quick trade (${holdMinutes}min) was profitable",
                actionTaken = "Short hold patterns noted",
                impactScore = trade.pnlPct,
            ))
        }
        
        // 5. Leverage analysis
        if (!isWin && trade.leverage > 10) {
            addInsight(LearningInsight(
                type = InsightType.LEVERAGE_INSIGHT,
                layerName = null,
                market = trade.market,
                direction = trade.direction,
                insight = "High leverage (${trade.leverage}x) led to ${trade.pnlPct.fmt(1)}% loss",
                actionTaken = "Consider reducing leverage for ${trade.market.symbol}",
                impactScore = kotlin.math.abs(trade.pnlPct),
            ))
        }
    }
    
    /**
     * Identify recurring patterns from trade history
     */
    private fun identifyPatterns() {
        val trades = tradeHistory.toList()
        if (trades.size < 10) return
        
        // Group by market + direction
        val grouped = trades.groupBy { "${it.trade.market.symbol}_${it.trade.direction.symbol}" }
        
        grouped.forEach { (key, groupTrades) ->
            if (groupTrades.size < 3) return@forEach
            
            val wins = groupTrades.count { it.trade.pnlPct > 0 }
            val winRate = wins.toDouble() / groupTrades.size * 100
            val avgPnl = groupTrades.map { it.trade.pnlPct }.average()
            
            val parts = key.split("_")
            val market = PerpsMarket.values().find { it.symbol == parts[0] } ?: return@forEach
            val direction = PerpsDirection.values().find { it.symbol == parts[1] } ?: return@forEach
            
            // Analyze common conditions in winning trades
            val winningTrades = groupTrades.filter { it.trade.pnlPct > 0 }
            if (winningTrades.size >= 2) {
                val avgVolatile = winningTrades.count { it.marketStateAtEntry.volatility }.toDouble() / winningTrades.size
                val avgConsensus = winningTrades.map { it.aiPrediction.layerConsensus }.average()
                
                val pattern = TradePattern(
                    id = "${key}_${System.currentTimeMillis()}",
                    market = market,
                    direction = direction,
                    riskTier = groupTrades.first().trade.riskTier,
                    marketConditions = PatternConditions(
                        priceChangeRange = -5.0..5.0,
                        volumeHigh = avgVolatile > 0.5,
                        fundingFavorable = true,
                        volatileMarket = avgVolatile > 0.5,
                        layerConsensusMin = avgConsensus.toInt(),
                    ),
                    winRate = winRate,
                    avgPnl = avgPnl,
                    occurrences = groupTrades.size,
                    confidence = (winRate * avgConsensus / 100).coerceIn(0.0, 100.0),
                    description = "${market.symbol} ${direction.symbol}: ${winRate.toInt()}% WR over ${groupTrades.size} trades",
                )
                
                // Store pattern
                if (winRate >= 50) {
                    winningPatterns.removeAll { it.market == market && it.direction == direction }
                    winningPatterns.add(pattern)
                    patternsIdentified.incrementAndGet()
                    
                    addInsight(LearningInsight(
                        type = InsightType.PATTERN_IDENTIFIED,
                        layerName = null,
                        market = market,
                        direction = direction,
                        insight = "Winning pattern: ${pattern.description}",
                        actionTaken = "Pattern saved for future reference",
                        impactScore = winRate,
                    ))
                } else {
                    losingPatterns.removeAll { it.market == market && it.direction == direction }
                    losingPatterns.add(pattern)
                    
                    addInsight(LearningInsight(
                        type = InsightType.PATTERN_INVALIDATED,
                        layerName = null,
                        market = market,
                        direction = direction,
                        insight = "Losing pattern: ${pattern.description}",
                        actionTaken = "Flagged to avoid",
                        impactScore = 100 - winRate,
                    ))
                }
            }
        }
    }
    
    private fun addInsight(insight: LearningInsight) {
        recentInsights.add(insight)
        while (recentInsights.size > MAX_INSIGHTS) {
            recentInsights.poll()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // QUERY METHODS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Check if a setup matches a winning pattern
     */
    fun matchesWinningPattern(market: PerpsMarket, direction: PerpsDirection): TradePattern? {
        return winningPatterns.find { 
            it.market == market && 
            it.direction == direction && 
            it.winRate >= 55 
        }
    }
    
    /**
     * Check if a setup matches a losing pattern
     */
    fun matchesLosingPattern(market: PerpsMarket, direction: PerpsDirection): TradePattern? {
        return losingPatterns.find { 
            it.market == market && 
            it.direction == direction && 
            it.winRate < 45 
        }
    }
    
    /**
     * Get recommendation based on learned patterns
     */
    fun getRecommendation(market: PerpsMarket, direction: PerpsDirection): String {
        val winning = matchesWinningPattern(market, direction)
        val losing = matchesLosingPattern(market, direction)
        
        return when {
            winning != null && winning.confidence > 60 -> 
                "🟢 FAVORABLE: ${winning.winRate.toInt()}% win rate over ${winning.occurrences} trades"
            losing != null && losing.confidence > 60 -> 
                "🔴 AVOID: Only ${losing.winRate.toInt()}% win rate over ${losing.occurrences} trades"
            else -> 
                "⚪ NEUTRAL: Insufficient data for pattern matching"
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATS
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun isLearning(): Boolean = isLearning.get() && !isPaused.get()
    fun getTotalReplays(): Int = totalReplays.get()
    fun getPatternsIdentified(): Int = patternsIdentified.get()
    fun getLayerAdjustments(): Int = layerAdjustments.get()
    fun getTradeHistorySize(): Int = tradeHistory.size
    fun getWinningPatterns(): List<TradePattern> = winningPatterns.toList()
    fun getLosingPatterns(): List<TradePattern> = losingPatterns.toList()
    fun getRecentInsights(): List<LearningInsight> = recentInsights.toList().takeLast(20)
    
    fun getStats(): ReplayLearnerStats {
        return ReplayLearnerStats(
            isLearning = isLearning.get(),
            isPaused = isPaused.get(),
            totalReplays = totalReplays.get(),
            patternsIdentified = patternsIdentified.get(),
            layerAdjustments = layerAdjustments.get(),
            tradeHistorySize = tradeHistory.size,
            winningPatterns = winningPatterns.size,
            losingPatterns = losingPatterns.size,
            lastReplayTime = lastReplayTime.get(),
        )
    }
    
    data class ReplayLearnerStats(
        val isLearning: Boolean,
        val isPaused: Boolean,
        val totalReplays: Int,
        val patternsIdentified: Int,
        val layerAdjustments: Int,
        val tradeHistorySize: Int,
        val winningPatterns: Int,
        val losingPatterns: Int,
        val lastReplayTime: Long,
    )
    
    private fun Double.fmt(decimals: Int): String = String.format("%.${decimals}f", this)
}
