package com.lifecyclebot.network

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

data class SwapQuote(
    val raw: JSONObject,
    val outAmount: Long,
    val priceImpactPct: Double,

    // Jupiter v2 / Ultra-style fields
    val requestId: String = "",
    val swapTransaction: String = "",
    val isUltra: Boolean = false,

    // original request params
    val inputMint: String = "",
    val outputMint: String = "",
    val inAmount: Long = 0L,

    // route metadata
    val router: String = "unknown",
    val isRfqRoute: Boolean = false,

    // V5.9.481 — when an Ultra binding-order request was rejected by the
    // RFQ market makers and we fell back to v6, this carries the reason
    // string so callers can surface it in forensics ('Ultra rejected →
    // Metis fallback') instead of leaving the operator wondering why the
    // sell didn't go through Ultra like the buy did.
    val ultraRejectedReason: String = "",
)

data class SwapTxResult(
    val txBase64: String,
    val requestId: String = "",
    val router: String = "unknown",
    val isRfqRoute: Boolean = false,
    // V5.9.482 — surface Jupiter's dynamicSlippageReport so callers can
    // log into LiveTradeLogStore (Forensics tile). picked = the slippageBps
    // Jupiter chose via simulation; incurred = the actual simulated slip.
    val dynSlipPickedBps: Int = -1,
    val dynSlipIncurredBps: Int = -1,
)

class JupiterApi(private val apiKey: String = "") {

    companion object {
        const val SOL_MINT = "So11111111111111111111111111111111111111112"

        private const val TAG = "JupiterApi"

        // V5.9.28 (live-trading fix): migrated from dead quote-api.jup.ag/v6.
        // User logs showed 'Unable to resolve host quote-api.jup.ag' across Cloudflare,
        // Google, Quad9, and system DNS — Jupiter is retiring that host before Jan 31 2026.
        // New free tier endpoint (no API key) is lite-api.jup.ag/swap/v1.
        // Paths /quote and /swap stay the same.
        private const val BASE_V6 = "https://lite-api.jup.ag/swap/v1"
        private const val BASE_URL = "https://api.jup.ag"
        private const val ORDER_ENDPOINT = "$BASE_URL/swap/v2/order"
        private const val EXECUTE_ENDPOINT = "$BASE_URL/swap/v2/execute"

        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val READ_TIMEOUT_MS = 20_000L
        private const val WRITE_TIMEOUT_MS = 15_000L

        @Volatile
        private var dnsStatusLogged = false
    }

    init {
        if (!dnsStatusLogged) {
            val keyStatus = if (apiKey.isNotBlank()) "API key configured" else "NO API KEY"
            log("🌐 JupiterApi initialized | DoH enabled | $keyStatus")
            dnsStatusLogged = true
        }
    }

    private val http = SharedHttpClient.builder()
        .dns(CloudflareDns.INSTANCE)
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val JSON = "application/json".toMediaType()

    /**
     * amountRaw:
     * - SOL input => lamports
     * - token input => raw token units (already decimal-scaled)
     */
    fun getQuote(
        inputMint: String,
        outputMint: String,
        amountRaw: Long,
        slippageBps: Int,
    ): SwapQuote {
        require(inputMint.isNotBlank()) { "inputMint blank" }
        require(outputMint.isNotBlank()) { "outputMint blank" }
        require(amountRaw > 0L) { "amountRaw must be > 0" }

        return try {
            getUltraQuote(
                inputMint = inputMint,
                outputMint = outputMint,
                amountRaw = amountRaw,
            )
        } catch (e: Exception) {
            log("⚠️ v2 quote failed, falling back to v6: ${e.message}")
            getQuoteV6(
                inputMint = inputMint,
                outputMint = outputMint,
                amountRaw = amountRaw,
                slippageBps = slippageBps,
            )
        }
    }

