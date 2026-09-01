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

    // V5.0.6630 §D — set of MEME specialist lane keys that must NOT
    // use this generic bridge. Alarm-only for now.
    private val SPECIALIST_LANE_KEYS_6630 = setOf(
        "SHITCOIN", "MOONSHOT", "CORE", "BLUECHIP", "EXPRESS",
        "PROJECT_SNIPER", "CYCLIC", "QUALITY", "DIP_HUNTER",
        "MANIPULATED", "TREASURY", "CASHGEN",
    )

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
        // V5.0.6630 §D SPECIALIST_MISROUTE_DIAGNOSTIC (operator Feb 2026:
        //   "SHITCOIN/MOONSHOT/BLUECHIP and the other specialist lanes must
        //    NOT be routed through TraderSizingBridge6444 as generic traders.
        //    The generic TraderSizingBridge remains for genuinely generic/
        //    non-specialist trading only.")
        // Alarm-only diagnostic: increment SPECIALIST_GENERIC_BRIDGE_
        // MISROUTE_6630 when a known meme specialist lane hits the generic
        // bridge. The full refactor (routing every specialist through
        // CanonicalSizingBridge6532 with per-asset-class shaping) is a
        // follow-up; the alarm makes the misroute grep-visible today.
        try {
            if (laneKey in SPECIALIST_LANE_KEYS_6630) {
                com.lifecyclebot.engine.PipelineHealthCollector
                    .labelInc("SPECIALIST_GENERIC_BRIDGE_MISROUTE_6630")
                com.lifecyclebot.engine.PipelineHealthCollector
                    .labelInc("SPECIALIST_GENERIC_BRIDGE_MISROUTE_${laneKey}_6630")
                // V5.0.6633 §P0-K SPECIALIST_AUTO_REROUTE (operator Feb 2026:
                //   "BLUECHIP/MOONSHOT/EXPRESS/... must use ONE common
                //    canonical mark/sizing resolver."). Auto-reroute the
                //   misroute through CanonicalSizingBridge6532 so a
                //   specialist lane accidentally hitting the generic bridge
                //   is still resolved by the correct authority. Alarm-only
                //   diagnostic (previous behaviour) is preserved via the
                //   two labels above.
                val classForRoute6633 = AssetClass.fromLane(laneKey)
                val rerouted6633 = CanonicalSizingBridge6532.resolve(
                    requestedSol = requestedSol,
                    assetClass = classForRoute6633,
                    laneName = laneKey,
                    walletSol = walletSol,
                    paperMode = paperMode,
                    laneRiskCapSol = overrideLaneRiskCapSol ?: DEFAULT_PORTFOLIO_CAP_SOL_6552,
                    laneMinExecutableSol = if (paperMode) OrderSizeResolver6441.paperExecutableMinimumSol() else 0.001,
                    canonicalAssetId = mintForSeal,
                    symbol = mintForSeal.ifBlank { laneKey },
                    source = "TraderSizingBridge6444.auto_reroute_6633",
                )
                com.lifecyclebot.engine.PipelineHealthCollector
                    .labelInc("SPECIALIST_AUTO_REROUTED_TO_CANONICAL_6633")
                com.lifecyclebot.engine.PipelineHealthCollector
                    .labelInc("SPECIALIST_AUTO_REROUTED_${laneKey}_6633")
                if (mintForSeal.isNotBlank() && rerouted6633.executable) {
                    try { SealedOrderSizeAuthority6497.sealFor(mintForSeal, rerouted6633, laneKey) } catch (_: Throwable) {}
                }
                return rerouted6633
            }
        } catch (_: Throwable) {}
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
