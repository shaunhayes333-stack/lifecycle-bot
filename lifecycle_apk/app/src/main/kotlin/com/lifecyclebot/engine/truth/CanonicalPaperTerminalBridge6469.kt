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
    ): Result {
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
            // Legacy mirror + paper ledger are still called during this migration,
            // but only after the terminal claim has granted ownership.
            try {
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
            } catch (_: Throwable) {}
            try {
                PaperAccountLedger6430.onSell(
                    grossProceedsSol = grossProceedsSol,
                    costBasisSoldSol = soldCostBasisSol,
                    feeSol = feesSol,
                )
            } catch (_: Throwable) {}

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
        try {
            val realizedSol = grossProceedsSol - soldCostBasisSol - feesSol
            val realizedPct = if (soldCostBasisSol > 0.0) (realizedSol / soldCostBasisSol) * 100.0 else 0.0
            val env = CanonicalFinalizedTradeBus6464.Envelope(
                tradeId = idKey,
                atMs = System.currentTimeMillis(),
                realizedPnlSol = realizedSol,
                realizedReturnPct = realizedPct,
                mint = mint,
                lane = lane,
            )
            val first = CanonicalFinalizedTradeBus6464.publish(env)
            if (first) {
                busPublished = true
                busPublishes.incrementAndGet()
                try {
                    CanonicalFinalizedTradeBus6464.deliverToConsumers(env) { name, e ->
                        try { FinalizedBusConsumerBridge6465.deliver(name, e) } catch (_: Throwable) { false }
                    }
                } catch (_: Throwable) {}
                try { PipelineHealthCollector.labelInc("FINALIZED_BUS_PUBLISHED") } catch (_: Throwable) {}
            }
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