    /**
     * V5.9.468 — TAKER-BOUND BINDING ORDER for execution.
     *
     * Operator-reported RCA: Jupiter Ultra v2 has a 2-phase design.
     * Phase 1: /order WITHOUT taker = non-binding quote (always succeeds
     *          even when RFQ provider would reject the trade).
     * Phase 2: /order WITH taker = binding order. THIS is where iris /
     *          dflow / okx RFQ providers can refuse to take the other
     *          side (e.g. when the token is in RAPID_CATASTROPHE_STOP
     *          freefall).
     *
     * Old flow: getQuote (no taker) → SUCCESS → buildSwapTx (with taker)
     *           → FAIL → silent kill (forensics frozen at SELL_QUOTE_OK).
     * New flow: getQuoteWithTaker (with taker) → SUCCESS or FAIL surfaces
     *           AT QUOTE TIME → user sees SELL_QUOTE_FAIL with the real
     *           reason. If it succeeds, the binding tx + requestId are
     *           already on the SwapQuote → buildSwapTx short-circuits.
     *
     * Falls back to the legacy v6 quote on RFQ rejection so the slippage
     * escalation loop can still try non-RFQ routes (Metis).
     */
    fun getQuoteWithTaker(
        inputMint: String,
        outputMint: String,
        amountRaw: Long,
        slippageBps: Int,
        taker: String,
    ): SwapQuote {
        require(inputMint.isNotBlank()) { "inputMint blank" }
        require(outputMint.isNotBlank()) { "outputMint blank" }
        require(amountRaw > 0L) { "amountRaw must be > 0" }
        require(taker.isNotBlank()) { "taker blank" }

        // V5.9.481 — try Ultra binding order TWICE before falling back to v6.
        // RFQ providers (iris/dflow/okx) are bursty under load; a one-shot
        // failure is often network-side. Double-tap Ultra so we don't dump
        // every sell to the slippage-vulnerable Metis path.
        var lastUltraEx: Exception? = null
        for (attempt in 1..2) {
            try {
                return getUltraOrder(
                    inputMint = inputMint,
                    outputMint = outputMint,
                    amountRaw = amountRaw,
                    taker = taker,
                )
            } catch (e: Exception) {
                lastUltraEx = e
                if (attempt == 1) {
                    log("⚠️ Ultra binding order attempt 1 failed (${e.message?.take(80)}) — retrying once before v6 fallback…")
                    Thread.sleep(250)
                }
            }
        }

        // Both Ultra attempts failed — RFQ providers truly declined. Surface
        // this in callers' forensics by tagging the returned v6 quote so the
        // sell ladder logs 'router=metis (Ultra rejected)' instead of just
        // 'router=metis'. This answers the operator question 'is it not
        // trying to sell via Ultra?': yes, but Ultra's market makers refuse
        // to take dumping-meme inventory, so we land on Metis v6 every
        // time on volatile sells.
        log("⚠️ Ultra REJECTED both attempts (${lastUltraEx?.message?.take(80)}) — falling back to Metis v6 (no RFQ MM willing to take side)")
        val v6Quote = getQuoteV6(
            inputMint = inputMint,
            outputMint = outputMint,
            amountRaw = amountRaw,
            slippageBps = slippageBps,
        )
        return v6Quote.copy(
            ultraRejectedReason = lastUltraEx?.message?.take(120) ?: "RFQ MM declined",
        )
    }

    /**
     * Quote only, no taker. Good for estimation.
     */
    private fun getUltraQuote(
        inputMint: String,
        outputMint: String,
        amountRaw: Long,
    ): SwapQuote {
        val startMs = System.currentTimeMillis()

        val url = buildString {
            append(ORDER_ENDPOINT)
            append("?inputMint=").append(inputMint)
            append("&outputMint=").append(outputMint)
            append("&amount=").append(amountRaw)
        }

        log("🚀 V2 QUOTE: ${shortMint(inputMint)} -> ${shortMint(outputMint)} | amountRaw=$amountRaw")

        val body = getOrThrow(url)
        val elapsed = System.currentTimeMillis() - startMs
        val json = JSONObject(body)

        if (json.has("error")) {
            val error = json.optString("error", "unknown")
            throw RuntimeException("Jupiter v2 quote error: $error")
        }

        val outAmount = parseLongSafely(
            json.opt("outAmount") ?: json.opt("outputAmount")
        )

        if (outAmount <= 0L) {
            throw RuntimeException("Jupiter v2 quote returned outAmount=0")
        }

        val priceImpactPct = parseDoubleSafely(
            json.opt("priceImpactPct") ?: json.opt("priceImpact")
        )

        val router = json.optString("router", "unknown")
        val isRfqRoute = isRfqRouter(router)

        log(
            "✅ V2 QUOTE OK: out=$outAmount impact=${fmt2(priceImpactPct)}% " +
                "router=$router rfq=$isRfqRoute (${elapsed}ms)"
        )

        return SwapQuote(
            raw = json,
            outAmount = outAmount,
            priceImpactPct = priceImpactPct,
            requestId = "",
            swapTransaction = "",
            isUltra = true,
            inputMint = inputMint,
            outputMint = outputMint,
            inAmount = amountRaw,
            router = router,
            isRfqRoute = isRfqRoute,
        )
    }

