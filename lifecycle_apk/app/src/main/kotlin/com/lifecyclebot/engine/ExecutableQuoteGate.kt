package com.lifecyclebot.engine

import com.lifecyclebot.network.JupiterApi

/**
 * V5.9.495z29 — Executable Quote Gate (operator spec item 4).
 *
 * Operator: "Profit-lock must use actual confirmed cost basis: confirmed
 * SOL spent from buy tx, confirmed token quantity from wallet/chain, live
 * executable sell quote for the actual wallet token balance, fees/slippage
 * included. Profit-lock is only allowed when executable quote proves net
 * SOL return is above entry basis by the configured threshold."
 *
 * Single chokepoint that every profit-lock entry point goes through.
 * Returns Approved iff a fresh Jupiter quote for `currentWalletTokenRaw`
 * units of `tokenMint` → SOL produces net SOL output that exceeds
 * `entrySolSpent * (1 + minNetProfitFraction)` after slippage.
 *
 * Inputs:
 *   • tokenMint                   — the token to be sold
 *   • currentWalletTokenRaw       — confirmed wallet balance in raw units
 *                                   (ensures we quote the ACTUAL holdable
 *                                   amount, not a stale cache)
 *   • entrySolSpent               — confirmed SOL paid for this position
 *                                   (from TokenLifecycleTracker)
 *   • minNetProfitFraction        — e.g. 0.10 for "must show ≥10% net gain"
 *   • slippageBps                 — applied to the quote before profit calc
 *   • jupiter                     — JupiterApi client (caller owns)
 *
 * Returns Verdict.Approved or Verdict.Rejected with a structured reason.
 */
object ExecutableQuoteGate {

    private const val TAG = "ExecQuoteGate"

    sealed class Verdict {
        data class Approved(
            val expectedSolOutNet: Double,
            val grossOutLamports: Long,
            val netOutLamports: Long,
            val priceImpactPct: Double,
        ) : Verdict()

        data class Rejected(
            val code: String,
            val reason: String,
            val expectedSolOutNet: Double = 0.0,
        ) : Verdict()
    }

    /**
     * Run the gate. Returns Approved iff the executable quote proves real
     * net profit above threshold. Caller MUST NOT broadcast the sell
     * transaction unless this returns Approved.
     */
    fun evaluate(
        tokenMint: String,
        currentWalletTokenRaw: Long,
        entrySolSpent: Double,
        minNetProfitFraction: Double,
        slippageBps: Int,
        jupiter: JupiterApi,
    ): Verdict {
        if (currentWalletTokenRaw <= 0L) {
            return Verdict.Rejected("ZERO_BALANCE",
                "wallet token raw=0 — cannot quote profit-lock")
        }
        if (entrySolSpent <= 0.0) {
            return Verdict.Rejected("NO_ENTRY_BASIS",
                "TokenLifecycleTracker has no confirmed entrySolSpent — cannot validate profit")
        }
        // Fetch a fresh quote: tokenMint → SOL for the EXACT wallet amount.
        // JupiterApi.getQuote is synchronous, returns SwapQuote with decoded fields.
        val q = try {
            jupiter.getQuote(
                inputMint   = tokenMint,
                outputMint  = JupiterApi.SOL_MINT,
                amountRaw   = currentWalletTokenRaw,
                slippageBps = slippageBps,
            )
        } catch (e: Exception) {
            return Verdict.Rejected("QUOTE_FAILED",
                "Jupiter quote threw: ${e.message?.take(80)}")
        }

        val outAmount = q.outAmount
        // Apply slippage to derive the worst-case net delivery the user is
        // guaranteed to receive (mirrors Jupiter's otherAmountThreshold).
        val netLamports = (outAmount.toDouble() * (1.0 - slippageBps / 10_000.0)).toLong()
        val netSol = netLamports / 1_000_000_000.0
        val priceImpactPct = q.priceImpactPct

        val requiredNetSol = entrySolSpent * (1.0 + minNetProfitFraction)
        if (netSol < requiredNetSol) {
            return Verdict.Rejected(
                code = "QUOTE_BELOW_THRESHOLD",
                reason = "executable quote net=${"%.6f".format(netSol)} SOL < required=" +
                         "${"%.6f".format(requiredNetSol)} SOL " +
                         "(entry=${"%.6f".format(entrySolSpent)} × (1+${(minNetProfitFraction*100).toInt()}%) " +
                         "| priceImpact=${"%.2f".format(priceImpactPct)}%)",
                expectedSolOutNet = netSol,
            )
        }

        // High price impact = thin liquidity; reject above 50% even if numbers pencil.
        if (priceImpactPct >= 50.0) {
            return Verdict.Rejected("EXTREME_PRICE_IMPACT",
                "priceImpact=${"%.2f".format(priceImpactPct)}% — too thin to safely sell at scale",
                expectedSolOutNet = netSol,
            )
        }

        ErrorLogger.info(TAG,
            "✅ APPROVED ${tokenMint.take(8)}…: net=${"%.6f".format(netSol)} SOL ≥ " +
            "${"%.6f".format(requiredNetSol)} | impact=${"%.2f".format(priceImpactPct)}%")
        return Verdict.Approved(
            expectedSolOutNet = netSol,
            grossOutLamports  = outAmount,
            netOutLamports    = netLamports,
            priceImpactPct    = priceImpactPct,
        )
    }
}
