package com.lifecyclebot.v3.scoring

import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import com.lifecyclebot.engine.ErrorLogger
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * V3.2 Liquidity Cycle AI
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Tracks market-wide liquidity cycles across Solana DEXes:
 * 
 * LIQUIDITY PHASES:
 * - DRY:       Low liquidity market-wide → Dangerous, easy to manipulate
 * - NORMAL:    Standard liquidity levels → Normal trading
 * - FLUSH:     High liquidity inflows → Opportunity phase
 * - EUPHORIA:  Extreme liquidity → Peak, reversal likely
 * 
 * CYCLE PATTERNS:
 * - INFLOW:    Liquidity increasing (money coming in)
 * - STABLE:    Liquidity steady
 * - OUTFLOW:   Liquidity decreasing (money leaving)
 * - ROTATION:  Liquidity moving between tokens/pools
 * 
 * MARKET-WIDE RISK INDICATOR:
 * Aggregates liquidity data from multiple tokens to detect:
 * - Market-wide liquidity crises
 * - Capital inflow/outflow trends
 * - Rotation patterns (money moving to new tokens)
 * 
 * CROSS-TALK INTEGRATION:
 * - FearGreedAI: Extreme fear + dry liquidity = DANGER
 * - MarketRegimeAI: Bear regime + outflow = SEVERE WARNING
 * - LiquidityDepthAI: Per-token depth vs market-wide trend
 */
object LiquidityCycleAI {
    
    private const val TAG = "LiquidityCycleAI"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA STRUCTURES
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class LiquidityPhase {
        DRY,       // < 30th percentile
        NORMAL,    // 30th - 70th percentile
        FLUSH,     // 70th - 90th percentile
        EUPHORIA   // > 90th percentile
    }
    
    enum class CyclePattern {
        INFLOW,    // Liquidity increasing
        STABLE,    // Liquidity steady
        OUTFLOW,   // Liquidity decreasing
        ROTATION   // Liquidity moving between pools
    }
    
    data class MarketLiquidityState(
        val phase: LiquidityPhase,
        val pattern: CyclePattern,
        val totalLiquidityUsd: Double,         // Aggregate tracked liquidity
        val avgPoolLiquidity: Double,          // Average per pool
        val liquidityChangePct: Double,        // % change over period
        val poolCount: Int,                    // Number of tracked pools
        val healthScore: Double,               // 0-100, overall market health
        val riskLevel: Int,                    // 1-5, higher = more risk
        val entryBoost: Int,
        val reason: String
    )
    
    // Rolling liquidity tracking
    private data class LiquiditySnapshot(
        val timestamp: Long,
        val totalLiquidity: Double,
        val poolCount: Int
    )
    
    private val liquidityHistory = ArrayDeque<LiquiditySnapshot>(100)
    private var currentState = AtomicReference(defaultState())
    
    // Per-pool tracking for rotation detection
    private val poolLiquidity = mutableMapOf<String, Double>()  // poolAddress -> liquidityUsd
    private var lastRotationCheck = 0L
    
    // Stats
    private var totalAnalyses = 0
    private var dryPeriodsDetected = 0
    private var euphoriaPeriodsDetected = 0
    private var rotationsDetected = 0
    
