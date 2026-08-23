package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6469 / V5.0.6475 — CANONICAL PAPER TERMINAL BRIDGE.
 *
 * V5.0.6475 source repair:
 *   The old bridge performed legacy side effects first:
 *     mirrorSell() + PaperAccountLedger.onSell()
 *   and only then called terminal idempotency. That was not idempotent: a
 *   duplicate paper sell could mutate canonical lots/cash before being rejected.
 *
 * Contract from 6475 forward:
 *   1. Build a stable terminal id.
 *   2. Claim TerminalSellIdempotency + TerminalMutationAuthority BEFORE any
 *      economic side effect.
 *   3. Only the granted caller may mutate mirror/lot/cash/event/finalized bus.
 *   4. Duplicate callbacks return applied=false and do not credit/debit/realize.
 */
object CanonicalPaperTerminalBridge6469 {

    private val fullSells = AtomicLong(0L)
    private val partialSells = AtomicLong(0L)
    private val duplicates = AtomicLong(0L)
    private val fanoutFailures = AtomicLong(0L)
    private val busPublishes = AtomicLong(0L)

    data class Result(
        val applied: Boolean,
        val terminalClaimed: Boolean,
        val busPublished: Boolean,
        val reason: String,
    )

    private data class Claim(
        val idKey: String,
        val granted: Boolean,
        val reason: String,
    )

    private fun claimBeforeSideEffects(
        positionId: String,
        mint: String,
        symbol: String,
        generation: Long,
        sellSig: String,
        exitReason: String,
        terminal: Boolean,
        sitePath: String,
    ): Claim {
        val idKey = TerminalSellIdempotency6464.makeKey(
            sellExecutionId = sellSig,
            fillId = sellSig,
            signature = sellSig,
        )
        val consume = TerminalSellIdempotency6464.beginTerminal(
            key = idKey,
            positionId = positionId,
            sitePath = sitePath,
        )
        if (consume != TerminalSellIdempotency6464.Consume.PROCEED) {
            duplicates.incrementAndGet()
            return Claim(idKey, granted = false, reason = consume.name)
        }
        val claim = TerminalMutationAuthority6466.claim(
            TerminalMutationAuthority6466.TerminalEvent(
                positionId = positionId,
                mint = mint,
                symbol = symbol,
                mode = "paper",
                generation = generation,
                terminalSequence = if (terminal) 999L else idKey.hashCode().toLong(),
                runId = "paper",
                exitReason = exitReason,
            ),
        )
        if (claim != TerminalMutationAuthority6466.ClaimResult.GRANTED) {
            duplicates.incrementAndGet()
            return Claim(idKey, granted = false, reason = claim.name)
        }
        return Claim(idKey, granted = true, reason = "GRANTED")
    }

