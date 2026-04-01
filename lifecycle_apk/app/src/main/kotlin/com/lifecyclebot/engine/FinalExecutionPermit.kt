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
    // V5.2 FIX: Reduced from 60s to 10s - was blocking Treasury/ShitCoin too long!
    private const val REJECTION_COOLDOWN_MS = 10_000L  // 10 seconds cooldown after V3 rejection
    
    // Per-token cooldown after execution attempt (ms)
    private const val EXECUTION_COOLDOWN_MS = 15_000L  // V5.2: Reduced from 30s to 15s
    
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
    ): Boolean {
        val now = System.currentTimeMillis()
        
        // Check if another execution is pending
        val existing = pendingExecutions[mint]
        if (existing != null) {
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
        
        ErrorLogger.debug(TAG, "🔓 ACQUIRED: $symbol | layer=$layer | size=${sizeSol}")
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
        
        // V5.2.6: PAPER MODE BYPASS - Allow all layers to trade independently
        // This is critical for learning - each layer needs its own trade data
        if (isPaperMode) {
            // In paper mode, only block if position is already open
            if (hasOpenPosition) {
                return PermitResult(
                    allowed = false,
                    reason = "POSITION_OPEN: Already have open position",
                    blockingLayer = "POSITION",
                )
            }
            // Allow all other trades for learning
            return PermitResult(
                allowed = true,
                reason = "PAPER_MODE_BYPASS",
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
