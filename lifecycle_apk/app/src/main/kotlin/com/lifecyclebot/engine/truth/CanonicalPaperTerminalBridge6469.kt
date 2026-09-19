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
        /** Immutable economic receipt. Journal/report writers must project
         * these values verbatim; they must never recalculate them from UI
         * quantity, price, TokenState, or PnL. */
        val economicEventId: String = "",
        val grossProceedsSol: Double = 0.0,
        val soldCostBasisSol: Double = 0.0,
        val feesSol: Double = 0.0,
        val preRemainingCostBasisSol: Double = 0.0,
        val postRemainingCostBasisSol: Double = 0.0,
        val canonicalConsumedRaw: BigInteger = BigInteger.ZERO,
        val preRemainingRaw: BigInteger = BigInteger.ZERO,
        val postRemainingRaw: BigInteger = BigInteger.ZERO,
        val tokenDecimals: Int = -1,
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
        val invariantCloseKey6522 = if (terminal) "paper|$positionId|$generation|FULL_CLOSE" else "paper|$positionId|$generation|PARTIAL_CLOSE|$sellSig"
        val idKey = TerminalSellIdempotency6464.makeKey(
            sellExecutionId = invariantCloseKey6522,
            fillId = invariantCloseKey6522,
            signature = invariantCloseKey6522,
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
                terminalSequence = if (terminal) TerminalMutationAuthority6466.FULL_CLOSE_SEQUENCE_6522 else idKey.hashCode().toLong(),
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
        // V5.0.7032 — what priced this sale, carried through to the durable
        // economic event so a future audit can reconstruct it. Zero means the
        // caller could not supply them, and the row is then self-identifying
        // as unreconstructible; see ContaminatedPartialQuarantine7032.
        exitPriceUsd7032: Double = 0.0,
        solUsdAtExit7032: Double = 0.0,
    ): Result {
        val canonicalBefore6522 = CanonicalPositionAuthority6441.getPosition(positionId)
        val sellDecimals6522 = canonicalBefore6522?.quantityScale ?: -1
        val qtyValidation6522 = CanonicalSellQuantityGuard6522.validate(
            mode = "paper", positionId = positionId, generation = generation, mint = mint,
            sellRaw = soldQtyRaw, sellDecimals = sellDecimals6522,
            callerRemainingRaw = preRemainingRaw, terminal = terminal,
        )
        if (!qtyValidation6522.allowed) {
            return Result(false, false, false, "CANONICAL_QTY_${qtyValidation6522.reason}")
        }
        // V5.0.7056 §5 + §6 — VALIDATE PARTIAL ECONOMICS BEFORE ANY MUTATION.
        //
        // This is the earliest point at which the canonical position, the sold
        // quantity, the claimed basis, the claimed proceeds and the exit price
        // are all in hand, and it is upstream of every mutation below: the
        // canonical position mutation, the lot mutation, the qty commit and the
        // PaperAccountLedger cash credit.
        //
        // ContaminatedPartialQuarantine7032 filters reward and learning AFTER
        // the fact, by which time cash has already moved — which is why the
        // operator's ledger reads +550.9932 SOL against -1.3565 SOL of clean
        // canonical performance, and why 108 of 124 partial rows above |1000%|
        // contributed ~+599 SOL. Directive §6: the quarantine belongs here.
        //
        // PARTIALS ONLY. A terminal full exit is untouched — three of the four
        // finalizeSell call sites are full exits that supply no exit price, and
        // failing those closed would strand positions the bot could no longer
        // exit, which is a worse failure than the one being repaired.
        if (!terminal) {
            val econ7056 = try {
                PaperPartialEconomicsGate7056.validate(
                    positionId = positionId, mint = mint, pos = canonicalBefore6522,
                    soldQtyRaw = soldQtyRaw, grossProceedsSol = grossProceedsSol,
                    callerBasisSol = soldCostBasisSol, feesSol = feesSol,
                    exitPriceUsd = exitPriceUsd7032, solUsdAtExit = solUsdAtExit7032,
                )
            } catch (_: Throwable) { null }
            if (econ7056 != null && !econ7056.ok) {
                PaperPartialEconomicsGate7056.logRefusal(positionId, mint, symbol, econ7056)
                // No claim taken, no event opened, no position touched, no cash.
                // The position stays whole and a later, well-priced attempt at
                // the same ladder step can still succeed.
                return Result(
                    applied = false, terminalClaimed = false, busPublished = false,
                    reason = "PARTIAL_ECONOMICS_${econ7056.reason}_7056",
                )
            }
        }
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

        val event6635 = CanonicalEconomicEvent6635.Event(
            economicEventId = sellSig, positionId = positionId, mint = mint,
            canonicalMint = ExecutorCanonicalMirror6442.canonicalMint(mint), symbol = symbol,
            mode = "paper", lane = lane,
            side = if (terminal) CanonicalEconomicEvent6635.Side.SELL else CanonicalEconomicEvent6635.Side.PARTIAL_SELL,
            timestampMs = System.currentTimeMillis(), qtyRaw = soldQtyRaw,
            decimals = sellDecimals6522, executionPriceUsd = 0.0,
            executionPriceSol = 0.0, notionalSol = grossProceedsSol, feeSol = feesSol,
            cashDeltaSol = grossProceedsSol - feesSol, positionQtyDeltaRaw = soldQtyRaw.negate(),
            realizedPnlDeltaSol = grossProceedsSol - soldCostBasisSol - feesSol,
            terminalFillIndex = if (terminal) 1 else 0,
        )
        if (!CanonicalEconomicEvent6635.openEvent(event6635)) {
            try { PipelineHealthCollector.labelInc("CANONICAL_PAPER_SELL_EVENT_OPEN_REJECTED_6641") } catch (_: Throwable) {}
            return Result(false, true, false, "CANONICAL_EVENT_OPEN_REJECTED")
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
            CanonicalEconomicEvent6635.markCommitted(sellSig, CanonicalEconomicEvent6635.Store.POSITION, "CanonicalPaperTerminalBridge6469")
            if (!SellQtyBoundaryClamp6427.commitRaw(positionId, soldQtyRaw, terminal)) {
                try { PipelineHealthCollector.labelInc("CANONICAL_PAPER_SELL_QTY_COMMIT_FAILED_6498") } catch (_: Throwable) {}
                return Result(applied = false, terminalClaimed = true, busPublished = false, reason = "SELL_QTY_COMMIT_FAILED_6498")
            }
            val ledgerApplied6486 = PaperAccountLedger6430.onSellAtomic6632(
                grossProceedsSol = grossProceedsSol,
                costBasisSoldSol = soldCostBasisSol,
                feeSol = feesSol,
                mint = mint,  // V5.0.6502 §1 — enable quarantine reject at ledger source
                attemptKey = sellSig,
                side = if (terminal) PaperEconomicAtomicCommit6632.Side.SELL
                    else PaperEconomicAtomicCommit6632.Side.PARTIAL_SELL,
                // V5.0.6659b — QuantityInvariantAuthority6500's mint
                // quarantine is a Solana-token domain. Applying it to
                // canonical cross-asset identifiers (eth|..., robinhood|...)
                // rejected every Crypto Universe close after the position had
                // already transitioned terminal. Keep the guard strict for
                // Solana positions and bypass only for an explicitly typed
                // non-Solana canonical asset.
                enforceSolanaMintQuarantine = canonicalBefore6522?.assetClass == AssetClass.SOLANA_TOKEN,
            )
            if (!ledgerApplied6486) {
                try { PipelineHealthCollector.labelInc("PAPER_TERMINAL_LEDGER_COMMIT_FAILED_6486") } catch (_: Throwable) {}
                return Result(false, true, false, "LEDGER_COMMIT_FAILED")
            }
            CanonicalEconomicEvent6635.markCommitted(sellSig, CanonicalEconomicEvent6635.Store.LEDGER, "CanonicalPaperTerminalBridge6469")

            val sellLotRow6641 = FillLotLedger6504.recordSellFill(
                mint = mint, lotId = sellSig, qtyTokenRaw = soldQtyRaw,
                lamports = BigInteger.valueOf((grossProceedsSol.coerceAtLeast(0.0) * 1_000_000_000.0).toLong().coerceAtLeast(0L)),
                finalized = true, isPaper = true, source = lane,
                note = "paperSell.atomic6641.${exitReason}".take(120),
            )
            if (sellLotRow6641 <= 0L) {
                try { PipelineHealthCollector.labelInc("PAPER_SELL_FILL_LOT_COMMIT_FAILED_6641") } catch (_: Throwable) {}
                return Result(false, true, false, "FILL_LOT_COMMIT_FAILED")
            }
            CanonicalEconomicEvent6635.markCommitted(sellSig, CanonicalEconomicEvent6635.Store.FILL_LOT, "CanonicalPaperTerminalBridge6469")

            busPublished = applyFanoutAfterClaim(
                economicEventId = sellSig,
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
                exitReason = exitReason,
                terminal = terminal,
                suppressLearningFanout6490 = suppressLearningFanout6490,
                exitPriceUsd7032 = exitPriceUsd7032,
                solUsdAtExit7032 = solUsdAtExit7032,
            )
            CanonicalEconomicEvent6635.markCommitted(sellSig, CanonicalEconomicEvent6635.Store.TERMINAL_EXEC, "CanonicalPaperTerminalBridge6469")
            if (terminal) {
                fullSells.incrementAndGet()
                try { PipelineHealthCollector.labelInc("CANONICAL_TERMINAL_SELL") } catch (_: Throwable) {}
            } else {
                partialSells.incrementAndGet()
                try { PipelineHealthCollector.labelInc("CANONICAL_TERMINAL_PARTIAL") } catch (_: Throwable) {}
            }
            return Result(applied = true, terminalClaimed = true, busPublished = busPublished, reason = "GRANTED",
                economicEventId = sellSig, grossProceedsSol = grossProceedsSol,
                soldCostBasisSol = soldCostBasisSol, feesSol = feesSol,
                preRemainingCostBasisSol = preRemainingCostBasisSol,
                postRemainingCostBasisSol = (preRemainingCostBasisSol - soldCostBasisSol).coerceAtLeast(0.0),
                canonicalConsumedRaw = soldQtyRaw, preRemainingRaw = preRemainingRaw,
                postRemainingRaw = preRemainingRaw.subtract(soldQtyRaw).coerceAtLeast(BigInteger.ZERO),
                tokenDecimals = CanonicalPositionAuthority6441.getPosition(positionId)?.quantityScale ?: -1)
        } catch (t: Throwable) {
            fanoutFailures.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "CANONICAL_PAPER_TERMINAL_BRIDGE_FANOUT_THREW_6469",
                    "positionId=$positionId mint=${mint.take(10)} err=${t.message}",
                )
                PipelineHealthCollector.labelInc("CANONICAL_PAPER_TERMINAL_BRIDGE_FANOUT_THREW_6469")
            } catch (_: Throwable) {}
            return Result(applied = true, terminalClaimed = true, busPublished = busPublished, reason = "APPLIED_WITH_FANOUT_ERROR",
                economicEventId = sellSig, grossProceedsSol = grossProceedsSol,
                soldCostBasisSol = soldCostBasisSol, feesSol = feesSol,
                preRemainingCostBasisSol = preRemainingCostBasisSol,
                postRemainingCostBasisSol = (preRemainingCostBasisSol - soldCostBasisSol).coerceAtLeast(0.0),
                canonicalConsumedRaw = soldQtyRaw, preRemainingRaw = preRemainingRaw,
                postRemainingRaw = preRemainingRaw.subtract(soldQtyRaw).coerceAtLeast(BigInteger.ZERO),
                tokenDecimals = CanonicalPositionAuthority6441.getPosition(positionId)?.quantityScale ?: -1)
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
            economicEventId = sellSig,
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
            exitReason = exitReason,
            terminal = terminal,
            // V5.0.7032 — defaults (0.0) on purpose: this entry point is not
            // given a mark or a rate by its caller, so a row it writes is
            // genuinely unreconstructible and should say so. It has no callers
            // at all today, so it cannot over-quarantine anything.
        )
        if (terminal) fullSells.incrementAndGet() else partialSells.incrementAndGet()
        return Result(applied = true, terminalClaimed = true, busPublished = bus, reason = "GRANTED")
    }

    private fun applyFanoutAfterClaim(
        economicEventId: String,
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
        exitReason: String,
        terminal: Boolean,
        suppressLearningFanout6490: Boolean = false,
        // V5.0.7032 — recordSell lives in THIS function, not in finalizeSell.
        // 7032 added these parameters to finalizeSell's signature and used them
        // at the recordSell call site without checking which function that call
        // site belonged to; they are different functions, and the build failed
        // on two unresolved references. Same mistake as 7017's entryMcapUsd:
        // matched on text, not on ownership.
        exitPriceUsd7032: Double = 0.0,
        solUsdAtExit7032: Double = 0.0,
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
                // One immutable identity across canonical event, durable
                // economic schema, journal and terminal execution.
                idempotencyKey = economicEventId,
                partial = !terminal,
                soldQty = soldQtyRaw,
                preRemainingQty = preRemainingRaw,
                preRemainingCostBasisSol = preRemainingCostBasisSol,
                grossProceedsSol = grossProceedsSol,
                exitFeesSol = feesSol,
                exitPriceUsd = exitPriceUsd7032,
                solUsdAtExit = solUsdAtExit7032,
            )
        } catch (_: Throwable) {}

        var busPublished = false
        if (terminal && !suppressLearningFanout6490) try {
            val realizedSol = grossProceedsSol - soldCostBasisSol - feesSol
            val realizedPct = if (soldCostBasisSol > 0.0) (realizedSol / soldCostBasisSol) * 100.0 else 0.0
            val settledAt6485 = System.currentTimeMillis()
            val entrySnap6485 = EntryStrategySnapshot6450.snapshot(positionId)
            val outcome6485 = when (CanonicalOutcomeClassifier6576.classifyReadonly(realizedPct)) {
                CanonicalOutcomeClassifier6576.Class.WIN -> CanonicalTradeFinalizedBus6450.Outcome.WIN
                CanonicalOutcomeClassifier6576.Class.LOSS -> CanonicalTradeFinalizedBus6450.Outcome.LOSS
                CanonicalOutcomeClassifier6576.Class.BREAKEVEN -> CanonicalTradeFinalizedBus6450.Outcome.BREAKEVEN
            }
            val finalizedEvent6653 = CanonicalTradeFinalizedBus6450.Event(
                positionId = positionId, mint = mint, outcome = outcome6485,
                netRealizedPnlSol = realizedSol, grossRealizedPnlSol = grossProceedsSol - soldCostBasisSol,
                returnFraction = realizedPct / 100.0, netReturnPct = realizedPct, feesSol = feesSol,
                entryLane = entrySnap6485?.entryLane ?: lane,
                entryStrategyPid = entrySnap6485?.entryStrategyPid ?: "",
                entryTactic = entrySnap6485?.entryTactic ?: "",
                exitReason = exitReason, holdingTimeMs = if (entrySnap6485 != null) settledAt6485 - entrySnap6485.entryTimestampMs else 0L,
                dataQuality = "canonical_paper_fill", priceIntegrity = "canonical_paper_fill",
                mode = "paper", settledAtMs = settledAt6485,
                assetClassTag = entrySnap6485?.assetClassTag ?: CanonicalPositionAuthority6441.getPosition(positionId)?.assetClass?.tag.orEmpty(),
                economicEventId = economicEventId,
            )

            fun publishCommitted6653() {
                if (CanonicalTradeFinalizedBus6450.publish(finalizedEvent6653)) {
                    busPublishes.incrementAndGet()
                    try { PipelineHealthCollector.labelInc("FINALIZED_BUS_PUBLISHED") } catch (_: Throwable) {}
                }
            }

            // V5.0.6653 — finalized learning is a post-COMMIT event.  Before
            // this repair the bus published after POSITION/LEDGER/FILL_LOT but
            // before JOURNAL and TERMINAL_EXEC, causing every consumer to retry
            // a non-existent exact event thousands of times.  Queue one
            // idempotent callback on the economic transaction itself instead.
            busPublished = if (CanonicalEconomicEvent6635.isCommitted(economicEventId)) {
                publishCommitted6653()
                true
            } else {
                CanonicalEconomicEvent6635.afterCommitted(economicEventId) {
                    publishCommitted6653()
                }.also { queued ->
                    if (queued) try { PipelineHealthCollector.labelInc("FINALIZED_BUS_QUEUED_AFTER_COMMIT_6653") } catch (_: Throwable) {}
                }
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
