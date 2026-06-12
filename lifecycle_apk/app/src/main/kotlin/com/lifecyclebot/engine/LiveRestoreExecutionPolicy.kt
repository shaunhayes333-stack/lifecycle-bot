package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import kotlin.math.max

/**
 * V5.9.1550 — live trading restore policy.
 *
 * Stale finality / WATCH / host-tracker drift must reduce confidence and size,
 * not terminally choke FDG-approved SAFE/CAUTION candidates. This policy is the
 * economic guardrail: restore only if the reduced live trade can realistically
 * clear round-trip costs. It never bypasses catastrophic safety, bad mints,
 * untradeable routes, confirmed zero liquidity, or true duplicate opens.
 */
object LiveRestoreExecutionPolicy {
    data class Penalty(
        val scorePenalty: Int = 0,
        val sizeMultiplier: Double = 1.0,
        val reason: String = "NONE",
        val liquidityOverrideUsd: Double = 0.0,
    ) {
        fun combine(other: Penalty): Penalty = Penalty(
            scorePenalty = this.scorePenalty + other.scorePenalty,
            sizeMultiplier = (this.sizeMultiplier * other.sizeMultiplier).coerceIn(0.05, 1.0),
            reason = listOf(this.reason, other.reason).filter { it.isNotBlank() && it != "NONE" }.joinToString("+").ifBlank { "NONE" },
            liquidityOverrideUsd = max(this.liquidityOverrideUsd, other.liquidityOverrideUsd),
        )
    }

    data class BreakEven(
        val allowed: Boolean,
        val sizeSol: Double,
        val allInCostPct: Double,
        val expectedEdgePct: Double,
        val minTargetPct: Double,
        val decision: String,
    )

    val NONE = Penalty()

    fun fromRuntimeDrift(liquidityUsd: Double = 0.0): Penalty {
        return try {
            val snap = RuntimeStateSnapshot.current()
            var p = NONE
            if (snap.mode == "LIVE" && snap.hostTrackerOpenCount != snap.liveOpenPositions) {
                p = p.combine(Penalty(scorePenalty = -10, sizeMultiplier = 0.35, reason = "HOST_TRACKER_DESYNC", liquidityOverrideUsd = liquidityUsd))
            }
            if (snap.mode == "LIVE" && snap.orphanLivePositions > 0) {
                p = p.combine(Penalty(scorePenalty = -10, sizeMultiplier = 0.35, reason = "ORPHAN_LIVE_POSITIONS", liquidityOverrideUsd = liquidityUsd))
            }
            if (snap.mode == "LIVE" && snap.reconcilerTotalChecked == 0 && snap.canonicalOpenPositions > 0) {
                p = p.combine(Penalty(scorePenalty = -12, sizeMultiplier = 0.25, reason = "RECONCILER_STALLED", liquidityOverrideUsd = liquidityUsd))
            }
            p
        } catch (_: Throwable) { NONE }
    }

    fun fromStaleWatch(liquidityUsd: Double): Penalty = Penalty(
        scorePenalty = -8,
        sizeMultiplier = 0.50,
        reason = "STALE_WATCH_FINALITY",
        liquidityOverrideUsd = liquidityUsd,
    )

    fun fromLaneCap(liquidityUsd: Double): Penalty = Penalty(
        scorePenalty = -8,
        sizeMultiplier = 0.25,
        reason = "STALE_LANE_CAP_UNCERTAINTY",
        liquidityOverrideUsd = liquidityUsd,
    )

    fun isSafeOrCaution(ts: TokenState): Boolean {
        val tier = try { ts.safety.tier.name.uppercase() } catch (_: Throwable) { "UNKNOWN" }
        return tier == "SAFE" || tier == "CAUTION"
    }

    fun trustedLiquidityUsd(ts: TokenState, overrideUsd: Double = 0.0): Double {
        return max(ts.lastLiquidityUsd, overrideUsd)
    }

