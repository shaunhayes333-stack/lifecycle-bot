package com.lifecyclebot.engine.truth

import java.math.BigInteger
import kotlin.math.abs
import kotlin.math.max

/** Strict replay of a durable LIVE receipt against the exact current wallet raw quantity.
 * No new buy or learner decision. Ambiguous histories remain on the protective path.
 */
internal object ExactLiveReceiptRecovery9048 {
    fun reconstruct(events: List<EconomicEventSchema6464.Event>, mint: String,
                    walletRaw: BigInteger, decimals: Int, nowMs: Long): CanonicalPositionAuthority6441.Position? {
        if (mint.isBlank() || walletRaw <= BigInteger.ONE || decimals !in 0..18 || nowMs <= 0L) return null
        val live = events.filter { it.mode.equals("live", true) && it.mint == mint }
        val open = live.groupBy { it.positionId }.values.filter { rows ->
            rows.any { it is EconomicEventSchema6464.Buy } &&
                rows.none { it is EconomicEventSchema6464.Sell && (!it.partial || it.remainingQty.signum() == 0) }
        }
        if (open.size != 1) return null
        val rows = open.single().sortedBy { it.atMs }
        val first = rows.firstOrNull() as? EconomicEventSchema6464.Buy ?: return null
        if (first.positionId.isBlank() || first.lane.isBlank() || first.atMs <= 0L || first.atMs > nowMs) return null
        if (live.any { it.positionId != first.positionId && it.atMs >= first.atMs }) return null
        if (rows.any { it.idempotencyKey.isBlank() || it.atMs > nowMs } ||
            rows.map { it.idempotencyKey }.toSet().size != rows.size) return null
        val cls = if (first.assetClassTag.isBlank()) AssetClass.fromLane(first.lane)
                  else AssetClass.values().firstOrNull { it.tag.equals(first.assetClassTag, true) }
        if (cls != AssetClass.SOLANA_TOKEN) return null
        var original = BigInteger.ZERO
        var remaining = BigInteger.ZERO
        var cost = 0.0
        var allocated = 0.0
        var proceeds = 0.0
        var pnl = 0.0
        var fees = 0.0
        var entryUsd = 0.0
        for (event in rows) when (event) {
            is EconomicEventSchema6464.Buy -> {
                if (event.tokenDecimals != decimals || event.quantityScale != decimals ||
                    !event.executedCostSol.isFinite() || event.executedCostSol <= 0.0 ||
                    !event.entryFeesSol.isFinite() || event.entryFeesSol < 0.0 ||
                    !event.fillPrice.isFinite() || event.fillPrice <= 0.0 || event.filledQty.signum() <= 0 ||
                    (event.assetClassTag.isNotBlank() && !event.assetClassTag.equals("SOLANA_TOKEN", true)) ||
                    (event.lane.isNotBlank() && !event.lane.equals(first.lane, true))) return null
                val prev = remaining.toDouble()
                val added = event.filledQty.toDouble()
                entryUsd = (prev * entryUsd + added * event.fillPrice) / (prev + added)
                remaining += event.filledQty
                original += event.filledQty
                cost += event.executedCostSol
                fees += event.entryFeesSol
            }
            is EconomicEventSchema6464.Sell -> {
                if (!event.partial || event.soldQty.signum() <= 0 || event.soldQty >= remaining ||
                    event.remainingQty != remaining - event.soldQty ||
                    !event.allocatedCostBasisSol.isFinite() || event.allocatedCostBasisSol < 0.0 ||
                    !event.remainingCostBasisSol.isFinite() || event.remainingCostBasisSol <= 0.0 ||
                    !event.grossProceedsSol.isFinite() || event.grossProceedsSol < 0.0 ||
                    !event.exitFeesSol.isFinite() || event.exitFeesSol < 0.0 || !event.realizedPnlSol.isFinite()) return null
                allocated += event.allocatedCostBasisSol
                if (abs(cost - allocated - event.remainingCostBasisSol) > max(1e-8, cost * 1e-6)) return null
                remaining = event.remainingQty
                proceeds += event.grossProceedsSol
                pnl += event.realizedPnlSol
                fees += event.exitFeesSol
            }
            else -> return null
        }
        if (remaining != walletRaw || !entryUsd.isFinite() || entryUsd <= 0.0 ||
            !cost.isFinite() || !allocated.isFinite() || cost <= allocated ||
            !proceeds.isFinite() || !pnl.isFinite() || !fees.isFinite()) return null
        return CanonicalPositionAuthority6441.Position(
            positionId = first.positionId, mode = "live", mint = mint, symbol = first.symbol,
            lane = first.lane, runId = "RECEIPT_RESTORE_9048", openedAtMs = first.atMs,
            entryCostSol = cost, remainingQtyRaw = remaining, originalQtyRaw = original,
            soldCostBasisSol = allocated, realizedPnlSol = pnl, realizedProceedsSol = proceeds,
            feesSol = fees, tokenDecimals = decimals, quantityScale = decimals,
            lifecycle = if (remaining < original) CanonicalPositionAuthority6441.Lifecycle.PARTIALLY_CLOSED else CanonicalPositionAuthority6441.Lifecycle.OPEN,
            lastMutationMs = nowMs, quarantineReason = "", entryPriceUsd = entryUsd,
            entryPriceSource = "DURABLE_LIVE_BUY_EVENT_7931", assetClass = AssetClass.SOLANA_TOKEN)
    }
}
