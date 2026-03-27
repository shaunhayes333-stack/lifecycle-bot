package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.util.ErrorLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/**
 * HoldingLogicLayer - Dynamic position management and mode switching.
 * 
 * This layer evaluates held positions in real-time and can:
 *   1. Switch trading modes based on evolving market conditions
 *   2. Recommend hold/sell actions based on mode-specific logic
 *   3. Adjust profit targets and stop losses dynamically
 *   4. Track position health across all trading modes
 * 
 * MODE SWITCHING LOGIC:
 *   - PUMP_SNIPER → MOMENTUM_SWING: If pump stabilizes with strong trend
 *   - MOONSHOT → LONG_HOLD: If fundamentals improve significantly
 *   - SCALP → MOMENTUM_SWING: If quick profit turns into sustained move
 *   - Any → BLUE_CHIP: If token reaches "established" status
 *   - Any → LIQUIDATION_HUNTER: If position underwater but recovery signals
 */
object HoldingLogicLayer {
    
    private const val TAG = "HoldingLogic"
    private const val PREFS_NAME = "holding_logic_prefs"
    
    private lateinit var prefs: SharedPreferences
    private val mutex = Mutex()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MODE SWITCH RECOMMENDATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class ModeSwitchRecommendation(
        val shouldSwitch: Boolean,
        val newMode: String,
        val newModeEmoji: String,
        val reason: String,
        val confidence: Double,  // 0-100
        val newTargetPct: Double? = null,  // New profit target if switching
        val newStopPct: Double? = null,    // New stop loss if switching
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HOLD EVALUATION RESULT
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class HoldEvaluation(
        val action: HoldAction,
        val reason: String,
        val confidence: Double,  // 0-100
        val modeSwitchRecommendation: ModeSwitchRecommendation? = null,
        val adjustedTargetPct: Double? = null,
        val adjustedStopPct: Double? = null,
        val urgency: Urgency = Urgency.NORMAL,
    )
    
    enum class HoldAction {
        HOLD,           // Continue holding
        HOLD_TIGHTER,   // Hold but tighten stops
        SCALE_OUT,      // Partial exit recommended
        EXIT_NOW,       // Full exit recommended
        ADD_MORE,       // Conditions favor adding to position
        SWITCH_MODE,    // Change trading mode for this position
    }
    
    enum class Urgency {
        LOW,        // Can wait for better exit
        NORMAL,     // Standard evaluation
        HIGH,       // Should act soon
        CRITICAL,   // Act immediately
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MODE-SPECIFIC HOLD PARAMETERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class ModeHoldParams(
        val mode: String,
        val targetProfitPct: Double,    // Default profit target
        val stopLossPct: Double,        // Default stop loss
        val trailingStopPct: Double,    // Trailing stop after profit
        val maxHoldTimeMs: Long,        // Maximum hold time
        val allowModeSwitch: Boolean,   // Can switch to other modes
        val scaleOutAt: List<Double>,   // PnL % levels to scale out (e.g., [50, 100, 200])
    )
    
    private val MODE_PARAMS = mapOf(
        "STANDARD" to ModeHoldParams("STANDARD", 30.0, -15.0, 8.0, 4 * 60 * 60 * 1000L, true, listOf(30.0, 60.0)),
        "MOONSHOT" to ModeHoldParams("MOONSHOT", 200.0, -25.0, 15.0, 24 * 60 * 60 * 1000L, true, listOf(100.0, 300.0, 500.0)),
        "PUMP_SNIPER" to ModeHoldParams("PUMP_SNIPER", 50.0, -20.0, 10.0, 30 * 60 * 1000L, true, listOf(25.0, 50.0)),
        "COPY_TRADE" to ModeHoldParams("COPY_TRADE", 40.0, -15.0, 8.0, 2 * 60 * 60 * 1000L, true, listOf(25.0, 50.0)),
        "LONG_HOLD" to ModeHoldParams("LONG_HOLD", 500.0, -30.0, 20.0, 7 * 24 * 60 * 60 * 1000L, false, listOf(100.0, 250.0, 500.0)),
        "BLUE_CHIP" to ModeHoldParams("BLUE_CHIP", 100.0, -10.0, 5.0, 30 * 24 * 60 * 60 * 1000L, false, listOf(50.0, 100.0)),
        "CYCLIC" to ModeHoldParams("CYCLIC", 25.0, -12.0, 6.0, 60 * 60 * 1000L, true, listOf(15.0, 25.0)),
        "SLEEPER" to ModeHoldParams("SLEEPER", 300.0, -35.0, 20.0, 48 * 60 * 60 * 1000L, true, listOf(100.0, 200.0, 400.0)),
        "NICHE" to ModeHoldParams("NICHE", 150.0, -25.0, 12.0, 8 * 60 * 60 * 1000L, true, listOf(75.0, 150.0)),
        "PRESALE_SNIPE" to ModeHoldParams("PRESALE_SNIPE", 100.0, -30.0, 15.0, 10 * 60 * 1000L, true, listOf(50.0, 100.0)),
        "ARBITRAGE" to ModeHoldParams("ARBITRAGE", 5.0, -2.0, 1.0, 5 * 60 * 1000L, false, listOf(3.0, 5.0)),
        "MOMENTUM_SWING" to ModeHoldParams("MOMENTUM_SWING", 60.0, -18.0, 10.0, 3 * 60 * 60 * 1000L, true, listOf(30.0, 60.0, 100.0)),
        "MICRO_CAP" to ModeHoldParams("MICRO_CAP", 300.0, -40.0, 25.0, 12 * 60 * 60 * 1000L, true, listOf(100.0, 200.0, 500.0)),
        "REVIVAL" to ModeHoldParams("REVIVAL", 200.0, -35.0, 18.0, 6 * 60 * 60 * 1000L, true, listOf(100.0, 200.0)),
        "WHALE_FOLLOW" to ModeHoldParams("WHALE_FOLLOW", 50.0, -15.0, 8.0, 2 * 60 * 60 * 1000L, true, listOf(25.0, 50.0)),
        "PUMP_DUMP" to ModeHoldParams("PUMP_DUMP", 40.0, -20.0, 8.0, 15 * 60 * 1000L, false, listOf(20.0, 40.0)),
        "MARKET_MAKER" to ModeHoldParams("MARKET_MAKER", 8.0, -5.0, 3.0, 30 * 60 * 1000L, false, listOf(5.0, 8.0)),
        "LIQUIDATION_HUNTER" to ModeHoldParams("LIQUIDATION_HUNTER", 80.0, -25.0, 12.0, 4 * 60 * 60 * 1000L, true, listOf(40.0, 80.0)),
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ErrorLogger.info(TAG, "HoldingLogicLayer initialized with ${MODE_PARAMS.size} mode configurations")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN EVALUATION FUNCTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Evaluate a held position and recommend actions.
     * 
     * @param position The current position
     * @param ts Current TokenState with live market data
     * @param currentPnlPct Current profit/loss percentage
     * @param isPaperMode Whether this is paper trading
     * @return HoldEvaluation with recommended action
     */
    suspend fun evaluatePosition(
        position: Position,
        ts: TokenState,
        currentPnlPct: Double,
        isPaperMode: Boolean,
    ): HoldEvaluation = mutex.withLock {
        try {
            val mode = position.tradingMode
            val params = MODE_PARAMS[mode] ?: MODE_PARAMS["STANDARD"]!!
            
            val holdTimeMs = System.currentTimeMillis() - position.entryTime
            val holdTimeMinutes = holdTimeMs / (60 * 1000)
            
            // ─────────────────────────────────────────────────────────────────
            // 1. CHECK FOR CRITICAL CONDITIONS (exit immediately)
            // ─────────────────────────────────────────────────────────────────
            
            // Stop loss hit
            if (currentPnlPct <= params.stopLossPct) {
                return@withLock HoldEvaluation(
                    action = HoldAction.EXIT_NOW,
                    reason = "Stop loss triggered: ${currentPnlPct.toInt()}% <= ${params.stopLossPct.toInt()}%",
                    confidence = 95.0,
                    urgency = Urgency.CRITICAL,
                )
            }
            
            // Trailing stop (after profit achieved)
            if (position.peakGainPct > params.targetProfitPct * 0.5) {
                val trailingStop = position.peakGainPct - params.trailingStopPct
                if (currentPnlPct < trailingStop) {
                    return@withLock HoldEvaluation(
                        action = HoldAction.EXIT_NOW,
                        reason = "Trailing stop: ${currentPnlPct.toInt()}% < peak-trail (${position.peakGainPct.toInt()}%-${params.trailingStopPct.toInt()}%)",
                        confidence = 90.0,
                        urgency = Urgency.HIGH,
                    )
                }
            }
            
            // Max hold time exceeded
            if (holdTimeMs > params.maxHoldTimeMs) {
                return@withLock HoldEvaluation(
                    action = HoldAction.EXIT_NOW,
                    reason = "Max hold time exceeded: ${holdTimeMinutes}min > ${params.maxHoldTimeMs / 60000}min",
                    confidence = 75.0,
                    urgency = Urgency.HIGH,
                )
            }
            
            // ─────────────────────────────────────────────────────────────────
            // 2. CHECK FOR MODE SWITCH OPPORTUNITY
            // ─────────────────────────────────────────────────────────────────
            
            val modeSwitchRec = if (params.allowModeSwitch) {
                evaluateModeSwitchOpportunity(position, ts, currentPnlPct, holdTimeMs)
            } else null
            
            if (modeSwitchRec?.shouldSwitch == true && modeSwitchRec.confidence >= 70.0) {
                return@withLock HoldEvaluation(
                    action = HoldAction.SWITCH_MODE,
                    reason = "Mode switch recommended: ${position.tradingMode} → ${modeSwitchRec.newMode}",
                    confidence = modeSwitchRec.confidence,
                    modeSwitchRecommendation = modeSwitchRec,
                    urgency = Urgency.NORMAL,
                )
            }
            
            // ─────────────────────────────────────────────────────────────────
            // 3. CHECK FOR SCALE-OUT OPPORTUNITY
            // ─────────────────────────────────────────────────────────────────
            
            for (scaleOutLevel in params.scaleOutAt) {
                if (currentPnlPct >= scaleOutLevel && position.partialSoldPct < scaleOutLevel) {
                    return@withLock HoldEvaluation(
                        action = HoldAction.SCALE_OUT,
                        reason = "Scale-out target hit: ${currentPnlPct.toInt()}% >= ${scaleOutLevel.toInt()}%",
                        confidence = 80.0,
                        urgency = Urgency.NORMAL,
                    )
                }
            }
            
            // ─────────────────────────────────────────────────────────────────
            // 4. CHECK FOR ADD-MORE OPPORTUNITY
            // ─────────────────────────────────────────────────────────────────
            
            val canAddMore = isPaperMode || !position.isFullyBuilt
            if (canAddMore && currentPnlPct > 5.0 && currentPnlPct < params.targetProfitPct * 0.3) {
                // Token is slightly profitable and momentum is building
                val hasGoodMomentum = ts.meta.momScore > 60 && ts.meta.volScore > 50
                if (hasGoodMomentum && holdTimeMinutes > 2) {
                    return@withLock HoldEvaluation(
                        action = HoldAction.ADD_MORE,
                        reason = "Confirmed move with momentum (pnl=${currentPnlPct.toInt()}%, mom=${ts.meta.momScore.toInt()})",
                        confidence = 65.0,
                        urgency = Urgency.LOW,
                    )
                }
            }
            
            // ─────────────────────────────────────────────────────────────────
            // 5. TIGHTEN STOPS IF IN PROFIT
            // ─────────────────────────────────────────────────────────────────
            
            if (currentPnlPct > params.targetProfitPct * 0.7) {
                val tighterStop = currentPnlPct - (params.trailingStopPct * 0.6)
                return@withLock HoldEvaluation(
                    action = HoldAction.HOLD_TIGHTER,
                    reason = "Near target, tightening stop to ${tighterStop.toInt()}%",
                    confidence = 75.0,
                    adjustedStopPct = tighterStop,
                    urgency = Urgency.NORMAL,
                )
            }
            
            // ─────────────────────────────────────────────────────────────────
            // 6. DEFAULT: CONTINUE HOLDING
            // ─────────────────────────────────────────────────────────────────
            
            return@withLock HoldEvaluation(
                action = HoldAction.HOLD,
                reason = "Holding: pnl=${currentPnlPct.toInt()}%, target=${params.targetProfitPct.toInt()}%, time=${holdTimeMinutes}min",
                confidence = 70.0,
                modeSwitchRecommendation = modeSwitchRec,  // Include even if not acting on it
                urgency = Urgency.NORMAL,
            )
            
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Error evaluating position: ${e.message}")
            return@withLock HoldEvaluation(
                action = HoldAction.HOLD,
                reason = "Evaluation error, defaulting to hold",
                confidence = 50.0,
                urgency = Urgency.NORMAL,
            )
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MODE SWITCH EVALUATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun evaluateModeSwitchOpportunity(
        position: Position,
        ts: TokenState,
        currentPnlPct: Double,
        holdTimeMs: Long,
    ): ModeSwitchRecommendation? {
        val currentMode = position.tradingMode
        val holdMinutes = holdTimeMs / (60 * 1000)
        
        // ─────────────────────────────────────────────────────────────────
        // PUMP_SNIPER → MOMENTUM_SWING
        // If quick pump stabilizes into sustained trend, switch to ride it longer
        // ─────────────────────────────────────────────────────────────────
        if (currentMode == "PUMP_SNIPER") {
            val hasStableTrend = ts.meta.emafanAlignment in listOf("BULL_FAN", "BULL_FLAT")
            val pumpStabilized = holdMinutes > 5 && currentPnlPct in 10.0..40.0
            val goodMomentum = ts.meta.momScore > 55
            
            if (hasStableTrend && pumpStabilized && goodMomentum) {
                return ModeSwitchRecommendation(
                    shouldSwitch = true,
                    newMode = "MOMENTUM_SWING",
                    newModeEmoji = "🌊",
                    reason = "Pump stabilized into trend (${ts.meta.emafanAlignment})",
                    confidence = 72.0,
                    newTargetPct = 80.0,
                    newStopPct = currentPnlPct - 15.0,  // Protect current gains
                )
            }
        }
        
        // ─────────────────────────────────────────────────────────────────
        // MOONSHOT → LONG_HOLD
        // If moonshot gains significant value and fundamentals are strong
        // ─────────────────────────────────────────────────────────────────
        if (currentMode == "MOONSHOT") {
            val bigGains = currentPnlPct > 150.0
            val strongLiquidity = ts.lastLiquidityUsd > 50000
            val healthyVolume = ts.meta.volScore > 50
            val notPumping = !ts.meta.spikeDetected
            
            if (bigGains && strongLiquidity && healthyVolume && notPumping) {
                return ModeSwitchRecommendation(
                    shouldSwitch = true,
                    newMode = "LONG_HOLD",
                    newModeEmoji = "💎",
                    reason = "Moonshot maturing into conviction hold (liq=$${ts.lastLiquidityUsd.toInt()})",
                    confidence = 75.0,
                    newTargetPct = 500.0,
                    newStopPct = currentPnlPct * 0.6,  // Lock in most of gains
                )
            }
        }
        
        // ─────────────────────────────────────────────────────────────────
        // Any → BLUE_CHIP
        // If token reaches "established" status (high liquidity, stable)
        // ─────────────────────────────────────────────────────────────────
        if (currentMode !in listOf("BLUE_CHIP", "LONG_HOLD")) {
            val isEstablished = ts.lastLiquidityUsd > 200000 && ts.lastMcap > 1000000
            val isStable = ts.meta.emafanAlignment == "FLAT" && ts.meta.volScore in 30.0..70.0
            val hasHeldLong = holdMinutes > 60
            
            if (isEstablished && isStable && hasHeldLong) {
                return ModeSwitchRecommendation(
                    shouldSwitch = true,
                    newMode = "BLUE_CHIP",
                    newModeEmoji = "🔵",
                    reason = "Token established (mcap=$${(ts.lastMcap/1000).toInt()}k, liq=$${(ts.lastLiquidityUsd/1000).toInt()}k)",
                    confidence = 70.0,
                    newTargetPct = 100.0,
                    newStopPct = -10.0,
                )
            }
        }
        
        // ─────────────────────────────────────────────────────────────────
        // Underwater positions → LIQUIDATION_HUNTER
        // If position is down but showing recovery signals
        // ─────────────────────────────────────────────────────────────────
        if (currentPnlPct < -10.0 && currentPnlPct > -25.0) {
            val showingRecovery = ts.meta.momScore > 55 && ts.meta.volScore > 60
            val liquidityStable = ts.lastLiquidityUsd > position.entryLiquidityUsd * 0.8
            val notInFreefall = ts.meta.emafanAlignment != "BEAR_FAN"
            
            if (showingRecovery && liquidityStable && notInFreefall) {
                return ModeSwitchRecommendation(
                    shouldSwitch = true,
                    newMode = "LIQUIDATION_HUNTER",
                    newModeEmoji = "🦅",
                    reason = "Recovery signals detected (mom=${ts.meta.momScore.toInt()})",
                    confidence = 65.0,
                    newTargetPct = 20.0,  // Modest target for recovery
                    newStopPct = -35.0,   // Wider stop for recovery attempt
                )
            }
        }
        
        // ─────────────────────────────────────────────────────────────────
        // STANDARD → CYCLIC
        // If price is showing clear cyclic/range-bound pattern
        // ─────────────────────────────────────────────────────────────────
        if (currentMode == "STANDARD") {
            val inRange = ts.meta.rangePct in 5.0..20.0
            val midRange = ts.meta.posInRange in 30.0..70.0
            val notTrending = ts.meta.emafanAlignment == "FLAT"
            
            if (inRange && midRange && notTrending && holdMinutes > 10) {
                return ModeSwitchRecommendation(
                    shouldSwitch = true,
                    newMode = "CYCLIC",
                    newModeEmoji = "♻️",
                    reason = "Range-bound pattern detected (range=${ts.meta.rangePct.toInt()}%)",
                    confidence = 68.0,
                    newTargetPct = ts.meta.rangePct * 0.7,  // Target upper range
                    newStopPct = -(ts.meta.rangePct * 0.5), // Stop at lower range
                )
            }
        }
        
        return null  // No switch recommended
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER FUNCTIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get hold parameters for a specific mode.
     */
    fun getHoldParams(mode: String): ModeHoldParams {
        return MODE_PARAMS[mode] ?: MODE_PARAMS["STANDARD"]!!
    }
    
    /**
     * Get all available modes with their parameters.
     */
    fun getAllModeParams(): Map<String, ModeHoldParams> = MODE_PARAMS
    
    /**
     * Check if a position should consider mode switch based on time alone.
     */
    fun shouldCheckModeSwitch(position: Position): Boolean {
        val params = MODE_PARAMS[position.tradingMode] ?: return false
        return params.allowModeSwitch
    }
    
    /**
     * Get recommended initial mode based on token characteristics.
     */
    fun recommendInitialMode(
        liquidity: Double,
        mcap: Double,
        age: Long,
        volScore: Double,
        source: String,
    ): String {
        return when {
            // Ultra new token
            age < 5 * 60 * 1000 && source.contains("PUMP", ignoreCase = true) -> "PRESALE_SNIPE"
            age < 10 * 60 * 1000 && volScore > 80 -> "PUMP_SNIPER"
            
            // Micro cap plays
            mcap < 10000 && liquidity < 5000 -> "MICRO_CAP"
            
            // Dormant revivals
            volScore > 70 && source.contains("SLEEPER", ignoreCase = true) -> "SLEEPER"
            
            // Recovery plays
            source.contains("REVIVAL", ignoreCase = true) -> "REVIVAL"
            
            // Established tokens
            liquidity > 200000 && mcap > 1000000 -> "BLUE_CHIP"
            liquidity > 50000 -> "LONG_HOLD"
            
            // Trending with momentum
            volScore > 60 -> "MOMENTUM_SWING"
            
            // Default
            else -> "STANDARD"
        }
    }
    
    /**
     * Get emoji for a trading mode.
     */
    fun getModeEmoji(mode: String): String {
        return when (mode) {
            "STANDARD" -> "📈"
            "MOONSHOT" -> "🚀"
            "PUMP_SNIPER" -> "🔫"
            "COPY_TRADE" -> "🦊"
            "LONG_HOLD" -> "💎"
            "BLUE_CHIP" -> "🔵"
            "CYCLIC" -> "♻️"
            "SLEEPER" -> "💤"
            "NICHE" -> "🧬"
            "PRESALE_SNIPE" -> "🎯"
            "ARBITRAGE" -> "⚡"
            "MOMENTUM_SWING" -> "🌊"
            "MICRO_CAP" -> "🔬"
            "REVIVAL" -> "🔥"
            "WHALE_FOLLOW" -> "🐋"
            "PUMP_DUMP" -> "💣"
            "MARKET_MAKER" -> "🏛️"
            "LIQUIDATION_HUNTER" -> "🦅"
            else -> "📈"
        }
    }
}
