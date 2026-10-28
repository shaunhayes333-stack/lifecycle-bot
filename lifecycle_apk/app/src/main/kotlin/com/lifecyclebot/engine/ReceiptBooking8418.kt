package com.lifecyclebot.engine

import com.lifecyclebot.network.SignedTxLedger8085
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8418 §RECOVERED_RECEIPT_BOOKING — PendingReconcileQueue books what a late or restart-recovered signature's own
 * receipt proves, instead of only logging it.
 *
 *  - BUY owned by a position (a top-up: ledger positionId + TOPUP_8418 attempt): credited ONCE with the receipt's raw
 *    quantity, cost (lamports spent less fee) and fee, keyed by the attempt id (else the signature). The canonical add
 *    uses the same idempotency key as Executor's in-window mirror ("LIVE_TOPUP_7807:<sig>"), so the two paths can never
 *    both apply. Once booked the ledger row stops blocking the owner's next attempt.
 *  - SELL owned by a position: proceeds from its own receipt, booked through the canonical sell mirror under the live
 *    finalizer's key ("SELL:<pid>:<sig>"), so a later in-flow finalization of the same signature is a DUPLICATE.
 *  - A failed receipt, or none by the last checkpoint: a terminal outcome with no effect on any position.
 *  - A BUY with no position owner is an ENTRY: its receipt is owned by the entry proof path (protectLandedLiveBuy7807 /
 *    canonical live recovery), not credited here.
 */
object ReceiptBooking8418 {

    enum class Outcome8418 { BOOKED, DUPLICATE, PENDING, NO_EFFECT_FAILED, NO_EFFECT_NO_RECEIPT, NO_EFFECT_OWNER_GONE, ENTRY_PROOF_PATH }

    /** What booking does to the books; production writes the position, tests record calls. */
    interface Books8418 {
        /** Credit a confirmed buy leg to [positionId]: "APPLIED", "DUPLICATE", or anything else = owner gone. */
        fun creditBuy(positionId: String, mint: String, signature: String, fill: Settlement8418.Fill8418): String
        /** Book sell proceeds for [positionId]: "APPLIED", "DUPLICATE", or anything else = owner gone. */
        fun bookSell(positionId: String, mint: String, signature: String, soldRaw: BigInteger, proceedsSol: Double): String
    }

    private val booked = ConcurrentHashMap<String, String>()

    private fun bookKey8418(attemptId: String, signature: String): String = attemptId.ifBlank { signature }

    fun isBooked8418(attemptId: String, signature: String): Boolean =
        booked.containsKey(bookKey8418(attemptId, signature)) ||
            (SignedTxLedger8085.get(signature)?.let { SignedTxLedger8085.isBooked8418(it) } ?: false)

    private fun markBooked(attemptId: String, signature: String) {
        if (booked.size > 4_000) booked.clear()
        booked[bookKey8418(attemptId, signature)] = signature
        try { SignedTxLedger8085.markBooked8418(signature) } catch (_: Throwable) {}
    }

    /** Mark an in-window booking (Executor.liveTopUp) so a recovered copy of the same signature is a duplicate. */
    fun noteBookedInFlow8418(attemptId: String, signature: String) = markBooked(attemptId, signature)

    /**
     * One checkpoint for a BUY. [receipt] null = not readable yet; [lastCheckpoint] turns "not yet" into a terminal
     * no-effect outcome.
     */
    fun onBuyReceipt8418(
        positionId: String, attemptId: String, mint: String, signature: String,
        receipt: TradeVerifier.BuyResult?, lastCheckpoint: Boolean, books: Books8418,
    ): Outcome8418 {
        if (isBooked8418(attemptId, signature)) return Outcome8418.DUPLICATE
        if (receipt?.outcome == TradeVerifier.Outcome.FAILED_CONFIRMED) return Outcome8418.NO_EFFECT_FAILED
        val fill = Settlement8418.buyFill8418(receipt)
            ?: return if (lastCheckpoint) Outcome8418.NO_EFFECT_NO_RECEIPT else Outcome8418.PENDING
        if (positionId.isBlank()) return Outcome8418.ENTRY_PROOF_PATH
        return when (try { books.creditBuy(positionId, mint, signature, fill) } catch (_: Throwable) { "FAULT" }) {
            "APPLIED" -> { markBooked(attemptId, signature); Outcome8418.BOOKED }
            "DUPLICATE" -> { markBooked(attemptId, signature); Outcome8418.DUPLICATE }
            else -> Outcome8418.NO_EFFECT_OWNER_GONE
        }
    }

