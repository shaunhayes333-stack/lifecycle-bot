package com.lifecyclebot.data

import com.lifecyclebot.engine.position.CanonicalPositionStore
import com.lifecyclebot.engine.ForensicLogger

/**
 * V5.0.6431 — MIGRATION GUIDE: BotStatus → CanonicalPositionStore
 *
 * BotStatus.openPositions previously tried to be the source of truth but was actually
 * a cache that could diverge from lane-specific mirrors. This caused the split-brain
 * errors documented in errors #1 and #10.
 *
 * New architecture:
 *   - CanonicalPositionStore = single source of truth (all mutations go here)
 *   - BotStatus.openPositions = read-only view delegating to canonical
 *   - Lane-specific position maps = derived from canonical (not independent)
 *   - HostWalletTokenTracker = authority check (wallet always wins on conflicts)
 *
 * This file documents the migration and provides compatibility shims.
 */

/**
 * V5.0.6431 — Replace:
 *   val tokenState = TokenState(...)
 *   status.tokens[mint] = tokenState
 *
 * With:
 *   CanonicalPositionStore.openPosition(
 *     mint = mint,
 *     symbol = tokenState.symbol,
 *     position = tokenState.position,
 *     reason = "ENTRY_BUY"  // or appropriate reason
 *   ).onSuccess { versionedPos ->
 *     // Use versionedPos.position as the canonical position
 *   }
 */
object BotStatusMigration {
    
    /**
     * Called at startup to validate existing persisted positions.
     * If they exist in storage but not in canonical, they're recovered.
     * If they exist in canonical but wallet is empty, they're ghosted.
     */
    suspend fun validateAndMigratePersistedPositions(
        persistedPositions: Map<String, TokenState>
    ) {
        ForensicLogger.lifecycle(
            "MIGRATION_VALIDATING_PERSISTED_POSITIONS",
            "count=${persistedPositions.size}"
        )
        
        for ((mint, tokenState) in persistedPositions) {
            try {
                if (tokenState.position.isOpen && tokenState.position.qtyToken > 0.0) {
                    CanonicalPositionStore.openPosition(
                        mint = mint,
                        symbol = tokenState.symbol,
                        position = tokenState.position,
                        reason = "MIGRATION_RECOVERED_FROM_STORAGE"
                    ).onSuccess {
                        ForensicLogger.lifecycle(
                            "MIGRATION_RECOVERED_POSITION",
                            "mint=${mint.take(10)} symbol=${tokenState.symbol}"
                        )
                    }.onFailure { e ->
                        ForensicLogger.lifecycle(
                            "MIGRATION_RECOVERY_FAILED",
                            "mint=${mint.take(10)} error=${e.message}"
                        )
                    }
                }
            } catch (e: Exception) {
                ForensicLogger.lifecycle(
                    "MIGRATION_EXCEPTION",
                    "mint=$mint error=${e.message}"
                )
            }
        }
    }
    
    /**
     * Called from lane exit paths instead of mutating status.tokens directly.
     * Ensures all mutations are canonical and journalled.
     */
    fun partialSellViaCanonical(
        mint: String,
        remainingQty: Double,
        soldQty: Double,
        reason: String
    ): Result<CanonicalPositionStore.VersionedPosition> {
        return CanonicalPositionStore.partialSell(
            mint = mint,
            remainingQty = remainingQty,
            soldQty = soldQty,
            reason = reason
        )
    }
    
    /**
     * Called from exit paths when position is fully closed.
     */
    fun closeViaCanonical(
        mint: String,
        finalPnL: Double,
        reason: String
    ): Result<CanonicalPositionStore.VersionedPosition> {
        return CanonicalPositionStore.closePosition(
            mint = mint,
            finalPnL = finalPnL,
            reason = reason
        )
    }
}
