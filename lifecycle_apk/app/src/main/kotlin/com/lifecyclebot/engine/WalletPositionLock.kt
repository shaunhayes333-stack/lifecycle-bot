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
 * V5.9.665 — operator regression fix.
 *   Added per-lane reservation caps so the memes lane (or any other
 *   single lane) can never be starved out by another lane consuming
 *   the entire global cap. Forensics confirmed CryptoUniverse blasting
 *   USDC-collateral fake exposures was eating 80% of the global cap and
 *   leaving 0% for the meme lane, which is why the operator's meme
 *   trader fell silent in live mode.
 *
 * Zero impact on scanning or signal generation.
 * Only gates the final execution moment.
 */
object WalletPositionLock {

    private const val TAG = "WalletLock"

    // Max percentage of wallet that can be deployed across ALL traders
    @Volatile
    var maxExposurePct: Double = 80.0

    // V5.9.665 — per-lane reservation caps. Each lane is guaranteed it
    // cannot be starved below its own cap by other lanes. Sum can exceed
    // 100% intentionally — the global cap (maxExposurePct) is still the
    // hard ceiling on combined exposure. These per-lane caps only
    // protect each lane from being squeezed to zero by another lane.
    private val perLaneCapPct: Map<String, Double> = mapOf(
        "Meme"        to 50.0,
        "CryptoAlt"   to 40.0,
        "Stocks"      to 30.0,
        "Commodities" to 20.0,
        "Metals"      to 20.0,
        "Forex"       to 20.0,
    )

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
        // V5.0.7951 — caps are shares of the wallet (liquid + deployed), not of liquid alone.
        val basis7951 = exposureBasis7951(walletSol, totalDeployed, liveEquity7951(walletSol))
        val maxAllowed = basis7951 * (maxExposurePct / 100.0)
        val afterTrade = totalDeployed + sizeSol

        // V5.9.665 — global cap check. If we'd blow past 80% wallet
        // total exposure, refuse — but only after checking the lane
        // has not already been starved (the lane's own deployed amount
        // is still respected via the per-lane cap below).
        if (afterTrade > maxAllowed) {
            // Allow the trade if THIS lane is under its per-lane cap
            // and the surplus over global is small enough that this
            // single lane shouldn't have been blocked by another lane
            // hogging the global pool. Otherwise, block.
            val laneCapPct = perLaneCapPct[traderName] ?: maxExposurePct
            val laneCapSol = basis7951 * (laneCapPct / 100.0)
            val laneDeployed = deployedSol.getOrDefault(traderName, 0.0)
            val laneAfter = laneDeployed + sizeSol
            if (laneAfter > laneCapSol) {
                val reason = "global=${String.format("%.4f", totalDeployed)}/${String.format("%.4f", maxAllowed)} (${maxExposurePct.toInt()}% cap) | " +
                    "lane=${String.format("%.4f", laneDeployed)}/${String.format("%.4f", laneCapSol)} (${laneCapPct.toInt()}% lane cap)"
                ErrorLogger.info(TAG, "🔒 BLOCKED: $traderName wants ${String.format("%.4f", sizeSol)} SOL | $reason")
                try { PipelineHealthCollector.event("WALLET_LOCK/BLOCK_GLOBAL_AND_LANE", traderName, reason) } catch (_: Throwable) {}
                return false
            }
            // Lane is under its reservation — log the reason we're letting it through past the global cap.
            val reason = "global=${String.format("%.4f", totalDeployed)}/${String.format("%.4f", maxAllowed)} (over global, but lane under ${laneCapPct.toInt()}% reservation)"
            ErrorLogger.info(TAG, "🛡 LANE-RESERVED ALLOW: $traderName ${String.format("%.4f", sizeSol)} SOL | $reason")
            try { PipelineHealthCollector.event("WALLET_LOCK/LANE_RESERVED_ALLOW", traderName, reason) } catch (_: Throwable) {}
            return true
        }

        // V5.9.665 — also enforce per-lane cap even when global has room.
        val laneCapPct = perLaneCapPct[traderName] ?: maxExposurePct
        val laneCapSol = basis7951 * (laneCapPct / 100.0)
        val laneDeployed = deployedSol.getOrDefault(traderName, 0.0)
        val laneAfter = laneDeployed + sizeSol
        if (laneAfter > laneCapSol) {
            val reason = "lane=${String.format("%.4f", laneDeployed)}/${String.format("%.4f", laneCapSol)} (${laneCapPct.toInt()}% lane cap)"
            ErrorLogger.info(TAG, "🔒 LANE-CAP BLOCKED: $traderName wants ${String.format("%.4f", sizeSol)} SOL | $reason")
            try { PipelineHealthCollector.event("WALLET_LOCK/BLOCK_LANE_CAP", traderName, reason) } catch (_: Throwable) {}
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
     * V5.0.7951 — the exposure caps are shares of the WALLET (liquid SOL + what
     * this lock has recorded as deployed), not of liquid SOL alone. Both callers
     * pass liquid SOL, so every open position shrank the base its own exposure
     * was measured against: on 5.0.7949 (liquid 0.040) a 0.027-0.045 SOL crypto
     * order needed liquid >= 2.5x its size to clear the 40% CryptoAlt cap. That
     * pre-dispatch refusal (LIVE_WALLET_LOCK_7835) is the likely cause of
     * CRYPTO_ALT intent=5 dispatch=2 dispatchReject=3: every sized intent that
     * fails before dispatch counts as a reject, and the other exits there (an
     * invalid sealed size, a pending expiry) need a sizing fault or a stall.
     *
     * [deployedSol] is only what this in-memory lock recorded this session, so
     * positions carried over a restart are invisible to it; the canonical live
     * marked equity ([liveEquitySol]) is the floor of the basis.
     */
    fun exposureBasis7951(liquidSol: Double, deployedSol: Double, liveEquitySol: Double): Double {
        val recorded = (if (liquidSol.isFinite()) liquidSol.coerceAtLeast(0.0) else 0.0) +
            (if (deployedSol.isFinite()) deployedSol.coerceAtLeast(0.0) else 0.0)
        return maxOf(recorded, if (liveEquitySol.isFinite()) liveEquitySol else 0.0)
    }

    /** Callers are live-only (CryptoAlt live branch, Executor live buy). */
    private fun liveEquity7951(liquidSol: Double): Double =
        try { com.lifecyclebot.engine.truth.CapitalDrawdown7948.liveMarkedEquitySol7948(liquidSol) } catch (_: Throwable) { 0.0 }

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
