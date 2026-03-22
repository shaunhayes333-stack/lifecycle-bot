package com.lifecyclebot.engine

/**
 * TradeStateMachine — Structured trade lifecycle management
 * ═══════════════════════════════════════════════════════════════════════
 * 
 * States:
 * SCAN       → Looking for opportunities
 * VALIDATE   → Anti-rug checks, safety validation
 * WATCH      → Building entry signal, waiting for pattern
 * ENTER      → Executing buy
 * MONITOR    → Tracking position, looking for exit signals
 * SCALE      → Adding to winning position (optional)
 * EXIT       → Executing sell
 * COOLDOWN   → Post-trade cooldown before re-entry
 */
enum class TradeState {
    SCAN,       // Looking for tokens
    VALIDATE,   // Running safety checks
    WATCH,      // Building signal, waiting for entry pattern
    ENTER,      // Executing buy
    MONITOR,    // Watching open position
    SCALE,      // Scaling into winner
    EXIT,       // Executing sell
    COOLDOWN,   // Post-trade cooldown
}

/**
 * Entry pattern detection for better timing
 */
enum class EntryPattern {
    NONE,                    // No pattern detected
    FIRST_SPIKE,             // Initial pump detected
    PULLBACK,                // Healthy pullback after spike
    RE_ACCELERATION,         // Second leg up - optimal entry
    FOMO_SPIKE,              // Parabolic - too late, skip
}

/**
 * TokenTradeState — Per-token state tracking
 */
data class TokenTradeState(
    val mint: String,
    var state: TradeState = TradeState.SCAN,
    var entryPattern: EntryPattern = EntryPattern.NONE,
    var stateEnteredAt: Long = System.currentTimeMillis(),
    var validationPassed: Boolean = false,
    var watchStartPrice: Double = 0.0,
    var spikeHighPrice: Double = 0.0,
    var pullbackLowPrice: Double = 0.0,
    var lastPriceCheck: Double = 0.0,
    var lastPriceCheckTime: Long = 0,
    var consecutiveDrops: Int = 0,
    var cooldownUntil: Long = 0,
)

/**
 * TradeStateMachine — Manages state transitions for all tokens
 */
object TradeStateMachine {
    
    private val tokenStates = java.util.concurrent.ConcurrentHashMap<String, TokenTradeState>()
    private const val COOLDOWN_MS = 5 * 60_000L  // 5 minute cooldown after exit
    private const val WATCH_TIMEOUT_MS = 3 * 60_000L  // 3 minutes max in WATCH state
    
    // Entry pattern thresholds
    private const val SPIKE_THRESHOLD_PCT = 5.0      // 5% rise = spike detected
    private const val PULLBACK_THRESHOLD_PCT = 3.0   // 3% drop from spike = pullback
    private const val REACCEL_THRESHOLD_PCT = 2.0    // 2% rise from pullback = re-acceleration
    
    fun getState(mint: String): TokenTradeState {
        return tokenStates.getOrPut(mint) { TokenTradeState(mint) }
    }
    
    fun setState(mint: String, newState: TradeState, reason: String = "") {
        val ts = getState(mint)
        val oldState = ts.state
        ts.state = newState
        ts.stateEnteredAt = System.currentTimeMillis()
        
        if (oldState != newState) {
            ErrorLogger.info("StateMachine", "🔄 ${mint.take(8)}: $oldState → $newState ${if(reason.isNotBlank()) "($reason)" else ""}")
        }
    }
    
    /**
     * Check if token is in cooldown
     */
    fun isInCooldown(mint: String): Boolean {
        val ts = getState(mint)
        return ts.state == TradeState.COOLDOWN && System.currentTimeMillis() < ts.cooldownUntil
    }
    
    /**
     * Start cooldown after exit
     */
    fun startCooldown(mint: String) {
        val ts = getState(mint)
        ts.state = TradeState.COOLDOWN
        ts.cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
        ts.entryPattern = EntryPattern.NONE
        ts.spikeHighPrice = 0.0
        ts.pullbackLowPrice = 0.0
        ErrorLogger.info("StateMachine", "⏸️ ${mint.take(8)}: Cooldown for ${COOLDOWN_MS/1000}s")
    }
    
    /**
     * Clear cooldown for dynamic re-entry
     */
    fun clearCooldown(mint: String) {
        val ts = getState(mint)
        ts.state = TradeState.WATCH
        ts.cooldownUntil = 0
        ts.entryPattern = EntryPattern.NONE
        ErrorLogger.info("StateMachine", "🔄 ${mint.take(8)}: Cooldown cleared for re-entry")
    }
    
