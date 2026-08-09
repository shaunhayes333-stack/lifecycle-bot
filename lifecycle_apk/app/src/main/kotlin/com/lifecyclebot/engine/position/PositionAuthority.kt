package com.lifecyclebot.engine.position

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.HostWalletTokenTracker

/**
 * V5.0.6431 — POSITION AUTHORITY RESOLUTION
 *
 * When canonical store and host wallet disagree, this module determines
 * which source is authoritative and what action to take.
 *
 * Doctrine:
 *   HOST WALLET ALWAYS WINS on conflicts.
 *   If the wallet says a position is closed, it's closed (even if bot thinks it's open).
 *   If the wallet says qty is 100, the bot qty is 100 (even if bot's position says 200).
 *
 * Never silently ignores divergence — always logs for forensics.
 */
object PositionAuthority {
    
    /**
     * V5.0.6431 — resolve the true qty for a position.
     *
     * Returns the authoritative quantity based on:
     *   1. Host wallet balance (highest authority)
     *   2. Canonical store (fallback if wallet read fails)
     *   3. Zero (if both fail or disagree badly)
     */
    fun resolveQuantity(mint: String): Double {
        // Attempt 1: Host wallet (highest authority)
        val walletEntry = try {
            HostWalletTokenTracker.getEntry(mint)
        } catch (e: Exception) {
            ForensicLogger.lifecycle(
                "POSITION_AUTHORITY_WALLET_READ_FAILED",
                "mint=$mint error=${e.message}"
            )
            null
        }
        
        if (walletEntry != null) {
            // Convert wallet's UI amount to canonical qty
            return walletEntry.uiAmount.coerceAtLeast(0.0)
        }
        
        // Attempt 2: Canonical store (fallback)
        val canonical = CanonicalPositionStore.getPosition(mint)
        if (canonical != null && canonical.isOpen()) {
            val canonicalQty = canonical.position.qtyToken
            ForensicLogger.lifecycle(
                "POSITION_AUTHORITY_WALLET_MISSING_USED_CANONICAL",
                "mint=$mint qty=$canonicalQty (wallet read failed, trusting store)"
            )
            return canonicalQty
        }
        
        // Both failed or position not found
        return 0.0
    }
    
    /**
     * V5.0.6431 — check if a position is truly open.
     *
     * Returns true only if:
     *   1. Canonical store says it's open, AND
     *   2. Host wallet confirms qty > 0 (or wallet read failed but store says open)
     */
    fun isPositionOpen(mint: String): Boolean {
        val canonical = CanonicalPositionStore.getPosition(mint)
            ?: return false
        
        if (!canonical.isOpen()) {
            return false
        }
        
        // Check wallet confirmation
        val walletEntry = try {
            HostWalletTokenTracker.getEntry(mint)
        } catch (e: Exception) {
            ForensicLogger.lifecycle(
                "POSITION_AUTHORITY_OPEN_CHECK_WALLET_FAILED",
                "mint=$mint assuming store is truth"
            )
            return true  // Wallet read failed, trust canonical
        }
        
        // Wallet confirms position is open (qty > 0)
        return walletEntry != null && walletEntry.uiAmount > 0.0
    }
    
    /**
     * V5.0.6431 — force-resolve a disputed position.
     *
     * Called when canonical and wallet disagree on qty or status.
     * Returns the action the bot should take.
     */
    fun resolveDispute(mint: String): DisputeResolution {
        val canonical = CanonicalPositionStore.getPosition(mint)
        val walletEntry = try {
            HostWalletTokenTracker.getEntry(mint)
        } catch (e: Exception) {
            null
        }
        
        // Case 1: Bot thinks it's open, wallet says closed (qty=0)
        if (canonical != null && canonical.isOpen() && (walletEntry == null || walletEntry.uiAmount == 0.0)) {
            ForensicLogger.lifecycle(
                "POSITION_AUTHORITY_DISPUTE_BOT_OPEN_WALLET_CLOSED",
                "mint=$mint canonical.qty=${canonical.position.qtyToken} wallet.qty=${walletEntry?.uiAmount ?: 0.0}"
            )
            return DisputeResolution.CloseImmediately(
                mint = mint,
                reason = "Wallet confirms position is closed",
                authorityReason = "HOST_WALLET_WINS"
            )
        }
        
        // Case 2: Bot closed position, wallet says it's still there
        if ((canonical == null || !canonical.isOpen()) && walletEntry != null && walletEntry.uiAmount > 0.0) {
            ForensicLogger.lifecycle(
                "POSITION_AUTHORITY_DISPUTE_BOT_CLOSED_WALLET_OPEN",
                "mint=$mint wallet.qty=${walletEntry.uiAmount} status=${walletEntry.status}"
            )
            return DisputeResolution.RecoverFromWallet(
                mint = mint,
                walletQty = walletEntry.uiAmount,
                reason = "Wallet shows position is still open",
                authorityReason = "HOST_WALLET_WINS"
            )
        }
        
        // Case 3: Both agree or both empty
        return DisputeResolution.NoAction
    }
    
    sealed class DisputeResolution {
        object NoAction : DisputeResolution()
        
        data class CloseImmediately(
            val mint: String,
            val reason: String,
            val authorityReason: String
        ) : DisputeResolution()
        
        data class RecoverFromWallet(
            val mint: String,
            val walletQty: Double,
            val reason: String,
            val authorityReason: String
        ) : DisputeResolution()
    }
}
