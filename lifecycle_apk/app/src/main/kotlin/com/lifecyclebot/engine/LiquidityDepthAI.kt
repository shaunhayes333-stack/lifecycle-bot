package com.lifecyclebot.engine

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * LiquidityDepthAI - Monitor LP Changes in Real-Time
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Tracks liquidity pool depth and changes to detect:
 *   1. LIQUIDITY GROWTH - LP increasing = bullish (project adding liquidity)
 *   2. LIQUIDITY DRAIN - LP decreasing = bearish (rug preparation)
 *   3. LIQUIDITY SPIKE - Sudden large add = potential pump incoming
 *   4. LIQUIDITY COLLAPSE - Rapid removal = RUG IN PROGRESS
 *   5. DEPTH QUALITY - Healthy depth ratio for position size
 * 
 * Key Signals:
 *   - Entry boost when liquidity is growing steadily
 *   - Exit urgency when liquidity is draining
 *   - Hard block when liquidity collapses (>30% drop in short time)
 *   - Position size adjustment based on depth quality
 * 
 * Learning:
 *   - Tracks which liquidity patterns lead to profitable trades
 *   - Adjusts signal weights based on outcomes
 */
object LiquidityDepthAI {
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA STRUCTURES
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class LiquiditySnapshot(
        val timestamp: Long,
        val liquidityUsd: Double,
        val mcapUsd: Double,
        val holderCount: Int,
    )
    
    data class LiquidityTrend(
        val trend: Trend,
        val changePercent: Double,      // % change over window
        val velocity: Double,           // Rate of change per minute
        val confidence: Double,         // 0-100%
        val depthQuality: DepthQuality,
        val reason: String,
    )
    
    enum class Trend {
        STRONG_GROWTH,      // Liquidity increasing rapidly (>10%/hr)
        GROWTH,             // Liquidity increasing steadily (2-10%/hr)
        STABLE,             // Liquidity relatively flat (+/- 2%/hr)
        DRAIN,              // Liquidity decreasing (-2% to -10%/hr)
        COLLAPSE,           // Liquidity collapsing rapidly (>-10%/hr)
        SPIKE,              // Sudden large add (>20% in 5 min)
        UNKNOWN,            // Insufficient data
    }
    
    enum class DepthQuality {
        EXCELLENT,          // Deep liquidity, can trade large sizes
        GOOD,               // Adequate liquidity for normal trades
        FAIR,               // Thin liquidity, reduce position size
        POOR,               // Very thin, micro positions only
        DANGEROUS,          // Extremely thin, high slippage risk
    }
    
    data class LiquiditySignal(
        val signal: SignalType,
        val trend: Trend,
        val depthQuality: DepthQuality,
        val entryAdjustment: Double,    // Entry score adjustment
        val exitUrgency: Double,        // Exit score addition
        val sizeMultiplier: Double,     // Position size multiplier (0.0-1.5)
        val confidence: Double,
        val reason: String,
        val shouldBlock: Boolean,       // Hard block on collapse
        val blockReason: String?,
    )
    
    enum class SignalType {
        LIQUIDITY_GROWING,      // Bullish - LP increasing
        LIQUIDITY_SPIKE,        // Very bullish - Large sudden add
        LIQUIDITY_STABLE,       // Neutral
        LIQUIDITY_DRAINING,     // Bearish - LP decreasing
        LIQUIDITY_COLLAPSE,     // DANGER - Rug likely
        DEPTH_WARNING,          // Position size warning
        NO_DATA,                // Insufficient data
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE TRACKING
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Historical liquidity per token (max 60 snapshots = ~1 hour at 1 min intervals)
    private val liquidityHistory = ConcurrentHashMap<String, MutableList<LiquiditySnapshot>>()
    private const val MAX_HISTORY_SIZE = 60
    
    // Entry liquidity at time of trade (for exit comparison)
    private val entryLiquidity = ConcurrentHashMap<String, Double>()
    
    // Learning: Track signal outcomes
    private data class SignalOutcome(
        var totalTrades: Int = 0,
        var profitableTrades: Int = 0,
        var totalPnlPct: Double = 0.0,
    )
    private val signalOutcomes = ConcurrentHashMap<SignalType, SignalOutcome>()
    
    // Learned weights (adjusted based on outcomes)
    private var growthEntryBoost = 8.0
    private var spikeEntryBoost = 15.0
    private var drainExitUrgency = 12.0
    private var collapseExitUrgency = 30.0
    
