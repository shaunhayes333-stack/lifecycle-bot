package com.lifecyclebot.engine

import com.lifecyclebot.network.JupiterApi
import com.lifecyclebot.network.SwapQuote

/**
 * SlippageGuard
 *
 * Fetches two quotes 2 seconds apart before executing any live trade.
 * If the quotes diverge by more than 1%, the market is moving too fast
 * and the trade is aborted — protecting against getting filled at a bad price.
 *
 * Also tracks cumulative fee costs so the journal can show net P&L.
 *
 * Fee breakdown per swap:
 *   Network fee:      ~0.000005 SOL (Solana tx fee, fixed)
 *   Jupiter fee:       0.3% of input amount (protocol fee)
 *   Price impact:     variable (taken from quote)
 *   Total per trade:  ~0.3% + price impact
 */
class SlippageGuard(private val jupiter: JupiterApi) {

    companion object {
        const val MAX_QUOTE_DIVERGENCE_PCT = 1.0   // abort if quotes differ by > 1%
        const val QUOTE_DELAY_MS           = 2_000L // wait between quotes
        const val SOLANA_TX_FEE_SOL        = 0.000005
        const val JUPITER_FEE_PCT          = 0.003  // 0.3%
    }

    data class ValidatedQuote(
        val quote: SwapQuote,
        val isValid: Boolean,
        val divergencePct: Double,   // how much did price move between quotes
        val estimatedFeeSol: Double, // total fee estimate for this trade
        val rejectReason: String,
    )

    /**
     * Get two quotes and validate they're within tolerance.
     * Returns a ValidatedQuote — check .isValid before proceeding.
     */
    fun validateQuote(
        inputMint: String,
        outputMint: String,
        amountLamports: Long,
        slippageBps: Int,
        inputSol: Double,
    ): ValidatedQuote {
        // First quote - with retry for network errors
        val q1 = getQuoteWithRetry(inputMint, outputMint, amountLamports, slippageBps, "Quote 1")
            ?: return ValidatedQuote(
                com.lifecyclebot.network.SwapQuote(raw = org.json.JSONObject(), outAmount = 0L, priceImpactPct = 0.0),
                false, 0.0, 0.0,
                "Quote 1 failed after retries"
            )

        // Wait — let market settle
        Thread.sleep(QUOTE_DELAY_MS)

        // Second quote - with retry for network errors
        val q2 = getQuoteWithRetry(inputMint, outputMint, amountLamports, slippageBps, "Quote 2")
            ?: return buildResult(q1, q1, inputSol)  // Use first quote if second fails

        return buildResult(q1, q2, inputSol)
    }
    
    /**
     * Get a quote with retry logic for transient network errors.
     * Retries up to 3 times with exponential backoff.
     */
    private fun getQuoteWithRetry(
        inputMint: String,
        outputMint: String,
        amountLamports: Long,
        slippageBps: Int,
        label: String,
    ): SwapQuote? {
        var lastError: Exception? = null
        val maxRetries = 3
        val baseDelayMs = 1000L
        
        for (attempt in 1..maxRetries) {
            try {
                return jupiter.getQuote(inputMint, outputMint, amountLamports, slippageBps)
            } catch (e: Exception) {
                lastError = e
                val isNetworkError = e.message?.contains("resolve host") == true ||
                                     e.message?.contains("Unable to resolve") == true ||
                                     e.message?.contains("timeout") == true ||
                                     e.message?.contains("connect") == true
                
                if (isNetworkError && attempt < maxRetries) {
                    val delay = baseDelayMs * attempt  // 1s, 2s, 3s
                    ErrorLogger.info("SlippageGuard", 
                        "⚠️ $label attempt $attempt failed (${e.message?.take(40)}), retrying in ${delay}ms...")
                    Thread.sleep(delay)
                } else {
                    // Non-network error or final retry - give up
                    ErrorLogger.error("SlippageGuard", "$label failed: ${e.message?.take(60)}")
                    break
                }
            }
        }
        return null
    }

    private fun buildResult(q1: SwapQuote, q2: SwapQuote, inputSol: Double): ValidatedQuote {
        val out1 = q1.outAmount.toDouble()
        val out2 = q2.outAmount.toDouble()

        val divergence = if (out1 > 0)
            Math.abs((out2 - out1) / out1 * 100.0) else 0.0

        val feeSol = (inputSol * JUPITER_FEE_PCT) + SOLANA_TX_FEE_SOL

        val isValid = divergence <= MAX_QUOTE_DIVERGENCE_PCT
        val rejectReason = if (!isValid)
            "Price moved ${divergence.fmt(2)}% between quotes (max $MAX_QUOTE_DIVERGENCE_PCT%) — market too volatile"
        else ""

        // Use the worse (lower output) quote for safety
        val useQuote = if (out2 < out1) q2 else q1

        return ValidatedQuote(
            quote          = useQuote,
            isValid        = isValid,
            divergencePct  = divergence,
            estimatedFeeSol = feeSol,
            rejectReason   = rejectReason,
        )
    }

    /**
     * Calculate total fees for a completed trade.
     * Call after a sell to get accurate net P&L.
     */
    fun calcNetPnl(grossPnlSol: Double, inputSol: Double): Pair<Double, Double> {
        val totalFees = (inputSol * JUPITER_FEE_PCT * 2) + (SOLANA_TX_FEE_SOL * 2) // buy + sell
        val netPnl    = grossPnlSol - totalFees
        return netPnl to totalFees
    }
}

private fun Double.fmt(d: Int) = "%.${d}f".format(this)
