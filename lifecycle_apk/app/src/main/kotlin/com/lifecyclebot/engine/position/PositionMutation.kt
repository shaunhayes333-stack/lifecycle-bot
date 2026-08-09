package com.lifecyclebot.engine.position

import com.lifecyclebot.data.Position

/**
 * V5.0.6431 — APPEND-ONLY POSITION MUTATION EVENT LOG
 *
 * Every state transition is immutable and journalled. Reconciler uses this log to:
 *   1. Detect divergence between canonical store and external mirrors
 *   2. Replay mutations to recover from corruption
 *   3. Build audit trail for forensics
 *
 * Sealed hierarchy ensures type safety: only known mutation types exist.
 */
seal class PositionMutation(
    open val mint: String,
    open val symbol: String,
    open val version: Long,
    open val timestamp: Long
) {
    /**
     * Position entered (buy executed, position size set).
     */
    data class Open(
        override val mint: String,
        override val symbol: String,
        override val version: Long,
        val position: Position,
        val reason: String,  // "ENTRY_BUY", "RECOVERY_REHYDRATE", etc.
        override val timestamp: Long
    ) : PositionMutation(mint, symbol, version, timestamp)
    
    /**
     * Partial exit (qty reduced, position still open).
     */
    data class PartialSell(
        override val mint: String,
        override val symbol: String,
        override val version: Long,
        val previousVersion: Long,
        val soldQty: Double,
        val remainingQty: Double,
        val reason: String,  // "PARTIAL_LADDER_R1", "STRICT_SL_PARTIAL", etc.
        override val timestamp: Long
    ) : PositionMutation(mint, symbol, version, timestamp)
    
    /**
     * Full exit (position closed, qty = 0).
     */
    data class Close(
        override val mint: String,
        override val symbol: String,
        override val version: Long,
        val previousVersion: Long,
        val finalPnL: Double,
        val reason: String,  // "STOP_LOSS", "TAKE_PROFIT", "HARD_FLOOR", etc.
        override val timestamp: Long
    ) : PositionMutation(mint, symbol, version, timestamp)
    
    /**
     * Position recovered from host-wallet (e.g., after app restart).
     */
    data class Recovered(
        override val mint: String,
        override val symbol: String,
        override val version: Long,
        val position: Position,
        val recoveryReason: String,  // "STARTUP_REHYDRATE", "WALLET_RECONCILIATION", etc.
        override val timestamp: Long
    ) : PositionMutation(mint, symbol, version, timestamp)
    
    /**
     * Position resolved as corrupted and forcibly closed.
     */
    data class Corrupted(
        override val mint: String,
        override val symbol: String,
        override val version: Long,
        val corruptionReason: String,
        val detectedBy: String,  // "PositionReconciler", "HostWalletTracker", etc.
        override val timestamp: Long
    ) : PositionMutation(mint, symbol, version, timestamp)
}
