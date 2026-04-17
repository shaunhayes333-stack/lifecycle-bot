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
)

data class SwapTxResult(
    val txBase64: String,
    val requestId: String = "",
    val router: String = "unknown",
    val isRfqRoute: Boolean = false,
)

class JupiterApi(private val apiKey: String = "") {

    companion object {
        const val SOL_MINT = "So11111111111111111111111111111111111111112"

        private const val TAG = "JupiterApi"

        private const val BASE_V6 = "https://quote-api.jup.ag/v6"
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

    private val http = OkHttpClient.Builder()
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
    fun buildSwapTx(quote: SwapQuote, userPublicKey: String): SwapTxResult {
        require(userPublicKey.isNotBlank()) { "userPublicKey blank" }

        if (quote.isUltra) {
            return buildUltraTx(quote, userPublicKey)
        }

        return SwapTxResult(
            txBase64 = buildSwapTxV6(quote, userPublicKey),
            requestId = "",
            router = "metis",
            isRfqRoute = false,
        )
    }

    private fun buildUltraTx(quote: SwapQuote, userPublicKey: String): SwapTxResult {
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

    private fun buildSwapTxV6(quote: SwapQuote, userPublicKey: String): String {
        val startMs = System.currentTimeMillis()

        val payload = JSONObject().apply {
            put("quoteResponse", quote.raw)
            put("userPublicKey", userPublicKey)
            put("wrapAndUnwrapSol", true)
            put("dynamicComputeUnitLimit", true)
            put("prioritizationFeeLamports", "auto")
        }

        log("🔧 V6 BUILD TX for ${userPublicKey.take(8)}...")
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

        log("✅ V6 BUILD TX OK: txLen=${swapTx.length} (${elapsed}ms)")
        return swapTx
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