    /**
     * Build a taker-bound order and return tx + requestId.
     * This is what should be used for actual execution.
     */
    private fun getUltraOrder(
        inputMint: String,
        outputMint: String,
        amountRaw: Long,
        taker: String,
    ): SwapQuote {
        require(taker.isNotBlank()) { "taker blank" }
        require(amountRaw > 0L) { "amountRaw must be > 0" }

        val startMs = System.currentTimeMillis()

        val url = buildString {
            append(ORDER_ENDPOINT)
            append("?inputMint=").append(inputMint)
            append("&outputMint=").append(outputMint)
            append("&amount=").append(amountRaw)
            append("&taker=").append(taker)
        }

        log(
            "🚀 V2 ORDER: ${shortMint(inputMint)} -> ${shortMint(outputMint)} " +
                "| amountRaw=$amountRaw | taker=${taker.take(8)}..."
        )

        val body = getOrThrow(url)
        val elapsed = System.currentTimeMillis() - startMs
        val json = JSONObject(body)

        if (json.has("error")) {
            val error = json.optString("error", "unknown")
            throw RuntimeException("Jupiter v2 order error: $error")
        }

        val outAmount = parseLongSafely(
            json.opt("outAmount") ?: json.opt("outputAmount")
        )
        if (outAmount <= 0L) {
            throw RuntimeException("Jupiter v2 order returned outAmount=0")
        }

        val requestId = json.optString("requestId", "")
        val swapTx = json.optString("transaction", "")
        val priceImpactPct = parseDoubleSafely(
            json.opt("priceImpactPct") ?: json.opt("priceImpact")
        )
        val router = json.optString("router", "unknown")
        val isRfqRoute = isRfqRouter(router)

        if (requestId.isBlank()) {
            throw RuntimeException("Jupiter v2 order returned empty requestId")
        }
        if (swapTx.isBlank()) {
            throw RuntimeException("Jupiter v2 order returned empty transaction")
        }

        log(
            "✅ V2 ORDER OK: out=$outAmount reqId=${requestId.take(12)}... " +
                "txLen=${swapTx.length} router=$router rfq=$isRfqRoute (${elapsed}ms)"
        )

        return SwapQuote(
            raw = json,
            outAmount = outAmount,
            priceImpactPct = priceImpactPct,
            requestId = requestId,
            swapTransaction = swapTx,
            isUltra = true,
            inputMint = inputMint,
            outputMint = outputMint,
            inAmount = amountRaw,
            router = router,
            isRfqRoute = isRfqRoute,
        )
    }

    /**
     * Legacy v6 fallback.
     */
    private fun getQuoteV6(
        inputMint: String,
        outputMint: String,
        amountRaw: Long,
        slippageBps: Int,
    ): SwapQuote {
        val startMs = System.currentTimeMillis()

        val url = buildString {
            append(BASE_V6)
            append("/quote?inputMint=").append(inputMint)
            append("&outputMint=").append(outputMint)
            append("&amount=").append(amountRaw)
            append("&slippageBps=").append(slippageBps)
        }

        log("📊 V6 QUOTE: ${shortMint(inputMint)} -> ${shortMint(outputMint)} | amountRaw=$amountRaw")

        val body = getOrThrow(url)
        val elapsed = System.currentTimeMillis() - startMs
        val json = JSONObject(body)

        if (json.has("error")) {
            val error = json.optString("error", "unknown")
            throw RuntimeException("Jupiter v6 quote error: $error")
        }

        val outAmount = parseLongSafely(json.opt("outAmount"))
        if (outAmount <= 0L) {
            throw RuntimeException("Jupiter v6 quote returned outAmount=0")
        }

        val priceImpactPct = parseDoubleSafely(json.opt("priceImpactPct"))

        log("✅ V6 QUOTE OK: out=$outAmount impact=${fmt2(priceImpactPct)}% (${elapsed}ms)")

        return SwapQuote(
            raw = json,
            outAmount = outAmount,
            priceImpactPct = priceImpactPct,
            isUltra = false,
            inputMint = inputMint,
            outputMint = outputMint,
            inAmount = amountRaw,
            router = "metis",
            isRfqRoute = false,
        )
    }

