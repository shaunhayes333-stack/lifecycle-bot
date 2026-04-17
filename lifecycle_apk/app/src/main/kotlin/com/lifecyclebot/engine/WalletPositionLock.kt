package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * WalletPositionLock — Cross-Trader Exposure Control
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Prevents all 5 traders from over-committing the shared wallet.
 * Tracks total deployed SOL across Meme, CryptoAlt, Stocks, Commodities,
 * Metals, Forex — ensures max 80% wallet exposure at any time.
 *
 * Zero impact on scanning or signal generation.
 * Only gates the final execution moment.
 */
object WalletPositionLock {

    private const val TAG = "WalletLock"

    // Max percentage of wallet that can be deployed across ALL traders
    @Volatile
    var maxExposurePct: Double = 80.0

    // Track deployed SOL per trader
    private val deployedSol = ConcurrentHashMap<String, Double>()

    // Atomic lock for concurrent access
    private val lockCounter = AtomicInteger(0)

    /**
     * Check if a new trade can be opened given current exposure.
     *
     * @param traderName Identifier (e.g., "Meme", "CryptoAlt", "Stocks")
     * @param sizeSol    SOL amount this trade wants to deploy
     * @param walletSol  Current total wallet balance
     * @return true if trade is allowed, false if it would exceed exposure cap
     */
    fun canOpen(traderName: String, sizeSol: Double, walletSol: Double): Boolean {
        if (walletSol <= 0.01) return false

        val totalDeployed = getTotalDeployed()
        val maxAllowed = walletSol * (maxExposurePct / 100.0)
        val afterTrade = totalDeployed + sizeSol

        if (afterTrade > maxAllowed) {
            ErrorLogger.info(TAG, "🔒 BLOCKED: $traderName wants ${String.format("%.4f", sizeSol)} SOL | " +
                "deployed=${String.format("%.4f", totalDeployed)}/${String.format("%.4f", maxAllowed)} (${maxExposurePct.toInt()}% cap)")
            return false
        }

        return true
    }

    /**
     * Record that a trade has been opened.
     */
    fun recordOpen(traderName: String, sizeSol: Double) {
        val current = deployedSol.getOrDefault(traderName, 0.0)
        deployedSol[traderName] = current + sizeSol
        lockCounter.incrementAndGet()
        ErrorLogger.debug(TAG, "📈 +${String.format("%.4f", sizeSol)} SOL by $traderName | total=${String.format("%.4f", getTotalDeployed())}")
    }

    /**
     * Record that a trade has been closed.
     */
    fun recordClose(traderName: String, sizeSol: Double) {
        val current = deployedSol.getOrDefault(traderName, 0.0)
        deployedSol[traderName] = (current - sizeSol).coerceAtLeast(0.0)
        ErrorLogger.debug(TAG, "📉 -${String.format("%.4f", sizeSol)} SOL by $traderName | total=${String.format("%.4f", getTotalDeployed())}")
    }

    /**
     * Get total deployed SOL across all traders.
     */
    fun getTotalDeployed(): Double = deployedSol.values.sum()

    /**
     * Get current exposure percentage.
     */
    fun getExposurePct(walletSol: Double): Double {
        if (walletSol <= 0.01) return 0.0
        return (getTotalDeployed() / walletSol * 100.0).coerceIn(0.0, 100.0)
    }

    /**
     * Get per-trader breakdown.
     */
    fun getBreakdown(): Map<String, Double> = HashMap(deployedSol)

    /**
     * Reset on bot restart (positions will re-register on load).
     */
    fun reset() {
        deployedSol.clear()
        lockCounter.set(0)
    }

    fun getDiagnostics(walletSol: Double): String {
        val total = getTotalDeployed()
        val pct = getExposurePct(walletSol)
        val breakdown = deployedSol.entries.joinToString(", ") { "${it.key}=${String.format("%.3f", it.value)}" }
        return "WalletLock: ${String.format("%.4f", total)} SOL deployed (${String.format("%.1f", pct)}% of ${String.format("%.4f", walletSol)}) | cap=${maxExposurePct.toInt()}% | [$breakdown]"
    }
}
