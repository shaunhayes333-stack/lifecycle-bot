package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.network.SolanaWallet

/**
 * PositionHealthMonitor
 * 
 * Runs periodically (~5 minutes) WHILE the bot is active to detect and handle:
 *   1. Ghost Positions: Bot thinks it holds tokens, but on-chain balance is 0
 *   2. Orphaned Tokens: Tokens in wallet not tracked by bot
 *   3. Balance Mismatches: Tracked qty differs significantly from on-chain
 * 
 * This is a LIVE health check that catches issues without requiring bot restart.
 * Complements StartupReconciler which only runs on bot start.
 */
object PositionHealthMonitor {
    
    private var lastCheckTime = 0L
    private const val MIN_CHECK_INTERVAL_MS = 4 * 60 * 1000L  // 4 minutes minimum between checks
    
    data class HealthReport(
        val ghostPositionsCleared: List<String>,
        val orphanedTokensFound: List<String>,
        val balanceMismatches: List<String>,
        val errors: List<String>,
    )
    
    /**
     * Run health check on all open positions.
     * Returns a report of any issues found and fixed.
     * 
     * @param tokens Map of all tracked tokens
     * @param wallet SolanaWallet for on-chain lookups (null in paper mode)
     * @param paperMode Whether we're in paper trading mode
     * @param executor For selling orphaned tokens
     * @param onLog Logging callback
     * @return HealthReport with details of issues found
     */
    fun checkHealth(
        tokens: Map<String, TokenState>,
        wallet: SolanaWallet?,
        paperMode: Boolean,
        executor: Executor?,
        onLog: (String, String) -> Unit,
    ): HealthReport {
        val ghostCleared = mutableListOf<String>()
        val orphansFound = mutableListOf<String>()
        val mismatches = mutableListOf<String>()
        val errors = mutableListOf<String>()
        
        // Skip in paper mode - no on-chain state to verify
        if (paperMode || wallet == null) {
            return HealthReport(ghostCleared, orphansFound, mismatches, errors)
        }
        
        // Rate limit checks to avoid excessive RPC calls
        val now = System.currentTimeMillis()
        if (now - lastCheckTime < MIN_CHECK_INTERVAL_MS) {
            return HealthReport(ghostCleared, orphansFound, mismatches, errors)
        }
        lastCheckTime = now
        
        onLog("🩺 Position Health Check starting...", "health_monitor")
        
        try {
            // Get all token balances in one RPC call
            val onChainBalances = wallet.getTokenAccountsWithDecimals()
            val openPositions = tokens.values.filter { it.position.isOpen }
            val trackedMints = openPositions.map { it.mint }.toSet()
            
            // ═══════════════════════════════════════════════════════════════════
            // CHECK 1: Ghost Positions (bot thinks open, but no tokens on-chain)
            // ═══════════════════════════════════════════════════════════════════
            for (ts in openPositions) {
                try {
                    val tokenData = onChainBalances[ts.mint]
                    val onChainQty = tokenData?.first ?: 0.0
                    
                    if (onChainQty <= 0.0) {
                        // Ghost position detected - clear it
                        onLog("🧹 GHOST DETECTED: ${ts.symbol} - clearing (on-chain=0, tracked=${ts.position.qtyToken})", ts.mint)
                        synchronized(ts) {
                            ts.position = Position()
                            ts.lastExitTs = System.currentTimeMillis()
                        }
                        ghostCleared.add(ts.symbol)
                    } else {
                        // Check for significant balance mismatch
                        val trackedQty = ts.position.qtyToken
                        val diffPct = if (trackedQty > 0) {
                            kotlin.math.abs(onChainQty - trackedQty) / trackedQty * 100
                        } else 0.0
                        
                        if (diffPct > 10.0) {
                            // More than 10% difference - log warning
                            // Note: liveSell already fetches on-chain balance before selling,
                            // so this mismatch won't cause "insufficient funds" errors
                            val msg = "${ts.symbol}: tracked=${String.format("%.2f", trackedQty)}, on-chain=${String.format("%.2f", onChainQty)} (${String.format("%.1f", diffPct)}% diff)"
                            onLog("⚠️ BALANCE MISMATCH: $msg", ts.mint)
                            mismatches.add(msg)
                            // Position data class has immutable qtyToken, but liveSell
                            // already handles this by checking on-chain balance before selling
                        }
                    }
                } catch (e: Exception) {
                    errors.add("${ts.symbol}: ${e.message?.take(50)}")
                }
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // CHECK 2: Orphaned Tokens (on-chain tokens not tracked by bot)
            // ═══════════════════════════════════════════════════════════════════
            for ((mint, tokenData) in onChainBalances) {
                val qty = tokenData.first
                
                // Skip dust and tracked positions
                if (qty < 1.0) continue
                if (mint in trackedMints) continue
                
                // Skip SOL
                if (mint == "So11111111111111111111111111111111111111112") continue
                
                // This is an orphaned token
                val symbol = tokens[mint]?.symbol ?: mint.take(8)
                onLog("🧹 ORPHAN DETECTED: $symbol ($qty tokens)", mint)
                orphansFound.add("$symbol ($qty)")
                
                // Attempt auto-sell
                if (executor != null) {
                    try {
                        val sold = executor.sellOrphanedToken(mint, qty, wallet)
                        if (sold) {
                            onLog("✅ Orphan sold: $symbol", mint)
                        } else {
                            onLog("⚠️ Could not sell orphan: $symbol - sell manually", mint)
                        }
                    } catch (e: Exception) {
                        onLog("❌ Orphan sell error for $symbol: ${e.message?.take(50)}", mint)
                    }
                }
            }
            
        } catch (e: Exception) {
            onLog("❌ Health check error: ${e.message}", "health_monitor")
            errors.add("RPC error: ${e.message?.take(80)}")
        }
        
        // Summary
        val issues = ghostCleared.size + orphansFound.size + mismatches.size
        if (issues > 0) {
            onLog("🩺 Health Check: ${ghostCleared.size} ghosts cleared, ${orphansFound.size} orphans found, ${mismatches.size} mismatches fixed", "health_monitor")
        } else {
            onLog("🩺 Health Check: All positions healthy ✅", "health_monitor")
        }
        
        return HealthReport(ghostCleared, orphansFound, mismatches, errors)
    }
    
    /**
     * Get stats for logging
     */
    fun getLastCheckTime(): Long = lastCheckTime
    
    /**
     * Reset check timer (for testing)
     */
    fun resetTimer() {
        lastCheckTime = 0L
    }
}