    /**
     * Always rebuild a fresh taker-bound order for execution.
     * Do NOT trust stale prebuilt transactions for real execution.
     */
    fun buildSwapTx(
        quote: SwapQuote,
        userPublicKey: String,
        dynamicSlippageMaxBps: Int? = null,
    ): SwapTxResult {
        require(userPublicKey.isNotBlank()) { "userPublicKey blank" }

        if (quote.isUltra) {
            // Ultra v2 binding orders already encode their own slippage
            // protection at order-creation time; dynamic slippage is a v6
            // /swap concept and does not apply here.
            return buildUltraTx(quote, userPublicKey)
        }

        val v6 = buildSwapTxV6Detailed(quote, userPublicKey, dynamicSlippageMaxBps)
        return SwapTxResult(
            txBase64 = v6.first,
            requestId = "",
            router = "metis",
            isRfqRoute = false,
            dynSlipPickedBps = v6.second,
            dynSlipIncurredBps = v6.third,
        )
    }

    private fun buildUltraTx(quote: SwapQuote, userPublicKey: String): SwapTxResult {
        // V5.9.468 — SHORT-CIRCUIT: if the quote is already a binding
        // taker-bound order (requestId + swapTransaction populated by
        // getQuoteWithTaker / getUltraOrder), use it directly. Avoids
        // a second /order roundtrip that previously failed silently
        // when the RFQ provider rejected the trade post-quote.
        if (quote.requestId.isNotBlank() && quote.swapTransaction.isNotBlank()) {
            log("✅ V2 BUILD (cached binding order): reqId=${quote.requestId.take(12)}... " +
                "txLen=${quote.swapTransaction.length} router=${quote.router} rfq=${quote.isRfqRoute}")
            return SwapTxResult(
                txBase64 = quote.swapTransaction,
                requestId = quote.requestId,
                router = quote.router,
                isRfqRoute = quote.isRfqRoute,
            )
        }

        val inputMint = quote.inputMint.ifBlank {
            quote.raw.optString("inputMint", "")
        }
        val outputMint = quote.outputMint.ifBlank {
            quote.raw.optString("outputMint", "")
        }
        val amountRaw = when {
            quote.inAmount > 0L -> quote.inAmount
            else -> parseLongSafely(
                quote.raw.opt("amount") ?: quote.raw.opt("inAmount")
            )
        }

        if (inputMint.isBlank() || outputMint.isBlank() || amountRaw <= 0L) {
            throw RuntimeException(
                "Jupiter buildUltraTx missing params: " +
                    "inputMint=$inputMint outputMint=$outputMint amountRaw=$amountRaw"
            )
        }

        val freshOrder = getUltraOrder(
            inputMint = inputMint,
            outputMint = outputMint,
            amountRaw = amountRaw,
            taker = userPublicKey,
        )

        return SwapTxResult(
            txBase64 = freshOrder.swapTransaction,
            requestId = freshOrder.requestId,
            router = freshOrder.router,
            isRfqRoute = freshOrder.isRfqRoute,
        )
    }

    private fun buildSwapTxV6(
        quote: SwapQuote,
        userPublicKey: String,
        dynamicSlippageMaxBps: Int? = null,
    ): String = buildSwapTxV6Detailed(quote, userPublicKey, dynamicSlippageMaxBps).first