    fun mintActuallyOpen(mint: String, ts: TokenState? = null): Boolean {
        return try {
            val local = ts?.position?.isOpen == true || ts?.position?.pendingVerify == true
            val hostHeld = HostWalletTokenTracker.isActuallyHeld(mint)
            val lifecycle = TokenLifecycleTracker.get(mint)?.let {
                it.status != TokenLifecycleTracker.Status.CLEARED &&
                it.status != TokenLifecycleTracker.Status.RECONCILE_FAILED &&
                it.currentWalletTokenQty > 0.000001
            } ?: false
            local || hostHeld || lifecycle
        } catch (_: Throwable) { ts?.position?.isOpen == true }
    }

    fun breakEvenCheck(ts: TokenState, requestedSizeSol: Double, penalty: Penalty, walletSol: Double): BreakEven {
        val liq = trustedLiquidityUsd(ts, penalty.liquidityOverrideUsd)
        val reduced = (requestedSizeSol * penalty.sizeMultiplier).coerceAtMost(requestedSizeSol)
        val capped = if (penalty.reason != "NONE") reduced.coerceIn(0.01, 0.025) else reduced
        val priorityFeeSol = 0.0001
        val buySlippagePct = 5.0.coerceAtMost(1.0 + (2500.0 / liq.coerceAtLeast(1200.0)))
        val expectedSellSlippagePct = 5.0.coerceAtMost(1.5 + (3000.0 / liq.coerceAtLeast(1200.0)))
        val routeFeePct = 0.30
        val priorityFeePct = if (capped > 0.0) (priorityFeeSol * 2.0 / capped) * 100.0 else 999.0
        val spreadPct = 0.75
        val liquidityGivebackPct = when {
            liq < 1200.0 -> 999.0
            liq < 2000.0 -> 3.0
            liq < 5000.0 -> 2.0
            else -> 1.0
        }
        val safetyBufferPct = 1.0
        val allInCostPct = buySlippagePct + expectedSellSlippagePct + routeFeePct + priorityFeePct + spreadPct + liquidityGivebackPct + safetyBufferPct
        val minTarget = allInCostPct + 2.0
        val configuredTp = max(max(ts.position.treasuryTakeProfit, ts.position.blueChipTakeProfit), ts.position.shitCoinTakeProfit)
        val scoreEdge = (ts.entryScore / 2.5).coerceIn(0.0, 45.0)
        val phaseEdge = when (ts.phase.uppercase()) {
            "BREAKOUT", "MOMENTUM", "MARKUP" -> 8.0
            "REACCUMULATION", "PULLBACK" -> 4.0
            else -> 0.0
        }
        val expectedEdge = max(configuredTp.takeIf { it > 0.0 } ?: 0.0, scoreEdge + phaseEdge)
        val fixedFeeTooHigh = priorityFeePct > 2.5
        val exitGivebackTooHigh = expectedSellSlippagePct + liquidityGivebackPct > expectedEdge
        val decision = when {
            capped <= 0.0 || capped > walletSol -> "SKIP_FEES_TOO_HIGH"
            liq < 1200.0 -> "SKIP_BELOW_BREAK_EVEN"
            fixedFeeTooHigh -> "SKIP_FEES_TOO_HIGH"
            exitGivebackTooHigh -> "SKIP_EXIT_GIVEBACK_TOO_HIGH"
            expectedEdge < minTarget -> "SKIP_BELOW_BREAK_EVEN"
            capped < requestedSizeSol -> "EXECUTE_REDUCED_SIZE_BREAK_EVEN_PASS"
            else -> "EXECUTE_BREAK_EVEN_PASS"
        }
        return BreakEven(
            allowed = decision.startsWith("EXECUTE"),
            sizeSol = capped,
            allInCostPct = allInCostPct,
            expectedEdgePct = expectedEdge,
            minTargetPct = minTarget,
            decision = decision,
        )
    }

    fun logBreakEven(ts: TokenState, be: BreakEven, penalty: Penalty) {
        try {
            ForensicLogger.lifecycle(
                "BREAK_EVEN_CHECK",
                "symbol=${ts.symbol} size=${"%.4f".format(be.sizeSol)} allInCostPct=${"%.2f".format(be.allInCostPct)} expectedEdgePct=${"%.2f".format(be.expectedEdgePct)} minTargetPct=${"%.2f".format(be.minTargetPct)} decision=${be.decision} penalty=${penalty.reason}"
            )
        } catch (_: Throwable) {}
    }
}
