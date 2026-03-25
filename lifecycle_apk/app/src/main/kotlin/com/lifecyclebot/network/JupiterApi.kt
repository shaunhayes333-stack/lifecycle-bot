package com.lifecyclebot.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SwapQuote(
    val raw: JSONObject,
    val outAmount: Long,
    val priceImpactPct: Double,
    // Ultra API fields
    val requestId: String = "",        // Required for Ultra execute
    val swapTransaction: String = "",  // Pre-built tx from Ultra
    val isUltra: Boolean = false,      // Flag to indicate Ultra quote
)

class JupiterApi {

    companion object {
        const val SOL_MINT = "So11111111111111111111111111111111111111112"
        private const val BASE_V6 = "https://quote-api.jup.ag/v6"
        private const val BASE_ULTRA = "https://api.jup.ag/ultra/v1"
        private const val TAG = "JupiterApi"
        
        // Use Ultra API by default (faster, better MEV protection, auto-slippage)
        var useUltraApi = true
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val JSON = "application/json".toMediaType()

    /**
     * Get a Jupiter quote with enhanced logging.
     * Uses Ultra API by default (faster, auto-slippage, built-in MEV protection).
     * Falls back to v6 API if Ultra fails.
     * 
     * @param amountLamports  for SOL-in swaps; for token-in swaps use raw token units
     */
    fun getQuote(
        inputMint: String,
        outputMint: String,
        amountLamports: Long,
        slippageBps: Int,
    ): SwapQuote {
        // Try Ultra API first (faster, better)
        if (useUltraApi) {
            try {
                return getUltraOrder(inputMint, outputMint, amountLamports)
            } catch (e: Exception) {
                log("⚠️ Ultra API failed, falling back to v6: ${e.message?.take(50)}")
            }
        }
        
        // Fallback to v6 API
        return getQuoteV6(inputMint, outputMint, amountLamports, slippageBps)
    }
    
    /**
     * Ultra API: Get order (quote + transaction in one call)
     * Endpoint: POST /ultra/v1/order
     * 
     * Benefits:
     * - Auto-optimized slippage
     * - Built-in MEV protection via Jupiter Beam
     * - Sub-second execution (~300ms)
     * - Lower fees (5-10x less than average)
     */
    private fun getUltraOrder(
        inputMint: String,
        outputMint: String,
        amountLamports: Long,
    ): SwapQuote {
        val startMs = System.currentTimeMillis()
        log("🚀 ULTRA ORDER: ${inputMint.take(8)}→${outputMint.take(8)} amt=$amountLamports")
        
        val payload = JSONObject().apply {
            put("inputMint", inputMint)
            put("outputMint", outputMint)
            put("amount", amountLamports.toString())
            put("taker", "")  // Will be set during buildSwapTx
        }
        
        val body = postOrThrow("$BASE_ULTRA/order", payload.toString())
        val elapsed = System.currentTimeMillis() - startMs
        
        val json = JSONObject(body)
        
        // Check for error
        if (json.has("error")) {
            val error = json.optString("error", "unknown")
            log("❌ Ultra ORDER error: $error (${elapsed}ms)")
            throw RuntimeException("Jupiter Ultra order error: $error")
        }
        
        val outAmount = json.optString("outAmount", "0").toLongOrNull() ?: 0L
        val requestId = json.optString("requestId", "")
        val swapTx = json.optString("transaction", "")
        
        // Ultra uses "outputAmount" sometimes
        val finalOutAmount = if (outAmount > 0) outAmount 
            else json.optString("outputAmount", "0").toLongOrNull() ?: 0L
        
        if (finalOutAmount <= 0) {
            log("❌ Ultra returned 0 output - no route found")
            throw RuntimeException("Jupiter Ultra: no route found")
        }
        
        // Price impact from Ultra (may be in different field)
        val priceImpact = json.optString("priceImpactPct", "0").toDoubleOrNull() 
            ?: json.optDouble("priceImpact", 0.0)
        
        log("✅ ULTRA OK: out=$finalOutAmount reqId=${requestId.take(12)}... (${elapsed}ms)")
        
        return SwapQuote(
            raw = json,
            outAmount = finalOutAmount,
            priceImpactPct = priceImpact,
            requestId = requestId,
            swapTransaction = swapTx,
            isUltra = true,
        )
    }
    
    /**
     * Legacy v6 API quote (fallback)
     */
    private fun getQuoteV6(
        inputMint: String,
        outputMint: String,
        amountLamports: Long,
        slippageBps: Int,
    ): SwapQuote {
        val url = "$BASE_V6/quote?inputMint=$inputMint&outputMint=$outputMint" +
                  "&amount=$amountLamports&slippageBps=$slippageBps"
        
        val startMs = System.currentTimeMillis()
        log("📊 V6 quote: ${outputMint.take(8)}... amt=$amountLamports slip=$slippageBps")
        
        val body = getOrThrow(url)
        val elapsed = System.currentTimeMillis() - startMs
        
        val j = JSONObject(body)
        
        if (j.has("error")) {
            val error = j.optString("error", "unknown")
            log("❌ V6 Quote ERROR: $error (${elapsed}ms)")
            throw RuntimeException("Jupiter v6 quote error: $error")
        }
        
        val outAmount = j.optString("outAmount", "0").toLongOrNull() ?: 0L
        val priceImpact = j.optString("priceImpactPct", "0").toDoubleOrNull() ?: 0.0
        
        if (outAmount <= 0) {
            log("❌ V6 Quote returned 0 output - token may be illiquid")
            throw RuntimeException("Jupiter v6 returned 0 output - no liquidity")
        }
        
        log("✅ V6 Quote OK: out=$outAmount impact=${String.format("%.2f", priceImpact)}% (${elapsed}ms)")
        
        return SwapQuote(
            raw = j,
            outAmount = outAmount,
            priceImpactPct = priceImpact,
            isUltra = false,
        )
    }

    /**
     * Build a versioned transaction for the swap.
     * For Ultra API quotes, the transaction is already included.
     * For v6 quotes, we build it via /swap endpoint.
     * 
     * Returns base64-encoded transaction bytes ready for signing.
     */
    fun buildSwapTx(quote: SwapQuote, userPublicKey: String): String {
        // Ultra API: Transaction already built, just need to update taker
        if (quote.isUltra && quote.swapTransaction.isNotBlank()) {
            log("🚀 Using pre-built Ultra transaction")
            return quote.swapTransaction
        }
        
        // Ultra API: Need to re-fetch with taker address
        if (quote.isUltra) {
            return buildUltraTx(quote, userPublicKey)
        }
        
        // v6 API: Build via /swap endpoint
        return buildSwapTxV6(quote, userPublicKey)
    }
    
    /**
     * Ultra API: Get order with taker address for signing
     */
    private fun buildUltraTx(quote: SwapQuote, userPublicKey: String): String {
        val startMs = System.currentTimeMillis()
        log("🚀 Building Ultra tx for ${userPublicKey.take(8)}...")
        
        // Re-fetch order with taker address
        val inputMint = quote.raw.optString("inputMint", "")
        val outputMint = quote.raw.optString("outputMint", "")
        val amount = quote.raw.optString("amount", quote.raw.optString("inAmount", "0"))
        
        val payload = JSONObject().apply {
            put("inputMint", inputMint)
            put("outputMint", outputMint)
            put("amount", amount)
            put("taker", userPublicKey)
        }
        
        val body = postOrThrow("$BASE_ULTRA/order", payload.toString())
        val elapsed = System.currentTimeMillis() - startMs
        
        val json = JSONObject(body)
        
        if (json.has("error")) {
            val error = json.optString("error", "unknown")
            log("❌ Ultra buildTx error: $error (${elapsed}ms)")
            throw RuntimeException("Jupiter Ultra buildTx error: $error")
        }
        
        val swapTx = json.optString("transaction", "")
        if (swapTx.isBlank()) {
            log("❌ Ultra returned empty transaction!")
            throw RuntimeException("Jupiter Ultra returned empty transaction")
        }
        
        log("✅ Ultra tx built OK (${swapTx.length} chars, ${elapsed}ms)")
        return swapTx
    }
    
    /**
     * v6 API: Build transaction via /swap endpoint
     */
    private fun buildSwapTxV6(quote: SwapQuote, userPublicKey: String): String {
        val startMs = System.currentTimeMillis()
        log("🔧 Building v6 swap tx for ${userPublicKey.take(8)}...")
        
        val payload = JSONObject().apply {
            put("quoteResponse",              quote.raw)
            put("userPublicKey",              userPublicKey)
            put("wrapAndUnwrapSol",           true)
            put("dynamicComputeUnitLimit",    true)
            put("prioritizationFeeLamports",  "auto")
        }
        
        val body = postOrThrow("$BASE_V6/swap", payload.toString())
        val elapsed = System.currentTimeMillis() - startMs
        
        val json = JSONObject(body)
        
        if (json.has("error")) {
            val error = json.optString("error", "unknown")
            log("❌ V6 BuildTx ERROR: $error (${elapsed}ms)")
            throw RuntimeException("Jupiter v6 buildSwapTx error: $error")
        }
        
        val swapTx = json.optString("swapTransaction", "")
        if (swapTx.isBlank()) {
            log("❌ V6 BuildTx returned empty transaction!")
            throw RuntimeException("Jupiter v6 returned empty swapTransaction")
        }
        
        log("✅ V6 Swap tx built OK (${swapTx.length} chars, ${elapsed}ms)")
        return swapTx
    }
    
    /**
     * Ultra API: Execute signed transaction via Jupiter's infrastructure
     * This provides better MEV protection and faster landing.
     * 
     * @param signedTxB64 Base64 encoded signed transaction
     * @param requestId The requestId from the order response
     * @return Transaction signature
     */
    fun executeUltra(signedTxB64: String, requestId: String): String {
        val startMs = System.currentTimeMillis()
        log("🚀 ULTRA EXECUTE: reqId=${requestId.take(12)}...")
        
        val payload = JSONObject().apply {
            put("signedTransaction", signedTxB64)
            put("requestId", requestId)
        }
        
        val body = postOrThrow("$BASE_ULTRA/execute", payload.toString())
        val elapsed = System.currentTimeMillis() - startMs
        
        val json = JSONObject(body)
        
        if (json.has("error")) {
            val error = json.optString("error", "unknown")
            log("❌ Ultra EXECUTE error: $error (${elapsed}ms)")
            throw RuntimeException("Jupiter Ultra execute error: $error")
        }
        
        val signature = json.optString("signature", json.optString("txid", ""))
        val status = json.optString("status", "unknown")
        
        if (signature.isBlank()) {
            log("❌ Ultra execute returned no signature! status=$status")
            throw RuntimeException("Jupiter Ultra: no signature returned, status=$status")
        }
        
        log("✅ ULTRA EXECUTED: sig=${signature.take(20)}... status=$status (${elapsed}ms)")
        return signature
    }

    // ── helpers ────────────────────────────────────────────
    
    private fun log(msg: String) {
        // Uses Android Log if available, otherwise println
        try {
            android.util.Log.d(TAG, msg)
        } catch (_: Exception) {
            println("[$TAG] $msg")
        }
    }

    private fun getOrThrow(url: String): String {
        val req  = Request.Builder().url(url)
            .header("User-Agent", "lifecycle-bot-android/6.0")
            .header("Accept", "application/json")
            .build()
        
        try {
            val resp = http.newCall(req).execute()
            val code = resp.code
            val body = resp.body?.string()
            
            if (!resp.isSuccessful) {
                log("❌ GET failed: HTTP $code | ${body?.take(100) ?: "no body"}")
                throw RuntimeException("Jupiter GET $code: ${body?.take(200) ?: url}")
            }
            
            if (body.isNullOrBlank()) {
                log("❌ GET returned empty body")
                throw RuntimeException("Empty Jupiter response for: ${url.takeLast(60)}")
            }
            
            return body
        } catch (e: java.net.UnknownHostException) {
            log("❌ DNS ERROR: Cannot resolve quote-api.jup.ag - check network/DNS")
            throw RuntimeException("Cannot resolve Jupiter API host - check internet connection")
        } catch (e: java.net.SocketTimeoutException) {
            log("❌ TIMEOUT: Jupiter API did not respond in time")
            throw RuntimeException("Jupiter API timeout - server may be overloaded")
        } catch (e: java.io.IOException) {
            log("❌ NETWORK ERROR: ${e.javaClass.simpleName} - ${e.message}")
            throw RuntimeException("Network error calling Jupiter: ${e.message}")
        }
    }

    /**
     * Simulate a swap transaction via the RPC simulateTransaction method.
     * Returns null if simulation passes (no error), or an error string if it fails.
     *
     * Call this before signSendAndConfirm() to catch:
     *   - Insufficient SOL balance
     *   - Slippage exceeded
     *   - Program errors (e.g. stale oracle)
     *
     * @param swapTxB64  base64 transaction from buildSwapTx()
     * @param rpcUrl     wallet's RPC endpoint
     */
    fun simulateSwap(swapTxB64: String, rpcUrl: String): String? {
        val startMs = System.currentTimeMillis()
        log("🧪 Simulating swap via ${rpcUrl.take(35)}...")
        
        return try {
            val payload = org.json.JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "simulateTransaction")
                .put("params", org.json.JSONArray()
                    .put(swapTxB64)
                    .put(org.json.JSONObject()
                        .put("encoding", "base64")
                        .put("commitment", "confirmed")
                        .put("sigVerify", false)))  // skip sig check — tx isn't signed yet
                .toString()

            val req  = Request.Builder().url(rpcUrl)
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody(JSON)).build()
            val resp = http.newCall(req).execute()
            val body = resp.body?.string()
            val elapsed = System.currentTimeMillis() - startMs
            
            if (body.isNullOrBlank()) {
                log("⚠️ Simulation returned empty response (${elapsed}ms)")
                return "RPC error: empty response"
            }
            
            val json = org.json.JSONObject(body)
            
            // Check for RPC-level error
            if (json.has("error")) {
                val rpcError = json.optJSONObject("error")?.optString("message", "unknown") 
                    ?: json.optString("error", "unknown")
                log("❌ Simulation RPC error: $rpcError (${elapsed}ms)")
                return "RPC error: $rpcError"
            }

            val err = json.optJSONObject("result")?.optJSONObject("value")?.opt("err")
            if (err != null && err.toString() != "null") {
                // Simulation failed — return human-readable error
                val logs = json.optJSONObject("result")
                    ?.optJSONObject("value")
                    ?.optJSONArray("logs")
                val lastLog = (0 until (logs?.length() ?: 0))
                    .mapNotNull { logs?.optString(it) }
                    .lastOrNull { it.contains("Error") || it.contains("failed") || it.contains("insufficient") }
                val errMsg = "Simulate failed: $err${if (lastLog != null) " | $lastLog" else ""}"
                log("❌ $errMsg (${elapsed}ms)")
                return errMsg
            }
            
            log("✅ Simulation PASSED (${elapsed}ms)")
            null  // simulation passed
        } catch (e: Exception) {
            // Log the actual error but don't block - simulation is advisory
            log("⚠️ Simulation exception: ${e.javaClass.simpleName} - ${e.message?.take(60)}")
            null  // simulation errors are non-fatal — proceed with real tx
        }
    }

    private fun postOrThrow(url: String, json: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "lifecycle-bot-android/6.0")
            .header("Accept", "application/json")
            .post(json.toRequestBody(JSON)).build()
        
        try {
            val resp = http.newCall(req).execute()
            val code = resp.code
            val body = resp.body?.string()
            
            if (!resp.isSuccessful) {
                log("❌ POST failed: HTTP $code | ${body?.take(200) ?: "no body"}")
                throw RuntimeException("Jupiter POST $code: ${body?.take(200) ?: ""}")
            }
            
            if (body.isNullOrBlank()) {
                log("❌ POST returned empty body")
                throw RuntimeException("Empty Jupiter swap response")
            }
            
            return body
        } catch (e: java.net.UnknownHostException) {
            log("❌ DNS ERROR: Cannot resolve quote-api.jup.ag")
            throw RuntimeException("Cannot resolve Jupiter API host - check internet connection")
        } catch (e: java.net.SocketTimeoutException) {
            log("❌ TIMEOUT: Jupiter swap API did not respond in time")
            throw RuntimeException("Jupiter swap timeout - try again")
        } catch (e: java.io.IOException) {
            log("❌ NETWORK ERROR on POST: ${e.javaClass.simpleName} - ${e.message}")
            throw RuntimeException("Network error building swap: ${e.message}")
        }
    }
}