    /**
     * V5.9.482 — same as buildSwapTxV6 but returns Triple(txBase64, dynSlipPickedBps, dynSlipIncurredBps).
     * Jupiter's response includes a `dynamicSlippageReport` JSON when dynamicSlippage was requested;
     * we extract `slippageBps` (picked) + `simulatedIncurredSlippageBps` for forensics. Returns -1 / -1
     * when dynamicSlippage was not requested or the report is missing.
     */
    private fun buildSwapTxV6Detailed(
        quote: SwapQuote,
        userPublicKey: String,
        dynamicSlippageMaxBps: Int? = null,
    ): Triple<String, Int, Int> {
        val startMs = System.currentTimeMillis()

        val payload = JSONObject().apply {
            put("quoteResponse", quote.raw)
            put("userPublicKey", userPublicKey)
            put("wrapAndUnwrapSol", true)
            put("dynamicComputeUnitLimit", true)
            put("prioritizationFeeLamports", "auto")

            // V5.9.480 — JUPITER DYNAMIC SLIPPAGE.
            // V5.9.482 — caller now sends WIDE bounds from attempt 1 so
            // Jupiter's simulation has real room to pick a sensible
            // slippageBps.
            // V5.9.487 — CRITICAL: minBps was hard-coded to 50, which let
            // Jupiter's simulation IGNORE the operator's escalation ladder.
            // On every retry (200/400/600/1000/2000bps) Jupiter kept
            // picking ~80bps because [50, maxBps] gave it freedom to lowball.
            // The on-chain instruction then reverted with 0x1788 because the
            // pool moved >0.8% between simulation and landing.
            //
            // FIX: floor minBps at the quote's own slippageBps. Jupiter is
            // now forced to pick somewhere in [escalated_bps, maxBps] —
            // guaranteed at least our intended escalation level. The simulator
            // can still pick HIGHER if the pool is volatile, but it can never
            // pick lower than what we explicitly asked for.
            if (dynamicSlippageMaxBps != null && dynamicSlippageMaxBps > 0) {
                val quoteSlipBps = quote.raw.optInt("slippageBps", 50)
                val effectiveMin = quoteSlipBps.coerceAtLeast(50)
                val effectiveMax = dynamicSlippageMaxBps.coerceAtMost(9999)
                    .coerceAtLeast(effectiveMin)  // never collapse the band
                val ds = JSONObject().apply {
                    put("minBps", effectiveMin)
                    put("maxBps", effectiveMax)
                }
                put("dynamicSlippage", ds)
            }
        }

        log("🔧 V6 BUILD TX for ${userPublicKey.take(8)}... " +
            "${if (dynamicSlippageMaxBps != null) "[dynamicSlippage maxBps=$dynamicSlippageMaxBps minBps=${quote.raw.optInt("slippageBps", 50).coerceAtLeast(50)}]" else ""}")
        val body = postOrThrow("$BASE_V6/swap", payload.toString())
        val elapsed = System.currentTimeMillis() - startMs
        val json = JSONObject(body)

        if (json.has("error")) {
            val error = json.optString("error", "unknown")
            throw RuntimeException("Jupiter v6 buildSwapTx error: $error")
        }

        val swapTx = json.optString("swapTransaction", "")
        if (swapTx.isBlank()) {
            throw RuntimeException("Jupiter v6 returned empty swapTransaction")
        }

        val dsReport = json.optJSONObject("dynamicSlippageReport")
        val picked = dsReport?.optInt("slippageBps", -1) ?: -1
        val incurred = dsReport?.optInt("simulatedIncurredSlippageBps", -1) ?: -1
        val dsLog = if (dsReport != null) " | dyn-slip picked=${picked}bps incurred=${incurred}bps" else ""
        log("✅ V6 BUILD TX OK: txLen=${swapTx.length} (${elapsed}ms)$dsLog")
        return Triple(swapTx, picked, incurred)
    }

