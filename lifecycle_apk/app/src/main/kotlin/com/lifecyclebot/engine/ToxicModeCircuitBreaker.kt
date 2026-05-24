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
    
    private fun warn(message: String) {
        try { Log.w(TAG, message) } catch (_: Throwable) {
            try { ErrorLogger.warn(TAG, message) } catch (_: Throwable) {}
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HARD DISABLED MODES
    // These modes are completely disabled - no entries allowed
    // ═══════════════════════════════════════════════════════════════════════════
    
    // V5.9.1055 — COPY_TRADE/COPY removed from hard-disabled.
    // Copy trade is a valid learning strategy. It learns from wallet signals
    // and should trade through bad periods, not get permanently killed.
    // ToxicModeCircuitBreaker timed freezes handle runaway loss patterns.
    private val HARD_DISABLED_MODES = emptySet<String>()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LIQUIDITY FLOORS BY MODE
    // No entry below these thresholds - no exceptions
    // ═══════════════════════════════════════════════════════════════════════════
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LIQUIDITY FLOORS BY MODE
    // 
    // UPDATED: Clean split between watchlist and execution floors
    //   - Watchlist/Shadow: $2,000 (handled in FDG)
    //   - Execution minimum: $10,000 (DEFAULT floor)
    //   - High-risk modes: $15,000+ (extra safety margin)
    //
    // No entry below these thresholds - no exceptions
    // ═══════════════════════════════════════════════════════════════════════════
    
    // V5.9.1068 — REALIGNED FOR PUMP.FUN BONDING-CURVE REALITY.
    // Pump.fun launches arrive at ~$2,000-$2,500 liquidity (bonding curve
    // pre-graduation). Previous floors of $10k-$15k silently killed every
    // entry from MOMENTUM / FRESH_LAUNCH / PRESALE_SNIPE / SENTIMENT_IGNITION
    // / WHALE_FOLLOW / DEFAULT lanes — operator snapshot showed 5 of 7
    // active lanes (MOONSHOT, MANIPULATED, SHITCOIN, QUALITY, DIP_HUNTER)
    // producing ZERO executions despite 95 lane-evals each. Floor lowered
    // to $1,500 across all modes so the bot can actually trade the
    // tokens that arrive. Per-mode risk is already handled by SL/SmartSizer.
    private val LIQUIDITY_FLOORS = mapOf(
        "COPY_TRADE" to 1_500.0,
        "COPY" to 1_500.0,
        "WHALE_FOLLOW" to 1_500.0,
        "WHALE_ACCUMULATION" to 1_500.0,
        "MOMENTUM" to 1_500.0,
        "MOMENTUM_SWING" to 1_500.0,
        "FRESH_LAUNCH" to 1_500.0,
        "PRESALE_SNIPE" to 1_500.0,
        "SENTIMENT_IGNITION" to 1_500.0,
        "DEFAULT" to 1_500.0,
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SOURCE RESTRICTIONS
    // Dangerous source + mode combinations
    // ═══════════════════════════════════════════════════════════════════════════
    
    // V5.9.1055: COPY_TRADE/COPY source combos removed — copy follows wallet signals, source doesn't matter
    private val BLOCKED_SOURCE_MODE_COMBOS = setOf(
        "RAYDIUM_NEW_POOL:WHALE_FOLLOW",
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

    // V5.9.1094/1096 — execution-state global pause source of truth.
    // 1094 incorrectly promoted ordinary lane-level blocks (e.g.
    // LIQUIDITY_BELOW_FLOOR_3000) into a global buy pause, which starved all
    // entries. 1096 narrows this to true global emergency states only.
    @Volatile private var lastEntryBlockMs: Long = 0L
    @Volatile private var lastEntryBlockReason: String = ""
    @Volatile private var lastEntryBlockMode: String = ""
    @Volatile private var lastEntryBlockEmitMs: Long = 0L
    private const val ENTRY_BLOCK_PAUSE_MS = 120_000L
    private const val EXECUTION_STATE_BLOCK_LOG_MS = 10_000L

    data class EntryPause(
        val active: Boolean,
        val reason: String = "",
        val mode: String = "",
        val ageMs: Long = 0L,
    )

    private fun recordEntryBlocked(modeUpper: String, reason: String): String {
        blockedEntries++
        return reason
    }

    private fun markGlobalEntryPause(modeUpper: String, reason: String): String {
        // V5.9.1117 — ordinary liquidity/phase blocks are lane-local telemetry,
        // not a global EXECUTION_STATE=CIRCUIT_BREAKER. 3084 showed one SHITCOIN
        // LIQUIDITY_BELOW_FLOOR_3000 token suppressing 1569 FDG calls globally.
        if (reason.contains("LIQUIDITY_BELOW_FLOOR", true) ||
            reason.contains("PHASE_LOW_LIQ", true) ||
            reason.contains("LOW_LIQUIDITY", true)
        ) {
            return recordEntryBlocked(modeUpper, reason)
        }
        blockedEntries++
        lastEntryBlockMs = System.currentTimeMillis()
        lastEntryBlockReason = reason
        lastEntryBlockMode = modeUpper
        return reason
    }

    fun currentEntryPause(): EntryPause {
        val ms = lastEntryBlockMs
        if (ms <= 0L) return EntryPause(false)
        val age = System.currentTimeMillis() - ms
        if (age > ENTRY_BLOCK_PAUSE_MS) return EntryPause(false, lastEntryBlockReason, lastEntryBlockMode, age)
        return EntryPause(true, lastEntryBlockReason, lastEntryBlockMode, age)
    }

    fun emitExecutionStateBlockedIfDue(symbol: String, source: String): Boolean {
        val pause = currentEntryPause()
        if (!pause.active) return false
        val now = System.currentTimeMillis()
        if (now - lastEntryBlockEmitMs >= EXECUTION_STATE_BLOCK_LOG_MS) {
            lastEntryBlockEmitMs = now
            try {
                ForensicLogger.lifecycle(
                    "EXECUTION_STATE_BLOCKED",
                    "state=CIRCUIT_BREAKER mode=${pause.mode} symbol=$symbol reason=${pause.reason} ageSec=${pause.ageMs / 1000} source=$source"
                )
            } catch (_: Throwable) {}
        }
        return true
    }
    
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
        confidence: Int,
        isPaperMode: Boolean = false  // V5.2: Paper mode bypasses liquidity floors
    ): String? {
        
        // 1. Emergency stop
        if (emergencyStop.get()) {
            return markGlobalEntryPause("GLOBAL", "EMERGENCY_STOP_ACTIVE")
        }
        
        // 2. Hard disabled modes
        val modeUpper = mode.uppercase()
        if (modeUpper in HARD_DISABLED_MODES) {
            warn("🚫 BLOCKED: $mode is HARD DISABLED (toxic loss pattern)")
            return recordEntryBlocked(modeUpper, "MODE_HARD_DISABLED")
        }
        
        // 3. Frozen modes (circuit breaker tripped)
        val freezeEnd = frozenModes[modeUpper]
        if (freezeEnd != null && System.currentTimeMillis() < freezeEnd) {
            val remainingMins = (freezeEnd - System.currentTimeMillis()) / 60_000
            warn("🚫 BLOCKED: $mode frozen for ${remainingMins}min (circuit breaker)")
            return recordEntryBlocked(modeUpper, "MODE_FROZEN_${remainingMins}MIN")
        }
        
        // 4. Liquidity floor check
        // V5.2/V5.9.47: lenient mode uses much lower floor (paper/proven-edge)
        val lenient = ModeLeniency.useLenientGates(isPaperMode)
        val floor = if (lenient) {
            // V5.9.1118 — paper/proven-edge bootstrap must match pump.fun
            // bonding-curve reality. 3084 showed $2.0k-$2.5k candidates being
            // globally buried by LIQUIDITY_BELOW_FLOOR_3000. Live keeps the
            // regular per-mode floor; only lenient learning path uses $1.5k.
            1_500.0
        } else {
            LIQUIDITY_FLOORS[modeUpper] ?: LIQUIDITY_FLOORS["DEFAULT"]!!
        }
        if (liquidityUsd < floor) {
            warn("🚫 BLOCKED: $mode requires \$${floor.toInt()} liq, got \$${liquidityUsd.toInt()}")
            return recordEntryBlocked(modeUpper, "LIQUIDITY_BELOW_FLOOR_${floor.toInt()}")
        }
        
        // 5. Source + mode combo block
        val combo = "${source.uppercase()}:$modeUpper"
        if (combo in BLOCKED_SOURCE_MODE_COMBOS) {
            warn("🚫 BLOCKED: $source + $mode combo is banned")
            return recordEntryBlocked(modeUpper, "BLOCKED_SOURCE_MODE_COMBO")
        }
        
        // 6. Phase restrictions for aggressive modes
        val dangerousPhases = setOf("early_unknown", "pre_pump", "unknown")
        if (phase.lowercase() in dangerousPhases) {
            if (modeUpper in setOf("WHALE_FOLLOW", "WHALE_ACCUMULATION")) {
                warn("🚫 BLOCKED: $mode not allowed in phase=$phase")
                return recordEntryBlocked(modeUpper, "PHASE_RESTRICTED_${phase}")
            }
            // V5.9.1118 — in paper/lenient bootstrap, early_unknown/pre_pump is
            // exactly where meme learning samples come from. Do not add a second
            // $15k phase floor after the lenient $1.5k floor has already passed.
            if (!lenient && liquidityUsd < 15_000) {
                return recordEntryBlocked(modeUpper, "PHASE_LOW_LIQ_${phase}")
            }
        }
        
        // 7. AI degraded + aggressive mode
        if (isAIDegraded) {
            // V5.9.1055: COPY_TRADE/COPY removed — copy trade follows wallet signals,
            // not AI scoring, so AI degradation doesn't affect its signal quality.
            if (modeUpper in setOf("WHALE_FOLLOW", "FRESH_LAUNCH", "PRESALE_SNIPE")) {
                warn("🚫 BLOCKED: $mode not allowed when AI degraded")
                return recordEntryBlocked(modeUpper, "AI_DEGRADED_AGGRESSIVE_MODE")
            }
        }
        
        // 8. Memory score check
        //
        // V5.9.787 — operator fix B: relax MEMORY_TOO_NEGATIVE so paper-mode
        // learning isn't permanently locked by historic losses. The whole
        // point of paper mode is to let the bot trade through unfavorable
        // patterns and accumulate fresh outcomes — but the previous code
        // blocked at -8 (which a single -0.3x multiplier from TokenWinMemory
        // hits immediately). New behavior:
        //   • PAPER mode → never block on memoryScore (log + allow). The
        //     operator audit specifically requires paper mode to remain
        //     un-choked so strategy learners get fresh feature-rich samples.
        //   • LIVE mode  → only block at <= -12 (was <= -8). A single losing
        //     run no longer permanently locks the lane; the trader has to
        //     have a substantially negative memory profile before LIVE
        //     entries are blocked.
        if (memoryScore <= -12 && !isPaperMode) {
            warn("🚫 BLOCKED [LIVE]: Memory score $memoryScore too negative")
            return recordEntryBlocked(modeUpper, "MEMORY_TOO_NEGATIVE")
        }
        if (memoryScore <= -8 && isPaperMode) {
            // Visibility only — do NOT block paper trades.
            warn("⚠️ PAPER warning: Memory score $memoryScore is negative — allowing for learning.")
        }
        
        // 9. Confidence check for whale-follow only (not copy trade — it learns from wallet signals)
        if (modeUpper in setOf("WHALE_FOLLOW", "WHALE_ACCUMULATION")) {
            if (confidence < 50) {
                return recordEntryBlocked(modeUpper, "CONFIDENCE_TOO_LOW_FOR_MODE")
            }
        }
        
        // All checks passed
        return null
    }
    
    /**
     * Simplified check - just mode and liquidity.
     * V5.2: Added isPaperMode parameter
     */
    fun checkEntryAllowed(mode: String, liquidityUsd: Double, isPaperMode: Boolean = false): String? {
        return checkEntryAllowed(
            mode = mode,
            source = "",
            liquidityUsd = liquidityUsd,
            phase = "",
            memoryScore = 0,
            isAIDegraded = false,
            confidence = 100,
            isPaperMode = isPaperMode
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // RECORD LOSS - Called after trade closes
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Record a trade loss. This may trip the circuit breaker.
     * V5.2: Added isPaper parameter - paper=1min max, live=5min max
     */
    fun recordLoss(mode: String, pnlPct: Double, mint: String, symbol: String, isPaper: Boolean = true) {
        if (pnlPct >= 0) return  // Not a loss
        
        val modeUpper = mode.uppercase()
        val now = System.currentTimeMillis()
        
        // V5.9.1055: paper = 10 min (operator directive — not real money, needs to learn)
        // live = 60 min (real money protection)
        val freezeMultiplier = if (isPaper) 10L else 60L  // 10 min paper, 60 min live
        
        // Add to recent losses
        val losses = recentLosses.getOrPut(modeUpper) { mutableListOf() }
        synchronized(losses) {
            losses.add(LossRecord(pnlPct, now))
            // Keep only last hour
            losses.removeAll { now - it.timestamp > 60 * 60 * 1000 }
        }
        
        // Check circuit breaker conditions
        
        // Condition 1: Single catastrophic loss > 40%
        // V5.2: Paper=1min, Live=5min (was 12 hours!)
        if (pnlPct <= -40) {
            tripCircuitBreaker(modeUpper, 60 * 1000L * freezeMultiplier, 
                "CATASTROPHIC_LOSS_${pnlPct.toInt()}%")
            Log.e(TAG, "🚨 CIRCUIT BREAKER: $modeUpper frozen ${freezeMultiplier}min (${if (isPaper) "paper" else "live"}) after ${pnlPct.toInt()}% loss on $symbol")
            return
        }
        
        // Condition 2: Single severe loss > 25%
        // V5.2: Paper=1min, Live=5min
        if (pnlPct <= -25) {
            tripCircuitBreaker(modeUpper, 60 * 1000L * freezeMultiplier,
                "SEVERE_LOSS_${pnlPct.toInt()}%")
            Log.e(TAG, "🚨 CIRCUIT BREAKER: $modeUpper frozen ${freezeMultiplier}min (${if (isPaper) "paper" else "live"}) after ${pnlPct.toInt()}% loss on $symbol")
            return
        }
        
        // Count recent significant losses
        synchronized(losses) {
            val recentBigLosses = losses.count { it.pnlPct <= -20 }
            val recentMediumLosses = losses.count { it.pnlPct <= -15 }
            
            // Condition 3: 2+ losses > 20%
            // V5.2: Paper=1min, Live=5min
            if (recentBigLosses >= 2) {
                tripCircuitBreaker(modeUpper, 60 * 1000L * freezeMultiplier,
                    "MULTIPLE_BIG_LOSSES")
                Log.e(TAG, "🚨 CIRCUIT BREAKER: $modeUpper frozen ${freezeMultiplier}min (${if (isPaper) "paper" else "live"}) after $recentBigLosses big losses")
                return
            }
            
            // Condition 4: 3+ losses > 15%
            // V5.2: Paper=1min, Live=5min
            if (recentMediumLosses >= 3) {
                tripCircuitBreaker(modeUpper, 60 * 1000L * freezeMultiplier,
                    "TRIPLE_MEDIUM_LOSSES")
                Log.e(TAG, "🚨 CIRCUIT BREAKER: $modeUpper frozen ${freezeMultiplier}min (${if (isPaper) "paper" else "live"}) after $recentMediumLosses medium losses")
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

    /** V5.9.1129 — deterministic invariant tests for early entry gating. */
    fun resetForTests() {
        frozenModes.clear()
        recentLosses.clear()
        lastEntryBlockMs = 0L
        lastEntryBlockReason = ""
        lastEntryBlockMode = ""
        lastEntryBlockEmitMs = 0L
        blockedEntries = 0
        circuitTrips = 0
    }

    /** V5.9.1129 — test hook only; production trips still flow through recordLoss/checkEntryAllowed. */
    fun forceTripForTests(mode: String = "SHITCOIN", durationMs: Long = 60_000L, reason: String = "TEST_CIRCUIT") {
        val modeUpper = mode.uppercase()
        tripCircuitBreaker(modeUpper, durationMs, reason)
        lastEntryBlockMs = System.currentTimeMillis()
        lastEntryBlockReason = reason
        lastEntryBlockMode = modeUpper
        lastEntryBlockEmitMs = 0L
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
                warn("⚠️ FORCE EXIT: $mode collapse detected (liq=$liquidityCollapsing, copy=$copyInvalidated, whale=$whalesStopped)")
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
            warn("⚠️ FORCE EXIT: Multiple collapse signals ($collapseSignals)")
            return true
        }
        
        // Liquidity collapse alone is enough for small-cap positions
        if (liquidityCollapsing && depthDangerous) {
            warn("⚠️ FORCE EXIT: Liquidity collapse + depth dangerous")
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
        lastEntryBlockMs = 0L
        lastEntryBlockReason = ""
        lastEntryBlockMode = ""
        lastEntryBlockEmitMs = 0L
    }
}
