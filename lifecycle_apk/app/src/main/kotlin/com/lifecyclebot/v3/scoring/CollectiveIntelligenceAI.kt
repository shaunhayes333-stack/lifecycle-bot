package com.lifecyclebot.v3.scoring

import com.lifecyclebot.collective.CollectiveLearning
import com.lifecyclebot.engine.ErrorLogger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * COLLECTIVE INTELLIGENCE AI - Layer 22
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * The "Hive Mind" layer that interprets, maintains, and synthesizes collective
 * knowledge from all AATE instances connected via Turso.
 * 
 * RESPONSIBILITIES:
 * ─────────────────────────────────────────────────────────────────────────────
 * 
 * 1. DATA INTERPRETATION
 *    - Pattern quality scoring (reliability of collective patterns)
 *    - Cross-instance signal synthesis (consensus detection)
 *    - Mode performance aggregation (best modes across collective)
 * 
 * 2. DATA MAINTENANCE
 *    - Prune stale patterns (> 7 days old)
 *    - Deduplicate similar patterns from same instance
 *    - Anomaly detection (flag corrupted/outlier data)
 * 
 * 3. INTELLIGENCE FEATURES
 *    - Predict token success based on collective history
 *    - Dynamic threshold adjustment based on collective win rates
 *    - Consensus scoring (require N instances to agree)
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object CollectiveIntelligenceAI {
    
    private const val TAG = "CollectiveAI"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Data retention
    private const val MAX_PATTERN_AGE_DAYS = 7
    private const val MAX_PATTERN_AGE_MS = MAX_PATTERN_AGE_DAYS * 24 * 60 * 60 * 1000L
    
    // Consensus thresholds
    private const val MIN_INSTANCES_FOR_CONSENSUS = 2  // At least 2 instances must agree
    private const val CONSENSUS_AGREEMENT_PCT = 60.0   // 60% must agree for strong signal
    
    // Quality scoring
    private const val MIN_TRADES_FOR_QUALITY = 5       // Minimum trades to score a pattern
    private const val HIGH_QUALITY_WIN_RATE = 65.0     // Above this = high quality
    private const val LOW_QUALITY_WIN_RATE = 40.0      // Below this = low quality
    
    // Anomaly detection
    private const val ANOMALY_PNL_THRESHOLD = 500.0    // +/-500% P&L is suspicious
    private const val ANOMALY_TRADE_COUNT_MAX = 1000   // >1000 trades in pattern is suspicious
    
    // Dynamic threshold adjustment
    private const val THRESHOLD_ADJUST_STEP = 2        // Adjust by 2% at a time
    private const val MIN_CONFIDENCE_THRESHOLD = 50    // Never go below 50%
    private const val MAX_CONFIDENCE_THRESHOLD = 90    // Never go above 90%
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Cached collective insights (refreshed periodically)
    private val patternQualityCache = ConcurrentHashMap<String, PatternQuality>()
    private val modePerformanceCache = ConcurrentHashMap<String, ModePerformance>()
    private val tokenPredictionCache = ConcurrentHashMap<String, TokenPrediction>()
    private val consensusCache = ConcurrentHashMap<String, ConsensusSignal>()
    
    // V4.0: Network signals cache - Hot tokens from other bots
    private val networkSignalsCache = ConcurrentHashMap<String, CollectiveLearning.NetworkSignal>()
    
    // Dynamic thresholds (adjusted based on collective performance)
    @Volatile private var dynamicConfidenceThreshold = 70
    @Volatile private var dynamicScoreThreshold = 35
    
    // Maintenance stats
    private val patternsAnalyzed = AtomicInteger(0)
    private val patternsPruned = AtomicInteger(0)
    private val anomaliesDetected = AtomicInteger(0)
    private val lastMaintenanceMs = AtomicLong(0)
    private val lastRefreshMs = AtomicLong(0)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class PatternQuality(
        val patternKey: String,
        val qualityScore: Int,           // 0-100
        val reliability: Reliability,
        val tradeCount: Int,
        val winRate: Double,
        val avgPnlPct: Double,
        val instanceCount: Int,          // How many instances reported this pattern
        val lastUpdated: Long,
        val isAnomaly: Boolean = false,
    )
    
    enum class Reliability {
        UNTESTED,      // < 5 trades
        LOW,           // 5-20 trades, < 40% win rate
        MEDIUM,        // 20-50 trades, 40-65% win rate
        HIGH,          // 50+ trades, > 65% win rate
        PROVEN,        // 100+ trades, > 70% win rate, multiple instances
    }
    
    data class ModePerformance(
        val modeName: String,
        val collectiveWinRate: Double,
        val collectiveAvgPnl: Double,
        val totalTrades: Int,
        val activeInstances: Int,
        val rank: Int,                   // 1 = best performing mode
        val recommendation: ModeRecommendation,
    )
    
    enum class ModeRecommendation {
        STRONGLY_RECOMMENDED,  // Top 20% performer
        RECOMMENDED,           // Above average
        NEUTRAL,               // Average
        CAUTION,               // Below average
        AVOID,                 // Bottom 20% performer
    }
    
    data class TokenPrediction(
        val mint: String,
        val symbol: String,
        val successProbability: Double,  // 0-100%
        val collectiveSignal: Signal,
        val instancesReporting: Int,
        val historicalWinRate: Double,
        val avgHistoricalPnl: Double,
        val confidence: Int,
    )
    
    enum class Signal {
        STRONG_BUY,    // > 70% consensus bullish
        BUY,           // > 55% consensus bullish
        NEUTRAL,       // Mixed signals
        SELL,          // > 55% consensus bearish
        STRONG_SELL,   // > 70% consensus bearish
        NO_DATA,       // Not enough collective data
    }
    
    data class ConsensusSignal(
        val mint: String,
        val bullishInstances: Int,
        val bearishInstances: Int,
        val neutralInstances: Int,
        val totalInstances: Int,
        val consensusStrength: Double,   // 0-100%
        val signal: Signal,
        val timestamp: Long,
    )
    
    data class CollectiveInsight(
        val score: Int,                  // Contribution to V3 score (-20 to +20)
        val confidence: Int,             // Confidence adjustment (-10 to +10)
        val signal: Signal,
        val reasoning: String,
        val patternQuality: Reliability,
        val consensusStrength: Double,
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN SCORING FUNCTION (Called by UnifiedScorer)
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Score a token based on collective intelligence.
     * Returns a score adjustment (-20 to +20) and confidence adjustment.
     * 
     * V4.0: Now integrates NETWORK SIGNALS - hot tokens broadcast by other bots
     * get significant score boosts, making the hive mind work together!
     */
    fun score(
        mint: String,
        symbol: String,
        source: String,
        liquidityUsd: Double,
        v3Score: Int,
        v3Confidence: Int,
    ): CollectiveInsight {
        
        if (!CollectiveLearning.isEnabled()) {
            return CollectiveInsight(
                score = 0,
                confidence = 0,
                signal = Signal.NO_DATA,
                reasoning = "Collective learning disabled",
                patternQuality = Reliability.UNTESTED,
                consensusStrength = 0.0
            )
        }
        
        try {
            // 1. Check token prediction cache
            val prediction = tokenPredictionCache[mint]
            
            // 2. Check consensus signals
            val consensus = consensusCache[mint]
            
            // 3. Get pattern quality for this source
            val patternKey = "${source}_${getLiquidityBucket(liquidityUsd)}"
            val patternQuality = patternQualityCache[patternKey]
            
            // 4. V4.0: Check network signals cache for hot tokens from other bots
            val networkSignal = networkSignalsCache[mint]
            
            // Calculate score adjustment based on collective data
            var scoreAdj = 0
            var confAdj = 0
            var reasoning = mutableListOf<String>()
            
            // ═══════════════════════════════════════════════════════════════════
            // V4.0: NETWORK SIGNAL BOOST - The Hive Mind Working Together
            // When other bots broadcast a winner, THIS bot gets a score boost!
            // ═══════════════════════════════════════════════════════════════════
            if (networkSignal != null) {
                when (networkSignal.signalType) {
                    "MEGA_WINNER" -> {
                        // Another bot made 50%+ on this token - BIG BOOST
                        scoreAdj += 25
                        confAdj += 12
                        reasoning.add("🔥NETWORK_MEGA(+${networkSignal.pnlPct.toInt()}%)")
                    }
                    "HOT_TOKEN" -> {
                        // Another bot made 20%+ - solid boost
                        scoreAdj += 15
                        confAdj += 8
                        reasoning.add("🌐NETWORK_HOT(+${networkSignal.pnlPct.toInt()}%)")
                    }
                    "AVOID" -> {
                        // Another bot lost big - PENALIZE
                        scoreAdj -= 20
                        confAdj -= 10
                        reasoning.add("⚠️NETWORK_AVOID(${networkSignal.pnlPct.toInt()}%)")
                    }
                }
                
                // Extra boost if multiple bots acknowledged this signal
                if (networkSignal.ackCount >= 3) {
                    scoreAdj += 5
                    reasoning.add("MULTI_ACK(${networkSignal.ackCount})")
                }
            }
            
            // Token-specific prediction
            if (prediction != null && prediction.instancesReporting >= MIN_INSTANCES_FOR_CONSENSUS) {
                when (prediction.collectiveSignal) {
                    Signal.STRONG_BUY -> {
                        scoreAdj += 15
                        confAdj += 8
                        reasoning.add("HIVE_STRONG_BUY(${prediction.successProbability.toInt()}%)")
                    }
                    Signal.BUY -> {
                        scoreAdj += 8
                        confAdj += 4
                        reasoning.add("HIVE_BUY(${prediction.successProbability.toInt()}%)")
                    }
                    Signal.SELL -> {
                        scoreAdj -= 10
                        confAdj -= 5
                        reasoning.add("HIVE_SELL")
                    }
                    Signal.STRONG_SELL -> {
                        scoreAdj -= 18
                        confAdj -= 10
                        reasoning.add("HIVE_STRONG_SELL")
                    }
                    else -> {}
                }
            }
            
            // Consensus signal
            if (consensus != null && consensus.totalInstances >= MIN_INSTANCES_FOR_CONSENSUS) {
                if (consensus.consensusStrength >= 70) {
                    when (consensus.signal) {
                        Signal.STRONG_BUY -> {
                            scoreAdj += 5
                            reasoning.add("CONSENSUS_70%+")
                        }
                        Signal.STRONG_SELL -> {
                            scoreAdj -= 8
                            reasoning.add("CONSENSUS_BEARISH_70%+")
                        }
                        else -> {}
                    }
                }
            }
            
            // Pattern quality adjustment
            if (patternQuality != null) {
                when (patternQuality.reliability) {
                    Reliability.PROVEN -> {
                        if (patternQuality.winRate >= HIGH_QUALITY_WIN_RATE) {
                            scoreAdj += 5
                            confAdj += 3
                            reasoning.add("PROVEN_PATTERN(${patternQuality.winRate.toInt()}%WR)")
                        }
                    }
                    Reliability.HIGH -> {
                        if (patternQuality.winRate >= 60) {
                            scoreAdj += 3
                            confAdj += 2
                        }
                    }
                    Reliability.LOW -> {
                        scoreAdj -= 3
                        confAdj -= 2
                        reasoning.add("LOW_QUALITY_PATTERN")
                    }
                    else -> {}
                }
                
                // Anomaly penalty
                if (patternQuality.isAnomaly) {
                    scoreAdj -= 10
                    confAdj -= 5
                    reasoning.add("ANOMALY_DETECTED")
                }
            }
            
            // Apply dynamic threshold check
            if (v3Confidence < dynamicConfidenceThreshold) {
                confAdj -= 2
                reasoning.add("BELOW_COLLECTIVE_THRESHOLD")
            }
            
            // V4.0: Clamp adjustments (higher max due to network signals)
            scoreAdj = scoreAdj.coerceIn(-30, 30)
            confAdj = confAdj.coerceIn(-15, 15)
            
            val finalSignal = when {
                networkSignal?.signalType == "MEGA_WINNER" -> Signal.STRONG_BUY
                networkSignal?.signalType == "HOT_TOKEN" -> Signal.BUY
                networkSignal?.signalType == "AVOID" -> Signal.STRONG_SELL
                else -> prediction?.collectiveSignal ?: consensus?.signal ?: Signal.NO_DATA
            }
            
            return CollectiveInsight(
                score = scoreAdj,
                confidence = confAdj,
                signal = finalSignal,
                reasoning = if (reasoning.isEmpty()) "NO_COLLECTIVE_DATA" else reasoning.joinToString("|"),
                patternQuality = patternQuality?.reliability ?: Reliability.UNTESTED,
                consensusStrength = consensus?.consensusStrength ?: 0.0
            )
            
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Scoring error: ${e.message}")
            return CollectiveInsight(
                score = 0,
                confidence = 0,
                signal = Signal.NO_DATA,
                reasoning = "ERROR",
                patternQuality = Reliability.UNTESTED,
                consensusStrength = 0.0
            )
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA INTERPRETATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Analyze and score pattern quality from collective data.
     */
    suspend fun analyzePatternQuality() {
        if (!CollectiveLearning.isEnabled()) return
        
        try {
            val patterns = CollectiveLearning.downloadAllPatterns()
            
            for (pattern in patterns) {
                val key = pattern.patternKey
                val tradeCount = pattern.totalTrades
                val winRate = if (tradeCount > 0) {
                    pattern.wins.toDouble() / tradeCount * 100
                } else 0.0
                val avgPnl = pattern.avgPnlPct
                
                // Determine reliability
                val reliability = when {
                    tradeCount < MIN_TRADES_FOR_QUALITY -> Reliability.UNTESTED
                    tradeCount >= 100 && winRate >= 70 && pattern.instanceCount >= 2 -> Reliability.PROVEN
                    tradeCount >= 50 && winRate >= HIGH_QUALITY_WIN_RATE -> Reliability.HIGH
                    tradeCount >= 20 && winRate >= LOW_QUALITY_WIN_RATE -> Reliability.MEDIUM
                    else -> Reliability.LOW
                }
                
                // Calculate quality score (0-100)
                val qualityScore = calculateQualityScore(tradeCount, winRate, avgPnl, pattern.instanceCount)
                
                // Check for anomalies
                val isAnomaly = detectAnomaly(tradeCount, avgPnl, winRate)
                
                patternQualityCache[key] = PatternQuality(
                    patternKey = key,
                    qualityScore = qualityScore,
                    reliability = reliability,
                    tradeCount = tradeCount,
                    winRate = winRate,
                    avgPnlPct = avgPnl,
                    instanceCount = pattern.instanceCount,
                    lastUpdated = System.currentTimeMillis(),
                    isAnomaly = isAnomaly
                )
                
                patternsAnalyzed.incrementAndGet()
                
                if (isAnomaly) {
                    anomaliesDetected.incrementAndGet()
                    ErrorLogger.info(TAG, "🚨 ANOMALY: $key | trades=$tradeCount pnl=$avgPnl% WR=$winRate%")
                }
            }
            
            ErrorLogger.info(TAG, "📊 Analyzed ${patterns.size} patterns | " +
                "${anomaliesDetected.get()} anomalies detected")
            
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Pattern analysis error: ${e.message}")
        }
    }
    
    /**
     * Aggregate mode performance across all instances.
     */
    suspend fun aggregateModePerformance() {
        if (!CollectiveLearning.isEnabled()) return
        
        try {
            val modeStats = CollectiveLearning.downloadModeStatsForAI()
            val modeList = mutableListOf<ModePerformance>()
            
            for ((modeName, stats) in modeStats) {
                val winRate = if (stats.totalTrades > 0) {
                    stats.wins.toDouble() / stats.totalTrades * 100
                } else 0.0
                
                modeList.add(ModePerformance(
                    modeName = modeName,
                    collectiveWinRate = winRate,
                    collectiveAvgPnl = stats.avgPnlPct,
                    totalTrades = stats.totalTrades,
                    activeInstances = stats.instanceCount,
                    rank = 0,  // Will be set after sorting
                    recommendation = ModeRecommendation.NEUTRAL
                ))
            }
            
            // Sort by win rate and assign ranks
            val sorted = modeList.sortedByDescending { it.collectiveWinRate }
            val total = sorted.size.coerceAtLeast(1)
            
            sorted.forEachIndexed { index, mode ->
                val percentile = (index.toDouble() / total) * 100
                val recommendation = when {
                    percentile <= 20 -> ModeRecommendation.STRONGLY_RECOMMENDED
                    percentile <= 40 -> ModeRecommendation.RECOMMENDED
                    percentile <= 60 -> ModeRecommendation.NEUTRAL
                    percentile <= 80 -> ModeRecommendation.CAUTION
                    else -> ModeRecommendation.AVOID
                }
                
                modePerformanceCache[mode.modeName] = mode.copy(
                    rank = index + 1,
                    recommendation = recommendation
                )
            }
            
            // Log top performers
            sorted.take(3).forEach { mode ->
                ErrorLogger.info(TAG, "🏆 TOP MODE: ${mode.modeName} | " +
                    "WR=${mode.collectiveWinRate.toInt()}% | " +
                    "trades=${mode.totalTrades}")
            }
            
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Mode aggregation error: ${e.message}")
        }
    }
    
    /**
     * Synthesize cross-instance signals for consensus detection.
     */
    suspend fun synthesizeConsensus() {
        if (!CollectiveLearning.isEnabled()) return
        
        try {
            val signals = CollectiveLearning.downloadRecentSignals()
            val tokenSignals = mutableMapOf<String, MutableList<String>>()  // mint -> list of signals
            
            // Group signals by token
            for (signal in signals) {
                tokenSignals.getOrPut(signal.mint) { mutableListOf() }.add(signal.signal)
            }
            
            // Calculate consensus for each token
            for ((mint, signalList) in tokenSignals) {
                val bullish = signalList.count { it in listOf("BUY", "STRONG_BUY", "EXECUTE") }
                val bearish = signalList.count { it in listOf("SELL", "STRONG_SELL", "REJECT") }
                val neutral = signalList.size - bullish - bearish
                val total = signalList.size
                
                val consensusStrength = if (total > 0) {
                    (max(bullish, bearish).toDouble() / total) * 100
                } else 0.0
                
                val signal = when {
                    total < MIN_INSTANCES_FOR_CONSENSUS -> Signal.NO_DATA
                    bullish > bearish && consensusStrength >= 70 -> Signal.STRONG_BUY
                    bullish > bearish && consensusStrength >= CONSENSUS_AGREEMENT_PCT -> Signal.BUY
                    bearish > bullish && consensusStrength >= 70 -> Signal.STRONG_SELL
                    bearish > bullish && consensusStrength >= CONSENSUS_AGREEMENT_PCT -> Signal.SELL
                    else -> Signal.NEUTRAL
                }
                
                consensusCache[mint] = ConsensusSignal(
                    mint = mint,
                    bullishInstances = bullish,
                    bearishInstances = bearish,
                    neutralInstances = neutral,
                    totalInstances = total,
                    consensusStrength = consensusStrength,
                    signal = signal,
                    timestamp = System.currentTimeMillis()
                )
            }
            
            ErrorLogger.info(TAG, "🤝 Consensus synthesized for ${tokenSignals.size} tokens")
            
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Consensus synthesis error: ${e.message}")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA MAINTENANCE
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Run full maintenance cycle (prune, dedupe, anomaly scan).
     */
    suspend fun runMaintenance() {
        if (!CollectiveLearning.isEnabled()) return
        
        val startTime = System.currentTimeMillis()
        ErrorLogger.info(TAG, "🧹 Starting collective maintenance...")
        
        try {
            // 1. Prune stale patterns
            val pruned = pruneStalePatterns()
            
            // 2. Deduplicate patterns
            val deduped = deduplicatePatterns()
            
            // 3. Run anomaly scan
            val anomalies = scanForAnomalies()
            
            // 4. Update dynamic thresholds
            updateDynamicThresholds()
            
            lastMaintenanceMs.set(System.currentTimeMillis())
            
            val elapsed = System.currentTimeMillis() - startTime
            ErrorLogger.info(TAG, "🧹 Maintenance complete in ${elapsed}ms | " +
                "pruned=$pruned deduped=$deduped anomalies=$anomalies")
            
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Maintenance error: ${e.message}")
        }
    }
    
    /**
     * Prune patterns older than MAX_PATTERN_AGE_DAYS.
     */
    private suspend fun pruneStalePatterns(): Int {
        val cutoffTime = System.currentTimeMillis() - MAX_PATTERN_AGE_MS
        var pruned = 0
        
        try {
            pruned = CollectiveLearning.pruneOldPatterns(cutoffTime)
            patternsPruned.addAndGet(pruned)
            
            // Also clear stale cache entries
            val staleKeys = patternQualityCache.filter { 
                it.value.lastUpdated < cutoffTime 
            }.keys
            staleKeys.forEach { patternQualityCache.remove(it) }
            
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Prune error: ${e.message}")
        }
        
        return pruned
    }
    
    /**
     * Deduplicate similar patterns from same instance.
     */
    private suspend fun deduplicatePatterns(): Int {
        // This would require more complex logic to identify duplicates
        // For now, CollectiveLearning handles this via UPSERT
        return 0
    }
    
    /**
     * Scan for anomalous data.
     */
    private suspend fun scanForAnomalies(): Int {
        var anomalyCount = 0
        
        for ((key, quality) in patternQualityCache) {
            if (detectAnomaly(quality.tradeCount, quality.avgPnlPct, quality.winRate)) {
                anomalyCount++
                
                // Mark as anomaly in cache
                patternQualityCache[key] = quality.copy(isAnomaly = true)
                
                // Could also flag in database for review
                ErrorLogger.info(TAG, "🚨 Anomaly flagged: $key")
            }
        }
        
        return anomalyCount
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INTELLIGENCE FEATURES
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Predict token success based on collective history.
     */
    fun predictTokenSuccess(
        mint: String,
        symbol: String,
        source: String,
        liquidityUsd: Double,
    ): TokenPrediction {
        
        // Check cache first
        tokenPredictionCache[mint]?.let { return it }
        
        // Calculate prediction based on available data
        val patternKey = "${source}_${getLiquidityBucket(liquidityUsd)}"
        val patternQuality = patternQualityCache[patternKey]
        val consensus = consensusCache[mint]
        
        val historicalWinRate = patternQuality?.winRate ?: 50.0
        val avgPnl = patternQuality?.avgPnlPct ?: 0.0
        val instanceCount = max(patternQuality?.instanceCount ?: 0, consensus?.totalInstances ?: 0)
        
        // Calculate success probability
        val successProb = when {
            instanceCount < MIN_INSTANCES_FOR_CONSENSUS -> 50.0  // No data, neutral
            historicalWinRate >= 70 && avgPnl > 10 -> 75.0 + (historicalWinRate - 70) * 0.5
            historicalWinRate >= 60 -> 60.0 + (historicalWinRate - 60) * 0.5
            historicalWinRate >= 50 -> 50.0 + (historicalWinRate - 50) * 0.5
            else -> 30.0 + historicalWinRate * 0.3
        }.coerceIn(10.0, 95.0)
        
        val signal = when {
            successProb >= 75 -> Signal.STRONG_BUY
            successProb >= 60 -> Signal.BUY
            successProb >= 45 -> Signal.NEUTRAL
            successProb >= 30 -> Signal.SELL
            else -> Signal.STRONG_SELL
        }
        
        val prediction = TokenPrediction(
            mint = mint,
            symbol = symbol,
            successProbability = successProb,
            collectiveSignal = signal,
            instancesReporting = instanceCount,
            historicalWinRate = historicalWinRate,
            avgHistoricalPnl = avgPnl,
            confidence = (successProb * 0.8 + instanceCount * 2).toInt().coerceIn(0, 100)
        )
        
        tokenPredictionCache[mint] = prediction
        return prediction
    }
    
    /**
     * Update dynamic thresholds based on collective performance.
     */
    private fun updateDynamicThresholds() {
        // Calculate collective win rate
        val totalWins = patternQualityCache.values.sumOf { 
            (it.tradeCount * it.winRate / 100).toInt() 
        }
        val totalTrades = patternQualityCache.values.sumOf { it.tradeCount }
        
        if (totalTrades < 100) return  // Not enough data
        
        val collectiveWinRate = totalWins.toDouble() / totalTrades * 100
        
        // Adjust thresholds based on performance
        when {
            collectiveWinRate >= 65 -> {
                // Performing well, can be slightly more aggressive
                dynamicConfidenceThreshold = (dynamicConfidenceThreshold - THRESHOLD_ADJUST_STEP)
                    .coerceIn(MIN_CONFIDENCE_THRESHOLD, MAX_CONFIDENCE_THRESHOLD)
            }
            collectiveWinRate <= 45 -> {
                // Performing poorly, be more conservative
                dynamicConfidenceThreshold = (dynamicConfidenceThreshold + THRESHOLD_ADJUST_STEP)
                    .coerceIn(MIN_CONFIDENCE_THRESHOLD, MAX_CONFIDENCE_THRESHOLD)
            }
        }
        
        ErrorLogger.info(TAG, "📊 Collective WR=${collectiveWinRate.toInt()}% | " +
            "Dynamic threshold=${dynamicConfidenceThreshold}%")
    }
    
    /**
     * Get consensus requirement for a token.
     * Returns true if token has sufficient consensus to trade.
     */
    fun hasConsensus(mint: String): Boolean {
        val consensus = consensusCache[mint] ?: return false
        return consensus.totalInstances >= MIN_INSTANCES_FOR_CONSENSUS &&
               consensus.consensusStrength >= CONSENSUS_AGREEMENT_PCT
    }
    
    /**
     * Get mode recommendation from collective data.
     */
    fun getModeRecommendation(modeName: String): ModeRecommendation {
        return modePerformanceCache[modeName]?.recommendation ?: ModeRecommendation.NEUTRAL
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // REFRESH & STATS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Full refresh of all collective intelligence caches.
     * Should be called periodically (e.g., every 15 minutes).
     * 
     * V4.0: Now also refreshes NETWORK SIGNALS - hot tokens from other bots!
     */
    suspend fun refresh() {
        if (!CollectiveLearning.isEnabled()) return
        
        val startTime = System.currentTimeMillis()
        ErrorLogger.info(TAG, "🧠 Refreshing collective intelligence...")
        
        // Run all analysis in parallel
        coroutineScope {
            launch { analyzePatternQuality() }
            launch { aggregateModePerformance() }
            launch { synthesizeConsensus() }
            launch { refreshNetworkSignals() }  // V4.0: New!
        }
        
        lastRefreshMs.set(System.currentTimeMillis())
        
        val elapsed = System.currentTimeMillis() - startTime
        ErrorLogger.info(TAG, "🧠 Refresh complete in ${elapsed}ms | " +
            "patterns=${patternQualityCache.size} | " +
            "modes=${modePerformanceCache.size} | " +
            "consensus=${consensusCache.size} | " +
            "network=${networkSignalsCache.size}")
    }
    
    /**
     * V4.0: Refresh network signals from other bots.
     * These are hot tokens that other bots have broadcast.
     *
     * V5.9.40: Gated behind BotService.status.running. Network signals
     * are trading-side telemetry and must not fire, log, or update the
     * shared cache while the user has the bot stopped.
     */
    private suspend fun refreshNetworkSignals() {
        if (!com.lifecyclebot.engine.BotService.status.running) {
            ErrorLogger.debug(TAG, "Skipping network signal refresh — bot not running")
            return
        }
        try {
            val signals = CollectiveLearning.getNetworkSignals(50)
            
            // Clear old and update cache
            networkSignalsCache.clear()
            
            for (signal in signals) {
                // Key by mint address
                networkSignalsCache[signal.mint] = signal
                
                // Log significant signals
                if (signal.signalType == "MEGA_WINNER" || signal.signalType == "HOT_TOKEN") {
                    ErrorLogger.info(TAG, "📡 NETWORK SIGNAL: ${signal.signalType} ${signal.symbol} " +
                        "+${signal.pnlPct.toInt()}% from ${signal.broadcasterId.take(8)}...")
                }
            }
            
            // Also cleanup expired signals in the database
            CollectiveLearning.cleanupExpiredSignals()
            
            ErrorLogger.info(TAG, "📡 Loaded ${signals.size} network signals from hive")
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "refreshNetworkSignals error: ${e.message}")
        }
    }
    
    /**
     * V4.0: Get list of mints that have active network signals.
     * BotService can use this to add hot tokens to watchlist!
     */
    fun getNetworkHotMints(): List<String> {
        return networkSignalsCache.filter { 
            it.value.signalType in listOf("MEGA_WINNER", "HOT_TOKEN")
        }.keys.toList()
    }
    
    /**
     * V4.0: Check if a mint has a positive network signal.
     */
    fun hasPositiveNetworkSignal(mint: String): Boolean {
        val signal = networkSignalsCache[mint] ?: return false
        return signal.signalType in listOf("MEGA_WINNER", "HOT_TOKEN")
    }
    
    /**
     * V4.0: Check if a mint should be avoided (negative network signal).
     */
    fun shouldAvoid(mint: String): Boolean {
        val signal = networkSignalsCache[mint] ?: return false
        return signal.signalType == "AVOID"
    }
    
    /**
     * V5.6.29d: Get all active network signals for UI display.
     */
    fun getActiveNetworkSignals(): List<CollectiveLearning.NetworkSignal> {
        return networkSignalsCache.values.toList()
    }
    
    /**
     * V5.6.29d: Get last refresh time for UI.
     */
    fun getLastRefreshTime(): Long {
        return lastRefreshMs.get()
    }
    
    /**
     * Get stats for UI display.
     */
    fun getStats(): CollectiveAIStats {
        return CollectiveAIStats(
            patternsAnalyzed = patternsAnalyzed.get(),
            patternsPruned = patternsPruned.get(),
            anomaliesDetected = anomaliesDetected.get(),
            cachedPatterns = patternQualityCache.size,
            cachedModes = modePerformanceCache.size,
            cachedConsensus = consensusCache.size,
            dynamicConfThreshold = dynamicConfidenceThreshold,
            lastRefreshMs = lastRefreshMs.get(),
            lastMaintenanceMs = lastMaintenanceMs.get(),
            isEnabled = CollectiveLearning.isEnabled()
        )
    }
    
    data class CollectiveAIStats(
        val patternsAnalyzed: Int,
        val patternsPruned: Int,
        val anomaliesDetected: Int,
        val cachedPatterns: Int,
        val cachedModes: Int,
        val cachedConsensus: Int,
        val dynamicConfThreshold: Int,
        val lastRefreshMs: Long,
        val lastMaintenanceMs: Long,
        val isEnabled: Boolean,
    ) {
        fun summary(): String = buildString {
            append("🧠 CollectiveAI: ")
            append("patterns=$cachedPatterns ")
            append("modes=$cachedModes ")
            append("consensus=$cachedConsensus ")
            append("threshold=$dynamicConfThreshold% ")
            append("anomalies=$anomaliesDetected")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER FUNCTIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun calculateQualityScore(
        tradeCount: Int,
        winRate: Double,
        avgPnl: Double,
        instanceCount: Int
    ): Int {
        // Weight factors
        val tradeWeight = min(tradeCount / 100.0, 1.0) * 25      // Max 25 points for 100+ trades
        val winRateWeight = (winRate / 100.0) * 40                // Max 40 points for 100% WR
        val pnlWeight = (avgPnl.coerceIn(-50.0, 50.0) + 50) / 100 * 20  // Max 20 points
        val instanceWeight = min(instanceCount / 5.0, 1.0) * 15   // Max 15 points for 5+ instances
        
        return (tradeWeight + winRateWeight + pnlWeight + instanceWeight).toInt().coerceIn(0, 100)
    }
    
    private fun detectAnomaly(tradeCount: Int, avgPnl: Double, winRate: Double): Boolean {
        return abs(avgPnl) > ANOMALY_PNL_THRESHOLD ||
               tradeCount > ANOMALY_TRADE_COUNT_MAX ||
               (tradeCount > 20 && (winRate > 95 || winRate < 5))  // Suspicious win rates
    }
    
    private fun getLiquidityBucket(liquidityUsd: Double): String {
        return when {
            liquidityUsd < 5000 -> "MICRO"
            liquidityUsd < 20000 -> "LOW"
            liquidityUsd < 50000 -> "MID"
            liquidityUsd < 200000 -> "HIGH"
            else -> "WHALE"
        }
    }
}
