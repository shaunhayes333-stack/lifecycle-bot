package com.lifecyclebot.perps

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 📈 TRAILING STOP LOSS MANAGER - V5.7.5
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Dynamic stop loss that follows price movement to lock in profits.
 * 
 * MODES:
 * ─────────────────────────────────────────────────────────────────────────────
 *   • PERCENTAGE    - Trail by fixed % from high water mark
 *   • ATR           - Trail by Average True Range (volatility-adjusted)
 *   • STEP          - Move SL in steps (e.g., every 5% gain, move SL up 3%)
 *   • BREAKEVEN     - Move SL to entry once profit threshold reached
 * 
 * EXAMPLE (LONG position):
 * ─────────────────────────────────────────────────────────────────────────────
 *   Entry: $100, Initial SL: $95 (5% below)
 *   Price moves to $110 → Trailing SL moves to $104.50 (5% below new high)
 *   Price moves to $120 → Trailing SL moves to $114 (5% below new high)
 *   Price drops to $114 → STOP TRIGGERED, locked in $14 profit!
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object PerpsTrailingStop {
    
    private const val TAG = "📈TrailingStop"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class TrailingMode {
        PERCENTAGE,     // Simple percentage trailing
        ATR,            // Volatility-adjusted (requires ATR calculation)
        STEP,           // Move SL in discrete steps
        BREAKEVEN,      // Move to breakeven after profit threshold
        HYBRID,         // Combination: breakeven first, then trail
    }
    
    data class TrailingConfig(
        val mode: TrailingMode = TrailingMode.HYBRID,
        val trailPct: Double = 5.0,           // Trail by 5% from high
        val activationPct: Double = 3.0,      // Activate trailing after 3% profit
        val breakevenTriggerPct: Double = 5.0, // Move to breakeven after 5% profit
        val stepSize: Double = 5.0,           // Step mode: every 5% gain
        val stepTrailPct: Double = 3.0,       // Step mode: trail 3% per step
        val minProfitLock: Double = 1.0,      // Minimum profit to lock in %
    )
    
    // Default config
    private var globalConfig = TrailingConfig()
    
    // Per-position configs (override global)
    private val positionConfigs = ConcurrentHashMap<String, TrailingConfig>()
    
    // Tracking state per position
    data class TrailState(
        val positionId: String,
        val entryPrice: Double,
        val direction: PerpsDirection,
        var highWaterMark: Double,      // Highest price for LONG, lowest for SHORT
        var currentTrailStop: Double,   // Current trailing stop price
        var isActivated: Boolean = false,  // Has trailing been activated?
        var isBreakeven: Boolean = false,  // Has moved to breakeven?
        var lastUpdateTime: Long = System.currentTimeMillis(),
    )
    
    private val trailStates = ConcurrentHashMap<String, TrailState>()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Initialize trailing stop for a new position
     */
    fun initPosition(
        positionId: String,
        entryPrice: Double,
        direction: PerpsDirection,
        initialStopLoss: Double,
        config: TrailingConfig = globalConfig,
    ) {
        val state = TrailState(
            positionId = positionId,
            entryPrice = entryPrice,
            direction = direction,
            highWaterMark = entryPrice,
            currentTrailStop = initialStopLoss,
        )
        
        trailStates[positionId] = state
        positionConfigs[positionId] = config
        
        ErrorLogger.debug(TAG, "📈 Trailing initialized: $positionId | entry=$entryPrice | SL=$initialStopLoss | mode=${config.mode}")
    }
    
    /**
     * Update trailing stop with new price
     * Returns: new stop loss price (or null if no change)
     */
    fun updatePrice(positionId: String, currentPrice: Double): Double? {
        val state = trailStates[positionId] ?: return null
        val config = positionConfigs[positionId] ?: globalConfig
        
        val oldStop = state.currentTrailStop
        var newStop = oldStop
        
        when (config.mode) {
            TrailingMode.PERCENTAGE -> {
                newStop = calculatePercentageTrail(state, config, currentPrice)
            }
            TrailingMode.STEP -> {
                newStop = calculateStepTrail(state, config, currentPrice)
            }
            TrailingMode.BREAKEVEN -> {
                newStop = calculateBreakevenTrail(state, config, currentPrice)
            }
            TrailingMode.HYBRID -> {
                newStop = calculateHybridTrail(state, config, currentPrice)
            }
            TrailingMode.ATR -> {
                // ATR mode requires external volatility data
                newStop = calculatePercentageTrail(state, config, currentPrice)
            }
        }
        
        // Only update if stop moved in favorable direction
        val improved = when (state.direction) {
            PerpsDirection.LONG -> newStop > oldStop
            PerpsDirection.SHORT -> newStop < oldStop
        }
        
        if (improved) {
            state.currentTrailStop = newStop
            state.lastUpdateTime = System.currentTimeMillis()
            
            val profitLocked = when (state.direction) {
                PerpsDirection.LONG -> ((newStop - state.entryPrice) / state.entryPrice * 100)
                PerpsDirection.SHORT -> ((state.entryPrice - newStop) / state.entryPrice * 100)
            }
            
            ErrorLogger.info(TAG, "📈 TRAIL UPDATED: $positionId | SL: ${"%.2f".format(oldStop)} → ${"%.2f".format(newStop)} | Profit locked: ${"%.1f".format(profitLocked)}%")
            
            return newStop
        }
        
        // Update high water mark even if stop didn't move
        updateHighWaterMark(state, currentPrice)
        
        return null
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TRAILING CALCULATIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun calculatePercentageTrail(state: TrailState, config: TrailingConfig, currentPrice: Double): Double {
        updateHighWaterMark(state, currentPrice)
        
        // Check if trailing should activate
        val currentPnlPct = getPnlPct(state, currentPrice)
        if (currentPnlPct < config.activationPct) {
            return state.currentTrailStop
        }
        
        state.isActivated = true
        
        // Calculate new trailing stop based on high water mark
        return when (state.direction) {
            PerpsDirection.LONG -> state.highWaterMark * (1 - config.trailPct / 100)
            PerpsDirection.SHORT -> state.highWaterMark * (1 + config.trailPct / 100)
        }
    }
    
    private fun calculateStepTrail(state: TrailState, config: TrailingConfig, currentPrice: Double): Double {
        val currentPnlPct = getPnlPct(state, currentPrice)
        
        // Calculate how many steps we've completed
        val stepsCompleted = (currentPnlPct / config.stepSize).toInt()
        
        if (stepsCompleted <= 0) {
            return state.currentTrailStop
        }
        
        state.isActivated = true
        
        // Move stop by stepTrailPct for each completed step
        val totalTrailPct = stepsCompleted * config.stepTrailPct
        
        return when (state.direction) {
            PerpsDirection.LONG -> state.entryPrice * (1 + totalTrailPct / 100)
            PerpsDirection.SHORT -> state.entryPrice * (1 - totalTrailPct / 100)
        }
    }
    
    private fun calculateBreakevenTrail(state: TrailState, config: TrailingConfig, currentPrice: Double): Double {
        val currentPnlPct = getPnlPct(state, currentPrice)
        
        // Move to breakeven (entry price) once threshold reached
        if (currentPnlPct >= config.breakevenTriggerPct && !state.isBreakeven) {
            state.isBreakeven = true
            state.isActivated = true
            ErrorLogger.info(TAG, "🔒 BREAKEVEN ACTIVATED: ${state.positionId} | SL moved to entry")
            return state.entryPrice
        }
        
        return state.currentTrailStop
    }
    
    private fun calculateHybridTrail(state: TrailState, config: TrailingConfig, currentPrice: Double): Double {
        updateHighWaterMark(state, currentPrice)
        val currentPnlPct = getPnlPct(state, currentPrice)
        
        // Phase 1: Move to breakeven first
        if (!state.isBreakeven && currentPnlPct >= config.breakevenTriggerPct) {
            state.isBreakeven = true
            state.isActivated = true
            ErrorLogger.info(TAG, "🔒 HYBRID PHASE 1: Breakeven activated for ${state.positionId}")
            return state.entryPrice
        }
        
        // Phase 2: Start trailing after breakeven
        if (state.isBreakeven && currentPnlPct >= config.activationPct) {
            val trailStop = when (state.direction) {
                PerpsDirection.LONG -> state.highWaterMark * (1 - config.trailPct / 100)
                PerpsDirection.SHORT -> state.highWaterMark * (1 + config.trailPct / 100)
            }
            
            // Only return if better than breakeven
            return when (state.direction) {
                PerpsDirection.LONG -> maxOf(trailStop, state.entryPrice)
                PerpsDirection.SHORT -> minOf(trailStop, state.entryPrice)
            }
        }
        
        return state.currentTrailStop
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun updateHighWaterMark(state: TrailState, currentPrice: Double) {
        state.highWaterMark = when (state.direction) {
            PerpsDirection.LONG -> maxOf(state.highWaterMark, currentPrice)
            PerpsDirection.SHORT -> minOf(state.highWaterMark, currentPrice)
        }
    }
    
    private fun getPnlPct(state: TrailState, currentPrice: Double): Double {
        return when (state.direction) {
            PerpsDirection.LONG -> (currentPrice - state.entryPrice) / state.entryPrice * 100
            PerpsDirection.SHORT -> (state.entryPrice - currentPrice) / state.entryPrice * 100
        }
    }
    
    /**
     * Check if current price has hit trailing stop
     */
    fun isStopHit(positionId: String, currentPrice: Double): Boolean {
        val state = trailStates[positionId] ?: return false
        
        return when (state.direction) {
            PerpsDirection.LONG -> currentPrice <= state.currentTrailStop
            PerpsDirection.SHORT -> currentPrice >= state.currentTrailStop
        }
    }
    
    /**
     * Get current trailing stop price
     */
    fun getTrailStop(positionId: String): Double? {
        return trailStates[positionId]?.currentTrailStop
    }
    
    /**
     * Get trailing state for a position
     */
    fun getState(positionId: String): TrailState? {
        return trailStates[positionId]
    }
    
    /**
     * Remove position from tracking
     */
    fun removePosition(positionId: String) {
        trailStates.remove(positionId)
        positionConfigs.remove(positionId)
    }
    
    /**
     * Set global config
     */
    fun setGlobalConfig(config: TrailingConfig) {
        globalConfig = config
        ErrorLogger.info(TAG, "📈 Global trailing config updated: ${config.mode} | trail=${config.trailPct}%")
    }
    
    /**
     * Get stats
     */
    fun getStats(): Map<String, Any> {
        val activated = trailStates.values.count { it.isActivated }
        val breakeven = trailStates.values.count { it.isBreakeven }
        
        return mapOf(
            "tracked_positions" to trailStates.size,
            "activated" to activated,
            "at_breakeven" to breakeven,
            "global_mode" to globalConfig.mode.name,
        )
    }
}
