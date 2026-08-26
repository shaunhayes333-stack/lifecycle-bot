package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** V5.0.6486 — one typed paper transaction reducer for every trader family. */
object CanonicalPaperTransaction6486 {
    private val lock = ReentrantLock()
    private val syntheticUnit = BigInteger.valueOf(1_000_000_000L)
    data class Result(val applied: Boolean, val positionId: String, val reason: String)

    fun open(positionId: String, mint: String, symbol: String, lane: String, source: String,
             costSol: Double, feeSol: Double = 0.0, qtyRaw: BigInteger = syntheticUnit,
             decimals: Int = 9, entryScore: Int = 0, tactic: String = lane,
             quantityScale: Int = decimals,
             // V5.0.6525 §ASSET_CLASS_AXIS + §ENTRY_PRICE_PROPAGATION —
             // Operator audit Feb 2026: the paper bridge threw away
             // signal.price and forced 1e9 qty @ 9 decimals on every
             // asset class. ForexTrader.open() then produced canonical
             // rows with entryPriceUsd=0.0, mark=0.0, and the exit
             // scheduler queued Birdeye lookups on "GBPJPY". Accept the
             // asset class, the real entry price, and the price-source
             // metadata so the canonical row is economically valid on
             // non-Solana assets. Defaults preserve the pre-6525 SOL
             // token behaviour.
             assetClass: AssetClass = AssetClass.SOLANA_TOKEN,
             entryPriceUsd: Double = 0.0,
             entryPriceSource: String = "",
             entryPoolAddress: String = "",
             entryDex: String = ""): Result = lock.withLock {
        if (positionId.isBlank() || mint.isBlank() || !costSol.isFinite() || costSol <= 0.0 ||
            !feeSol.isFinite() || feeSol < 0.0 || qtyRaw <= BigInteger.ZERO)
            return@withLock Result(false, positionId, "INVALID_OPEN")
        if (CanonicalPositionAuthority6441.getPosition(positionId) != null)
            return@withLock Result(false, positionId, "POSITION_EXISTS")
        if (!PaperAccountLedger6430.onBuy(costSol, feeSol))
            return@withLock Result(false, positionId, "INSUFFICIENT_CANONICAL_CASH")
        val idem = "PAPER6486:OPEN:$positionId"
        val opened = CanonicalPositionAuthority6441.openPosition(
            idempotencyKey = idem, positionId = positionId, mint = mint, symbol = symbol,
            lane = lane, runId = positionId.substringAfterLast(':', positionId),
            entryCostSol = costSol, openedQtyRaw = qtyRaw, tokenDecimals = decimals,
            feesSol = feeSol, paperMode = false, modeOverride = "paper", quantityScale = quantityScale,
            entryPriceUsd = entryPriceUsd, entryPriceSource = entryPriceSource,
            entryPoolAddress = entryPoolAddress, entryDex = entryDex,
            assetClass = assetClass)
        if (opened != CanonicalPositionAuthority6441.MutateResult.APPLIED) {
            PaperAccountLedger6430.rollbackBuy(costSol, feeSol, "PAPER6486_OPEN_$opened")
            return@withLock Result(false, positionId, "POSITION_$opened")
        }
        CanonicalLotQuantity6464.onBuyFilled(positionId, mint, qtyRaw)
        PositionStateLedger6454.onEntry(positionId)
        SellQtyBoundaryClamp6427.syncAuthoritativeRaw(positionId, qtyRaw, qtyRaw)
        EconomicEventSchema6464.recordBuy("paper", positionId, mint, symbol, idem, costSol,
            qtyRaw, costSol / qtyRaw.toDouble(), feeSol, decimals, quantityScale)
        EntryStrategySnapshot6450.setEntry(EntryStrategySnapshot6450.Snapshot(
            positionId, mint, lane, "", tactic, "", "", source, entryScore, 0.0, 0.0,
            System.currentTimeMillis(), ""))
        CanonicalMintOccupancyRegistry6464.markOpen("paper", mint, symbol, source)
        try { PipelineHealthCollector.labelInc("PAPER_TRANSACTION_OPEN_COMMITTED_6486") } catch (_: Throwable) {}
        Result(true, positionId, "OPEN_COMMITTED")
    }

