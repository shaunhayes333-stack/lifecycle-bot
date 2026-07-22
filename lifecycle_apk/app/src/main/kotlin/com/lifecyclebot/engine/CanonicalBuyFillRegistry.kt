package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6320 — CANONICAL BUY FILL REGISTRY (operator hotfix §8).
 *
 * Single source of truth for the on-chain-proven entry fill of every
 * live position, keyed by mint. Populated exactly once per acquisition
 * by [Executor.promoteVerifiedLiveBuy] at the moment wallet-verify
 * completes (i.e. the moment the true raw qty and decimals are known).
 *
 * Every downstream surface that needs to display, compute, or journal
 * the entry side of a trade MUST read from here rather than from
 * ts.position, Trade.entryPriceSnapshot, or the WebSocket mark:
 *
 *   * SELL journal row's entryQtyToken / entryPriceSnapshot
 *   * Open Position card (Entry price, Size, tokens)
 *   * MFE / peak / trail tracker
 *   * Live sell toast PnL
 *   * TradeHistoryStore CSV export
 *
 * Because BuyFill is IMMUTABLE once written, no lane reclassification,
 * reconciliation heal, WebSocket update, partial exit, or top-up can
 * mutate the original acquisition record. Top-ups create a SEPARATE
 * fill (fillIndex increment) so the canonical average price can be
 * recomputed correctly if ever needed.
 *
 * This is the minimum viable §8 IMMUTABLE_BUY_FILL surface — the full
 * §7 BigInteger raw-amount model still requires touching every
 * executable qty path (Executor line ~1441 rawTokenAmountToUiAmount
 * remains the choke point for now). §9 canonical PositionId is
 * deliberately NOT bundled here: this store is keyed by mint only,
 * which handles the operator-visible Pilly-style divergence today
 * without requiring the wallet+mint+sig+fillIndex refactor.
 */
object CanonicalBuyFillRegistry {

    data class CanonicalBuyFill(
        val mint: String,
        val walletVerifiedQty: Double,
        val decimals: Int,
        val entryPriceSol: Double,
        val entryPriceUsd: Double,
        val solSpentNet: Double,
        val entryTsMs: Long,
        val buySignature: String,
        val fillIndex: Int,
        val lane: String,        // canonical (via LaneAlias.normalize)
    )

    private val fills = ConcurrentHashMap<String, CanonicalBuyFill>()

    /**
     * Store the on-chain-proven fill. Called from promoteVerifiedLiveBuy.
     * If a fill already exists for the mint (top-up / re-entry after
     * partial), it is REPLACED — the caller is responsible for tracking
     * fillIndex increments. First write wins the initial canonical
     * "acquisition" event.
     */
    fun record(fill: CanonicalBuyFill) {
        if (fill.mint.isBlank() || fill.walletVerifiedQty <= 0.0) return
        val existing = fills[fill.mint]
        val toStore = if (existing == null) fill
            else fill.copy(fillIndex = existing.fillIndex + 1)
        fills[fill.mint] = toStore
        try {
            ForensicLogger.lifecycle(
                "CANONICAL_BUY_FILL_RECORDED_6320",
                "mint=${fill.mint.take(10)} sym=${fill.mint.take(6)} qty=${fill.walletVerifiedQty} decimals=${fill.decimals} entryPxSol=${fill.entryPriceSol} entryPxUsd=${fill.entryPriceUsd} solSpent=${fill.solSpentNet} sig=${fill.buySignature.take(12)} lane=${fill.lane} fillIndex=${toStore.fillIndex}",
            )
            PipelineHealthCollector.labelInc("CANONICAL_BUY_FILL_RECORDED_6320")
        } catch (_: Throwable) {}
    }

    fun get(mint: String): CanonicalBuyFill? = fills[mint]

    /** Called from position close paths so a fresh acquisition on the
     *  same mint starts a clean canonical record. */
    fun clear(mint: String, reason: String) {
        val removed = fills.remove(mint)
        if (removed != null) {
            try {
                ForensicLogger.lifecycle(
                    "CANONICAL_BUY_FILL_CLEARED_6320",
                    "mint=${mint.take(10)} reason=$reason",
                )
                PipelineHealthCollector.labelInc("CANONICAL_BUY_FILL_CLEARED_6320")
            } catch (_: Throwable) {}
        }
    }

    fun activeCount(): Int = fills.size
}
