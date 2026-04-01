package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * EmergentGuardrails — Safety Guardrails for 30-Day Proof Mode
 * ══════════════════════════════════════════════════════════════════════════════
 * 
 * AATe EMERGENT PATCH PACKAGE - SECTION 6 & 8
 * 
 * Provides safety guardrails during a tracked 30-day run:
 *   - Config change blocking (prevent tampering during proof run)
 *   - Trade rate limiting (prevent runaway execution)
 *   - Aggression freezing (lock behavior AI aggression)
 *   - Pipeline tracing (logging for audit)
 * 
 * CONSTRAINTS:
 *   - DO_NOT_MODIFY_CORE_LOGIC: true
 *   - DO_NOT_CHANGE_THRESHOLDS: true
 *   - ADD_ONLY: true
 */
object EmergentGuardrails {
    
    private const val TAG = "Guardrails"
    
    // ═══════════════════════════════════════════════════════════════════════
    // CONFIG CHANGE BLOCKING
    // Once a 30-day run is started, block config changes to ensure validity
    // ═══════════════════════════════════════════════════════════════════════
    
    @Volatile private var configChangesDisabled: Boolean = false
    
    /**
     * Disable config changes (call when run starts).
     */
    fun disableConfigChanges() {
        configChangesDisabled = true
        ErrorLogger.info(TAG, "🔒 Config changes DISABLED for 30-day proof run")
    }
    
    /**
     * Re-enable config changes (call when run ends).
     */
    fun enableConfigChanges() {
        configChangesDisabled = false
        ErrorLogger.info(TAG, "🔓 Config changes ENABLED")
    }
    
    /**
     * Check if config changes are allowed.
     * Returns false if a 30-day run is active and changes should be blocked.
     */
    fun areConfigChangesAllowed(): Boolean {
        if (RunTracker30D.isRunActive() && configChangesDisabled) {
            return false
        }
        return true
    }
    