    fun add(positionId: String, mint: String, symbol: String, addedCostSol: Double,
            addedFeeSol: Double = 0.0, addedQtyRaw: BigInteger = syntheticUnit,
            // V5.0.6539 §TOP_UP_ATOMICITY — accept the fill's authoritative
            // USD/token price so canonical row weighted-average USD entry
            // basis is updated in the SAME atomic mutation, and the
            // durable economic-event fillPrice is USD/token rather than
            // SOL/raw. Defaults to 0.0 (skip rewrite; pre-6539 semantics).
            addedEntryPriceUsd: Double = 0.0,
            quantityScale: Int = 9): Result = lock.withLock {
        val pos = CanonicalPositionAuthority6441.getPosition(positionId)
            ?: return@withLock Result(false, positionId, "UNKNOWN_POSITION")
        if (!addedCostSol.isFinite() || addedCostSol <= 0.0 || addedQtyRaw <= BigInteger.ZERO)
            return@withLock Result(false, positionId, "INVALID_ADD")
        if (!PaperAccountLedger6430.onBuy(addedCostSol, addedFeeSol))
            return@withLock Result(false, positionId, "INSUFFICIENT_CANONICAL_CASH")
        val idem = "PAPER6486:ADD:$positionId:${pos.originalQtyRaw}"
        val applied = CanonicalPositionAuthority6441.addToPosition6486(
            idem, positionId, addedCostSol, addedQtyRaw, addedFeeSol, addedEntryPriceUsd)
        if (applied != CanonicalPositionAuthority6441.MutateResult.APPLIED) {
            PaperAccountLedger6430.rollbackBuy(addedCostSol, addedFeeSol, "PAPER6486_ADD_$applied")
            return@withLock Result(false, positionId, "POSITION_$applied")
        }
        CanonicalLotQuantity6464.onBuyFilled(positionId, mint, addedQtyRaw)
        CanonicalPositionAuthority6441.getPosition(positionId)?.let { updated6498 ->
            PositionStateLedger6454.onEntry(positionId)
            SellQtyBoundaryClamp6427.syncAuthoritativeRaw(positionId, updated6498.originalQtyRaw, updated6498.remainingQtyRaw)
        }
        // V5.0.6539 §DURABLE_ECONOMIC_EVENT — fillPrice is USD/token when
        // available so replay reproduces the same weighted USD basis
        // (previously we recorded SOL/rawUnit which is a nonsense unit and
        // cannot be replayed into a USD-basis position).
        val fillPrice6539 = if (addedEntryPriceUsd > 0.0 && addedEntryPriceUsd.isFinite())
            addedEntryPriceUsd else addedCostSol / addedQtyRaw.toDouble()
        EconomicEventSchema6464.recordBuy("paper", positionId, mint, symbol, idem, addedCostSol,
            addedQtyRaw, fillPrice6539, addedFeeSol, decimals = quantityScale, quantityScale = quantityScale)
        try { PipelineHealthCollector.labelInc("PAPER_TRANSACTION_ADD_COMMITTED_6486") } catch (_: Throwable) {}
        Result(true, positionId, "ADD_COMMITTED")
    }

    fun close(positionId: String, mint: String, symbol: String, grossProceedsSol: Double,
              soldQtyRaw: BigInteger? = null, soldCostBasisSol: Double? = null,
              sellFeeSol: Double = 0.0, exitReason: String, terminalSequence: Long): Result = lock.withLock {
        val pos = CanonicalPositionAuthority6441.getPosition(positionId)
            ?: return@withLock Result(false, positionId, "UNKNOWN_POSITION")
        val qty = soldQtyRaw ?: pos.remainingQtyRaw
        val basis = soldCostBasisSol ?: (pos.entryCostSol - pos.soldCostBasisSol).coerceAtLeast(0.0)
        if (qty <= BigInteger.ZERO || qty > pos.remainingQtyRaw || !grossProceedsSol.isFinite() ||
            grossProceedsSol < 0.0 || !basis.isFinite() || basis < 0.0)
            return@withLock Result(false, positionId, "INVALID_CLOSE")
        val terminal = qty >= pos.remainingQtyRaw
        val r = CanonicalPaperTerminalBridge6469.finalizeSell(
            positionId = positionId, mint = mint, symbol = symbol,
            generation = pos.openedAtMs, sellSig = "PAPER6486:$positionId:$terminalSequence",
            soldQtyRaw = qty, preRemainingRaw = pos.remainingQtyRaw,
            preRemainingCostBasisSol = (pos.entryCostSol - pos.soldCostBasisSol).coerceAtLeast(0.0),
            grossProceedsSol = grossProceedsSol, soldCostBasisSol = basis,
            feesSol = sellFeeSol, lane = pos.lane, exitReason = exitReason,
            terminal = terminal, directPositionMutation6486 = true,
        )
        if (!r.applied) return@withLock Result(false, positionId, r.reason)
        if (terminal) CanonicalMintOccupancyRegistry6464.markClosed("paper", mint)
        try { PipelineHealthCollector.labelInc(if (terminal) "PAPER_TRANSACTION_CLOSE_COMMITTED_6486" else "PAPER_TRANSACTION_PARTIAL_COMMITTED_6486") } catch (_: Throwable) {}
        Result(true, positionId, if (terminal) "CLOSE_COMMITTED" else "PARTIAL_COMMITTED")
    }