    /**
     * Execute signed v2 transaction through Jupiter.
     */
    fun executeUltra(signedTxB64: String, requestId: String): String {
        require(signedTxB64.isNotBlank()) { "signedTxB64 blank" }
        require(requestId.isNotBlank()) { "requestId blank" }

        val startMs = System.currentTimeMillis()
        log("🚀 V2 EXECUTE: reqId=${requestId.take(12)}...")

        val payload = JSONObject().apply {
            put("signedTransaction", signedTxB64)
            put("requestId", requestId)
        }

        val body = postOrThrow(EXECUTE_ENDPOINT, payload.toString())
        val elapsed = System.currentTimeMillis() - startMs
        val json = JSONObject(body)

        if (json.has("error")) {
            val error = json.optString("error", "unknown")
            throw RuntimeException("Jupiter v2 execute error: $error")
        }

        val signature = json.optString("signature", json.optString("txid", ""))
        val status = json.optString("status", "unknown")
        val code = json.optInt("code", 0)

        val inputAmountResult = parseLongSafely(json.opt("inputAmountResult"))
        val outputAmountResult = parseLongSafely(json.opt("outputAmountResult"))

        if (status.equals("Failed", ignoreCase = true) || code < 0) {
            val errorMsg = when (code) {
                -1 -> "Missing cached order / requestId expired"
                -2 -> "Invalid signed transaction"
                -3 -> "Invalid message bytes"
                -4 -> "Missing requestId"
                -5 -> "Missing signed transaction"
                -1000 -> "Failed to land on network"
                -1001 -> "Unknown execute failure"
                -1002 -> "Invalid transaction"
                -1005 -> "Transaction expired"
                -1006 -> "Transaction timed out"
                else -> "Execute failed (status=$status code=$code)"
            }
            throw RuntimeException(
                "Jupiter v2 execute failed: $errorMsg | sig=${signature.take(24)}"
            )
        }

        if (signature.isBlank()) {
            throw RuntimeException("Jupiter v2 execute returned empty signature")
        }

        if (status.equals("Success", ignoreCase = true) && code == 0) {
            log(
                "✅ V2 EXECUTE OK: sig=${signature.take(20)}... " +
                    "in=$inputAmountResult out=$outputAmountResult (${elapsed}ms)"
            )
        } else {
            log(
                "⚠️ V2 EXECUTE uncertain: sig=${signature.take(20)}... " +
                    "status=$status code=$code (${elapsed}ms)"
            )
        }

        return signature
    }

    /**
     * Advisory simulation only.
     */
    fun simulateSwap(swapTxB64: String, rpcUrl: String): String? {
        val startMs = System.currentTimeMillis()
        log("🧪 SIMULATE via ${rpcUrl.take(36)}...")

        return try {
            val payload = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "simulateTransaction")
                .put(
                    "params",
                    JSONArray()
                        .put(swapTxB64)
                        .put(
                            JSONObject()
                                .put("encoding", "base64")
                                .put("commitment", "confirmed")
                                .put("sigVerify", false)
                        )
                )
                .toString()

            val req = Request.Builder()
                .url(rpcUrl)
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody(JSON))
                .build()

            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                val elapsed = System.currentTimeMillis() - startMs

                if (body.isNullOrBlank()) {
                    log("⚠️ SIMULATE empty response (${elapsed}ms)")
                    return "RPC error: empty response"
                }

                val json = JSONObject(body)

                if (json.has("error")) {
                    val rpcError = json.optJSONObject("error")?.optString("message", "unknown")
                        ?: json.optString("error", "unknown")
                    log("❌ SIMULATE RPC ERROR: $rpcError (${elapsed}ms)")
                    return "RPC error: $rpcError"
                }

                val err = json.optJSONObject("result")
                    ?.optJSONObject("value")
                    ?.opt("err")

                if (err != null && err.toString() != "null") {
                    val logs = json.optJSONObject("result")
                        ?.optJSONObject("value")
                        ?.optJSONArray("logs")

                    val lastLog = (0 until (logs?.length() ?: 0))
                        .mapNotNull { logs?.optString(it) }
                        .lastOrNull {
                            it.contains("Error", ignoreCase = true) ||
                                it.contains("failed", ignoreCase = true) ||
                                it.contains("insufficient", ignoreCase = true)
                        }

                    val msg = "Simulate failed: $err${if (lastLog != null) " | $lastLog" else ""}"
                    log("❌ $msg (${elapsed}ms)")
                    return msg
                }

