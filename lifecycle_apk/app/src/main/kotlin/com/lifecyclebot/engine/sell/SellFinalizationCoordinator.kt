package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ErrorLogger
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
            preTokenBalanceRaw = preTokenBalanceRaw,
            postTokenBalanceRaw = postTokenBalanceRaw,
            walletPollRaw = walletPollRaw,
            solReceivedLamports = solReceivedLamports,
        )
        val actualConsumedRaw = fin.actualConsumedRaw
        val remainingRaw = fin.remainingRaw

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
        )
    }
}
