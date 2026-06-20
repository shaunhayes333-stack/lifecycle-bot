package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ErrorLogger
import java.math.BigInteger

/**
 * V5.9.495z39 — Tx-meta driven sell finalizer.
 *
 * Operator spec item 6: state machine after sell must be determined by
 * chain token balance and tx meta, not RPC empty map alone.
 *
 *   SELL_CONFIRMED
 *      ↓
 *   parse preTokenBalances/postTokenBalances for owner+mint
 *      ↓
 *   actualConsumedRaw = preTokenBalance - postTokenBalance
 *      ↓
 *   poll getTokenAccountsByOwner across retry window
 *      ↓
 *   if remainingRaw <= dust  → CLEARED
 *   else                    → PARTIAL_SELL / RESIDUAL_HELD
 *
 * Do NOT use RPC-empty-map as proof of zero tokens.
 * Do NOT use cached caller tokenUnits as proof of remaining balance.
 */
object TxMetaSellFinalizer {

    enum class FinalState { CLEARED, PARTIAL_SELL, RESIDUAL_HELD, INDETERMINATE }

    sealed class SellFinalityResult {
        data class Finalized(
            val signature: String,
            val soldRaw: BigInteger,
            val remainingRaw: BigInteger,
            val proceedsLamports: Long,
            val txSlot: Long,
        ) : SellFinalityResult()

        data class PartialFinalized(
            val signature: String,
            val soldRaw: BigInteger,
            val remainingRaw: BigInteger,
            val proceedsLamports: Long,
            val txSlot: Long,
        ) : SellFinalityResult()

        data class PendingRetry(
            val reason: String,
            val signature: String?,
            val mint: String,
            val previousRawQty: BigInteger,
        ) : SellFinalityResult()

        data class FailedWithProof(
            val reason: String,
            val signature: String?,
            val mint: String,
        ) : SellFinalityResult()
    }

    data class FinalizeResult(
        val finalState: FinalState,
        val actualConsumedRaw: BigInteger,
        val remainingRaw: BigInteger,
        val solReceived: Double,
        val finality: SellFinalityResult,
    )

    /** Dust threshold: 1000 raw atomic units. */
    private const val DUST_RAW: Long = 1000L

    /**
     * Legacy/test overload kept source-compatible, but now returns PendingRetry
     * for incomplete proof instead of optimistic INDETERMINATE success.
     */
    fun finalize(
        preTokenBalanceRaw: BigInteger?,
        postTokenBalanceRaw: BigInteger?,
        walletPollRaw: BigInteger?,
        solReceivedLamports: Long,
    ): FinalizeResult = finalize(
        mint = "UNKNOWN",
        signature = "LEGACY_TEST_SIGNATURE",
        previousRawQty = preTokenBalanceRaw ?: BigInteger.ZERO,
        preTokenBalanceRaw = preTokenBalanceRaw,
        postTokenBalanceRaw = postTokenBalanceRaw,
        walletPollRaw = walletPollRaw,
        solReceivedLamports = solReceivedLamports,
        txSlot = 0L,
        routedQuoteSettlementProof = solReceivedLamports > 0L,
    )

    /**
     * Determine final state from atomic live sell proof. A live sell is final ONLY
     * with signature + tx meta + token delta + post-balance proof + proceeds/route
     * settlement proof. Missing proof returns PendingRetry; it must not close
     * lifecycle, journal normal SELL, train learning, or release the close lease.
     */
    fun finalize(
        mint: String,
        signature: String?,
        previousRawQty: BigInteger,
        preTokenBalanceRaw: BigInteger?,
        postTokenBalanceRaw: BigInteger?,
        walletPollRaw: BigInteger?,
        solReceivedLamports: Long,
        txSlot: Long,
        routedQuoteSettlementProof: Boolean = false,
    ): FinalizeResult {
        val sig = signature?.trim().orEmpty()
        fun pending(reason: String): FinalizeResult {
            val pr = SellFinalityResult.PendingRetry(reason, signature?.takeIf { it.isNotBlank() }, mint, previousRawQty)
            try { ErrorLogger.warn("TxMetaSellFinalizer", "⏳ SELL_FINALITY_PENDING_RETRY mint=${mint.take(10)} reason=$reason sig=${sig.take(12)} prev=$previousRawQty pre=$preTokenBalanceRaw post=$postTokenBalanceRaw wallet=$walletPollRaw proceeds=$solReceivedLamports slot=$txSlot") } catch (_: Throwable) {}
            return FinalizeResult(FinalState.INDETERMINATE, BigInteger.ZERO, walletPollRaw ?: postTokenBalanceRaw ?: previousRawQty, solReceivedLamports / 1_000_000_000.0, pr)
        }

        if (sig.isBlank()) return pending("MISSING_SIGNATURE")
        if (txSlot < 0L) return pending("MISSING_TX_META")
        if (preTokenBalanceRaw == null) return pending("MISSING_PRE_TOKEN_BALANCE")
        if (postTokenBalanceRaw == null) return pending("MISSING_POST_TOKEN_BALANCE")
        if (walletPollRaw == null) return pending("MISSING_POST_BALANCE_PROOF")

        val consumed = (preTokenBalanceRaw - postTokenBalanceRaw).max(BigInteger.ZERO)
        if (consumed.signum() <= 0) return pending("MISSING_TOKEN_BALANCE_DELTA")
        if (previousRawQty.signum() > 0 && consumed > previousRawQty) {
            return FinalizeResult(
                finalState = FinalState.INDETERMINATE,
                actualConsumedRaw = consumed,
                remainingRaw = walletPollRaw,
                solReceived = solReceivedLamports / 1_000_000_000.0,
                finality = SellFinalityResult.FailedWithProof("TOKEN_DELTA_EXCEEDS_PREVIOUS_QTY", sig, mint),
            )
        }
        if (solReceivedLamports <= 0L && !routedQuoteSettlementProof) return pending("MISSING_PROCEEDS_OR_ROUTE_SETTLEMENT")

        val remaining = walletPollRaw
        val state = when {
            remaining <= BigInteger.valueOf(DUST_RAW) -> FinalState.CLEARED
            consumed.signum() > 0 -> FinalState.PARTIAL_SELL
            else -> FinalState.RESIDUAL_HELD
        }
        val finality = when (state) {
            FinalState.CLEARED -> SellFinalityResult.Finalized(sig, consumed, remaining, solReceivedLamports, txSlot)
            FinalState.PARTIAL_SELL, FinalState.RESIDUAL_HELD -> SellFinalityResult.PartialFinalized(sig, consumed, remaining, solReceivedLamports, txSlot)
            FinalState.INDETERMINATE -> SellFinalityResult.PendingRetry("INDETERMINATE", sig, mint, previousRawQty)
        }
        ErrorLogger.info("TxMetaSellFinalizer",
            "📑 SELL_FINAL state=$state consumed=$consumed remaining=$remaining solReceived=${"%.6f".format(solReceivedLamports / 1_000_000_000.0)} sig=${sig.take(12)}")
        return FinalizeResult(
            finalState = state,
            actualConsumedRaw = consumed,
            remainingRaw = remaining,
            solReceived = solReceivedLamports / 1_000_000_000.0,
            finality = finality,
        )
    }
}
