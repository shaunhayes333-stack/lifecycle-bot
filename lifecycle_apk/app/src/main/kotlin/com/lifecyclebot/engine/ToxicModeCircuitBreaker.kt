package com.lifecyclebot.engine

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ToxicModeCircuitBreaker - Emergency kill switch for catastrophic loss patterns
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * CRITICAL SAFETY SYSTEM
 * 
 * After analysis of catastrophic losses (-36% 52-HERTZ, -92% GROKCHAIN), this
 * circuit breaker implements hard blocks on toxic trading patterns.
 * 
 * DISABLED MODES:
 * - COPY_TRADE: Completely disabled until reworked
 * - WHALE_FOLLOW: Disabled below $15K liquidity
 * 
 * LIQUIDITY FLOORS:
 * - COPY_TRADE: DISABLED (no entries allowed)
 * - WHALE_FOLLOW: $15,000 minimum
 * - MOMENTUM: $10,000 minimum
 * - FRESH_LAUNCH/NEW_POOL: $12,000 minimum
 * 
 * CIRCUIT BREAKERS:
 * - Any loss > 40%: Freeze that mode for 12 hours
 * - 2 losses > 20% in a mode: Freeze that mode for 6 hours
 * - 3 losses > 15% in a mode: Freeze that mode for 3 hours
 * 
 * COLLAPSE DETECTION:
 * - Liquidity collapse + copy invalidation = IMMEDIATE FULL EXIT
 */
object ToxicModeCircuitBreaker {
    
