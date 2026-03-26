package com.lifecyclebot.engine

import com.lifecyclebot.data.BotStatus
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.network.SolanaWallet

/**
 * PositionHealthMonitor
 * 
 * Runs periodically (~5 minutes) WHILE the bot is active to detect and handle:
 *   1. Ghost Positions: Bot thinks it holds tokens, but on-chain balance is 0
 *   2. Orphaned Tokens: Tokens in wallet not tracked by bot
 * 
 * This is a LIVE health check that catches issues without requiring bot restart.
 * Complements StartupReconciler which only runs on bot start.
 */
class PositionHealthMonitor(
    private val wallet: SolanaWallet,
    private val status: BotStatus,
    private val onLog: (String) -> Unit,
    private val onAlert: (String, String) -> Unit,
    private val executor: Executor? = null,
) {
    companion object {
        private var lastCheckTime = 0L
        private const val MIN_CHECK_INTERVAL_MS = 4 * 60 * 1000L  // 4 minutes minimum
    }

    data class HealthReport(
        val ghostPositionsCleared: Int,
        val orphanedTokensFound: Int,
        val orphanedTokensSold: Int,
    )

    /**
     * Run health check on all open positions.
     * Returns a report of any issues found and fixed.
     */
    fun checkHealth(): HealthReport {
        // Rate limit checks to avoid excessive RPC calls
        val now = System.currentTimeMillis()
        if (now - lastCheckTime < MIN_CHECK_INTERVAL_MS) {
            return HealthReport(0, 0, 0)
        }
        lastCheckTime = now

        onLog("🩺 Position Health Check starting...")

        var ghostsCleared = 0
        var orphansFound = 0
        var orphansSold = 0

        try {
            // Get all token balances in one RPC call
            val tokenAccounts = wallet.getTokenAccounts()
            val openPositions = status.openPositions
            val trackedMints = openPositions.map { it.mint }.toSet()

            // CHECK 1: Ghost Positions
            openPositions.forEach { ts ->
                try {
                    val onChainQty = tokenAccounts[ts.mint] ?: 0.0

                    if (onChainQty <= 0.0) {
                        onLog("🧹 GHOST: ${ts.symbol} - on-chain=0, clearing")
                        synchronized(ts) {
                            ts.position = com.lifecyclebot.data.Position()
                            ts.lastExitTs = System.currentTimeMillis()
                        }
                        ghostsCleared++
                        onAlert("Ghost Cleared", "${ts.symbol}: position cleared (no tokens on-chain)")
                    }
                } catch (e: Exception) {
                    onLog("⚠️ Health check error for ${ts.symbol}: ${e.message}")
                }
            }

            // CHECK 2: Orphaned Tokens
            tokenAccounts.forEach { (mint, qty) ->
                if (qty < 1.0) return@forEach
                if (mint in trackedMints) return@forEach
                if (mint == "So11111111111111111111111111111111111111112") return@forEach

                orphansFound++
                val symbol = status.tokens[mint]?.symbol ?: mint.take(8)
                onLog("🧹 ORPHAN: $symbol ($qty tokens)")

                if (executor != null) {
                    try {
                        val sold = executor.sellOrphanedToken(mint, qty, wallet)
                        if (sold) {
                            orphansSold++
                            onLog("✅ Orphan sold: $symbol")
                            onAlert("Orphan Sold", "Auto-sold $symbol")
                        }
                    } catch (e: Exception) {
                        onLog("⚠️ Orphan sell failed: ${e.message}")
                    }
                }
            }

        } catch (e: Exception) {
            onLog("❌ Health check error: ${e.message}")
        }

        // Summary
        if (ghostsCleared > 0 || orphansFound > 0) {
            onLog("🩺 Health: $ghostsCleared ghosts, $orphansFound orphans ($orphansSold sold)")
        } else {
            onLog("🩺 Health: All positions OK")
        }

        return HealthReport(ghostsCleared, orphansFound, orphansSold)
    }
}