    // Stats
    private var totalAnalyses = 0
    private var collapseDetections = 0
    private var spikeDetections = 0
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN API
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Record a liquidity snapshot for a token.
     * Call this every time you get fresh liquidity data (typically every loop cycle).
     */
    fun recordSnapshot(mint: String, liquidityUsd: Double, mcapUsd: Double = 0.0, holderCount: Int = 0) {
        val history = liquidityHistory.getOrPut(mint) { mutableListOf() }
        
        val snapshot = LiquiditySnapshot(
            timestamp = System.currentTimeMillis(),
            liquidityUsd = liquidityUsd,
            mcapUsd = mcapUsd,
            holderCount = holderCount,
        )
        
        synchronized(history) {
            history.add(snapshot)
            // Keep only recent history
            while (history.size > MAX_HISTORY_SIZE) {
                history.removeAt(0)
            }
        }
    }
    
    /**
     * Record entry liquidity when opening a position.
     * Used to calculate liquidity change during hold.
     */
    fun recordEntryLiquidity(mint: String, liquidityUsd: Double) {
        entryLiquidity[mint] = liquidityUsd
    }
    
    /**
     * Get entry liquidity for a token (for exit comparison).
     */
    fun getEntryLiquidity(mint: String): Double? = entryLiquidity[mint]
    
    /**
     * Clear entry liquidity when position is closed.
     */
    fun clearEntryLiquidity(mint: String) {
        entryLiquidity.remove(mint)
    }
    
    /**
     * Analyze liquidity trend for a token.
     * Returns detailed trend information.
     */
    fun analyzeTrend(mint: String): LiquidityTrend {
        val history = liquidityHistory[mint] ?: return LiquidityTrend(
            trend = Trend.UNKNOWN,
            changePercent = 0.0,
            velocity = 0.0,
            confidence = 0.0,
            depthQuality = DepthQuality.FAIR,
            reason = "No liquidity data"
        )
        
        synchronized(history) {
            if (history.size < 3) {
                return LiquidityTrend(
                    trend = Trend.UNKNOWN,
                    changePercent = 0.0,
                    velocity = 0.0,
                    confidence = 0.0,
                    depthQuality = assessDepthQuality(history.lastOrNull()?.liquidityUsd ?: 0.0),
                    reason = "Insufficient data (${history.size} snapshots)"
                )
            }
            
            val current = history.last()
            val oldest = history.first()
            val timeDeltaMs = current.timestamp - oldest.timestamp
            val timeDeltaMin = timeDeltaMs / 60_000.0
            
            if (timeDeltaMin < 1.0) {
                return LiquidityTrend(
                    trend = Trend.UNKNOWN,
                    changePercent = 0.0,
                    velocity = 0.0,
                    confidence = 0.0,
                    depthQuality = assessDepthQuality(current.liquidityUsd),
                    reason = "Insufficient time window (${timeDeltaMin.toInt()}s)"
                )
            }
            
            // Calculate change
            val changeUsd = current.liquidityUsd - oldest.liquidityUsd
            val changePct = if (oldest.liquidityUsd > 0) {
                (changeUsd / oldest.liquidityUsd) * 100
            } else 0.0
            
            // Velocity = change per minute
            val velocity = changePct / timeDeltaMin
            
            // Check for recent spike (last 5 minutes)
            val fiveMinAgo = System.currentTimeMillis() - (5 * 60 * 1000)
            val recentSnapshots = history.filter { it.timestamp >= fiveMinAgo }
            val recentSpike = if (recentSnapshots.size >= 2) {
                val recentOldest = recentSnapshots.first()
                val recentChange = if (recentOldest.liquidityUsd > 0) {
                    ((current.liquidityUsd - recentOldest.liquidityUsd) / recentOldest.liquidityUsd) * 100
                } else 0.0
                recentChange > 20.0  // >20% in 5 min = spike
            } else false
            
            // Determine trend
            val trend = when {
                recentSpike -> Trend.SPIKE
                velocity >= 0.15 -> Trend.STRONG_GROWTH     // >10%/hr
                velocity >= 0.03 -> Trend.GROWTH            // 2-10%/hr
                velocity >= -0.03 -> Trend.STABLE           // +/- 2%/hr
                velocity >= -0.15 -> Trend.DRAIN            // -2 to -10%/hr
                else -> Trend.COLLAPSE                       // <-10%/hr
            }
            
            // Confidence based on data quality
            val confidence = when {
                history.size >= 30 && timeDeltaMin >= 20 -> 90.0
                history.size >= 15 && timeDeltaMin >= 10 -> 75.0
                history.size >= 8 && timeDeltaMin >= 5 -> 60.0
                else -> 40.0
            }
            
            val depthQuality = assessDepthQuality(current.liquidityUsd)
            
            return LiquidityTrend(
                trend = trend,
                changePercent = changePct,
                velocity = velocity,
                confidence = confidence,
                depthQuality = depthQuality,
                reason = "${trend.name}: ${changePct.toInt()}% over ${timeDeltaMin.toInt()}min (${velocity.format(2)}%/min)"
            )
        }
    }
    
