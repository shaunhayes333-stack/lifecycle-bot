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
        fun combine(other: Penalty): Penalty {
            val reasons = listOf(this.reason, other.reason)
                .flatMap { it.split("+") }
                .map { it.trim() }
                .filter { it.isNotBlank() && it != "NONE" }
                .distinct()
            return Penalty(
                scorePenalty = this.scorePenalty + other.scorePenalty,
                sizeMultiplier = (this.sizeMultiplier * other.sizeMultiplier).coerceIn(0.05, 1.0),
                reason = reasons.joinToString("+").ifBlank { "NONE" },
                liquidityOverrideUsd = max(this.liquidityOverrideUsd, other.liquidityOverrideUsd),
            )
        }
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

    fun isSlotHealthClean(): Boolean = try {
        val snap = RuntimeStateSnapshot.current()
        snap.mode == "LIVE" &&
            snap.hostTrackerOpenCount == 0 &&
            snap.liveOpenPositions == 0 &&
            snap.orphanLivePositions == 0 &&
            snap.canonicalOpenPositions == 0
    } catch (_: Throwable) { false }

    fun fromRuntimeDrift(liquidityUsd: Double = 0.0): Penalty {
        return try {
            val snap = RuntimeStateSnapshot.current()
            if (snap.mode != "LIVE") return NONE
            if (isSlotHealthClean()) return NONE
            var p = NONE
            if (snap.hostTrackerOpenCount != snap.liveOpenPositions) {
                p = p.combine(Penalty(scorePenalty = -10, sizeMultiplier = 0.35, reason = "HOST_TRACKER_DESYNC", liquidityOverrideUsd = liquidityUsd))
            }
            if (snap.orphanLivePositions > 0) {
                p = p.combine(Penalty(scorePenalty = -10, sizeMultiplier = 0.35, reason = "ORPHAN_LIVE_POSITIONS", liquidityOverrideUsd = liquidityUsd))
            }
            if (snap.reconcilerTotalChecked == 0 && snap.canonicalOpenPositions > 0) {
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

    fun breakEvenCheck(ts: TokenState, requestedSizeSol: Double, penalty: Penalty, walletSol: Double, signalScore: Double = ts.entryScore): BreakEven {
        val liq = trustedLiquidityUsd(ts, penalty.liquidityOverrideUsd)
        val reduced = (requestedSizeSol * penalty.sizeMultiplier).coerceAtMost(requestedSizeSol)
        val liquidityCap = when {
            liq in 150.0..499.999 -> 0.010
            liq in 500.0..799.999 -> 0.015
            liq in 800.0..1199.999 -> 0.020
            else -> requestedSizeSol
        }
        val priorityFeeSol = 0.0001
        // V5.9.1582 — fixed network/priority fees make 0.005 SOL live probes
        // non-executable economics. EXPRESS/drawdown/liquidity penalties may reduce
        // risk size, but they must not shrink below break-even viability. Raise to
        // the minimum fee-viable size when wallet/liquidity permit; otherwise skip.
        val minFeeViableSizeSol = ((priorityFeeSol * 2.0) * 100.0 / 2.5).coerceAtLeast(0.008)
        val cappedRaw = if (penalty.reason != "NONE" || liq < 1200.0) {
            reduced.coerceAtMost(liquidityCap)
        } else reduced
        val capped = when {
            cappedRaw <= 0.0 -> 0.0
            cappedRaw < minFeeViableSizeSol && requestedSizeSol >= minFeeViableSizeSol -> minFeeViableSizeSol.coerceAtMost(requestedSizeSol)
            cappedRaw < minFeeViableSizeSol -> cappedRaw
            else -> cappedRaw
        }
        val buySlippagePct = 5.0.coerceAtMost(1.0 + (2500.0 / liq.coerceAtLeast(1200.0)))
        val expectedSellSlippagePct = 5.0.coerceAtMost(1.5 + (3000.0 / liq.coerceAtLeast(1200.0)))
        val routeFeePct = 0.30
        val priorityFeePct = if (capped > 0.0) (priorityFeeSol * 2.0 / capped) * 100.0 else 999.0
        val spreadPct = 0.75
        val liquidityGivebackPct = when {
            liq < 150.0 -> 999.0
            liq < 500.0 -> 8.0
            liq < 1200.0 -> 5.0
            liq < 2000.0 -> 3.0
            liq < 5000.0 -> 2.0
            else -> 1.0
        }
        val safetyBufferPct = 1.0
        val allInCostPct = buySlippagePct + expectedSellSlippagePct + routeFeePct + priorityFeePct + spreadPct + liquidityGivebackPct + safetyBufferPct
        val minTarget = allInCostPct + 2.0
        val configuredTp = max(max(ts.position.treasuryTakeProfit, ts.position.blueChipTakeProfit), ts.position.shitCoinTakeProfit)
        val scoreEdge = (max(signalScore, ts.entryScore) / 2.5).coerceIn(0.0, 45.0)
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
            capped < minFeeViableSizeSol -> "SKIP_BELOW_MIN_EXECUTABLE_SIZE"
            liq < 150.0 -> "NO_VALID_SELL_ROUTE"
            fixedFeeTooHigh -> "SKIP_FEES_TOO_HIGH"
            exitGivebackTooHigh -> "NOT_PROFITABLE_AFTER_COSTS"
            expectedEdge < minTarget -> "NOT_PROFITABLE_AFTER_COSTS"
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

    // V5.9.1566 — SELL-SIDE break-even with treasury feed-back.
    // ─────────────────────────────────────────────────────────────────────
    // Doctrine (operator): "break-even check belongs at SELL time when the
    // token is held in a positive state. It must consider the profit split
    // so it both feeds the treasury and doesn't self-starve the bot."
    //
    // Treasury takes MEME_SELL_TREASURY_PCT (25%) of realized profit. So the
    // trading wallet only nets (1 - 0.25) = 75% of the realized pnl. For the
    // trade to be net-positive AFTER costs AND treasury feed:
    //     0.75 × pnlPct >= allInCostPct + safetyMargin
    //   ⇒ pnlPct        >= (allInCostPct + safetyMargin) / 0.75
    //
    // Call this from profit-taking exits ONLY (TP, trailing, profit-lock).
    // Do NOT call from stop-loss / hard-floor / emergency / rug exits —
    // those fire on capital protection regardless of break-even math.
    //
    // Returns true if the sell is doctrine-allowed:
    //   - pnl is non-positive (stop-loss path, not our concern), OR
    //   - paper mode (no real costs), OR
    //   - pnl beats true-break-even-with-treasury.
    fun sellSideBreakEvenOk(ts: TokenState, currentPnlPct: Double, isPaper: Boolean): Boolean {
        if (isPaper) return true
        if (currentPnlPct <= 0.0) return true  // stop-loss handles negative side
        val be = breakEvenCheck(ts, ts.position.solSpent.coerceAtLeast(0.01),
            NONE, walletSol = 1.0, signalScore = ts.entryScore)
        val treasurySharePct = com.lifecyclebot.engine.TreasuryManager.MEME_SELL_TREASURY_PCT
        val safetyMarginPct = 1.0
        val effectiveBreakEven = (be.allInCostPct + safetyMarginPct) / (1.0 - treasurySharePct)
        val ok = currentPnlPct >= effectiveBreakEven
        if (!ok) {
            try { ForensicLogger.lifecycle(
                "SELL_SIDE_BREAK_EVEN_DEFER",
                "symbol=${ts.symbol} pnl=${"%.2f".format(currentPnlPct)}%% effBE=${"%.2f".format(effectiveBreakEven)}%% (allInCost=${"%.2f".format(be.allInCostPct)} treasuryShare=${(treasurySharePct*100).toInt()}%% safety=${safetyMarginPct}) — holding for more upside"
            ) } catch (_: Throwable) {}
        }
        return ok
    }
}
