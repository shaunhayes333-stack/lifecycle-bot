package com.lifecyclebot.engine

/** Bounded crypto-only quote/build refresh. The send call is outside every retry catch. */
internal object CryptoLiveQuoteBoundary8870 {
    const val WAIT_REASON = "CRYPTO_LIVE_QUOTE_REFRESH_WAIT_8870"

    class Wait(val detail: String, cause: Throwable? = null) : IllegalStateException("$WAIT_REASON:$detail", cause)

    data class Request(val inputMint: String, val outputMint: String, val amountRaw: Long,
                       val maxPriceImpactPct: Double? = null)

    data class Observation(val inputMint: String, val outputMint: String, val amountRaw: Long,
                           val outputRaw: Long, val observedAtMs: Long, val priceImpactPct: Double = Double.NaN)

    private fun validate(request: Request, observation: Observation) {
        require(request.inputMint == observation.inputMint && request.outputMint == observation.outputMint &&
            request.amountRaw > 0L && request.amountRaw == observation.amountRaw) { "CRYPTO_QUOTE_IDENTITY_OR_AMOUNT_MISMATCH_8870" }
        require(observation.outputRaw > 0L) { "CRYPTO_QUOTE_OUTPUT_UNPROVEN_8870" }
        request.maxPriceImpactPct?.let { limit ->
            require(observation.priceImpactPct.isFinite() && observation.priceImpactPct <= limit) { "CRYPTO_QUOTE_IMPACT_REFUSED_8870" }
        }
    }

    /**
     * Both provider quote and actual built order are checked. At most one unsigned refresh/rebuild is allowed;
     * a send exception (including an uncertain signature or HTTP 422) propagates without another quote or send.
     */
    fun <Q : Any, B, R> execute(
        request: Request,
        initialQuote: Q?,
        now: () -> Long = System::currentTimeMillis,
        fetchQuote: () -> Q,
        observe: (Q) -> Observation,
        build: (Q) -> B,
        builtQuote: (B, Q) -> Q,
        send: (B, Q) -> R,
        onRefresh: () -> Unit = {},
    ): R {
        var supplied = initialQuote
        var refreshing = false
        repeat(2) { attempt ->
            val quote = supplied ?: try { fetchQuote() } catch (error: Throwable) {
                if (error is java.util.concurrent.CancellationException) throw error
                if (refreshing) throw Wait("REFRESH_UNAVAILABLE", error)
                throw error
            }
            val observation = observe(quote)
            validate(request, observation)
            if (!BuyRouteSafety8522.quoteFresh8522(observation.observedAtMs, now())) {
                if (attempt > 0) throw Wait("REFRESH_STILL_STALE")
                supplied = null; refreshing = true; onRefresh()
                return@repeat
            }
            val built = try { build(quote) } catch (error: Throwable) {
                if (error is java.util.concurrent.CancellationException) throw error
                if (attempt == 0 && error.message?.contains("422") == true) {
                    supplied = null; refreshing = true; onRefresh()
                    return@repeat
                }
                throw error
            }
            val actualQuote = builtQuote(built, quote)
            val actualObservation = observe(actualQuote)
            validate(request, actualObservation)
            if (!BuyRouteSafety8522.quoteFresh8522(actualObservation.observedAtMs, now())) {
                if (attempt > 0) throw Wait("BUILD_OUTLIVED_REFRESH")
                supplied = null; refreshing = true; onRefresh()
                return@repeat
            }
            // This return/throw cannot re-enter the loop. Once signing may begin, the same intent owns all recovery.
            return send(built, actualQuote)
        }
        throw Wait("UNSIGNED_REFRESH_EXHAUSTED")
    }
}