    /**
     * Get full liquidity signal for trading decisions.
     * This is the main method called by FinalDecisionGate and LifecycleStrategy.
     */
    fun getSignal(mint: String, symbol: String, isOpenPosition: Boolean = false): LiquiditySignal {
        totalAnalyses++
        
        val trend = analyzeTrend(mint)
        
        // Check for collapse on open position
        var shouldBlock = false
        var blockReason: String? = null
        
        if (isOpenPosition) {
            val entryLiq = entryLiquidity[mint]
            if (entryLiq != null && entryLiq > 0) {
                val history = liquidityHistory[mint]
                val currentLiq = history?.lastOrNull()?.liquidityUsd ?: 0.0
                val dropPct = ((entryLiq - currentLiq) / entryLiq) * 100
                
                // Collapse detection: >30% drop from entry
                if (dropPct > 30) {
                    shouldBlock = true
                    blockReason = "LIQUIDITY_COLLAPSE: ${dropPct.toInt()}% drop since entry"
                    collapseDetections++
                }
            }
        }
        
        // Calculate adjustments based on trend
        val (signal, entryAdj, exitUrg, sizeMult) = when (trend.trend) {
            Trend.SPIKE -> {
                spikeDetections++
                SignalType.LIQUIDITY_SPIKE to spikeEntryBoost to 0.0 to 1.3
            }
            Trend.STRONG_GROWTH -> {
                SignalType.LIQUIDITY_GROWING to growthEntryBoost to 0.0 to 1.2
            }
            Trend.GROWTH -> {
                SignalType.LIQUIDITY_GROWING to (growthEntryBoost * 0.5) to 0.0 to 1.1
            }
            Trend.STABLE -> {
                SignalType.LIQUIDITY_STABLE to 0.0 to 0.0 to 1.0
            }
            Trend.DRAIN -> {
                SignalType.LIQUIDITY_DRAINING to (-5.0) to drainExitUrgency to 0.8
            }
            Trend.COLLAPSE -> {
                collapseDetections++
                shouldBlock = true
                blockReason = blockReason ?: "LIQUIDITY_COLLAPSE: Rapid drain detected"
                SignalType.LIQUIDITY_COLLAPSE to (-15.0) to collapseExitUrgency to 0.5
            }
            Trend.UNKNOWN -> {
                SignalType.NO_DATA to 0.0 to 0.0 to 1.0
            }
        }
        
        // Adjust for depth quality
        val depthSizeMult = when (trend.depthQuality) {
            DepthQuality.EXCELLENT -> 1.2
            DepthQuality.GOOD -> 1.0
            DepthQuality.FAIR -> 0.8
            DepthQuality.POOR -> 0.5
            DepthQuality.DANGEROUS -> 0.3
        }
        
        val finalSizeMult = (sizeMult * depthSizeMult).coerceIn(0.3, 1.5)
        
        // Log significant signals
        if (trend.trend in listOf(Trend.SPIKE, Trend.COLLAPSE, Trend.STRONG_GROWTH)) {
            ErrorLogger.info("LiquidityAI", "💧 $symbol: ${trend.trend.name} | ${trend.reason} | depth=${trend.depthQuality}")
        }
        
        return LiquiditySignal(
            signal = signal,
            trend = trend.trend,
            depthQuality = trend.depthQuality,
            entryAdjustment = entryAdj,
            exitUrgency = exitUrg,
            sizeMultiplier = finalSizeMult,
            confidence = trend.confidence,
            reason = trend.reason,
            shouldBlock = shouldBlock,
            blockReason = blockReason,
        )
    }
    
