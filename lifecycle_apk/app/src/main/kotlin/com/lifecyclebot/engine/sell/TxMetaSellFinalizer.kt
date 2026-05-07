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

    data class FinalizeResult(
        val finalState: FinalState,
        val actualConsumedRaw: BigInteger,
        val remainingRaw: BigInteger,
        val solReceived: Double,
    )

    /** Dust threshold: 1000 raw atomic units. */
    private const val DUST_RAW: Long = 1000L

    /**
     * Determine final state from on-chain tx meta values.
     *
     * @param preTokenBalanceRaw   token balance before the sell tx (from tx meta)
     * @param postTokenBalanceRaw  token balance after the sell tx (from tx meta)
     * @param walletPollRaw        latest wallet poll for the same mint (after retries)
     * @param solReceivedLamports  delta in wallet SOL produced by the sell tx
     */
    fun finalize(
        preTokenBalanceRaw: BigInteger?,
        postTokenBalanceRaw: BigInteger?,
        walletPollRaw: BigInteger?,
        solReceivedLamports: Long,
    ): FinalizeResult {
        val solReceived = solReceivedLamports / 1_000_000_000.0

        // Prefer tx meta when both pre and post are present.
        val consumed = if (preTokenBalanceRaw != null && postTokenBalanceRaw != null)
            (preTokenBalanceRaw - postTokenBalanceRaw).max(BigInteger.ZERO)
        else BigInteger.ZERO

        val remaining = walletPollRaw ?: postTokenBalanceRaw ?: BigInteger.ZERO

        val state = when {
            walletPollRaw == null && postTokenBalanceRaw == null ->
                FinalState.INDETERMINATE
            remaining <= BigInteger.valueOf(DUST_RAW) ->
                FinalState.CLEARED
            consumed.signum() > 0 ->
                FinalState.PARTIAL_SELL
            else ->
                FinalState.RESIDUAL_HELD
        }
        ErrorLogger.info("TxMetaSellFinalizer",
            "📑 SELL_FINAL state=$state consumed=$consumed remaining=$remaining solReceived=${"%.6f".format(solReceived)}")
        return FinalizeResult(
            finalState = state,
            actualConsumedRaw = consumed,
            remainingRaw = remaining,
            solReceived = solReceived,
        )
    }
}