                log("✅ SIMULATE PASSED (${elapsed}ms)")
                null
            }
        } catch (e: Exception) {
            log("⚠️ SIMULATE exception: ${e.javaClass.simpleName} - ${e.message?.take(80)}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // HTTP HELPERS
    // ─────────────────────────────────────────────────────────────────────────────

    private fun getOrThrow(url: String): String {
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "lifecycle-bot-android/6.0")
            .header("Accept", "application/json")

        if (apiKey.isNotBlank() && url.contains("api.jup.ag")) {
            reqBuilder.header("x-api-key", apiKey)
        }

        val req = reqBuilder.build()

        var lastErr: RuntimeException = RuntimeException("Jupiter GET failed")
        for (attempt in 0..2) {
            if (attempt > 0) Thread.sleep(1500L * attempt)
            try {
                http.newCall(req).execute().use { resp ->
                    val code = resp.code
                    val body = resp.body?.string()
                    if (code == 429) {
                        lastErr = RuntimeException("Jupiter GET $code: ${body?.take(300) ?: "no body"}")
                        return@use
                    }
                    if (!resp.isSuccessful) {
                        if (code == 401) throw RuntimeException("Jupiter API 401: API key required")
                        throw RuntimeException("Jupiter GET $code: ${body?.take(300) ?: "no body"}")
                    }
                    if (body.isNullOrBlank()) throw RuntimeException("Empty Jupiter GET response")
                    return body
                }
            } catch (e: UnknownHostException) {
                lastErr = RuntimeException("Cannot resolve Jupiter host")
            } catch (e: SocketTimeoutException) {
                throw RuntimeException("Jupiter GET timeout")
            } catch (e: java.io.IOException) {
                throw RuntimeException("Jupiter GET network error: ${e.message}")
            }
        }
        throw lastErr
    }

    private fun postOrThrow(url: String, json: String): String {
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "lifecycle-bot-android/6.0")
            .header("Accept", "application/json")
            .post(json.toRequestBody(JSON))

        if (apiKey.isNotBlank() && url.contains("api.jup.ag")) {
            reqBuilder.header("x-api-key", apiKey)
        }

        val req = reqBuilder.build()

        var lastErr: RuntimeException = RuntimeException("Jupiter POST failed")
        for (attempt in 0..2) {
            if (attempt > 0) Thread.sleep(1500L * attempt)
            try {
                http.newCall(req).execute().use { resp ->
                    val code = resp.code
                    val body = resp.body?.string()
                    if (code == 429) {
                        lastErr = RuntimeException("Jupiter POST $code: ${body?.take(300) ?: "no body"}")
                        return@use
                    }
                    if (!resp.isSuccessful) {
                        if (code == 401) throw RuntimeException("Jupiter API 401: API key required")
                        throw RuntimeException("Jupiter POST $code: ${body?.take(300) ?: "no body"}")
                    }
                    if (body.isNullOrBlank()) throw RuntimeException("Empty Jupiter POST response")
                    return body
                }
            } catch (e: UnknownHostException) {
                lastErr = RuntimeException("Cannot resolve Jupiter host")
            } catch (e: SocketTimeoutException) {
                throw RuntimeException("Jupiter POST timeout")
            } catch (e: java.io.IOException) {
                throw RuntimeException("Jupiter POST network error: ${e.message}")
            }
        }
        throw lastErr
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // PARSING HELPERS
    // ─────────────────────────────────────────────────────────────────────────────

    private fun parseLongSafely(value: Any?): Long {
        return when (value) {
            null -> 0L
            is Long -> value
            is Int -> value.toLong()
            is Double -> value.toLong()
            is Float -> value.toLong()
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
                ?: value.toDoubleOrNull()?.toLong()
                ?: 0L
            else -> 0L
        }
    }

    private fun parseDoubleSafely(value: Any?): Double {
        return when (value) {
            null -> 0.0
            is Double -> sanitizeDouble(value)
            is Float -> sanitizeDouble(value.toDouble())
            is Int -> value.toDouble()
            is Long -> value.toDouble()
            is Number -> sanitizeDouble(value.toDouble())
            is String -> sanitizeDouble(value.toDoubleOrNull() ?: 0.0)
            else -> 0.0
        }
    }

    private fun sanitizeDouble(value: Double): Double {
        return if (value.isNaN() || value.isInfinite()) 0.0 else value
    }

    private fun isRfqRouter(router: String): Boolean {
        return router.lowercase() in setOf("iris", "dflow", "okx", "hashflow", "rfq")
    }

    private fun shortMint(mint: String): String {
        return if (mint.length <= 8) mint else mint.take(8)
    }

    private fun fmt2(value: Double): String = String.format("%.2f", value)

    private fun log(msg: String) {
        try {
            Log.d(TAG, msg)
        } catch (_: Exception) {
            println("[$TAG] $msg")
        }
    }
}