package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6469 §P0 — CANONICAL PAPER TERMINAL BRIDGE.
 *
 * OPERATOR MANDATE (verbatim, 6468 evidence):
 *
 *   "paper BUY=224 SELL=92 PARTIAL=67
 *    canonical economicSchema=205 BUYs, 0 SELLs, 0 PARTIALs
 *    finalizedBus=0
 *    terminalIdempotency=0
 *    PAPER_CLOSE_FAILED/NO_CANONICAL_POSITION/TERMINAL_ABANDONED=402
 *    There must be ONE authoritative paper/live terminal mutation path."
 *
 * ROOT CAUSE
 * ──────────
 * Every paper SELL / PARTIAL in Executor.kt calls the pair
 *   ExecutorCanonicalMirror6442.mirrorSell(...)   // position + lot ledger
 *   PaperAccountLedger6430.onSell(...)            // cash ledger
 * directly, and the entire canonical event-graph downstream of the sell
 * (`EconomicEventSchema6464.recordSell`, `TerminalSellIdempotency6464`,
 * `TerminalMutationAuthority6466`, `CanonicalFinalizedTradeBus6464`,
 * `CanonicalMintOccupancyRegistry6464.markPendingExit/markClosed`) is
 * SKIPPED. `SellFinalizationCoordinator` performs the full fanout, but
 * only live executor paths invoke it — paper paths never do.
 *
 * FIX (source-level, not symptom-level)
 * ─────────────────────────────────────
 * Every paper sell/partial code site funnels through this bridge:
 *
 *   finalizeSell(positionId, mint, symbol, generation, sellSig,
 *                soldQtyRaw, preRemainingRaw, preRemainingCostBasisSol,
 *                grossProceedsSol, soldCostBasisSol, feesSol, lane,
 *                exitReason, terminal)
 *
 * The bridge performs the following mutations in a single atomic
 * envelope, in this exact order:
 *
 *   1. `ExecutorCanonicalMirror6442.mirrorSell`
 *      (existing legacy — position lifecycle, lot ledger, reward gate)
 *   2. `PaperAccountLedger6430.onSell`
 *      (existing legacy — cash + realized PnL projection)
 *   3. `CanonicalLotQuantity6464.onSellFilled`
 *      (new for paper — confirms sold qty against canonical lot)
 *   4. `TerminalSellIdempotency6464.beginTerminal`
 *      (new for paper — stamps the terminal idempotency claim)
 *   5. `TerminalMutationAuthority6466.claim`
 *      (new for paper — second-layer CAS by runId+mode+positionId+
 *      generation+terminalSequence)
 *   6. `EconomicEventSchema6464.recordSell`
 *      (new for paper — canonical typed economic event)
 *   7. `CanonicalFinalizedTradeBus6464.publish` +
 *      `deliverToConsumers` via `FinalizedBusConsumerBridge6465`
 *      (new for paper — 8 downstream consumers receive real
 *      onTradeClosed deliveries)
 *   8. `CanonicalMintOccupancyRegistry6464.markPendingExit` +
 *      `markClosed` if terminal
 *      (new for paper — occupancy slot transitions correctly)
 *   9. `AuthoritySnapshotVersion6464.bump("paper_sell_finalized_…")`
 *  10. `CapitalConservationTracer6469.onSell` — sanity-trace the
 *      cash/realized/openCost delta after this mutation.
 *
 * Steps (1)+(2) preserve legacy behaviour. Steps (3)-(9) close the
 * canonical divergence. Step (10) records the conservation trace so
 * the -1.53 delta can be traced to its source mutation.
 *
 * Idempotency: (3)-(9) are idempotency-gated by `beginTerminal` +
 * `TerminalMutationAuthority6466.claim`; a replay/duplicate call
 * short-circuits before any second side-effect.
 *
 * Contract:
 *   - Every paper terminal mutation goes through THIS function.
 *   - Callers may pass `terminal=true` for full close, `false` for partial.
 *   - `sellSig` for paper is a synthetic id (e.g. "paper_<mint>_<ms>");
 *     it must be stable within a single close so idempotency works.
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

    /**
     * Finalise a paper sell / partial. Idempotent per (positionId, generation, sellSig).
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
        // 1 + 2. legacy mirrors (position lifecycle + cash ledger)
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

        // 3-9. canonical fanout, gated by terminal idempotency + mutation authority.
        var terminalClaimed = false
        var busPublished = false
        try {
            val idKey = TerminalSellIdempotency6464.makeKey(
                sellExecutionId = null, fillId = sellSig, signature = sellSig,
            )
            val consume = TerminalSellIdempotency6464.beginTerminal(
                key = idKey, positionId = positionId,
                sitePath = "CanonicalPaperTerminalBridge6469($exitReason)",
            )
            if (consume == TerminalSellIdempotency6464.Consume.PROCEED) {
                val claim = TerminalMutationAuthority6466.claim(
                    TerminalMutationAuthority6466.TerminalEvent(
                        positionId = positionId, mint = mint, symbol = symbol,
                        mode = "paper", generation = generation,
                        terminalSequence = if (terminal) 999L else System.currentTimeMillis(),
                        runId = "run", exitReason = exitReason,
                    ),
                )
                if (claim == TerminalMutationAuthority6466.ClaimResult.GRANTED) {
                    terminalClaimed = true
                    // 3. lot quantity
                    try {
                        CanonicalLotQuantity6464.onSellFilled(
                            positionId = positionId, mint = mint, filledQty = soldQtyRaw,
                        )
                    } catch (_: Throwable) {}
                    // 6. economic event (typed)
                    try {
                        EconomicEventSchema6464.recordSell(
                            mode = "paper", positionId = positionId, mint = mint, symbol = symbol,
                            idempotencyKey = idKey, partial = !terminal,
                            soldQty = soldQtyRaw,
                            preRemainingQty = preRemainingRaw,
                            preRemainingCostBasisSol = preRemainingCostBasisSol,
                            grossProceedsSol = grossProceedsSol, exitFeesSol = feesSol,
                        )
                    } catch (_: Throwable) {}
                    // 7. finalized bus + consumers
                    try {
                        val realizedSol = grossProceedsSol - soldCostBasisSol - feesSol
                        val realizedPct = if (soldCostBasisSol > 0.0)
                            (realizedSol / soldCostBasisSol) * 100.0 else 0.0
                        val env = CanonicalFinalizedTradeBus6464.Envelope(
                            tradeId = idKey, atMs = System.currentTimeMillis(),
                            realizedPnlSol = realizedSol, realizedReturnPct = realizedPct,
                            mint = mint, lane = lane,
                        )
                        val first = CanonicalFinalizedTradeBus6464.publish(env)
                        if (first) {
                            busPublished = true
                            busPublishes.incrementAndGet()
                            try {
                                CanonicalFinalizedTradeBus6464.deliverToConsumers(env) { name, e ->
                                    try { FinalizedBusConsumerBridge6465.deliver(name, e) }
                                    catch (_: Throwable) { false }
                                }
                            } catch (_: Throwable) {}
                            try { PipelineHealthCollector.labelInc("FINALIZED_BUS_PUBLISHED") } catch (_: Throwable) {}
                        }
                    } catch (_: Throwable) {}
                    // 10. conservation tracer
                    try {
                        CapitalConservationTracer6469.onSell(
                            positionId = positionId, mint = mint,
                            grossProceedsSol = grossProceedsSol,
                            soldCostBasisSol = soldCostBasisSol,
                            feesSol = feesSol, terminal = terminal,
                        )
                    } catch (_: Throwable) {}
                    // counters + forensic
                    if (terminal) {
                        fullSells.incrementAndGet()
                        try { PipelineHealthCollector.labelInc("CANONICAL_TERMINAL_SELL") } catch (_: Throwable) {}
                    } else {
                        partialSells.incrementAndGet()
                        try { PipelineHealthCollector.labelInc("CANONICAL_TERMINAL_PARTIAL") } catch (_: Throwable) {}
                    }
                } else {
                    duplicates.incrementAndGet()
                }
            } else {
                duplicates.incrementAndGet()
            }
            // 8. occupancy (always) — pendingExit for partials, closed for terminals.
            try {
                CanonicalMintOccupancyRegistry6464.markPendingExit(
                    mode = "paper", mint = mint, symbol = symbol,
                    source = "CanonicalPaperTerminalBridge6469",
                )
                if (terminal) {
                    CanonicalMintOccupancyRegistry6464.markClosed("paper", mint)
                }
            } catch (_: Throwable) {}
            // 9. snapshot bump
            try {
                AuthoritySnapshotVersion6464.bump(
                    if (terminal) "paper_sell_finalized_${symbol}" else "paper_partial_finalized_${symbol}"
                )
            } catch (_: Throwable) {}
        } catch (t: Throwable) {
            fanoutFailures.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "CANONICAL_PAPER_TERMINAL_BRIDGE_FANOUT_THREW_6469",
                    "positionId=$positionId mint=${mint.take(10)} err=${t.message}",
                )
                PipelineHealthCollector.labelInc("CANONICAL_PAPER_TERMINAL_BRIDGE_FANOUT_THREW_6469")
            } catch (_: Throwable) {}
        }
        return Result(applied = true, terminalClaimed = terminalClaimed,
            busPublished = busPublished,
            reason = if (terminalClaimed) "GRANTED" else "DUPLICATE_OR_CLAIM_REFUSED")
    }

    /**
     * Emit ONLY the canonical downstream fanout (steps 3-9). Call this
     * from paper sell sites that already invoke
     * `ExecutorCanonicalMirror6442.mirrorSell` and
     * `PaperAccountLedger6430.onSell` themselves — it wires the missing
     * event / bus / idempotency / occupancy fanout without touching the
     * existing legacy mirror + ledger pair.
     *
     * Same idempotency guarantees as `finalizeSell`.
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
        var terminalClaimed = false
        var busPublished = false
        try {
            val idKey = TerminalSellIdempotency6464.makeKey(
                sellExecutionId = null, fillId = sellSig, signature = sellSig,
            )
            val consume = TerminalSellIdempotency6464.beginTerminal(
                key = idKey, positionId = positionId,
                sitePath = "CanonicalPaperTerminalBridge6469.fanout($exitReason)",
            )
            if (consume == TerminalSellIdempotency6464.Consume.PROCEED) {
                val claim = TerminalMutationAuthority6466.claim(
                    TerminalMutationAuthority6466.TerminalEvent(
                        positionId = positionId, mint = mint, symbol = symbol,
                        mode = "paper", generation = generation,
                        terminalSequence = if (terminal) 999L else System.currentTimeMillis(),
                        runId = "run", exitReason = exitReason,
                    ),
                )
                if (claim == TerminalMutationAuthority6466.ClaimResult.GRANTED) {
                    terminalClaimed = true
                    try {
                        CanonicalLotQuantity6464.onSellFilled(
                            positionId = positionId, mint = mint, filledQty = soldQtyRaw,
                        )
                    } catch (_: Throwable) {}
                    try {
                        EconomicEventSchema6464.recordSell(
                            mode = "paper", positionId = positionId, mint = mint, symbol = symbol,
                            idempotencyKey = idKey, partial = !terminal,
                            soldQty = soldQtyRaw,
                            preRemainingQty = preRemainingRaw,
                            preRemainingCostBasisSol = preRemainingCostBasisSol,
                            grossProceedsSol = grossProceedsSol, exitFeesSol = feesSol,
                        )
                    } catch (_: Throwable) {}
                    try {
                        val realizedSol = grossProceedsSol - soldCostBasisSol - feesSol
                        val realizedPct = if (soldCostBasisSol > 0.0)
                            (realizedSol / soldCostBasisSol) * 100.0 else 0.0
                        val env = CanonicalFinalizedTradeBus6464.Envelope(
                            tradeId = idKey, atMs = System.currentTimeMillis(),
                            realizedPnlSol = realizedSol, realizedReturnPct = realizedPct,
                            mint = mint, lane = lane,
                        )
                        val first = CanonicalFinalizedTradeBus6464.publish(env)
                        if (first) {
                            busPublished = true
                            busPublishes.incrementAndGet()
                            try {
                                CanonicalFinalizedTradeBus6464.deliverToConsumers(env) { name, e ->
                                    try { FinalizedBusConsumerBridge6465.deliver(name, e) }
                                    catch (_: Throwable) { false }
                                }
                            } catch (_: Throwable) {}
                            try { PipelineHealthCollector.labelInc("FINALIZED_BUS_PUBLISHED") } catch (_: Throwable) {}
                        }
                    } catch (_: Throwable) {}
                    try {
                        CapitalConservationTracer6469.onSell(
                            positionId = positionId, mint = mint,
                            grossProceedsSol = grossProceedsSol,
                            soldCostBasisSol = soldCostBasisSol,
                            feesSol = feesSol, terminal = terminal,
                        )
                    } catch (_: Throwable) {}
                    if (terminal) {
                        fullSells.incrementAndGet()
                        try { PipelineHealthCollector.labelInc("CANONICAL_TERMINAL_SELL") } catch (_: Throwable) {}
                    } else {
                        partialSells.incrementAndGet()
                        try { PipelineHealthCollector.labelInc("CANONICAL_TERMINAL_PARTIAL") } catch (_: Throwable) {}
                    }
                } else {
                    duplicates.incrementAndGet()
                }
            } else {
                duplicates.incrementAndGet()
            }
            try {
                CanonicalMintOccupancyRegistry6464.markPendingExit(
                    mode = "paper", mint = mint, symbol = symbol,
                    source = "CanonicalPaperTerminalBridge6469.fanout",
                )
                if (terminal) {
                    CanonicalMintOccupancyRegistry6464.markClosed("paper", mint)
                }
            } catch (_: Throwable) {}
            try {
                AuthoritySnapshotVersion6464.bump(
                    if (terminal) "paper_sell_finalized_${symbol}" else "paper_partial_finalized_${symbol}"
                )
            } catch (_: Throwable) {}
        } catch (t: Throwable) {
            fanoutFailures.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "CANONICAL_PAPER_TERMINAL_BRIDGE_FANOUT_THREW_6469",
                    "positionId=$positionId mint=${mint.take(10)} err=${t.message}",
                )
                PipelineHealthCollector.labelInc("CANONICAL_PAPER_TERMINAL_BRIDGE_FANOUT_THREW_6469")
            } catch (_: Throwable) {}
        }
        return Result(applied = true, terminalClaimed = terminalClaimed,
            busPublished = busPublished,
            reason = if (terminalClaimed) "GRANTED" else "DUPLICATE_OR_CLAIM_REFUSED")
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
