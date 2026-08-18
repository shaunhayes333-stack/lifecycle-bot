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

        return Result(
            finalState = fin.finalState,
            actualConsumedRaw = actualConsumedRaw,
            remainingRaw = remainingRaw,
            solReceived = sellSolReceived,
            realizedPnl = pnl,
            sellAmountViolation = violation,
            freshWalletSol = fresh,
            finality = fin.finality,
            pendingRetry = false,
        ).also { res ->
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
                val idKey = com.lifecyclebot.engine.truth.TerminalSellIdempotency6464.makeKey(
                    sellExecutionId = null, fillId = sellSig, signature = sellSig,
                )
                val positionId = intent.mint  // best-effort; multi-lot future revision may lift this
                val consume = com.lifecyclebot.engine.truth.TerminalSellIdempotency6464.beginTerminal(
                    key = idKey, positionId = positionId,
                    sitePath = "SellFinalizationCoordinator.finalize(${traderTag})",
                )
                if (consume == com.lifecyclebot.engine.truth.TerminalSellIdempotency6464.Consume.PROCEED) {
                    com.lifecyclebot.engine.truth.CanonicalLotQuantity6464.onSellFilled(
                        positionId = positionId, mint = intent.mint, filledQty = actualConsumedRaw,
                    )
                    val partial = fin.finalState != TxMetaSellFinalizer.FinalState.CLOSED_FULL
                    com.lifecyclebot.engine.truth.EconomicEventSchema6464.recordSell(
                        mode = "paper",   // executor sets its own mode; this path is paper-safe.
                        positionId = positionId, mint = intent.mint, symbol = intent.symbol,
                        idempotencyKey = idKey, partial = partial,
                        soldQty = actualConsumedRaw,
                        preRemainingQty = intent.entryTokenRaw.takeIf { it.signum() > 0 } ?: intent.confirmedWalletRaw,
                        preRemainingCostBasisSol = intent.entrySolSpent,
                        grossProceedsSol = sellSolReceived, exitFeesSol = feesSol,
                    )
                    val realizedPct = if (intent.entrySolSpent > 0.0)
                        (pnl.realizedPnlSol / intent.entrySolSpent) * 100.0 else 0.0
                    com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464.publish(
                        com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464.Envelope(
                            tradeId = idKey, atMs = System.currentTimeMillis(),
                            realizedPnlSol = pnl.realizedPnlSol, realizedReturnPct = realizedPct,
                            mint = intent.mint, lane = traderTag,
                        )
                    )
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
                    mode = "paper", mint = intent.mint, symbol = intent.symbol,
                    source = "SellFinalizationCoordinator",
                )
                if (fin.finalState == TxMetaSellFinalizer.FinalState.CLOSED_FULL) {
                    com.lifecyclebot.engine.truth.CanonicalMintOccupancyRegistry6464.markClosed("paper", intent.mint)
                }
                com.lifecyclebot.engine.truth.AuthoritySnapshotVersion6464.bump("sell_finalized_${intent.symbol}")
            } catch (_: Throwable) {}
        }
    }
}