    /** One checkpoint for a SELL, same contract. */
    fun onSellReceipt8418(
        positionId: String, mint: String, signature: String,
        receipt: TradeVerifier.SellResult?, lastCheckpoint: Boolean, books: Books8418,
    ): Outcome8418 {
        if (isBooked8418("", signature)) return Outcome8418.DUPLICATE
        if (receipt?.outcome == TradeVerifier.Outcome.FAILED_CONFIRMED) return Outcome8418.NO_EFFECT_FAILED
        val proceeds = Settlement8418.sellProceedsSol8418(receipt)
        if (proceeds == null || receipt == null || receipt.rawTokenConsumed.signum() <= 0)
            return if (lastCheckpoint) Outcome8418.NO_EFFECT_NO_RECEIPT else Outcome8418.PENDING
        if (positionId.isBlank()) return Outcome8418.NO_EFFECT_OWNER_GONE
        return when (try { books.bookSell(positionId, mint, signature, receipt.rawTokenConsumed, proceeds) } catch (_: Throwable) { "FAULT" }) {
            "APPLIED" -> { markBooked("", signature); Outcome8418.BOOKED }
            "DUPLICATE" -> { markBooked("", signature); Outcome8418.DUPLICATE }
            else -> Outcome8418.NO_EFFECT_OWNER_GONE
        }
    }

    /** Pure: the cost basis a sold raw quantity carries out of a position (pro rata of the remaining basis). */
    fun soldBasisSol8418(entryCostSol: Double, soldCostBasisSol: Double, remainingRaw: BigInteger, soldRaw: BigInteger): Double {
        if (remainingRaw.signum() <= 0 || soldRaw.signum() <= 0) return 0.0
        val remainingBasis = (entryCostSol - soldCostBasisSol).coerceAtLeast(0.0)
        val share = soldRaw.min(remainingRaw).toBigDecimal().divide(remainingRaw.toBigDecimal(), 12, java.math.RoundingMode.HALF_EVEN).toDouble()
        return remainingBasis * share
    }

    /** The production books: the canonical row plus the runtime Position, never one without the other. */
    object LiveBooks8418 : Books8418 {
        override fun creditBuy(positionId: String, mint: String, signature: String, fill: Settlement8418.Fill8418): String {
            val ts = try { BotService.status.tokens[mint] } catch (_: Throwable) { null } ?: return "NO_TOKEN_STATE"
            if (!ts.position.isOpen || ts.position.isPaperPosition) return "POSITION_NOT_OPEN_LIVE"
            if (Settlement8418.liveOwnerOf8418(mint) != positionId) return "OWNER_POSITION_CHANGED"
            val solUsd = try { WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
            val addedUsdPerToken = if (solUsd.isFinite() && solUsd in 5.0..10_000.0 && fill.qtyUi > 0.0) fill.costSol * solUsd / fill.qtyUi else 0.0
            val r = com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.addToPosition6486(
                idempotencyKey = "LIVE_TOPUP_7807:$signature", positionId = positionId,
                addedCostSol = fill.costSol, addedQtyRaw = fill.rawQty, feesSol = fill.feeSol, addedEntryPriceUsd = addedUsdPerToken,
            )
            if (r == com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.MutateResult.APPLIED) {
                try { com.lifecyclebot.engine.truth.CanonicalLotQuantity6464.onBuyFilled(positionId, mint, fill.rawQty) } catch (_: Throwable) {}
                ts.position = Settlement8418.appliedTopUp8418(ts.position, fill, solUsd, ts.lastPrice, System.currentTimeMillis())
            }
            return r.name
        }

        override fun bookSell(positionId: String, mint: String, signature: String, soldRaw: BigInteger, proceedsSol: Double): String {
            val pos = com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.getPosition(positionId) ?: return "UNKNOWN_POSITION"
            if (pos.mint != mint || !pos.mode.equals("live", true)) return "OWNER_MISMATCH"
            val sold = soldRaw.min(pos.remainingQtyRaw)
            if (sold.signum() <= 0) return "NOTHING_REMAINING"
            val ok = com.lifecyclebot.engine.truth.ExecutorCanonicalMirror6442.mirrorSell(
                mint = mint, generation = 0L, soldQtyRaw = sold, proceedsSol = proceedsSol,
                soldCostBasisSol = soldBasisSol8418(pos.entryCostSol, pos.soldCostBasisSol, pos.remainingQtyRaw, sold),
                feesSol = 0.0, paperMode = false, reason = "RECOVERED_RECEIPT_8418",
                positionId7835 = positionId, sellExecutionId7835 = signature,
            )
            val after = com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.getPosition(positionId)
            return when {
                // mirrorSell is true for APPLIED and DUPLICATE (same "SELL:<pid>:<sig>" key the live finalizer uses).
                ok && (after == null || after.remainingQtyRaw < pos.remainingQtyRaw) -> "APPLIED"
                ok -> "DUPLICATE"
                else -> "REFUSED"
            }
        }
    }
}