    private const val TAG = "CircuitBreaker"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HARD DISABLED MODES
    // These modes are completely disabled - no entries allowed
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val HARD_DISABLED_MODES = setOf(
        "COPY_TRADE",
        "COPY",
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LIQUIDITY FLOORS BY MODE
    // No entry below these thresholds - no exceptions
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val LIQUIDITY_FLOORS = mapOf(
        "COPY_TRADE" to Double.MAX_VALUE,      // Effectively disabled
        "COPY" to Double.MAX_VALUE,            // Effectively disabled
        "WHALE_FOLLOW" to 15_000.0,            // $15k minimum
        "WHALE_ACCUMULATION" to 15_000.0,      // $15k minimum
        "MOMENTUM" to 10_000.0,                // $10k minimum
        "MOMENTUM_SWING" to 10_000.0,          // $10k minimum
        "FRESH_LAUNCH" to 12_000.0,            // $12k minimum
        "PRESALE_SNIPE" to 15_000.0,           // $15k minimum (highest risk)
        "SENTIMENT_IGNITION" to 10_000.0,      // $10k minimum
        "DEFAULT" to 8_000.0,                  // Absolute floor for any mode
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SOURCE RESTRICTIONS
    // Dangerous source + mode combinations
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val BLOCKED_SOURCE_MODE_COMBOS = setOf(
        "RAYDIUM_NEW_POOL:COPY_TRADE",
        "RAYDIUM_NEW_POOL:COPY",
        "RAYDIUM_NEW_POOL:WHALE_FOLLOW",
        "PUMP_GRADUATE:COPY_TRADE",
        "PUMP_GRADUATE:COPY",
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CIRCUIT BREAKER STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Mode -> freeze end timestamp
    private val frozenModes = ConcurrentHashMap<String, Long>()
    
    // Mode -> list of recent loss percentages with timestamps
    private data class LossRecord(val pnlPct: Double, val timestamp: Long)
    private val recentLosses = ConcurrentHashMap<String, MutableList<LossRecord>>()
    
    // Global emergency stop
    private val emergencyStop = AtomicBoolean(false)
    
    // Stats
    @Volatile private var blockedEntries = 0
    @Volatile private var circuitTrips = 0
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN ENTRY GATE
    // Call this BEFORE any trade entry decision
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Check if entry is allowed for a given mode and conditions.
     * Returns null if allowed, or rejection reason string if blocked.
     */
    fun checkEntryAllowed(
        mode: String,
        source: String,
        liquidityUsd: Double,
        phase: String,
        memoryScore: Int,
        isAIDegraded: Boolean,
        confidence: Int
    ): String? {
        
        // 1. Emergency stop
        if (emergencyStop.get()) {
            blockedEntries++
            return "EMERGENCY_STOP_ACTIVE"
        }
        
        // 2. Hard disabled modes
        val modeUpper = mode.uppercase()
        if (modeUpper in HARD_DISABLED_MODES) {
            blockedEntries++
            Log.w(TAG, "🚫 BLOCKED: $mode is HARD DISABLED (toxic loss pattern)")
            return "MODE_HARD_DISABLED"
        }
        
        // 3. Frozen modes (circuit breaker tripped)
        val freezeEnd = frozenModes[modeUpper]
        if (freezeEnd != null && System.currentTimeMillis() < freezeEnd) {
            blockedEntries++
            val remainingMins = (freezeEnd - System.currentTimeMillis()) / 60_000
            Log.w(TAG, "🚫 BLOCKED: $mode frozen for ${remainingMins}min (circuit breaker)")
            return "MODE_FROZEN_${remainingMins}MIN"
        }
        
        // 4. Liquidity floor check
        val floor = LIQUIDITY_FLOORS[modeUpper] ?: LIQUIDITY_FLOORS["DEFAULT"]!!
        if (liquidityUsd < floor) {
            blockedEntries++
            Log.w(TAG, "🚫 BLOCKED: $mode requires \$${floor.toInt()} liq, got \$${liquidityUsd.toInt()}")
            return "LIQUIDITY_BELOW_FLOOR_${floor.toInt()}"
        }
        
        // 5. Source + mode combo block
        val combo = "${source.uppercase()}:$modeUpper"
        if (combo in BLOCKED_SOURCE_MODE_COMBOS) {
            blockedEntries++
            Log.w(TAG, "🚫 BLOCKED: $source + $mode combo is banned")
            return "BLOCKED_SOURCE_MODE_COMBO"
        }
        
        // 6. Phase restrictions for aggressive modes
        val dangerousPhases = setOf("early_unknown", "pre_pump", "unknown")
        if (phase.lowercase() in dangerousPhases) {
            if (modeUpper in setOf("COPY_TRADE", "COPY", "WHALE_FOLLOW", "WHALE_ACCUMULATION")) {
                blockedEntries++
                Log.w(TAG, "🚫 BLOCKED: $mode not allowed in phase=$phase")
                return "PHASE_RESTRICTED_${phase}"
            }
            // Even for other modes, require higher liquidity in dangerous phases
            if (liquidityUsd < 15_000) {
                blockedEntries++
                return "PHASE_LOW_LIQ_${phase}"
            }
        }
        
        // 7. AI degraded + aggressive mode
        if (isAIDegraded) {
            if (modeUpper in setOf("COPY_TRADE", "COPY", "WHALE_FOLLOW", "FRESH_LAUNCH", "PRESALE_SNIPE")) {
                blockedEntries++
                Log.w(TAG, "🚫 BLOCKED: $mode not allowed when AI degraded")
                return "AI_DEGRADED_AGGRESSIVE_MODE"
            }
        }
        
        // 8. Memory score check
        if (memoryScore <= -8) {
            blockedEntries++
            Log.w(TAG, "🚫 BLOCKED: Memory score $memoryScore too negative")
            return "MEMORY_TOO_NEGATIVE"
        }
        
        // 9. Confidence check for copy/whale modes
        if (modeUpper in setOf("COPY_TRADE", "COPY", "WHALE_FOLLOW", "WHALE_ACCUMULATION")) {
            if (confidence < 50) {
                blockedEntries++
                return "CONFIDENCE_TOO_LOW_FOR_MODE"
            }
        }
        
        // All checks passed
        return null
    }
    
    /**
     * Simplified check - just mode and liquidity.
     */
    fun checkEntryAllowed(mode: String, liquidityUsd: Double): String? {
        return checkEntryAllowed(
            mode = mode,
            source = "",
            liquidityUsd = liquidityUsd,
            phase = "",
            memoryScore = 0,
            isAIDegraded = false,
            confidence = 100
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // RECORD LOSS - Called after trade closes
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Record a trade loss. This may trip the circuit breaker.
     */
    fun recordLoss(mode: String, pnlPct: Double, mint: String, symbol: String) {
        if (pnlPct >= 0) return  // Not a loss
        
        val modeUpper = mode.uppercase()
        val now = System.currentTimeMillis()
        
        // Add to recent losses
        val losses = recentLosses.getOrPut(modeUpper) { mutableListOf() }
        synchronized(losses) {
            losses.add(LossRecord(pnlPct, now))
            // Keep only last hour
            losses.removeAll { now - it.timestamp > 60 * 60 * 1000 }
        }
        
        // Check circuit breaker conditions
        
        // Condition 1: Single catastrophic loss > 40%
        if (pnlPct <= -40) {
            tripCircuitBreaker(modeUpper, 12 * 60 * 60 * 1000L, 
                "CATASTROPHIC_LOSS_${pnlPct.toInt()}%")
            Log.e(TAG, "🚨 CIRCUIT BREAKER: $modeUpper frozen 12h after ${pnlPct.toInt()}% loss on $symbol")
            return
        }
        
        // Condition 2: Single severe loss > 25%
        if (pnlPct <= -25) {
            tripCircuitBreaker(modeUpper, 6 * 60 * 60 * 1000L, 
                "SEVERE_LOSS_${pnlPct.toInt()}%")
            Log.e(TAG, "🚨 CIRCUIT BREAKER: $modeUpper frozen 6h after ${pnlPct.toInt()}% loss on $symbol")
            return
        }
        
        // Count recent significant losses
        synchronized(losses) {
            val recentBigLosses = losses.count { it.pnlPct <= -20 }
            val recentMediumLosses = losses.count { it.pnlPct <= -15 }
            
            // Condition 3: 2+ losses > 20%
            if (recentBigLosses >= 2) {
                tripCircuitBreaker(modeUpper, 6 * 60 * 60 * 1000L, 
                    "MULTIPLE_BIG_LOSSES")
                Log.e(TAG, "🚨 CIRCUIT BREAKER: $modeUpper frozen 6h after $recentBigLosses big losses")
                return
            }
            
            // Condition 4: 3+ losses > 15%
            if (recentMediumLosses >= 3) {
                tripCircuitBreaker(modeUpper, 3 * 60 * 60 * 1000L, 
                    "TRIPLE_MEDIUM_LOSSES")
                Log.e(TAG, "🚨 CIRCUIT BREAKER: $modeUpper frozen 3h after $recentMediumLosses medium losses")
                return
            }
        }
    }
    
    /**
     * Trip the circuit breaker for a mode.
     */
    private fun tripCircuitBreaker(mode: String, durationMs: Long, reason: String) {
        val freezeEnd = System.currentTimeMillis() + durationMs
        frozenModes[mode] = freezeEnd
        circuitTrips++
        
        // Log for debugging (use ErrorLogger since BehaviorLearning.recordEvent doesn't exist)
        ErrorLogger.warn(TAG, "CIRCUIT_BREAKER: $mode frozen for ${durationMs / 60_000}min: $reason")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // COLLAPSE DETECTION - Should trigger FULL EXIT
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Check if collapse conditions warrant immediate full exit.
     * Returns true if FULL EXIT should be executed immediately.
     */
    fun shouldForceFullExit(
        liquidityCollapsing: Boolean,
        depthDangerous: Boolean,
        whalesStopped: Boolean,
        copyInvalidated: Boolean,
        buyPressureCollapsing: Boolean,
        mode: String
    ): Boolean {
        
        val modeUpper = mode.uppercase()
        
        // For COPY_TRADE and WHALE modes, any collapse signal = full exit
        if (modeUpper in setOf("COPY_TRADE", "COPY", "WHALE_FOLLOW", "WHALE_ACCUMULATION")) {
            if (liquidityCollapsing || copyInvalidated || whalesStopped) {
                Log.w(TAG, "⚠️ FORCE EXIT: $mode collapse detected (liq=$liquidityCollapsing, copy=$copyInvalidated, whale=$whalesStopped)")
                return true
            }
        }
        
        // For any mode: multiple collapse signals = full exit
        val collapseSignals = listOf(
            liquidityCollapsing,
            depthDangerous,
            whalesStopped,
            copyInvalidated,
            buyPressureCollapsing
        ).count { it }
        
        if (collapseSignals >= 3) {
            Log.w(TAG, "⚠️ FORCE EXIT: Multiple collapse signals ($collapseSignals)")
            return true
        }
        
        // Liquidity collapse alone is enough for small-cap positions
        if (liquidityCollapsing && depthDangerous) {
            Log.w(TAG, "⚠️ FORCE EXIT: Liquidity collapse + depth dangerous")
            return true
        }
        
        return false
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TOXIC PATTERN DETECTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Check if a mint/pattern should be one-strike banned.
     */
    fun isToxicPattern(
        source: String,
        liquidityBucket: String,
        mode: String,
        hasCollapsed: Boolean
    ): Boolean {
        val sourceUpper = source.uppercase()
        val modeUpper = mode.uppercase()
        val liqUpper = liquidityBucket.uppercase()
        
        // Toxic pattern: new pool + low/tiny liq + copy trade + collapse
        if (sourceUpper.contains("NEW_POOL") || sourceUpper.contains("RAYDIUM_NEW")) {
            if (liqUpper in setOf("LOW", "TINY", "MICRO")) {
                if (modeUpper in setOf("COPY_TRADE", "COPY")) {
                    if (hasCollapsed) {
                        Log.e(TAG, "🚨 TOXIC PATTERN DETECTED: $sourceUpper + $liqUpper + $modeUpper + collapse")
                        return true
                    }
                }
            }
        }
        
        return false
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // EMERGENCY CONTROLS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Activate emergency stop - blocks ALL entries.
     */
    fun activateEmergencyStop() {
        emergencyStop.set(true)
        Log.e(TAG, "🚨🚨🚨 EMERGENCY STOP ACTIVATED - ALL ENTRIES BLOCKED 🚨🚨🚨")
    }
    
    /**
     * Deactivate emergency stop.
     */
    fun deactivateEmergencyStop() {
        emergencyStop.set(false)
        Log.i(TAG, "✅ Emergency stop deactivated")
    }
    
    /**
     * Manually freeze a mode.
     */
    fun freezeMode(mode: String, durationHours: Int) {
        tripCircuitBreaker(mode.uppercase(), durationHours * 60L * 60 * 1000, "MANUAL_FREEZE")
    }
    
    /**
     * Unfreeze a mode.
     */
    fun unfreezeMode(mode: String) {
        frozenModes.remove(mode.uppercase())
        Log.i(TAG, "✅ $mode unfrozen")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATS AND UTILITIES
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get current status.
     */
    fun getStatus(): String {
        val now = System.currentTimeMillis()
        val frozenList = frozenModes.filter { it.value > now }
            .map { "${it.key}(${(it.value - now) / 60_000}min)" }
            .joinToString(", ")
        
        return buildString {
            append("CircuitBreaker: ")
            append("blocked=$blockedEntries trips=$circuitTrips ")
            if (emergencyStop.get()) append("EMERGENCY_STOP ")
            if (frozenList.isNotEmpty()) append("frozen=[$frozenList] ")
            append("disabled=[${HARD_DISABLED_MODES.joinToString(",")}]")
        }
    }
    
    /**
     * Check if a mode is currently frozen.
     */
    fun isModeFrozen(mode: String): Boolean {
        val freezeEnd = frozenModes[mode.uppercase()] ?: return false
        return System.currentTimeMillis() < freezeEnd
    }
    
    /**
     * Check if a mode is hard disabled.
     */
    fun isModeDisabled(mode: String): Boolean {
        return mode.uppercase() in HARD_DISABLED_MODES
    }
    
    /**
     * Get liquidity floor for a mode.
     */
    fun getLiquidityFloor(mode: String): Double {
        return LIQUIDITY_FLOORS[mode.uppercase()] ?: LIQUIDITY_FLOORS["DEFAULT"]!!
    }
    
    /**
     * Reset all state (for testing).
     */
    fun reset() {
        frozenModes.clear()
        recentLosses.clear()
        emergencyStop.set(false)
        blockedEntries = 0
        circuitTrips = 0
    }
}
