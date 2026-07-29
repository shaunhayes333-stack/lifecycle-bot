package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6386 — IMMUTABLE FILL LOT LEDGER (Section 6 of the directive).
 *
 * OPERATOR DIRECTIVE (verbatim):
 *   "Make FillLotLedger6344 the sole realised cost-basis authority.
 *    Lot key: walletAddress + mintAddress + confirmedBuySignature
 *    Freeze: entry raw quantity, decimals, net lamports spent, entry
 *    SOL/token, entry USD/token, fee, lane, timestamp, signature.
 *    Top-up and re-entry create new lots.
 *    Never replace an existing lot by mint.
 *    Remove CanonicalBuyFillRegistry from realised PnL, sold-quantity
 *    attribution, stop basis and learning authority.
 *    Use FIFO lot consumption unless another explicit policy is configured."
 *
 * DESIGN
 * ──────
 * `FillLot6386` is a `data class` with `val`s — the compiler enforces
 * immutability. The ledger keys lots by (wallet, mint, sig). A single wallet
 * accumulating multiple positions on the same mint accumulates SEPARATE
 * lots — never a merged average. FIFO consumption drains lots in
 * decisionTimestamp order.
 */

/**
 * A single immutable fill lot. Every field frozen at creation from
 * finalized BUY proof.
 */
data class FillLot6386(
    val walletAddress: String,
    val mintAddress: String,
    val confirmedBuySignature: String,
    val entryRawQuantity: RawTokenAmount,
    val decimals: MintDecimals.Known,
    val netLamportsSpent: Lamports,
    val entrySolPerToken: SolPerToken,
    val entryUsdPerToken: UsdPerToken,
    val feeLamports: Lamports,
    val lane: String,
    val timestamp: Long,
    // Consumption tracking (via a separate mutable index; not on the lot itself).
) {
    init {
        require(walletAddress.isNotBlank())
        require(mintAddress.isNotBlank())
        require(confirmedBuySignature.isNotBlank())
        require(entryRawQuantity.isPositive())
        require(netLamportsSpent.isPositive() || netLamportsSpent.isZero()) // 0 allowed for gifts
        require(lane.isNotBlank())
    }
}

object FillLotLedger6386 {

    private data class LotIndex(val wallet: String, val mint: String, val sig: String)

    // Immutable lots keyed by (wallet, mint, sig).
    private val lots = ConcurrentHashMap<LotIndex, FillLot6386>()

    // Per-lot remaining raw quantity — the ONLY mutable state.
    // Initialized to lot.entryRawQuantity when lot is opened; drained by SELL proofs.
    private val remaining = ConcurrentHashMap<LotIndex, RawTokenAmount>()

    /**
     * Open a new lot from a finalized BUY proof. Throws if a lot with the
     * same (wallet, mint, sig) already exists — signatures are unique so
     * this catches double-open bugs.
     */
    fun openLot(lot: FillLot6386) {
        val idx = LotIndex(lot.walletAddress, lot.mintAddress, lot.confirmedBuySignature)
        val prior = lots.putIfAbsent(idx, lot)
        require(prior == null) {
            "V5.0.6386: attempted to overwrite fill lot ${idx.sig.take(10)} for wallet=${idx.wallet.take(10)} mint=${idx.mint.take(10)} — signatures are unique, this is a double-open bug"
        }
        remaining[idx] = lot.entryRawQuantity
    }

    /**
     * FIFO consumption. Returns the (lot, consumed raw qty) pairs in the
     * order they were drained, plus a shortfall if the request exceeds
     * total remaining. Called by finalized SELL proof to attribute realised
     * cost basis proportionally to the sold raw quantity.
     */
    data class FifoConsumeResult(
        val consumed: List<Pair<FillLot6386, RawTokenAmount>>,
        val shortfall: RawTokenAmount,
    )
    fun consumeFifo(walletAddress: String, mintAddress: String, requested: RawTokenAmount): FifoConsumeResult {
        require(requested.isPositive())
        val candidates = lots.entries
            .filter { it.key.wallet == walletAddress && it.key.mint == mintAddress && (remaining[it.key]?.isPositive() ?: false) }
            .sortedBy { it.value.timestamp }
        val consumed = mutableListOf<Pair<FillLot6386, RawTokenAmount>>()
        var remainingRequest = requested
        for (e in candidates) {
            if (remainingRequest.isZero()) break
            val avail = remaining[e.key] ?: RawTokenAmount.ZERO
            if (avail.isZero()) continue
            val take = if (avail >= remainingRequest) remainingRequest else avail
            consumed += e.value to take
            remaining[e.key] = avail - take
            remainingRequest = remainingRequest - take
        }
        return FifoConsumeResult(consumed, remainingRequest)
    }

    /**
     * Read-only view of all open lots for a (wallet, mint).
     */
    fun openLotsFor(walletAddress: String, mintAddress: String): List<FillLot6386> =
        lots.entries
            .filter { it.key.wallet == walletAddress && it.key.mint == mintAddress }
            .sortedBy { it.value.timestamp }
            .map { it.value }

    fun remainingRaw(walletAddress: String, mintAddress: String, signature: String): RawTokenAmount =
        remaining[LotIndex(walletAddress, mintAddress, signature)] ?: RawTokenAmount.ZERO

    fun totalRemainingRaw(walletAddress: String, mintAddress: String): RawTokenAmount =
        remaining.entries
            .filter { it.key.wallet == walletAddress && it.key.mint == mintAddress }
            .fold(RawTokenAmount.ZERO) { acc, e -> acc + e.value }

    internal fun clearAllForTest() {
        lots.clear()
        remaining.clear()
    }
}
