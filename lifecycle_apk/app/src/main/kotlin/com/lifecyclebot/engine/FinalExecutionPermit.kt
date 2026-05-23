package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * FINAL EXECUTION PERMIT - V4.0 UNIFIED EXECUTION AUTHORITY
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * PROBLEM SOLVED:
 * Multiple AI subsystems (V3, Treasury, BlueChip, ShitCoin) were executing trades
 * independently, causing conflicts like:
 * - Treasury buying while V3 rejects
 * - Multiple layers trying to buy same token simultaneously
 * - Inconsistent execution decisions
 * 
 * SOLUTION:
 * ALL execution calls MUST acquire a permit from this singleton gate.
 * The permit tracks:
 * 1. Which tokens have been REJECTED by any authoritative layer (V3)
 * 2. Which tokens already have PENDING or ACTIVE positions
 * 3. Cooldowns between execution attempts
 * 
 * FLOW:
 * V3 processes token → (REJECT) → registerRejection() → Treasury blocked
 * V3 processes token → (EXECUTE) → registerApproval() → Treasury allowed
 * Treasury/BlueChip/ShitCoin → canExecute() → checks rejection map → proceed or block
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object FinalExecutionPermit {
    
    private const val TAG = "FinalExecPermit"
    
    // Tokens rejected by V3 in this cycle - cleared each loop iteration
    // Key: mint, Value: rejection reason
    private val v3Rejections = ConcurrentHashMap<String, RejectionRecord>()
    
    // Tokens with pending execution - prevents duplicate buys
    // Key: mint, Value: pending execution details
    private val pendingExecutions = ConcurrentHashMap<String, PendingExecution>()
    
    // Per-token cooldown after rejection (ms)
    // V5.2 FIX: Reduced from 60s to 5s - was blocking Treasury/ShitCoin too long!
    private const val REJECTION_COOLDOWN_MS = 5_000L  // 5 seconds cooldown after V3 rejection
    
    // Per-token cooldown after execution attempt (ms)
    private const val EXECUTION_COOLDOWN_MS = 5_000L  // V5.2: Reduced from 30s to 5s
    
    // V5.2.6: Paper mode flag - when true, bypass V3 rejection blocks for learning
    @Volatile var isPaperMode: Boolean = true
    
    data class RejectionRecord(
        val mint: String,
        val symbol: String,
        val reason: String,
        val rejectedBy: String,  // "V3", "FDG", etc.
        val timestamp: Long,
        val v3Score: Int,
        val v3Confidence: Int,
    )
    
    data class PendingExecution(
        val mint: String,
        val symbol: String,
        val layer: String,  // "V3", "TREASURY", "BLUE_CHIP", "SHITCOIN"
        val timestamp: Long,
        val sizeSol: Double,
    )
    
    /**
     * Register a V3 rejection for a token.
     * Called when V3 returns REJECT, BLOCK, or WATCH.
     * This BLOCKS all other layers from executing on this token for the cooldown period.
     */
    fun registerRejection(
        mint: String,
        symbol: String,
        reason: String,
        rejectedBy: String = "V3",
        v3Score: Int = 0,
        v3Confidence: Int = 0,
    ) {
        v3Rejections[mint] = RejectionRecord(
            mint = mint,
            symbol = symbol,
            reason = reason,
            rejectedBy = rejectedBy,
            timestamp = System.currentTimeMillis(),
            v3Score = v3Score,
            v3Confidence = v3Confidence,
        )
        
        ErrorLogger.debug(TAG, "❌ REJECTION: $symbol | by=$rejectedBy | reason=$reason")
    }
    
    /**
     * Register a V3 approval for a token.
     * Clears any previous rejection, allowing other layers to also execute.
     * However, only ONE layer should actually execute (first-come-first-served).
     */
    fun registerApproval(
        mint: String,
        symbol: String,
        approvedBy: String = "V3",
        v3Score: Int = 0,
        v3Confidence: Int = 0,
    ) {
        // Clear rejection if V3 approved
        v3Rejections.remove(mint)
        
        ErrorLogger.debug(TAG, "✅ APPROVAL: $symbol | by=$approvedBy | score=$v3Score conf=$v3Confidence")
    }
    
    /**
     * Register that a layer is about to execute on a token.
     * Returns true if the execution can proceed (no conflicts).
     * Returns false if another layer already has a pending execution.
     */
    fun tryAcquireExecution(
        mint: String,
        symbol: String,
        layer: String,
        sizeSol: Double,
        attemptId: String = "",
        paperMode: Boolean = isPaperMode,
        rugScore: Int = -1,
    ): Boolean {
        val now = System.currentTimeMillis()

        if (RuntimeConfigOverlay.isTradingPaused()) {
            ErrorLogger.warn(TAG, "🛑 RUNTIME_PAUSED: $symbol | layer=$layer")
            return false
        }
        if (RuntimeConfigOverlay.isLaneDisabled(layer)) {
            try { ForensicLogger.lifecycle("LANE_EXECUTION_SUPPRESSED", "mint=${mint.take(10)} symbol=$symbol lane=$layer reason=RUNTIME_OVERLAY_DISABLED") } catch (_: Throwable) {}
            return false
        }


        // V5.9.1093 — finality BEFORE ENTER/permit side effects.
        // Existing lane code logs ENTER immediately after this function returns
        // true, so this is the last universal pre-side-effect choke point.
        val finalityAttemptId = attemptId.ifBlank {
            ExecutableOpenGate.recentAllowedAttemptId(mint, layer)
                ?: ExecutableOpenGate.nextAttemptId(mint, layer)
        }
        val finality = ExecutableOpenGate.canOpenExecutablePosition(
            mint = mint,
            symbol = symbol,
            rugScore = rugScore,
            mode = if (paperMode) "PAPER" else "LIVE",
            lane = layer,
            source = "FinalExecutionPermit.tryAcquireExecution",
            attemptId = finalityAttemptId,
        )
        if (!finality.allowed) {
            ErrorLogger.debug(TAG, "🚫 FINALITY_BLOCK: $symbol | layer=$layer attemptId=${finality.attemptId} reason=${finality.reason}")
            return false
        }

        val laneElection = LaneExecutionCoordinator.canRequestExecution(mint, layer)
        if (!laneElection.allowed) {
            try {
                ForensicLogger.lifecycle(
                    "LANE_EXECUTION_SUPPRESSED",
                    "mint=${mint.take(10)} symbol=$symbol lane=$layer primary=${laneElection.primaryLane} candidateVersion=${laneElection.candidateVersion} reason=${laneElection.reason}"
                )
            } catch (_: Throwable) {}
            ErrorLogger.debug(TAG, "🧭 LANE_TELEMETRY_ONLY: $symbol | layer=$layer primary=${laneElection.primaryLane}")
            return false
        }

        // V5.9.408 — Free-range: skip pending-exec lockout so overlapping
        // layers (Treasury + ShitCoin + BlueChip etc) can all fire.
        val wideOpen = FreeRangeMode.isWideOpen()

        // Check if another execution is pending
        val existing = pendingExecutions[mint]
        if (!wideOpen && existing != null) {
            val elapsed = now - existing.timestamp
            if (elapsed < EXECUTION_COOLDOWN_MS) {
                ErrorLogger.debug(TAG, "🔒 BLOCKED: $symbol | $layer blocked by pending ${existing.layer} (${elapsed}ms ago)")
                return false
            }
        }
        
        // Acquire the execution slot
        pendingExecutions[mint] = PendingExecution(
            mint = mint,
            symbol = symbol,
            layer = layer,
            timestamp = now,
            sizeSol = sizeSol,
        )
        
        ErrorLogger.debug(TAG, "🔓 ACQUIRED: $symbol | layer=$layer | attemptId=$finalityAttemptId | size=${sizeSol}")
        return true
    }
    
    /**
     * Release the execution permit after trade completes (success or failure).
     */
    fun releaseExecution(mint: String) {
        pendingExecutions.remove(mint)
    }
    
    /**
     * Check if a layer can execute on a token.
     * Returns PermitResult with allowed status and reason.
     * 
     * RULES:
     * 1. If V3 REJECTED this token in this cycle → BLOCKED (Treasury cannot override V3)
     * 2. If another layer has pending execution → BLOCKED (first-come-first-served)
     * 3. If position already open → BLOCKED (no duplicate positions)
     * 4. Otherwise → ALLOWED
     */
    fun canExecute(
        mint: String,
        symbol: String,
        requestingLayer: String,
        hasOpenPosition: Boolean,
    ): PermitResult {
        val now = System.currentTimeMillis()

        // V5.9.408 — Free-range mode: let every layer fire. Only position-open
        // is still honored (can't stack duplicate positions on one mint).
        if (FreeRangeMode.isWideOpen()) {
            if (hasOpenPosition) {
                return PermitResult(
                    allowed = false,
                    reason = "POSITION_OPEN: Already have open position",
                    blockingLayer = "POSITION",
                )
            }
            return PermitResult(
                allowed = true,
                reason = "FREE_RANGE_BYPASS",
                blockingLayer = null,
            )
        }

        // V5.9.876 — REPLACE blanket PAPER_MODE_BYPASS with structured paper path.
        //
        // PRIOR BEHAVIOR: paper mode short-circuited BEFORE V3 rejection cooldown
        // and BEFORE pending-execution race guard. This meant:
        //   (a) V3-rejected tokens could be retried by every other layer within
        //       the cooldown window — learning signal was scrambled because the
        //       same mint was getting outcomes from multiple lanes on the same
        //       cycle, contaminating the per-lane WR.
        //   (b) Race conditions between Treasury / ShitCoin / BlueChip all
        //       trying to buy the same mint simultaneously were not arbitrated,
        //       leading to duplicate paper entries in the journal.
        //
        // NEW BEHAVIOR (operator audit + GPT external review V5.9.809 critique):
        //   - Open-position check still blocks (correct).
        //   - V3 rejection cooldown becomes a TELEMETRY LABEL in paper mode
        //     (rather than allowing the trade to slip through unrecorded). The
        //     trade is permitted but the reason carries a "PAPER_TELEMETRY:
        //     V3_REJECTED_LAST_CYCLE" tag so the learning bus can label it
        //     accordingly and downstream learners can soft-down-weight.
        //   - Pending-execution race STILL blocks even in paper mode — we
        //     don't want two layers racing for the same mint with duplicate
        //     journal entries, that's not learning, that's noise.
        //
        // Per doctrine #86: no new hard veto, no scoring impact, just clean
        // up the duplicate-trade noise + label the soft signals so learners
        // can use them. Live mode is unaffected.
        if (isPaperMode) {
            // Position-open still blocks (duplicates are not learning).
            if (hasOpenPosition) {
                return PermitResult(
                    allowed = false,
                    reason = "POSITION_OPEN: Already have open position",
                    blockingLayer = "POSITION",
                )
            }
            // Pending-execution race STILL blocks (same-mint dup-buy noise).
            val pendingPaper = pendingExecutions[mint]
            if (pendingPaper != null && pendingPaper.layer != requestingLayer) {
                val elapsedP = now - pendingPaper.timestamp
                if (elapsedP < EXECUTION_COOLDOWN_MS) {
                    return PermitResult(
                        allowed = false,
                        reason = "PENDING_EXECUTION: ${pendingPaper.layer} already executing (${elapsedP/1000}s ago)",
                        blockingLayer = pendingPaper.layer,
                    )
                }
            }
            // V3 rejection cooldown becomes a telemetry label, not a block.
            val rejectionP = v3Rejections[mint]
            val telemetryTag: String? = if (rejectionP != null) {
                val elapsedR = now - rejectionP.timestamp
                if (elapsedR < REJECTION_COOLDOWN_MS) {
                    "PAPER_TELEMETRY:V3_REJECTED_LAST_CYCLE(${rejectionP.rejectedBy},${elapsedR/1000}s)"
                } else {
                    v3Rejections.remove(mint)
                    null
                }
            } else null
            return PermitResult(
                allowed = true,
                reason = telemetryTag ?: "PAPER_PERMITTED",
                blockingLayer = null,
            )
        }
        
        // Rule 1: Check V3 rejection
        val rejection = v3Rejections[mint]
        if (rejection != null) {
            val elapsed = now - rejection.timestamp
            if (elapsed < REJECTION_COOLDOWN_MS) {
                return PermitResult(
                    allowed = false,
                    reason = "V3_REJECTED: ${rejection.reason} (${rejection.rejectedBy}, ${elapsed/1000}s ago)",
                    blockingLayer = rejection.rejectedBy,
                )
            } else {
                // Cooldown expired, clear rejection
                v3Rejections.remove(mint)
            }
        }
        
        // Rule 2: Check pending execution
        val pending = pendingExecutions[mint]
        if (pending != null && pending.layer != requestingLayer) {
            val elapsed = now - pending.timestamp
            if (elapsed < EXECUTION_COOLDOWN_MS) {
                return PermitResult(
                    allowed = false,
                    reason = "PENDING_EXECUTION: ${pending.layer} already executing (${elapsed/1000}s ago)",
                    blockingLayer = pending.layer,
                )
            }
        }
        
        // Rule 3: Check open position
        if (hasOpenPosition) {
            return PermitResult(
                allowed = false,
                reason = "POSITION_OPEN: Already have open position",
                blockingLayer = "POSITION",
            )
        }
        
        // All checks passed
        return PermitResult(
            allowed = true,
            reason = "PERMITTED",
            blockingLayer = null,
        )
    }
    
    data class PermitResult(
        val allowed: Boolean,
        val reason: String,
        val blockingLayer: String?,
    )
    
    /**
     * Clear all state at the start of each bot loop.
     * This allows tokens to be re-evaluated each cycle.
     */
    fun clearCycleState() {
        // Clear old rejections (older than cooldown)
        val now = System.currentTimeMillis()
        v3Rejections.entries.removeIf { now - it.value.timestamp > REJECTION_COOLDOWN_MS }
        pendingExecutions.entries.removeIf { now - it.value.timestamp > EXECUTION_COOLDOWN_MS }
    }
    
    /**
     * Get stats for logging/debugging.
     */
    fun getStats(): String {
        return "FinalExecPermit: rejections=${v3Rejections.size} pending=${pendingExecutions.size}"
    }
    
    /**
     * Check if a token was recently rejected by V3.
     */
    fun wasRejectedByV3(mint: String): Boolean {
        val rejection = v3Rejections[mint] ?: return false
        val elapsed = System.currentTimeMillis() - rejection.timestamp
        return elapsed < REJECTION_COOLDOWN_MS
    }
    
    /**
     * Get rejection details for a token.
     */
    fun getRejectionReason(mint: String): String? {
        return v3Rejections[mint]?.reason
    }
}
