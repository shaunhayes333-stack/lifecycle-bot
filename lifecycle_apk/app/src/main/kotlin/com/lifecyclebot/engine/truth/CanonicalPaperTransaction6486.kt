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
             decimals: Int = 9, entryScore: Int = 0, tactic: String = lane): Result = lock.withLock {
        if (positionId.isBlank() || mint.isBlank() || !costSol.isFinite() || costSol <= 0.0 ||
            !feeSol.isFinite() || feeSol < 0.0 || qtyRaw <= BigInteger.ZERO)
            return@withLock Result(false, positionId, "INVALID_OPEN")
        if (CanonicalPositionAuthority6441.getPosition(positionId) != null)
            return@withLock Result(false, positionId, "POSITION_EXISTS")
        if (!PaperAccountLedger6430.onBuy(costSol, feeSol))
            return@withLock Result(false, positionId, "INSUFFICIENT_CANONICAL_CASH")
        val idem = "PAPER6486:OPEN:$positionId"
        val opened = CanonicalPositionAuthority6441.openPosition(
            idem, positionId, mint, symbol, lane, positionId.substringAfterLast(':', positionId),
            costSol, qtyRaw, decimals, feeSol, paperMode = false, modeOverride = "paper")
        if (opened != CanonicalPositionAuthority6441.MutateResult.APPLIED) {
            PaperAccountLedger6430.rollbackBuy(costSol, feeSol, "PAPER6486_OPEN_$opened")
            return@withLock Result(false, positionId, "POSITION_$opened")
        }
        CanonicalLotQuantity6464.onBuyFilled(positionId, mint, qtyRaw)
        EconomicEventSchema6464.recordBuy("paper", positionId, mint, symbol, idem, costSol,
            qtyRaw, costSol / qtyRaw.toDouble(), feeSol)
        EntryStrategySnapshot6450.setEntry(EntryStrategySnapshot6450.Snapshot(
            positionId, mint, lane, "", tactic, "", "", source, entryScore, 0.0, 0.0,
            System.currentTimeMillis(), ""))
        CanonicalMintOccupancyRegistry6464.markOpen("paper", mint, symbol, source)
        try { PipelineHealthCollector.labelInc("PAPER_TRANSACTION_OPEN_COMMITTED_6486") } catch (_: Throwable) {}
        Result(true, positionId, "OPEN_COMMITTED")
    }

    fun add(positionId: String, mint: String, symbol: String, addedCostSol: Double,
            addedFeeSol: Double = 0.0, addedQtyRaw: BigInteger = syntheticUnit): Result = lock.withLock {
        val pos = CanonicalPositionAuthority6441.getPosition(positionId)
            ?: return@withLock Result(false, positionId, "UNKNOWN_POSITION")
        if (!addedCostSol.isFinite() || addedCostSol <= 0.0 || addedQtyRaw <= BigInteger.ZERO)
            return@withLock Result(false, positionId, "INVALID_ADD")
        if (!PaperAccountLedger6430.onBuy(addedCostSol, addedFeeSol))
            return@withLock Result(false, positionId, "INSUFFICIENT_CANONICAL_CASH")
        val idem = "PAPER6486:ADD:$positionId:${pos.originalQtyRaw}"
        val applied = CanonicalPositionAuthority6441.addToPosition6486(
            idem, positionId, addedCostSol, addedQtyRaw, addedFeeSol)
        if (applied != CanonicalPositionAuthority6441.MutateResult.APPLIED) {
            PaperAccountLedger6430.rollbackBuy(addedCostSol, addedFeeSol, "PAPER6486_ADD_$applied")
            return@withLock Result(false, positionId, "POSITION_$applied")
        }
        CanonicalLotQuantity6464.onBuyFilled(positionId, mint, addedQtyRaw)
        EconomicEventSchema6464.recordBuy("paper", positionId, mint, symbol, idem, addedCostSol,
            addedQtyRaw, addedCostSol / addedQtyRaw.toDouble(), addedFeeSol)
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
}
