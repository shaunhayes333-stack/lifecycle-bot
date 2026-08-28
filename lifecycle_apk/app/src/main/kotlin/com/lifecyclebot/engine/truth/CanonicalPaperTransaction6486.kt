package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.ForensicLogger
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
             entryDex: String = "",
             executionIntent: com.lifecyclebot.engine.ExecutableOpenGate.ExecutionIntent? = null): Result = lock.withLock {
        // V5.0.6551 — every non-Solana paper open must be authorized before
        // debit. Missing/mismatched intent is rejected without mutation.
        if (assetClass != AssetClass.SOLANA_TOKEN) {
            val intent = executionIntent ?: CanonicalEntryAuthority6551.findPending(mint, "PAPER")
                ?: return@withLock Result(false, positionId, "MISSING_CANONICAL_EXECUTION_INTENT")
            if (intent.assetClassTag != assetClass.tag || intent.mint != mint || intent.candidateVersion <= 0L || !intent.fdgAllowed ||
                intent.authoritativeSignal.uppercase() != "BUY" ||
                intent.fdgVerdict.uppercase() !in setOf("BUY", "PROBE_ONLY") ||
                intent.resolvedSize <= 0.0 || kotlin.math.abs(intent.resolvedSize - costSol) > 1e-9 ||
                intent.mode.uppercase() != "PAPER")
                return@withLock Result(false, positionId, "CANONICAL_EXECUTION_INTENT_MISMATCH")
            CanonicalEntryAuthority6551.markDispatch(intent)
        }
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
            System.currentTimeMillis(), "",
            entryMarketRegime = try { com.lifecyclebot.engine.RegimeDetector.currentRegime().name } catch (_: Throwable) { "UNKNOWN" },
            assetClassTag = assetClass.tag))
        CanonicalMintOccupancyRegistry6464.markOpen("paper", mint, symbol, source)
        try { PipelineHealthCollector.labelInc("PAPER_TRANSACTION_OPEN_COMMITTED_6486") } catch (_: Throwable) {}
        // V5.0.6551 — intent/dispatch were sealed before debit; only the
        // successful canonical commit emits OPEN_CONFIRMED.
        if (assetClass != AssetClass.SOLANA_TOKEN) {
            CanonicalEntryAuthority6551.findPending(mint, "PAPER")?.let { intent ->
                CanonicalEntryAuthority6551.markConfirmed(intent, positionId)
            }
        }
        // Canonical BUY projection — one journal event per canonical open.
        // It is emitted only after the authority-backed commit succeeds.
        try {
            PipelineHealthCollector.labelInc("CANONICAL_BUY_JOURNAL_PROJECTED_6543")
            ForensicLogger.lifecycle(
                "CANONICAL_BUY_JOURNAL_PROJECTED_6543",
                "assetClass=${assetClass.tag} symbol=$symbol positionId=$positionId costSol=$costSol entryPriceUsd=$entryPriceUsd source=$source",
            )
        } catch (_: Throwable) {}
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
            addedQtyRaw, fillPrice6539, addedFeeSol, tokenDecimals = quantityScale, quantityScale = quantityScale)
        try { PipelineHealthCollector.labelInc("PAPER_TRANSACTION_ADD_COMMITTED_6486") } catch (_: Throwable) {}
        Result(true, positionId, "ADD_COMMITTED")
    }

    data class PartialResult(
        val applied: Boolean, val positionId: String, val reason: String,
        val operationId: String = "", val partialSequence: Long = 0L,
        val remainingCostSol: Double = 0.0, val realizedPnlSol: Double = 0.0,
    )

    /** V5.0.6566 — typed cross-asset partial. Canonical receipt commits first;
     * local trader maps may mirror remainingCostSol only when applied=true. */
    fun partial(positionId: String, mint: String, symbol: String, fraction: Double,
                currentPnlPct: Double, feeRate: Double, exitReason: String): PartialResult = lock.withLock {
        val pos = CanonicalPositionAuthority6441.getPosition(positionId)
            ?: return@withLock PartialResult(false, positionId, "UNKNOWN_POSITION")
        if (pos.mode != "paper" || fraction <= 0.0 || fraction >= 1.0 ||
            !currentPnlPct.isFinite() || !feeRate.isFinite() || feeRate < 0.0)
            return@withLock PartialResult(false, positionId, "INVALID_PARTIAL")
        val remainingBasis = (pos.entryCostSol - pos.soldCostBasisSol).coerceAtLeast(0.0)
        val soldBasis = remainingBasis * fraction
        val grossProceeds = (soldBasis * (1.0 + currentPnlPct / 100.0)).coerceAtLeast(0.0)
        val fees = grossProceeds * feeRate
        val receipt = CanonicalPaperPartialOperation6510.commit(
            positionId, mint, symbol, fraction, grossProceeds, fees, exitReason,
        )
        if (!receipt.applied) return@withLock PartialResult(false, positionId, receipt.reason,
            receipt.operationId, receipt.partialSequence, receipt.postCost, receipt.realizedPnl)
        try { PipelineHealthCollector.labelInc("PAPER_TRANSACTION_PARTIAL_COMMITTED_6566") } catch (_: Throwable) {}
        PartialResult(true, positionId, receipt.reason, receipt.operationId,
            receipt.partialSequence, receipt.postCost, receipt.realizedPnl)
    }

    fun close(positionId: String, mint: String, symbol: String, grossProceedsSol: Double,
              soldQtyRaw: BigInteger? = null, soldCostBasisSol: Double? = null,
              sellFeeSol: Double = 0.0, exitReason: String, terminalSequence: Long,
              expectedRealizedPnlSol6569: Double? = null, leveragedReturnPct6569: Double? = null): Result = lock.withLock {
        val pos = CanonicalPositionAuthority6441.getPosition(positionId)
            ?: return@withLock Result(false, positionId, "UNKNOWN_POSITION")
        val qty = soldQtyRaw ?: pos.remainingQtyRaw
        val basis = soldCostBasisSol ?: (pos.entryCostSol - pos.soldCostBasisSol).coerceAtLeast(0.0)
        if (qty <= BigInteger.ZERO || qty > pos.remainingQtyRaw || !grossProceedsSol.isFinite() ||
            grossProceedsSol < 0.0 || !basis.isFinite() || basis < 0.0)
            return@withLock Result(false, positionId, "INVALID_CLOSE")
        val terminal = qty >= pos.remainingQtyRaw
        val canonicalRealizedPnl6569 = grossProceedsSol - basis - sellFeeSol
        val expected6569 = expectedRealizedPnlSol6569
        val return6569 = leveragedReturnPct6569
        val tolerance6569 = maxOf(0.000001, kotlin.math.abs(expected6569 ?: 0.0) * 0.02)
        val arithmeticDivergence6569 = expected6569 != null && kotlin.math.abs(canonicalRealizedPnl6569 - expected6569) > tolerance6569
        val impossibleZero6569 = return6569 != null && kotlin.math.abs(return6569) > 5.0 && kotlin.math.abs(canonicalRealizedPnl6569) < 0.0005
        if (arithmeticDivergence6569 || impossibleZero6569) {
            CanonicalPerformanceFilter6395.quarantine(positionId, CanonicalPerformanceFilter6395.QuarantineReason.REPLAY_UNIT_MISMATCH)
            PaperLearningEligibility6519.record(mint, positionId, false, "LEVERAGED_TERMINAL_ARITHMETIC_DIVERGENCE_6569")
            try {
                PipelineHealthCollector.labelInc("LEVERAGED_TERMINAL_ARITHMETIC_DIVERGENCE_6569")
                ForensicLogger.lifecycle("LEVERAGED_TERMINAL_ARITHMETIC_DIVERGENCE_6569", "positionId=$positionId symbol=$symbol basis=$basis gross=$grossProceedsSol fee=$sellFeeSol expected=$expected6569 realized=$canonicalRealizedPnl6569 returnPct=$return6569 action=settle_but_quarantine_learning")
            } catch (_: Throwable) {}
        }
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