    /**
     * Detect entry pattern: SPIKE → PULLBACK → RE_ACCELERATION
     * Returns true if optimal entry point detected
     */
    fun detectEntryPattern(mint: String, currentPrice: Double, priceHistory: List<Double>): Boolean {
        val ts = getState(mint)
        
        if (ts.state != TradeState.WATCH) return false
        if (priceHistory.size < 5) return false
        
        val now = System.currentTimeMillis()
        
        // Initialize watch price
        if (ts.watchStartPrice == 0.0) {
            ts.watchStartPrice = priceHistory.first()
        }
        
        // Check for WATCH timeout
        if (now - ts.stateEnteredAt > WATCH_TIMEOUT_MS) {
            setState(mint, TradeState.SCAN, "watch timeout")
            return false
        }
        
        val pctFromWatch = if (ts.watchStartPrice > 0) {
            ((currentPrice - ts.watchStartPrice) / ts.watchStartPrice) * 100
        } else 0.0
        
        when (ts.entryPattern) {
            EntryPattern.NONE -> {
                // Looking for first spike
                if (pctFromWatch >= SPIKE_THRESHOLD_PCT) {
                    ts.entryPattern = EntryPattern.FIRST_SPIKE
                    ts.spikeHighPrice = currentPrice
                    ErrorLogger.info("StateMachine", "📈 ${mint.take(8)}: SPIKE detected +${pctFromWatch.toInt()}%")
                }
            }
            
            EntryPattern.FIRST_SPIKE -> {
                // Update spike high
                if (currentPrice > ts.spikeHighPrice) {
                    ts.spikeHighPrice = currentPrice
                }
                
                // Check for pullback
                val pctFromHigh = if (ts.spikeHighPrice > 0) {
                    ((ts.spikeHighPrice - currentPrice) / ts.spikeHighPrice) * 100
                } else 0.0
                
                if (pctFromHigh >= PULLBACK_THRESHOLD_PCT) {
                    ts.entryPattern = EntryPattern.PULLBACK
                    ts.pullbackLowPrice = currentPrice
                    ErrorLogger.info("StateMachine", "📉 ${mint.take(8)}: PULLBACK detected -${pctFromHigh.toInt()}%")
                }
                
                // Check for FOMO spike (too aggressive, skip)
                if (pctFromWatch >= 20.0) {
                    ts.entryPattern = EntryPattern.FOMO_SPIKE
                    ErrorLogger.info("StateMachine", "🚫 ${mint.take(8)}: FOMO spike - too late to enter")
                    setState(mint, TradeState.SCAN, "fomo spike")
                    return false
                }
            }
            
            EntryPattern.PULLBACK -> {
                // Update pullback low
                if (currentPrice < ts.pullbackLowPrice) {
                    ts.pullbackLowPrice = currentPrice
                }
                
                // Check for re-acceleration
                val pctFromLow = if (ts.pullbackLowPrice > 0) {
                    ((currentPrice - ts.pullbackLowPrice) / ts.pullbackLowPrice) * 100
                } else 0.0
                
                if (pctFromLow >= REACCEL_THRESHOLD_PCT) {
                    ts.entryPattern = EntryPattern.RE_ACCELERATION
                    ErrorLogger.info("StateMachine", "🚀 ${mint.take(8)}: RE-ACCELERATION +${pctFromLow.toInt()}% - OPTIMAL ENTRY!")
                    return true  // Optimal entry point!
                }
                
                // Check if pullback went too deep (potential dump)
                val totalDrop = if (ts.spikeHighPrice > 0) {
                    ((ts.spikeHighPrice - currentPrice) / ts.spikeHighPrice) * 100
                } else 0.0
                
                if (totalDrop >= 15.0) {
                    ErrorLogger.info("StateMachine", "💀 ${mint.take(8)}: Pullback too deep -${totalDrop.toInt()}%, aborting")
                    setState(mint, TradeState.SCAN, "deep pullback")
                    return false
                }
            }
            
            EntryPattern.RE_ACCELERATION -> {
                return true  // Already at optimal entry
            }
            
            EntryPattern.FOMO_SPIKE -> {
                return false  // Too late
            }
        }
        
        return false
    }
    
    /**
     * Fast rug detection - price drop monitoring
     * Returns true if fast rug detected (>8% in <10 sec)
     */
    fun checkFastRug(mint: String, currentPrice: Double): Boolean {
        val ts = getState(mint)
        val now = System.currentTimeMillis()
        
        if (ts.lastPriceCheck > 0 && ts.lastPriceCheckTime > 0) {
            val timeDelta = now - ts.lastPriceCheckTime
            val priceDelta = ((ts.lastPriceCheck - currentPrice) / ts.lastPriceCheck) * 100
            
            // Fast rug: >8% drop in <10 seconds
            if (timeDelta < 10_000 && priceDelta >= 8.0) {
                ErrorLogger.warn("StateMachine", "🚨 FAST RUG: ${mint.take(8)} dropped ${priceDelta.toInt()}% in ${timeDelta/1000}s")
                return true
            }
            
            // Track consecutive drops
            if (priceDelta > 1.0) {
                ts.consecutiveDrops++
            } else {
                ts.consecutiveDrops = 0
            }
        }
        
        ts.lastPriceCheck = currentPrice
        ts.lastPriceCheckTime = now
        
        return false
    }
    
    /**
     * Clear state for a token
     */
    fun clearState(mint: String) {
        tokenStates.remove(mint)
    }
    
    /**
     * Get stats
     */
    fun getStats(): String {
        val byState = tokenStates.values.groupBy { it.state }
        return byState.entries.joinToString(", ") { "${it.key}: ${it.value.size}" }
    }
}