    // Learned percentile thresholds
    private var dryThreshold = 50_000.0       // Below this = DRY
    private var flushThreshold = 200_000.0    // Above this = FLUSH
    private var euphoriaThreshold = 500_000.0 // Above this = EUPHORIA
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN ANALYSIS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Update market liquidity state with new pool data.
     * Call this periodically with aggregate liquidity data.
     */
    fun updateMarketState(
        totalLiquidityUsd: Double,
        poolCount: Int,
        topPoolLiquidities: Map<String, Double> = emptyMap()
    ): MarketLiquidityState {
        totalAnalyses++
        
        val now = System.currentTimeMillis()
        
        // Record snapshot
        liquidityHistory.addLast(LiquiditySnapshot(now, totalLiquidityUsd, poolCount))
        if (liquidityHistory.size > 100) liquidityHistory.removeFirst()
        
        // Update pool tracking
        topPoolLiquidities.forEach { (pool, liq) ->
            poolLiquidity[pool] = liq
        }
        
        // Calculate phase
        val phase = classifyPhase(totalLiquidityUsd)
        
        // Calculate pattern (trend)
        val pattern = detectPattern()
        
        // Calculate metrics
        val avgPoolLiq = if (poolCount > 0) totalLiquidityUsd / poolCount else 0.0
        val liquidityChange = calculateLiquidityChange()
        
        // Check for rotation
        if (now - lastRotationCheck > 5 * 60 * 1000) {  // Every 5 minutes
            checkRotation()
            lastRotationCheck = now
        }
        
        // Calculate health and risk
        val healthScore = calculateHealthScore(phase, pattern, liquidityChange)
        val riskLevel = calculateRiskLevel(phase, pattern, healthScore)
        
        // Calculate entry boost
        val entryBoost = calculateEntryBoost(phase, pattern, healthScore)
        
        val reason = buildReason(phase, pattern, liquidityChange, healthScore)
        
        // Track stats
        when (phase) {
            LiquidityPhase.DRY -> dryPeriodsDetected++
            LiquidityPhase.EUPHORIA -> euphoriaPeriodsDetected++
            else -> {}
        }
        
        val state = MarketLiquidityState(
            phase = phase,
            pattern = pattern,
            totalLiquidityUsd = totalLiquidityUsd,
            avgPoolLiquidity = avgPoolLiq,
            liquidityChangePct = liquidityChange,
            poolCount = poolCount,
            healthScore = healthScore,
            riskLevel = riskLevel,
            entryBoost = entryBoost,
            reason = reason
        )
        
        currentState.set(state)
        
        if (phase == LiquidityPhase.DRY || riskLevel >= 4) {
            ErrorLogger.warn(TAG, "⚠️ LIQUIDITY WARNING: ${phase.name} | risk=$riskLevel | change=${liquidityChange.toInt()}%")
        }
        
        return state
    }
    
    /**
     * Get current market liquidity state.
     */
    fun getCurrentState(): MarketLiquidityState {
        return currentState.get()
    }
    
    /**
     * Quick check if market is in a risky liquidity phase.
     */
    fun isRisky(): Boolean {
        return currentState.get().riskLevel >= 3
    }
    
    /**
     * Quick check if market has healthy liquidity.
     */
    fun isHealthy(): Boolean {
        return currentState.get().healthScore >= 60
    }
    
    /**
     * Check if liquidity is draining market-wide.
     */
    fun isOutflowing(): Boolean {
        return currentState.get().pattern == CyclePattern.OUTFLOW
    }
    
