package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
    
    private val openPositions = AtomicReference<Map<String, PositionInfo>>(emptyMap())
    
    data class PositionInfo(
        val mint: String,
        val symbol: String,
        val layer: String,
        val openedAt: Long,
        val size: Double,
        val qtyRaw: java.math.BigInteger = java.math.BigInteger.ZERO,
        val state: String = "OPEN",
    )
    
    /**
     * Register an open position.
     */
    fun registerPosition(mint: String, symbol: String, layer: String, size: Double) {
        val info = PositionInfo(
            mint = mint,
            symbol = symbol,
            layer = layer,
            openedAt = System.currentTimeMillis(),
            size = size,
        )
        while (true) {
            val before = openPositions.get()
            if (openPositions.compareAndSet(before, before + (mint to info))) break
        }
        ErrorLogger.debug(TAG, "📍 Position registered: $symbol @ $layer")
        // V5.0.6464 §P0-#1/§P1 — publish OPEN state + bump authority.
        try {
            com.lifecyclebot.engine.truth.CanonicalMintOccupancyRegistry6464.markOpen(
                mode = "paper", mint = mint, symbol = symbol, source = "EmergentGuardrails.register",
            )
            com.lifecyclebot.engine.truth.AuthoritySnapshotVersion6464.bump("register_$symbol")
        } catch (_: Throwable) {}
        // V5.0.6465 §P1 — CANONICAL IDENTITY MODEL enforcement.
        // Every position creation site converges here (paper open path);
        // record the 5 identity fields exactly once so alias merges for
        // NEW positions approach zero. Refuses to overwrite an existing
        // canonicalOriginLane, so a re-register on the same mint keeps
        // the original attribution.
        try {
            com.lifecyclebot.engine.truth.CanonicalIdentityModel6464.record(
                positionId = mint,
                canonicalOriginLaneRaw = layer,
                strategyId = layer,           // best-effort until executor exposes strategy pid
                routeId = "PAPER_ROUTE",
                executionLaneRaw = layer,
                exitPolicy = "STANDARD",
            )
        } catch (_: Throwable) {}
    }
    
    /** V5.0.6489 — atomic mint-keyed projection from all funded canonical lots. */
    fun rebuildFromCanonical6475(positions: List<com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.Position>) {
        val replacement = positions.asSequence().filter {
            (it.lifecycle == com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.Lifecycle.OPEN ||
             it.lifecycle == com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.Lifecycle.PARTIALLY_CLOSED) &&
                it.remainingQtyRaw > java.math.BigInteger.ZERO
        }.groupBy { it.mint }.mapValues { (_, lots) ->
            val p = lots.maxByOrNull { it.lastMutationMs } ?: lots.first()
            PositionInfo(
                mint = p.mint, symbol = p.symbol, layer = p.lane,
                openedAt = lots.minOf { it.openedAtMs },
                size = lots.sumOf { (it.entryCostSol - it.soldCostBasisSol).coerceAtLeast(0.0) },
                qtyRaw = lots.fold(java.math.BigInteger.ZERO) { acc, lot -> acc + lot.remainingQtyRaw },
                state = "OPEN",
            )
        }
        val before = openPositions.get()
        if (before != replacement) {
            openPositions.set(replacement)
            try {
                ErrorLogger.info(TAG, "CANONICAL_REGISTRY_MINT_AGGREGATE_REBUILT_6489 before=${before.size} activeMints=${replacement.size} activeLots=${positions.size}")
                PipelineHealthCollector.labelInc("CANONICAL_REGISTRY_MINT_AGGREGATE_REBUILT_6489")
            } catch (_: Throwable) {}
        }
    }

    /**
     * Unregister a closed position.
     */
    fun unregisterPosition(mint: String) {
        var removed: PositionInfo? = null
        while (true) {
            val before = openPositions.get()
            removed = before[mint]
            if (removed == null || openPositions.compareAndSet(before, before - mint)) break
        }
        if (removed != null) {
            ErrorLogger.debug(TAG, "📍 Position unregistered: ${removed.symbol}")
            // V5.0.6402 §H — position closed IS the meaningful state
            // change that admits the next candidate for this mint.
            // Bumps the epoch so subsequent shouldSuppress checks pass
            // through instead of being deduped in the cooldown window.
            try {
                com.lifecyclebot.engine.truth.SameMintCandidateEpoch6402
                    .onStateChange(mint, reason = "position_unregistered")
            } catch (_: Throwable) {}
            // V5.0.6464 §P0-#1/§P1 — release occupancy + bump authority
            // so the next same-mint candidate admits through the fast path.
            try {
                com.lifecyclebot.engine.truth.CanonicalMintOccupancyRegistry6464.markClosed(
                    mode = "paper", mint = mint,
                )
                com.lifecyclebot.engine.truth.AuthoritySnapshotVersion6464.bump("unregister_${removed.symbol}")
                com.lifecyclebot.engine.truth.CanonicalLotQuantity6464.purge(mint)
                com.lifecyclebot.engine.truth.CanonicalIdentityModel6464.purge(mint)
            } catch (_: Throwable) {}
        }
    }
    
    /**
     * Check if a position is open.
     */
    fun hasOpenPosition(mint: String): Boolean = openPositions.get().containsKey(mint)
    
    /**
     * Get the layer of an open position.
     */
    fun getPositionLayer(mint: String): String? = openPositions.get()[mint]?.layer

    /**
     * V5.0.6464 §P0-#2 — read-only snapshot for PositionRegistryParityAudit6464.
     * Returns a defensive copy keyed by mint.
     */
    data class RegistryEntry(
        val mint: String,
        val symbol: String,
        val state: String,     // "OPEN" here — legacy registry does not track pending
        val qtyRaw: java.math.BigInteger,
        val entryCostSol: Double,
    )
    fun snapshot(): Map<String, RegistryEntry> =
        openPositions.get().mapValues { (_, p) ->
            RegistryEntry(
                mint = p.mint, symbol = p.symbol, state = "OPEN",
                qtyRaw = p.qtyRaw,
                entryCostSol = p.size,
            )
        }
    
    /**
     * FIX_3 — MULTI-LAYER LOCK CHECK
     * Returns true if entry should be blocked due to layer conflict.
     */
    fun shouldBlockMultiLayerEntry(mint: String, requestingLayer: String): Boolean {
        val existingPosition = openPositions.get()[mint] ?: return false
        
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
        val position = openPositions.get()[mint]
        
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
        openPositions.set(emptyMap())
        ErrorLogger.info(TAG, "🧹 Guardrails reset")
    }
}
