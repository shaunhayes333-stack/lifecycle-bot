package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6444 — TRADER SIZING BRIDGE.
 *
 * OPERATOR MANDATE (V5.0.6443 next actions):
 *   "Trader Sizing Sites: Extend OrderSizeResolver6441 routing to every
 *    trader (BlueChip / Quality / Treasury / Moonshot / Shitcoin) so no
 *    lane sizes outside the canonical resolver."
 *
 * DESIGN
 * ──────
 * Each lane trader has its own sizing quirks (Treasury caps at 0.5 SOL,
 * Moonshot only 0.02, BlueChip escalates with wallet, etc). Rewriting
 * each trader is a per-lane refactor. This bridge is the CANONICAL
 * wrapper every trader can call:
 *
 *   resolveForLane(laneName, requestedSol, walletSol, paperMode)
 *      → resolved size that respects the lane's cap AND the canonical
 *        OrderSizeResolver6441 pipeline (risk → ladder → cash cap →
 *        lane cap → min executable).
 *
 * The bridge holds a per-lane risk cap map so every lane's ceiling is
 * declared in ONE place. Callers that want to override for a specific
 * strategy pass an explicit laneRiskCapSol.
 */
object TraderSizingBridge6444 {

    // V5.0.6552 — lane names are attribution, not eternal SOL ceilings.
    // Hard limits come from wallet-percent, liquidity, and portfolio risk at
    // resolution time; learned lane conviction may shape the proposal.
    private const val DEFAULT_WALLET_RISK_PCT_6552 = 0.12
    private const val DEFAULT_PORTFOLIO_CAP_SOL_6552 = 5.0

    private val invocations = AtomicLong(0L)
    private val perLaneInvocations = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Canonical entry point for lane traders. Every BUY sizing call from
     * a lane trader should go through this bridge.
     */
    fun resolveForLane(
        laneName: String,
        requestedSol: Double,
        walletSol: Double,
        paperMode: Boolean,
        overrideLaneRiskCapSol: Double? = null,
        mintForSeal: String = "",
        walletRiskPct: Double = DEFAULT_WALLET_RISK_PCT_6552,
        portfolioCapSol: Double = DEFAULT_PORTFOLIO_CAP_SOL_6552,
    ): OrderSizeResolver6441.Resolution {
        invocations.incrementAndGet()
        perLaneInvocations.computeIfAbsent(laneName) { AtomicLong(0L) }.incrementAndGet()
        val laneKey = laneName.uppercase()
        val dynamicWalletCap = (walletSol.coerceAtLeast(0.0) * walletRiskPct.coerceIn(0.0, 1.0))
        val laneCap = overrideLaneRiskCapSol ?: dynamicWalletCap.coerceAtMost(portfolioCapSol)
        return try {
            val r = OrderSizeResolver6441.resolve(
                requestedSol = requestedSol,
                laneName = laneKey,
                walletSol = walletSol,
                paperMode = paperMode,
                laneRiskCapSol = laneCap,
                laneMinExecutableSol = if (paperMode) OrderSizeResolver6441.paperExecutableMinimumSol() else 0.001,
            )
            // V5.0.6497 §1 — seal executable resolution for the mint so
            // downstream execution readers cannot re-compute a smaller
            // value. TraderSizingBridge6444.sizeForLane forwards blank
            // mint and does NOT seal (unchanged for backward compat).
            if (mintForSeal.isNotBlank() && r.executable) {
                try { SealedOrderSizeAuthority6497.sealFor(mintForSeal, r, laneKey) } catch (_: Throwable) {}
            }
            r
        } catch (t: Throwable) {
            try {
                ForensicLogger.lifecycle(
                    "TRADER_SIZING_BRIDGE_FAIL_6444",
                    "lane=$laneKey err=${t.message?.take(60)}",
                )
            } catch (_: Throwable) {}
            // V5.0.6485 — sizing exceptions cannot claim executable and
            // defer a contradictory rejection into the paper executor.
            OrderSizeResolver6441.Resolution(
                requestedSol = requestedSol, riskSol = 0.0, ladderSol = 0.0,
                cashCapSol = 0.0, laneCapSol = laneCap, finalSizeSol = 0.0,
                executable = false, reason = "BRIDGE_RESOLUTION_FAILED_6485",
            )
        }
    }

    /** Convenience — returns just the final SOL size. */
    fun sizeForLane(
        laneName: String,
        requestedSol: Double,
        walletSol: Double,
        paperMode: Boolean,
    ): Double = resolveForLane(laneName, requestedSol, walletSol, paperMode).finalSizeSol

    fun statusLine(): String {
        val n = invocations.get()
        val perLane = perLaneInvocations.entries
            .sortedByDescending { it.value.get() }
            .take(5)
            .joinToString(",") { "${it.key}=${it.value.get()}" }
        return "invocations=$n top5=[$perLane]"
    }

    /** Dynamic cap policy for pipeline health inspection. */
    fun declaredCaps(): String = "walletPct=$DEFAULT_WALLET_RISK_PCT_6552 portfolioCapSol=$DEFAULT_PORTFOLIO_CAP_SOL_6552"
}