    /**
     * Get entry score adjustment based on liquidity.
     */
    fun getEntryScoreAdjustment(mint: String, symbol: String): Double {
        val signal = getSignal(mint, symbol, isOpenPosition = false)
        return signal.entryAdjustment
    }
    
    /**
     * Get exit urgency based on liquidity (for open positions).
     */
    fun getExitUrgency(mint: String, symbol: String): Double {
        val signal = getSignal(mint, symbol, isOpenPosition = true)
        return signal.exitUrgency
    }
    
    /**
     * Get position size multiplier based on depth quality.
     */
    fun getSizeMultiplier(mint: String, symbol: String): Double {
        val signal = getSignal(mint, symbol, isOpenPosition = false)
        return signal.sizeMultiplier
    }
    
    /**
     * Check if trade should be blocked due to liquidity issues.
     */
    fun shouldBlockTrade(mint: String, symbol: String, isOpenPosition: Boolean): Pair<Boolean, String?> {
        val signal = getSignal(mint, symbol, isOpenPosition)
        return signal.shouldBlock to signal.blockReason
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DEPTH QUALITY ASSESSMENT
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun assessDepthQuality(liquidityUsd: Double): DepthQuality {
        return when {
            liquidityUsd >= 500_000 -> DepthQuality.EXCELLENT  // $500K+ = very deep
            liquidityUsd >= 100_000 -> DepthQuality.GOOD       // $100K-500K = good
            liquidityUsd >= 25_000 -> DepthQuality.FAIR        // $25K-100K = fair
            liquidityUsd >= 5_000 -> DepthQuality.POOR         // $5K-25K = thin
            else -> DepthQuality.DANGEROUS                      // <$5K = very thin
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LEARNING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Record trade outcome for learning.
     */
    fun recordOutcome(mint: String, pnlPct: Double, wasProfit: Boolean) {
        val history = liquidityHistory[mint] ?: return
        val trend = analyzeTrend(mint)
        
        val signalType = when (trend.trend) {
            Trend.SPIKE -> SignalType.LIQUIDITY_SPIKE
            Trend.STRONG_GROWTH, Trend.GROWTH -> SignalType.LIQUIDITY_GROWING
            Trend.STABLE -> SignalType.LIQUIDITY_STABLE
            Trend.DRAIN -> SignalType.LIQUIDITY_DRAINING
            Trend.COLLAPSE -> SignalType.LIQUIDITY_COLLAPSE
            else -> SignalType.NO_DATA
        }
        
        val outcome = signalOutcomes.getOrPut(signalType) { SignalOutcome() }
        synchronized(outcome) {
            outcome.totalTrades++
            if (wasProfit) outcome.profitableTrades++
            outcome.totalPnlPct += pnlPct
        }
        
        // Adjust weights every 20 outcomes per signal type
        if (outcome.totalTrades % 20 == 0 && outcome.totalTrades >= 20) {
            adjustWeights(signalType, outcome)
        }
    }
    
    private fun adjustWeights(signalType: SignalType, outcome: SignalOutcome) {
        val winRate = outcome.profitableTrades.toDouble() / outcome.totalTrades
        val avgPnl = outcome.totalPnlPct / outcome.totalTrades
        
        when (signalType) {
            SignalType.LIQUIDITY_GROWING -> {
                // If growing liquidity signals are profitable, increase boost
                growthEntryBoost = when {
                    winRate >= 0.6 && avgPnl > 10 -> (growthEntryBoost + 2).coerceAtMost(15.0)
                    winRate < 0.4 -> (growthEntryBoost - 2).coerceAtLeast(3.0)
                    else -> growthEntryBoost
                }
            }
            SignalType.LIQUIDITY_SPIKE -> {
                spikeEntryBoost = when {
                    winRate >= 0.6 && avgPnl > 15 -> (spikeEntryBoost + 3).coerceAtMost(25.0)
                    winRate < 0.4 -> (spikeEntryBoost - 3).coerceAtLeast(5.0)
                    else -> spikeEntryBoost
                }
            }
            SignalType.LIQUIDITY_DRAINING -> {
                // If draining signals correctly predict losses, increase urgency
                drainExitUrgency = when {
                    winRate < 0.4 -> (drainExitUrgency + 3).coerceAtMost(25.0)  // More losses = increase urgency
                    winRate >= 0.6 -> (drainExitUrgency - 2).coerceAtLeast(5.0) // False alarms = reduce
                    else -> drainExitUrgency
                }
            }
            SignalType.LIQUIDITY_COLLAPSE -> {
                // Collapse should always be high urgency
                collapseExitUrgency = (30.0).coerceAtLeast(collapseExitUrgency)
            }
            else -> { /* No adjustment */ }
        }
        
        ErrorLogger.info("LiquidityAI", "📊 Learned weights: growth=$growthEntryBoost spike=$spikeEntryBoost drain=$drainExitUrgency")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun saveToJson(): JSONObject {
        return JSONObject().apply {
            put("growthEntryBoost", growthEntryBoost)
            put("spikeEntryBoost", spikeEntryBoost)
            put("drainExitUrgency", drainExitUrgency)
            put("collapseExitUrgency", collapseExitUrgency)
            put("totalAnalyses", totalAnalyses)
            put("collapseDetections", collapseDetections)
            put("spikeDetections", spikeDetections)
            
            // Save signal outcomes
            val outcomesJson = JSONObject()
            signalOutcomes.forEach { (type, outcome) ->
                outcomesJson.put(type.name, JSONObject().apply {
                    put("totalTrades", outcome.totalTrades)
                    put("profitableTrades", outcome.profitableTrades)
                    put("totalPnlPct", outcome.totalPnlPct)
                })
            }
            put("outcomes", outcomesJson)
        }
    }
    
    fun loadFromJson(json: JSONObject) {
        try {
            growthEntryBoost = json.optDouble("growthEntryBoost", 8.0)
            spikeEntryBoost = json.optDouble("spikeEntryBoost", 15.0)
            drainExitUrgency = json.optDouble("drainExitUrgency", 12.0)
            collapseExitUrgency = json.optDouble("collapseExitUrgency", 30.0)
            totalAnalyses = json.optInt("totalAnalyses", 0)
            collapseDetections = json.optInt("collapseDetections", 0)
            spikeDetections = json.optInt("spikeDetections", 0)
            
            // Load outcomes
            val outcomesJson = json.optJSONObject("outcomes")
            if (outcomesJson != null) {
                SignalType.values().forEach { type ->
                    val outcomeJson = outcomesJson.optJSONObject(type.name)
                    if (outcomeJson != null) {
                        signalOutcomes[type] = SignalOutcome(
                            totalTrades = outcomeJson.optInt("totalTrades", 0),
                            profitableTrades = outcomeJson.optInt("profitableTrades", 0),
                            totalPnlPct = outcomeJson.optDouble("totalPnlPct", 0.0),
                        )
                    }
                }
            }
        } catch (e: Exception) {
            ErrorLogger.error("LiquidityAI", "Failed to load: ${e.message}")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITIES
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getStats(): String {
        val growingCount = signalOutcomes[SignalType.LIQUIDITY_GROWING]?.totalTrades ?: 0
        val collapseCount = signalOutcomes[SignalType.LIQUIDITY_COLLAPSE]?.totalTrades ?: 0
        return "LiquidityAI: $totalAnalyses analyses | $spikeDetections spikes | $collapseDetections collapses | " +
               "weights: growth=$growthEntryBoost spike=$spikeEntryBoost drain=$drainExitUrgency"
    }
    
    fun cleanup() {
        // Remove old history for tokens not seen in 30 minutes
        val thirtyMinutesAgo = System.currentTimeMillis() - (30 * 60 * 1000)
        liquidityHistory.entries.removeIf { (_, history) ->
            synchronized(history) {
                history.isEmpty() || history.last().timestamp < thirtyMinutesAgo
            }
        }
        
        // Clean up entry liquidity for closed positions
        entryLiquidity.entries.removeIf { (mint, _) ->
            !liquidityHistory.containsKey(mint)
        }
    }
    
    private fun Double.format(decimals: Int) = "%.${decimals}f".format(this)
    
    // Triple to Quadruple helper
    private infix fun <A, B, C> Triple<A, B, C>.to(d: Double): Quadruple<A, B, C, Double> = Quadruple(first, second, third, d)
    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
    
    // Operator overload for result unpacking
    private operator fun <A, B, C, D> Quadruple<A, B, C, D>.component1() = first
    private operator fun <A, B, C, D> Quadruple<A, B, C, D>.component2() = second
    private operator fun <A, B, C, D> Quadruple<A, B, C, D>.component3() = third
    private operator fun <A, B, C, D> Quadruple<A, B, C, D>.component4() = fourth
}