    /**
     * Check and log if a config change was attempted during lockout.
     */
    fun checkConfigChange(setting: String): Boolean {
        if (!areConfigChangesAllowed()) {
            ErrorLogger.warn(TAG, "🚫 CONFIG_BLOCKED: Attempted to change '$setting' during 30-day proof run")
            return false
        }
        return true
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // AGGRESSION FREEZE
    // Lock the behavior AI's aggression level during proof runs
    // ═══════════════════════════════════════════════════════════════════════
    
    @Volatile private var frozenAggression: Double? = null
    
    /**
     * Freeze aggression at current level.
     */
    fun freezeAggression(currentAggression: Double) {
        frozenAggression = currentAggression
        ErrorLogger.info(TAG, "🧊 Aggression FROZEN at ${String.format("%.2f", currentAggression)}")
    }
    
    /**
     * Unfreeze aggression.
     */
    fun unfreezeAggression() {
        frozenAggression = null
        ErrorLogger.info(TAG, "🔥 Aggression UNFROZEN")
    }
    
    /**
     * Get the frozen aggression level, or null if not frozen.
     */
    fun getFrozenAggression(): Double? = frozenAggression
    
    /**
     * Check if aggression is frozen.
     */
    fun isAggressionFrozen(): Boolean = frozenAggression != null
    
    // ═══════════════════════════════════════════════════════════════════════
    // TRADE RATE LIMITING (SECTION 8)
    // Prevent runaway execution by limiting trades per minute
    // ═══════════════════════════════════════════════════════════════════════
    
    private val tradeTimestamps = mutableListOf<Long>()
    private const val RATE_LIMIT_WINDOW_MS = 60_000L  // 1 minute window
    private const val DEFAULT_RATE_LIMIT = 10  // trades per minute
    
    @Volatile var rateLimitThreshold: Int = DEFAULT_RATE_LIMIT
    
    /**
     * Record a trade execution for rate limiting.
     */
    fun recordTradeExecution() {
        synchronized(tradeTimestamps) {
            val now = System.currentTimeMillis()
            tradeTimestamps.add(now)
            
            // Clean old timestamps
            val cutoff = now - RATE_LIMIT_WINDOW_MS
            tradeTimestamps.removeAll { it < cutoff }
        }
    }
    
    /**
     * Get the number of trades in the last minute.
     */
    fun getTradesLastMinute(): Int {
        synchronized(tradeTimestamps) {
            val cutoff = System.currentTimeMillis() - RATE_LIMIT_WINDOW_MS
            return tradeTimestamps.count { it >= cutoff }
        }
    }
    
    /**
     * Check if we're at or above the rate limit.
     * Returns true if rate limiting should be applied.
     */
    fun isRateLimited(): Boolean {
        val tradesLastMinute = getTradesLastMinute()
        if (tradesLastMinute >= rateLimitThreshold) {
            ErrorLogger.info(TAG, "[RATE_LIMIT] $tradesLastMinute trades in last minute (threshold=$rateLimitThreshold)")
            return true
        }
        return false
    }
    
    /**
     * Get a size multiplier based on current trade rate.
     * Returns 1.0 normally, or reduced value if approaching rate limit.
     * NOTE: This is logging only unless safe to scale size.
     */
    fun getRateLimitSizeMultiplier(): Double {
        val tradesLastMinute = getTradesLastMinute()
        val ratio = tradesLastMinute.toDouble() / rateLimitThreshold
        
        return when {
            ratio >= 1.0 -> {
                ErrorLogger.info(TAG, "[RATE_LIMIT] At limit - size multiplier: 0.5")
                0.5  // Half size when at limit
            }
            ratio >= 0.8 -> {
                ErrorLogger.debug(TAG, "[RATE_LIMIT] Near limit (${(ratio * 100).toInt()}%) - size multiplier: 0.75")
                0.75  // Reduce size when approaching limit
            }
            else -> 1.0  // Normal size
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // PIPELINE TRACING
    // Logging for audit trail
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Log pipeline trace for a trade decision.
     */
    fun tracePipeline(
        symbol: String,
        stage: String,
        details: String = "",
    ) {
        ErrorLogger.info(TAG, "[PIPELINE] $symbol: $stage${if (details.isNotEmpty()) " → $details" else ""}")
    }
    
    /**
     * Log full pipeline flow.
     */
    fun traceFullPipeline(
        symbol: String,
        received: Boolean,
        strategyResult: String,
        decision: String,
        executed: Boolean,
        result: String,
    ) {
        val flow = buildString {
            append("recv=${if (received) "✓" else "✗"} → ")
            append("strategy=$strategyResult → ")
            append("decision=$decision → ")
            append("exec=${if (executed) "✓" else "✗"} → ")
            append("result=$result")
        }
        ErrorLogger.info(TAG, "[PIPELINE] $symbol: $flow")
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // POSITION REGISTRY (FIX_2 & FIX_3)
    // Track open positions and their layers
    // ═══════════════════════════════════════════════════════════════════════
    
    private val openPositions = ConcurrentHashMap<String, PositionInfo>()
    
    data class PositionInfo(
        val mint: String,
        val symbol: String,
        val layer: String,
        val openedAt: Long,
        val size: Double,
    )
    
    /**
     * Register an open position.
     */
    fun registerPosition(mint: String, symbol: String, layer: String, size: Double) {
        openPositions[mint] = PositionInfo(
            mint = mint,
            symbol = symbol,
            layer = layer,
            openedAt = System.currentTimeMillis(),
            size = size,
        )
        ErrorLogger.debug(TAG, "📍 Position registered: $symbol @ $layer")
    }
    
    /**
     * Unregister a closed position.
     */
    fun unregisterPosition(mint: String) {
        val removed = openPositions.remove(mint)
        if (removed != null) {
            ErrorLogger.debug(TAG, "📍 Position unregistered: ${removed.symbol}")
        }
    }
    
    /**
     * Check if a position is open.
     */
    fun hasOpenPosition(mint: String): Boolean = openPositions.containsKey(mint)
    
    /**
     * Get the layer of an open position.
     */
    fun getPositionLayer(mint: String): String? = openPositions[mint]?.layer
    
    /**
     * FIX_3 — MULTI-LAYER LOCK CHECK
     * Returns true if entry should be blocked due to layer conflict.
     */
    fun shouldBlockMultiLayerEntry(mint: String, requestingLayer: String): Boolean {
        val existingPosition = openPositions[mint] ?: return false
        
        if (existingPosition.layer != requestingLayer) {
            ErrorLogger.info(TAG, "[LAYER_LOCK] ${existingPosition.symbol} | already_active @ ${existingPosition.layer}, requested by $requestingLayer")
            return true
        }
        return false
    }
    
    /**
     * FIX_2 — GHOST PROMOTION CHECK
     * Returns true if promotion should be blocked.
     */
    fun shouldBlockPromotion(mint: String, promotionSize: Double): Boolean {
        val position = openPositions[mint]
        
        // Block if no position exists (ghost)
        if (position == null) {
            ErrorLogger.info(TAG, "[PROMOTION_BLOCKED] $mint | no_active_position (ghost)")
            return true
        }
        
        // Block if position is closed or size is invalid
        if (position.size <= 0.0) {
            ErrorLogger.info(TAG, "[PROMOTION_BLOCKED] $mint | invalid_size: ${position.size}")
            return true
        }
        
        // Block if promotion size is invalid
        if (promotionSize <= 0.0) {
            ErrorLogger.info(TAG, "[PROMOTION_BLOCKED] $mint | invalid_promotion_size: $promotionSize")
            return true
        }
        
        return false
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // RUG LOG FORMATTING (FIX_5)
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * FIX_5 — Format price change percentage for rug detection logs.
     * Ensures consistent formatting and handles near-zero values.
     */
    fun formatPriceChange(priceChangePct: Double): String {
        return if (kotlin.math.abs(priceChangePct) < 0.01) {
            "0.00"
        } else {
            String.format("%.2f", priceChangePct)
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // CLEANUP
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Reset all guardrails state.
     */
    fun reset() {
        configChangesDisabled = false
        frozenAggression = null
        synchronized(tradeTimestamps) {
            tradeTimestamps.clear()
        }
        openPositions.clear()
        ErrorLogger.info(TAG, "🧹 Guardrails reset")
    }
}