    fun refund(positionId: String, reason: String): Result {
        val pos = CanonicalPositionAuthority6441.getPosition(positionId)
            ?: return Result(false, positionId, "NO_CANONICAL_DEBIT")
        if (pos.mode != "paper") return Result(false, positionId, "NOT_PAPER")
        return refund(positionId, pos.mint, pos.symbol, reason)
    }

    fun refund(positionId: String, mint: String, symbol: String, reason: String): Result {
        val pos = CanonicalPositionAuthority6441.getPosition(positionId)
            ?: return Result(false, positionId, "NO_CANONICAL_DEBIT")
        val basis = (pos.entryCostSol - pos.soldCostBasisSol).coerceAtLeast(0.0)
        return close(positionId, mint, symbol, basis, pos.remainingQtyRaw, basis, 0.0,
            "REFUND:$reason", System.currentTimeMillis())
    }
    data class DuplicateMintRepair6490(val duplicateMints: Int, val refundedLots: Int, val refundedBasisSol: Double, val failures: Int)

    /**
     * V5.0.6490 — startup correction for historical same-mint paper opens.
     * Keep the earliest funded position; refund every alias lot at remaining
     * basis (zero strategy PnL) and suppress learning. This restores deployable
     * cash without inventing profit or deleting economic history.
     */
    fun refundDuplicateActiveMintLots6490(): DuplicateMintRepair6490 {
        val groups = CanonicalPositionAuthority6441.openPositions()
            .filter { it.mode == "paper" && it.remainingQtyRaw > BigInteger.ZERO }
            .groupBy { it.mint }
            .filterValues { it.size > 1 }
        var refunded = 0; var failures = 0; var basisTotal = 0.0
        groups.values.forEach { lots ->
            val keep = lots.minWithOrNull(compareBy<CanonicalPositionAuthority6441.Position> { it.openedAtMs }.thenBy { it.positionId })
            lots.filter { it.positionId != keep?.positionId }.forEach { pos ->
                val basis = (pos.entryCostSol - pos.soldCostBasisSol).coerceAtLeast(0.0)
                val result = CanonicalPaperTerminalBridge6469.finalizeSell(
                    positionId = pos.positionId, mint = pos.mint, symbol = pos.symbol,
                    generation = pos.openedAtMs,
                    sellSig = "INVENTORY_CORRECTION_6490:${pos.positionId}",
                    soldQtyRaw = pos.remainingQtyRaw, preRemainingRaw = pos.remainingQtyRaw,
                    preRemainingCostBasisSol = basis, grossProceedsSol = basis,
                    soldCostBasisSol = basis, feesSol = 0.0, lane = pos.lane,
                    exitReason = "DUPLICATE_SAME_MINT_REFUND_6490", terminal = true,
                    directPositionMutation6486 = true, suppressLearningFanout6490 = true,
                )
                if (result.applied) { refunded++; basisTotal += basis } else failures++
            }
        }
        if (groups.isNotEmpty()) try {
            PipelineHealthCollector.labelInc("DUPLICATE_SAME_MINT_INVENTORY_REPAIRED_6490")
            com.lifecyclebot.engine.ForensicLogger.lifecycle("DUPLICATE_SAME_MINT_INVENTORY_REPAIRED_6490", "duplicateMints=${groups.size} refundedLots=$refunded refundedBasis=${"%.6f".format(basisTotal)} failures=$failures")
        } catch (_: Throwable) {}
        return DuplicateMintRepair6490(groups.size, refunded, basisTotal, failures)
    }


}