    /**
     * Finalise a paper sell / partial. Idempotent per stable sellSig/positionId.
     * This is the only paper close entrypoint executor code should use.
     */
    fun finalizeSell(
        positionId: String,
        mint: String,
        symbol: String,
        generation: Long,
        sellSig: String,
        soldQtyRaw: BigInteger,
        preRemainingRaw: BigInteger,
        preRemainingCostBasisSol: Double,
        grossProceedsSol: Double,
        soldCostBasisSol: Double,
        feesSol: Double,
        lane: String,
        exitReason: String,
        terminal: Boolean,
        directPositionMutation6486: Boolean = false,
        suppressLearningFanout6490: Boolean = false,
    ): Result {
        val qtyAdmission6498 = SellQtyBoundaryClamp6427.admitRaw(positionId, soldQtyRaw, mint, symbol)
        if (!qtyAdmission6498.allowed) {
            try { PipelineHealthCollector.labelInc("CANONICAL_PAPER_SELL_QTY_REJECTED_6498") } catch (_: Throwable) {}
            return Result(applied = false, terminalClaimed = false, busPublished = false, reason = "SELL_QTY_${qtyAdmission6498.reason}")
        }
        val claim = claimBeforeSideEffects(
            positionId = positionId,
            mint = mint,
            symbol = symbol,
            generation = generation,
            sellSig = sellSig,
            exitReason = exitReason,
            terminal = terminal,
            sitePath = "CanonicalPaperTerminalBridge6469.finalizeSell($exitReason)",
        )
        if (!claim.granted) {
            try { PipelineHealthCollector.labelInc("CANONICAL_PAPER_SELL_DUPLICATE_NO_SIDE_EFFECT_6475") } catch (_: Throwable) {}
            return Result(applied = false, terminalClaimed = false, busPublished = false, reason = claim.reason)
        }

        var busPublished = false
        try {
            if (!PaperAccountLedger6430.canApplySell6486(soldCostBasisSol)) {
                try { PipelineHealthCollector.labelInc("PAPER_TERMINAL_LEDGER_BASIS_REJECTED_6486") } catch (_: Throwable) {}
                return Result(false, true, false, "LEDGER_BASIS_REJECTED")
            }
            // V5.0.6486 — cash may move only after the canonical position
            // mutation reports APPLIED. A swallowed UNKNOWN_POSITION or invariant
            // failure must never be converted into free paper cash.
            val positionApplied6486 = if (directPositionMutation6486) {
                CanonicalPositionAuthority6441.partialSell(
                    idempotencyKey = "DIRECT:${claim.idKey}",
                    positionId = positionId,
                    soldQtyRaw = soldQtyRaw,
                    proceedsSol = grossProceedsSol,
                    soldCostBasisSol = soldCostBasisSol,
                    feesSol = feesSol,
                    paperMode = false,
                ) == CanonicalPositionAuthority6441.MutateResult.APPLIED
            } else {
                ExecutorCanonicalMirror6442.mirrorSell(
                    mint = mint,
                    generation = generation,
                    soldQtyRaw = soldQtyRaw,
                    proceedsSol = grossProceedsSol,
                    soldCostBasisSol = soldCostBasisSol,
                    feesSol = feesSol,
                    paperMode = true,
                    terminal = terminal,
                    lane = lane,
                    reason = exitReason,
                )
            }
            if (!positionApplied6486) {
                try { PipelineHealthCollector.labelInc("CANONICAL_PAPER_SELL_POSITION_REJECTED_6486") } catch (_: Throwable) {}
                return Result(applied = false, terminalClaimed = true, busPublished = false, reason = "POSITION_MUTATION_REJECTED")
            }
            if (!SellQtyBoundaryClamp6427.commitRaw(positionId, soldQtyRaw, terminal)) {
                try { PipelineHealthCollector.labelInc("CANONICAL_PAPER_SELL_QTY_COMMIT_FAILED_6498") } catch (_: Throwable) {}
                return Result(applied = false, terminalClaimed = true, busPublished = false, reason = "SELL_QTY_COMMIT_FAILED_6498")
            }
            val ledgerApplied6486 = PaperAccountLedger6430.onSell(
                grossProceedsSol = grossProceedsSol,
                costBasisSoldSol = soldCostBasisSol,
                feeSol = feesSol,
            )
            if (!ledgerApplied6486) {
                try { PipelineHealthCollector.labelInc("PAPER_TERMINAL_LEDGER_COMMIT_FAILED_6486") } catch (_: Throwable) {}
                return Result(false, true, false, "LEDGER_COMMIT_FAILED")
            }

            busPublished = applyFanoutAfterClaim(
                idKey = claim.idKey,
                positionId = positionId,
                mint = mint,
                symbol = symbol,
                soldQtyRaw = soldQtyRaw,
                preRemainingRaw = preRemainingRaw,
                preRemainingCostBasisSol = preRemainingCostBasisSol,
                grossProceedsSol = grossProceedsSol,
                soldCostBasisSol = soldCostBasisSol,
                feesSol = feesSol,
                lane = lane,
                terminal = terminal,
                suppressLearningFanout6490 = suppressLearningFanout6490,
            )
            if (terminal) {
                fullSells.incrementAndGet()
                try { PipelineHealthCollector.labelInc("CANONICAL_TERMINAL_SELL") } catch (_: Throwable) {}
            } else {
                partialSells.incrementAndGet()
                try { PipelineHealthCollector.labelInc("CANONICAL_TERMINAL_PARTIAL") } catch (_: Throwable) {}
            }
            return Result(applied = true, terminalClaimed = true, busPublished = busPublished, reason = "GRANTED")
        } catch (t: Throwable) {
            fanoutFailures.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "CANONICAL_PAPER_TERMINAL_BRIDGE_FANOUT_THREW_6469",
                    "positionId=$positionId mint=${mint.take(10)} err=${t.message}",
                )
                PipelineHealthCollector.labelInc("CANONICAL_PAPER_TERMINAL_BRIDGE_FANOUT_THREW_6469")
            } catch (_: Throwable) {}
            return Result(applied = true, terminalClaimed = true, busPublished = busPublished, reason = "APPLIED_WITH_FANOUT_ERROR")
        }
    }

    /**
     * Projection-only legacy helper. Executor close paths should not call this;
     * use finalizeSell(). Kept only for stale callers during migration.
     */
    fun emitCanonicalFanout(
        positionId: String,
        mint: String,
        symbol: String,
        generation: Long,
        sellSig: String,
        soldQtyRaw: BigInteger,
        preRemainingRaw: BigInteger,
        preRemainingCostBasisSol: Double,
        grossProceedsSol: Double,
        soldCostBasisSol: Double,
        feesSol: Double,
        lane: String,
        exitReason: String,
        terminal: Boolean,
    ): Result {
        val claim = claimBeforeSideEffects(
            positionId = positionId,
            mint = mint,
            symbol = symbol,
            generation = generation,
            sellSig = sellSig,
            exitReason = exitReason,
            terminal = terminal,
            sitePath = "CanonicalPaperTerminalBridge6469.emitCanonicalFanout($exitReason)",
        )
        if (!claim.granted) return Result(applied = false, terminalClaimed = false, busPublished = false, reason = claim.reason)
        val bus = applyFanoutAfterClaim(
            idKey = claim.idKey,
            positionId = positionId,
            mint = mint,
            symbol = symbol,
            soldQtyRaw = soldQtyRaw,
            preRemainingRaw = preRemainingRaw,
            preRemainingCostBasisSol = preRemainingCostBasisSol,
            grossProceedsSol = grossProceedsSol,
            soldCostBasisSol = soldCostBasisSol,
            feesSol = feesSol,
            lane = lane,
            terminal = terminal,
        )
        if (terminal) fullSells.incrementAndGet() else partialSells.incrementAndGet()
        return Result(applied = true, terminalClaimed = true, busPublished = bus, reason = "GRANTED")
    }

    private fun applyFanoutAfterClaim(
        idKey: String,
        positionId: String,
        mint: String,
        symbol: String,
        soldQtyRaw: BigInteger,
        preRemainingRaw: BigInteger,
        preRemainingCostBasisSol: Double,
        grossProceedsSol: Double,
        soldCostBasisSol: Double,
        feesSol: Double,
        lane: String,
        terminal: Boolean,
        suppressLearningFanout6490: Boolean = false,
    ): Boolean {
        try {
            CanonicalLotQuantity6464.onSellFilled(
                positionId = positionId,
                mint = mint,
                filledQty = soldQtyRaw,
            )
        } catch (_: Throwable) {}
        try {
            EconomicEventSchema6464.recordSell(
                mode = "paper",
                positionId = positionId,
                mint = mint,
                symbol = symbol,
                idempotencyKey = idKey,
                partial = !terminal,
                soldQty = soldQtyRaw,
                preRemainingQty = preRemainingRaw,
                preRemainingCostBasisSol = preRemainingCostBasisSol,
                grossProceedsSol = grossProceedsSol,
                exitFeesSol = feesSol,
            )
        } catch (_: Throwable) {}

        var busPublished = false
        if (terminal && !suppressLearningFanout6490) try {
            val realizedSol = grossProceedsSol - soldCostBasisSol - feesSol
            val realizedPct = if (soldCostBasisSol > 0.0) (realizedSol / soldCostBasisSol) * 100.0 else 0.0
            val settledAt6485 = System.currentTimeMillis()
            val entrySnap6485 = EntryStrategySnapshot6450.snapshot(positionId)
            val outcome6485 = when {
                realizedSol > 0.0001 -> CanonicalTradeFinalizedBus6450.Outcome.WIN
                realizedSol < -0.0001 -> CanonicalTradeFinalizedBus6450.Outcome.LOSS
                else -> CanonicalTradeFinalizedBus6450.Outcome.BREAKEVEN
            }
            busPublished = CanonicalTradeFinalizedBus6450.publish(
                CanonicalTradeFinalizedBus6450.Event(
                    positionId = positionId, mint = mint, outcome = outcome6485,
                    netRealizedPnlSol = realizedSol, grossRealizedPnlSol = grossProceedsSol - soldCostBasisSol,
                    returnFraction = realizedPct / 100.0, netReturnPct = realizedPct, feesSol = feesSol,
                    entryLane = entrySnap6485?.entryLane ?: lane,
                    entryStrategyPid = entrySnap6485?.entryStrategyPid ?: "",
                    entryTactic = entrySnap6485?.entryTactic ?: "",
                    exitReason = "CANONICAL_PAPER_FILL", holdingTimeMs = if (entrySnap6485 != null) settledAt6485 - entrySnap6485.entryTimestampMs else 0L,
                    dataQuality = "canonical_paper_fill", priceIntegrity = "canonical_paper_fill",
                    mode = "paper", settledAtMs = settledAt6485,
                )
            )
            if (busPublished) {
                busPublishes.incrementAndGet()
                try { PipelineHealthCollector.labelInc("FINALIZED_BUS_PUBLISHED") } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        if (terminal && suppressLearningFanout6490) try {
            PipelineHealthCollector.labelInc("INVENTORY_CORRECTION_LEARNING_SUPPRESSED_6490")
            ForensicLogger.lifecycle("INVENTORY_CORRECTION_LEARNING_SUPPRESSED_6490", "positionId=$positionId mint=${mint.take(10)} basis=${"%.6f".format(soldCostBasisSol)}")
        } catch (_: Throwable) {}
        try {
            CapitalConservationTracer6469.onSell(
                positionId = positionId,
                mint = mint,
                grossProceedsSol = grossProceedsSol,
                soldCostBasisSol = soldCostBasisSol,
                feesSol = feesSol,
                terminal = terminal,
            )
        } catch (_: Throwable) {}
        try {
            CanonicalMintOccupancyRegistry6464.markPendingExit(
                mode = "paper",
                mint = mint,
                symbol = symbol,
                source = "CanonicalPaperTerminalBridge6469",
            )
            if (terminal) CanonicalMintOccupancyRegistry6464.markClosed("paper", mint)
        } catch (_: Throwable) {}
        try {
            AuthoritySnapshotVersion6464.bump(
                if (terminal) "paper_sell_finalized_${symbol}" else "paper_partial_finalized_${symbol}"
            )
        } catch (_: Throwable) {}
        return busPublished
    }

    fun statusLine(): String =
        "fullSells=${fullSells.get()} partials=${partialSells.get()} " +
            "duplicates=${duplicates.get()} busPub=${busPublishes.get()} " +
            "fanoutFail=${fanoutFailures.get()}"

    internal fun resetForTest() {
        fullSells.set(0L); partialSells.set(0L)
        duplicates.set(0L); fanoutFailures.set(0L); busPublishes.set(0L)
    }
}
