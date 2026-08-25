package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.TokenLifecycleTracker
import java.math.BigInteger

/**
 * V5.9.495z39 — operator spec items 1 / 4 / 6 / 7 / 8 wiring.
 *
 * Single entry point that the executor's SELL_CONFIRMED branch calls
 * once the tx has landed. Coordinates:
 *
 *   1. SellAmountAuditor.audit         — flag + lock if actual >> requested
 *   2. TxMetaSellFinalizer.finalize    — derive final state from chain meta
 *   3. RealizedPnLCalculator.calculate — proportional cost basis PnL
 *   4. WalletRefreshAfterSell.forceRefresh — pull a fresh on-chain SOL balance
 *   5. SellForensicsWriter.writeSellLanded — emit canonical SELL_LANDED row
 *   6. TokenLifecycleTracker.onSellSettled — update authoritative ledger
 *
 * All inputs are chain-confirmed values. Caller MUST NOT pass cached UI
 * prices or RPC empty-map fallbacks here.
 */
object SellFinalizationCoordinator {

    data class Result(
        val finalState: TxMetaSellFinalizer.FinalState,
        val actualConsumedRaw: BigInteger,
        val remainingRaw: BigInteger,
        val solReceived: Double,
        val realizedPnl: RealizedPnLCalculator.Result,
        val sellAmountViolation: Boolean,
        val freshWalletSol: Double,
        val finality: TxMetaSellFinalizer.SellFinalityResult,
        val pendingRetry: Boolean,
    )