    /**
     * Get current market liquidity risk level (1-5).
     */
    fun getRiskLevel(): Int {
        return currentState.get().riskLevel
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CALCULATIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun classifyPhase(totalLiquidity: Double): LiquidityPhase {
        return when {
            totalLiquidity < dryThreshold -> LiquidityPhase.DRY
            totalLiquidity < flushThreshold -> LiquidityPhase.NORMAL
            totalLiquidity < euphoriaThreshold -> LiquidityPhase.FLUSH
            else -> LiquidityPhase.EUPHORIA
        }
    }
    
    private fun detectPattern(): CyclePattern {
        if (liquidityHistory.size < 5) return CyclePattern.STABLE
        
        val recent = liquidityHistory.toList().takeLast(10)
        val earlier = recent.take(5)
        val later = recent.takeLast(5)
        
        val earlierAvg = earlier.map { it.totalLiquidity }.average()
        val laterAvg = later.map { it.totalLiquidity }.average()
        
        if (earlierAvg <= 0) return CyclePattern.STABLE
        
        val changePct = ((laterAvg - earlierAvg) / earlierAvg) * 100
        
        return when {
            changePct > 10 -> CyclePattern.INFLOW
            changePct < -10 -> CyclePattern.OUTFLOW
            else -> CyclePattern.STABLE
        }
    }
    
    private fun calculateLiquidityChange(): Double {
        if (liquidityHistory.size < 2) return 0.0
        
        val recent = liquidityHistory.toList()
        val oldLiq = recent.firstOrNull()?.totalLiquidity ?: return 0.0
        val newLiq = recent.lastOrNull()?.totalLiquidity ?: return 0.0
        
        if (oldLiq <= 0) return 0.0
        return ((newLiq - oldLiq) / oldLiq) * 100
    }
    
    private fun checkRotation() {
        // Detect if liquidity is rotating between pools
        // (some pools gaining while others losing)
        
        val gaining = poolLiquidity.count { (pool, _) ->
            val prev = poolLiquidity[pool] ?: 0.0
            poolLiquidity[pool]?.let { it > prev * 1.1 } ?: false
        }
        
        val losing = poolLiquidity.count { (pool, _) ->
            val prev = poolLiquidity[pool] ?: 0.0
            poolLiquidity[pool]?.let { it < prev * 0.9 } ?: false
        }
        
        // Rotation = significant movement in both directions
        if (gaining > 2 && losing > 2) {
            rotationsDetected++
            ErrorLogger.debug(TAG, "Rotation detected: $gaining gaining, $losing losing")
        }
    }
    
    private fun calculateHealthScore(
        phase: LiquidityPhase,
        pattern: CyclePattern,
        changePct: Double
    ): Double {
        var score = 50.0  // Base
        
        // Phase contribution
        score += when (phase) {
            LiquidityPhase.DRY -> -30.0
            LiquidityPhase.NORMAL -> 0.0
            LiquidityPhase.FLUSH -> 20.0
            LiquidityPhase.EUPHORIA -> 10.0  // Euphoria is risky
        }
        
        // Pattern contribution
        score += when (pattern) {
            CyclePattern.INFLOW -> 20.0
            CyclePattern.STABLE -> 5.0
            CyclePattern.OUTFLOW -> -25.0
            CyclePattern.ROTATION -> -5.0
        }
        
        // Change rate contribution
        score += changePct.coerceIn(-20.0, 20.0)
        
        return score.coerceIn(0.0, 100.0)
    }
    
    private fun calculateRiskLevel(
        phase: LiquidityPhase,
        pattern: CyclePattern,
        healthScore: Double
    ): Int {
        var risk = 1
        
        // Phase risk
        risk += when (phase) {
            LiquidityPhase.DRY -> 2
            LiquidityPhase.EUPHORIA -> 1  // Reversal risk
            else -> 0
        }
        
        // Pattern risk
        risk += when (pattern) {
            CyclePattern.OUTFLOW -> 2
            CyclePattern.ROTATION -> 1
            else -> 0
        }
        
        // Health adjustment
        if (healthScore < 30) risk += 1
        if (healthScore < 20) risk += 1
        
        return risk.coerceIn(1, 5)
    }
    
    private fun calculateEntryBoost(
        phase: LiquidityPhase,
        pattern: CyclePattern,
        healthScore: Double
    ): Int {
        var boost = 0
        
        // Phase boost
        boost += when (phase) {
            LiquidityPhase.DRY -> -10
            LiquidityPhase.NORMAL -> 0
            LiquidityPhase.FLUSH -> 5
            LiquidityPhase.EUPHORIA -> -3  // Peak risk
        }
        
        // Pattern boost
        boost += when (pattern) {
            CyclePattern.INFLOW -> 4
            CyclePattern.STABLE -> 0
            CyclePattern.OUTFLOW -> -8
            CyclePattern.ROTATION -> -2
        }
        
        // Health modifier
        boost = (boost * (healthScore / 100.0 + 0.5)).toInt()
        
        return boost.coerceIn(-15, 10)
    }
    
    private fun buildReason(
        phase: LiquidityPhase,
        pattern: CyclePattern,
        changePct: Double,
        healthScore: Double
    ): String {
        val parts = mutableListOf<String>()
        
        parts += "${phase.name} liquidity"
        parts += "${pattern.name.lowercase()} ${changePct.toInt()}%"
        parts += "health=${healthScore.toInt()}"
        
        return parts.joinToString(" | ")
    }
    
    private fun defaultState(): MarketLiquidityState {
        return MarketLiquidityState(
            phase = LiquidityPhase.NORMAL,
            pattern = CyclePattern.STABLE,
            totalLiquidityUsd = 0.0,
            avgPoolLiquidity = 0.0,
            liquidityChangePct = 0.0,
            poolCount = 0,
            healthScore = 50.0,
            riskLevel = 1,
            entryBoost = 0,
            reason = "No data"
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // THRESHOLD LEARNING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Update thresholds based on historical data.
     * Call periodically to adapt to changing market conditions.
     */
    fun updateThresholds() {
        if (liquidityHistory.size < 50) return
        
        val values = liquidityHistory.map { it.totalLiquidity }.sorted()
        
        // Update percentile-based thresholds
        dryThreshold = values[(values.size * 0.3).toInt()]
        flushThreshold = values[(values.size * 0.7).toInt()]
        euphoriaThreshold = values[(values.size * 0.9).toInt()]
        
        ErrorLogger.debug(TAG, "Updated thresholds: dry=$dryThreshold flush=$flushThreshold euphoria=$euphoriaThreshold")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCORING MODULE INTERFACE
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreComponent {
        val state = getCurrentState()
        
        // Combine market-wide liquidity state with token-specific liquidity
        var boost = state.entryBoost
        
        // Adjust for token's liquidity relative to market
        val tokenLiq = candidate.liquidityUsd
        val avgLiq = state.avgPoolLiquidity
        
        if (avgLiq > 0) {
            val relativeStrength = tokenLiq / avgLiq
            if (relativeStrength > 2.0) boost += 3   // Token has strong liquidity
            else if (relativeStrength < 0.5) boost -= 5  // Token has weak liquidity
        }
        
        return ScoreComponent(
            name = "liquiditycycle",
            value = boost.coerceIn(-15, 12),
            reason = state.reason
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun saveToJson(): JSONObject {
        return JSONObject().apply {
            put("totalAnalyses", totalAnalyses)
            put("dryPeriodsDetected", dryPeriodsDetected)
            put("euphoriaPeriodsDetected", euphoriaPeriodsDetected)
            put("rotationsDetected", rotationsDetected)
            put("dryThreshold", dryThreshold)
            put("flushThreshold", flushThreshold)
            put("euphoriaThreshold", euphoriaThreshold)
        }
    }
    
    fun loadFromJson(json: JSONObject) {
        try {
            totalAnalyses = json.optInt("totalAnalyses", 0)
            dryPeriodsDetected = json.optInt("dryPeriodsDetected", 0)
            euphoriaPeriodsDetected = json.optInt("euphoriaPeriodsDetected", 0)
            rotationsDetected = json.optInt("rotationsDetected", 0)
            dryThreshold = json.optDouble("dryThreshold", 50_000.0)
            flushThreshold = json.optDouble("flushThreshold", 200_000.0)
            euphoriaThreshold = json.optDouble("euphoriaThreshold", 500_000.0)
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Load error: ${e.message}")
        }
    }
    
    fun getStats(): String {
        val state = currentState.get()
        return "LiquidityCycleAI: ${state.phase.name} ${state.pattern.name} | " +
               "health=${state.healthScore.toInt()} risk=${state.riskLevel} | " +
               "dry=$dryPeriodsDetected euphoria=$euphoriaPeriodsDetected rotations=$rotationsDetected"
    }
    
    fun cleanup() {
        // Clean old pool data
        val now = System.currentTimeMillis()
        liquidityHistory.removeAll { now - it.timestamp > 24 * 60 * 60 * 1000L }
    }
}