    /**
     * @param intent              the SellIntent that initiated this sell
     * @param preTokenBalanceRaw  tx-meta preTokenBalances[owner+mint]; null if missing
     * @param postTokenBalanceRaw tx-meta postTokenBalances[owner+mint]; null if missing
     * @param walletPollRaw       latest wallet poll for the same mint after retries; null if RPC blip
     * @param solReceivedLamports lamports delta produced by the sell tx
     * @param sellSolReceived     the SOL amount received (UI value)
     * @param feesSol             total fees paid for this sell (network + bot fee)
     * @param decimals            SPL decimals for the mint
     * @param slippageUsedBps     the slippage bps that was actually used to land the tx
     * @param sellSig             the on-chain sell signature
     * @param traderTag           "MEME" / "ALT" / "MARKETS"
     */
    fun finalize(
        intent: SellIntent,
        preTokenBalanceRaw: BigInteger?,
        postTokenBalanceRaw: BigInteger?,
        walletPollRaw: BigInteger?,
        solReceivedLamports: Long,
        sellSolReceived: Double,
        feesSol: Double,
        decimals: Int,
        slippageUsedBps: Int,
        sellSig: String,
        traderTag: String = "MEME",
    ): Result {
        // (2) chain-meta-driven final state + consumed amount
        val fin = TxMetaSellFinalizer.finalize(
            mint = intent.mint,
            signature = sellSig,
            previousRawQty = intent.entryTokenRaw.takeIf { it.signum() > 0 } ?: intent.confirmedWalletRaw,
            preTokenBalanceRaw = preTokenBalanceRaw,
            postTokenBalanceRaw = postTokenBalanceRaw,
            walletPollRaw = walletPollRaw,
            solReceivedLamports = solReceivedLamports,
            txSlot = 0L,
            routedQuoteSettlementProof = sellSolReceived > 0.0,
        )
        val actualConsumedRaw = fin.actualConsumedRaw
        val remainingRaw = fin.remainingRaw

        if (fin.finality is TxMetaSellFinalizer.SellFinalityResult.PendingRetry) {
            try {
                ForensicLogger.lifecycle(
                    "SELL_FINALITY_PENDING_RETRY",
                    "mint=${intent.mint.take(10)} symbol=${intent.symbol} reason=${fin.finality.reason} sig=${fin.finality.signature?.take(12) ?: ""} prev=${fin.finality.previousRawQty} action=no_close_no_journal_no_learning_keep_lease",
                )
                PipelineHealthCollector.labelInc("SELL_FINALITY_PENDING_RETRY")
                PipelineHealthCollector.labelInc("SELL_FINALITY_PENDING_RETRY_${fin.finality.reason}")
                CloseLease.recordRetry(intent.mint, "SELL_FINALITY_PENDING_RETRY_${fin.finality.reason}")
                SellReconciler.requestUrgentTick("SELL_FINALITY_PENDING_RETRY_${fin.finality.reason}")
            } catch (_: Throwable) {}
            return Result(
                finalState = fin.finalState,
                actualConsumedRaw = actualConsumedRaw,
                remainingRaw = remainingRaw,
                solReceived = sellSolReceived,
                realizedPnl = RealizedPnLCalculator.calculate(intent.entrySolSpent, intent.entryTokenRaw, BigInteger.ZERO, 0.0, 0.0),
                sellAmountViolation = false,
                freshWalletSol = 0.0,
                finality = fin.finality,
                pendingRetry = true,
            )
        }
        if (fin.finality is TxMetaSellFinalizer.SellFinalityResult.FailedWithProof) {
            try {
                ForensicLogger.lifecycle("SELL_FINALITY_FAILED_WITH_PROOF", "mint=${intent.mint.take(10)} symbol=${intent.symbol} reason=${fin.finality.reason} sig=${fin.finality.signature?.take(12) ?: ""} action=no_close")
                PipelineHealthCollector.labelInc("SELL_FINALITY_FAILED_WITH_PROOF")
                CloseLease.recordRetry(intent.mint, "SELL_FINALITY_FAILED_WITH_PROOF_${fin.finality.reason}")
            } catch (_: Throwable) {}
            return Result(
                finalState = fin.finalState,
                actualConsumedRaw = actualConsumedRaw,
                remainingRaw = remainingRaw,
                solReceived = sellSolReceived,
                realizedPnl = RealizedPnLCalculator.calculate(intent.entrySolSpent, intent.entryTokenRaw, BigInteger.ZERO, 0.0, 0.0),
                sellAmountViolation = true,
                freshWalletSol = 0.0,
                finality = fin.finality,
                pendingRetry = true,
            )
        }

        // (1) audit actual consumed vs requested. Locks the mint on violation.
        val pass = SellAmountAuditor.audit(intent, actualConsumedRaw)
        val violation = !pass

        // (3) proportional cost-basis realized PnL
        val pnl = RealizedPnLCalculator.calculate(
            entrySolSpent = intent.entrySolSpent,
            entryTokenRaw = intent.entryTokenRaw,
            actualConsumedRaw = actualConsumedRaw,
            sellSolReceived = sellSolReceived,
            feesSol = feesSol,
        )

        // (4) force on-chain wallet refresh — never trust stale balance
        val fresh = WalletRefreshAfterSell.forceRefresh(reason = "sell-${intent.reason}")

        // (5) canonical SELL_LANDED forensics row.
        // Skip emission when caller has no SOL-received value yet (degenerate);
        // the executor's existing Trade/journal pipeline remains the source of
        // truth for those cases. The auditor + lifecycle update above still run.
        val haveSolReceived = sellSolReceived > 0.0 || solReceivedLamports > 0L
        if (haveSolReceived) {
            SellForensicsWriter.writeSellLanded(
                intent = intent,
                finalState = fin.finalState,
                actualConsumedRaw = actualConsumedRaw,
                remainingRaw = remainingRaw,
                solReceived = sellSolReceived,
                pnl = pnl,
                slippageUsedBps = slippageUsedBps,
                sellAmountViolation = violation,
                decimals = decimals,
                traderTag = traderTag,
            )
        }

        // (6) update authoritative ledger
        try {
            val walletAfterUi = if (decimals > 0)
                remainingRaw.toBigDecimal().movePointLeft(decimals).toDouble()
            else
                null
            TokenLifecycleTracker.onSellSettled(
                mint = intent.mint,
                sig = sellSig,
                solReceived = sellSolReceived,
                walletTokenAfter = walletAfterUi,
            )
        } catch (e: Throwable) {
            ErrorLogger.warn("SellFinalizationCoordinator", "lifecycle update failed: ${e.message}")
        }

        val result6486 = Result(
            finalState = fin.finalState,
            actualConsumedRaw = actualConsumedRaw,
            remainingRaw = remainingRaw,
            solReceived = sellSolReceived,
            realizedPnl = pnl,
            sellAmountViolation = violation,
            freshWalletSol = fresh,
            finality = fin.finality,
            pendingRetry = false,
        )
        var canonicalMutationFailed6486 = false
        run {
            val res = result6486
            // V5.0.6463 §P1 — PARTIAL-SELL UNIT CORRECTNESS.
            try {
                com.lifecyclebot.engine.truth.PartialSellCorrectness6463.validate(
                    res, feesSol, siteTag = "SellFinalizationCoordinator.finalize(${traderTag})",
                )
            } catch (_: Throwable) {}

            // V5.0.6464 §P0 — CANONICAL EVENT FANOUT (idempotent, keyed by sellSig).
            // On the FIRST observation only:
            //   §P0-#3 confirm the sold qty on CanonicalLotQuantity6464
            //   §P0-#4 stamp the terminal idempotency record
            //   §P0-#5 emit the typed economic event
            //   §P0-#7 publish to the canonical finalized-trade bus
            // Duplicate observations bail before any of the above.
            try {
                val positionId = com.lifecyclebot.engine.truth.ExecutorCanonicalMirror6442.positionIdOf(intent.mint)
                val canonicalPosition6522 = com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.getPosition(positionId) ?: return@run
                val partial = fin.finalState != TxMetaSellFinalizer.FinalState.CLEARED
                val qtyValidation6522 = com.lifecyclebot.engine.truth.CanonicalSellQuantityGuard6522.validate(
                    mode = "live", positionId = positionId, generation = canonicalPosition6522.openedAtMs, mint = intent.mint,
                    sellRaw = actualConsumedRaw, sellDecimals = canonicalPosition6522.quantityScale,
                    callerRemainingRaw = canonicalPosition6522.remainingQtyRaw, terminal = !partial,
                )
                if (!qtyValidation6522.allowed) return@run
                val invariantCloseKey6522 = if (!partial) "live|$positionId|${canonicalPosition6522.openedAtMs}|FULL_CLOSE" else "live|$positionId|${canonicalPosition6522.openedAtMs}|PARTIAL_CLOSE|$sellSig"
                val idKey = com.lifecyclebot.engine.truth.TerminalSellIdempotency6464.makeKey(
                    sellExecutionId = invariantCloseKey6522, fillId = invariantCloseKey6522, signature = invariantCloseKey6522,
                )
                val consume = com.lifecyclebot.engine.truth.TerminalSellIdempotency6464.beginTerminal(
                    key = idKey, positionId = positionId,
                    sitePath = "SellFinalizationCoordinator.finalize(${traderTag})",
                )
                if (consume == com.lifecyclebot.engine.truth.TerminalSellIdempotency6464.Consume.PROCEED) {
                    // V5.0.6466 §P0 — TERMINAL MUTATION AUTHORITY.
                    // Second-layer CAS keyed on runId+mode+positionId+
                    // generation+terminalSequence so replay/reconciler
                    // reattempts cannot double-fire the fanout even if
                    // the sellSig gets reused across a run restart.
                    val positionId6466 = positionId
                    val termClaim6466 = com.lifecyclebot.engine.truth.TerminalMutationAuthority6466.claim(
                        com.lifecyclebot.engine.truth.TerminalMutationAuthority6466.TerminalEvent(
                            positionId = positionId6466, mint = intent.mint, symbol = intent.symbol,
                            mode = "live", generation = canonicalPosition6522.openedAtMs,
                            terminalSequence = if (!partial) com.lifecyclebot.engine.truth.TerminalMutationAuthority6466.FULL_CLOSE_SEQUENCE_6522 else idKey.hashCode().toLong(),
                            runId = "run", exitReason = intent.reason.name,
                        )
                    )
                    if (termClaim6466 != com.lifecyclebot.engine.truth.TerminalMutationAuthority6466.ClaimResult.GRANTED) {
                        // Duplicate: bail before any side effect.
                        return@run
                    }
                    val positionApplied6486 = com.lifecyclebot.engine.truth.ExecutorCanonicalMirror6442.mirrorSell(
                        mint = intent.mint,
                        generation = canonicalPosition6522.openedAtMs,
                        soldQtyRaw = actualConsumedRaw,
                        proceedsSol = sellSolReceived,
                        soldCostBasisSol = pnl.proportionalCostBasisSol,
                        feesSol = feesSol,
                        paperMode = false,
                        terminal = !partial,
                        lane = com.lifecyclebot.engine.truth.EntryStrategySnapshot6450.resolveExitLane(positionId, traderTag),
                        reason = intent.reason.name,
                    )
                    if (!positionApplied6486) {
                        canonicalMutationFailed6486 = true
                        com.lifecyclebot.engine.truth.LearningQuarantineGate6470.quarantinePositionId(positionId, "LIVE_SELL_CANONICAL_POSITION_REJECTED_6486")
                        try { PipelineHealthCollector.labelInc("LIVE_SELL_CANONICAL_POSITION_REJECTED_6486") } catch (_: Throwable) {}
                        return@run
                    }
                    com.lifecyclebot.engine.truth.CanonicalLotQuantity6464.onSellFilled(
                        positionId = positionId, mint = intent.mint, filledQty = actualConsumedRaw,
                    )
                    com.lifecyclebot.engine.truth.EconomicEventSchema6464.recordSell(
                        mode = "live",
                        positionId = positionId, mint = intent.mint, symbol = intent.symbol,
                        idempotencyKey = idKey, partial = partial,
                        soldQty = actualConsumedRaw,
                        preRemainingQty = intent.entryTokenRaw.takeIf { it.signum() > 0 } ?: intent.confirmedWalletRaw,
                        preRemainingCostBasisSol = intent.entrySolSpent,
                        grossProceedsSol = sellSolReceived, exitFeesSol = feesSol,
                    )
                    val realizedPct = pnl.realizedPnlPct
                    if (!partial) {
                        val settledAt6485 = System.currentTimeMillis()
                        val entrySnap6485 = com.lifecyclebot.engine.truth.EntryStrategySnapshot6450.snapshot(positionId)
                        val outcome6485 = when {
                            pnl.realizedPnlSol > 0.0001 -> com.lifecyclebot.engine.truth.CanonicalTradeFinalizedBus6450.Outcome.WIN
                            pnl.realizedPnlSol < -0.0001 -> com.lifecyclebot.engine.truth.CanonicalTradeFinalizedBus6450.Outcome.LOSS
                            else -> com.lifecyclebot.engine.truth.CanonicalTradeFinalizedBus6450.Outcome.BREAKEVEN
                        }
                        com.lifecyclebot.engine.truth.CanonicalTradeFinalizedBus6450.publish(
                            com.lifecyclebot.engine.truth.CanonicalTradeFinalizedBus6450.Event(
                                positionId = positionId, mint = intent.mint, outcome = outcome6485,
                                netRealizedPnlSol = pnl.realizedPnlSol,
                                grossRealizedPnlSol = sellSolReceived - pnl.proportionalCostBasisSol,
                                returnFraction = realizedPct / 100.0, netReturnPct = realizedPct, feesSol = feesSol,
                                entryLane = entrySnap6485?.entryLane ?: traderTag,
                                entryStrategyPid = entrySnap6485?.entryStrategyPid ?: "",
                                entryTactic = entrySnap6485?.entryTactic ?: "",
                                exitReason = intent.reason.name,
                                holdingTimeMs = if (entrySnap6485 != null) settledAt6485 - entrySnap6485.entryTimestampMs else 0L,
                                dataQuality = "confirmed_signature", priceIntegrity = "confirmed_signature",
                                mode = "live", settledAtMs = settledAt6485,
                            )
                        )
                    }
                    // V5.0.6464 §P1 — root-cause TTL: a healthy terminal
                    // sell clears any stale MECHANICAL_FAULT header.
                    try {
                        val cur = com.lifecyclebot.engine.truth.RootCauseTtl6464.current()
                        if (cur != null && cur.reason.startsWith("MECHANICAL_FAULT"))
                            com.lifecyclebot.engine.truth.RootCauseTtl6464.clear()
                    } catch (_: Throwable) {}
                }
                // V5.0.6464 §P1 — occupancy: mark PENDING_EXIT then CLOSED as this path landed.
                com.lifecyclebot.engine.truth.CanonicalMintOccupancyRegistry6464.markPendingExit(
                    mode = "live", mint = intent.mint, symbol = intent.symbol,
                    source = "SellFinalizationCoordinator",
                )
                if (fin.finalState == TxMetaSellFinalizer.FinalState.CLEARED) {
                    com.lifecyclebot.engine.truth.CanonicalMintOccupancyRegistry6464.markClosed("live", intent.mint)
                }
                com.lifecyclebot.engine.truth.AuthoritySnapshotVersion6464.bump("sell_finalized_${intent.symbol}")
            } catch (t: Throwable) {
                canonicalMutationFailed6486 = true
                try { ForensicLogger.lifecycle("LIVE_SELL_CANONICAL_FANOUT_FAILED_6486", "mint=${intent.mint.take(10)} sig=${sellSig.take(12)} err=${t.message?.take(100)}") } catch (_: Throwable) {}
            }
        }
        return if (canonicalMutationFailed6486) result6486.copy(pendingRetry = true) else result6486
    }
}
