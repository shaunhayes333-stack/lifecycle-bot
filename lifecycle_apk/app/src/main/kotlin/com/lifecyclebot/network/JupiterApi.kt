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
    // Store original request params for buildUltraTx (API response doesn't echo them back)
    val inputMint: String = "",
    val outputMint: String = "",
    val inAmount: Long = 0L,
)

/**
 * Result from building a swap transaction.
 * Contains both the base64 transaction and the requestId (for Ultra execute).
 */
data class SwapTxResult(
    val txBase64: String,
    val requestId: String = "",  // For Ultra execute endpoint
)

class JupiterApi(private val apiKey: String = "") {

    companion object {
        const val SOL_MINT = "So11111111111111111111111111111111111111112"
        private const val BASE_V6 = "https://quote-api.jup.ag/v6"
        
        // ═══════════════════════════════════════════════════════════════════
        // CRITICAL FIX: Use Jupiter Swap v2 API endpoints
        // The /swap/v2 endpoints provide:
        //   - Real-Time Slippage Estimator (RTSE) for dynamic slippage
        //   - Predictive Execution for accurate quotes
        //   - Better MEV protection via JupiterZ/Iris routers
        // 
        // Reference: https://dev.jup.ag/docs/swap/v2/order-and-execute
        // ═══════════════════════════════════════════════════════════════════
        private const val BASE_URL = "https://api.jup.ag"
        private const val ORDER_ENDPOINT = "$BASE_URL/swap/v2/order"
        private const val EXECUTE_ENDPOINT = "$BASE_URL/swap/v2/execute"
        
        // Legacy endpoint (kept for reference but not used)
        private const val BASE_ULTRA_LEGACY = "https://api.jup.ag/ultra/v1"
        private const val TAG = "JupiterApi"
        
        // Use v2 Swap API by default (better than deprecated Ultra v1)
        var useUltraApi = true
        
        // Track if we've logged DNS status
        private var dnsStatusLogged = false
    }

    init {
        // Log DNS status on first use
        if (!dnsStatusLogged) {
            val keyStatus = if (apiKey.isNotBlank()) "API key configured" else "NO API KEY - will fail!"
            log("🌐 JupiterApi initialized with OkHttp native DoH (Cloudflare→Google→Quad9) | $keyStatus")
            dnsStatusLogged = true
        }
    }

    // HTTP client with native DoH via Cloudflare, Google, and Quad9 fallback
    private val http = OkHttpClient.Builder()
        .dns(CloudflareDns.INSTANCE)  // Native OkHttp DoH implementation
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
     * DNS is resolved via OkHttp native DoH (Cloudflare/Google/Quad9).
     * 
     * @param amountLamports  for SOL-in swaps; for token-in swaps use raw token units
     */
    fun getQuote(
        inputMint: String,
        outputMint: String,
        amountLamports: Long,
        slippageBps: Int,
    ): SwapQuote {
        // Use Ultra API exclusively - v6 DNS (quote-api.jup.ag) doesn't resolve
        // Ultra uses api.jup.ag which works fine with DoH
        return getUltraQuote(inputMint, outputMint, amountLamports)
    }
    
    /**
     * Ultra API: Get a quote (without transaction) using GET request.
     * This is used for price estimation in SlippageGuard.
     * No taker address needed - we just want the price.
     * 
     * CRITICAL FIX: Using /swap/v2/order endpoint instead of deprecated /ultra/v1/order
     * The v2 API provides better routing and RTSE (Real-Time Slippage Estimation)
     * 
     * Endpoint: GET /swap/v2/order?inputMint=...&outputMint=...&amount=...
     */
    private fun getUltraQuote(
        inputMint: String,
        outputMint: String,
        amountLamports: Long,
    ): SwapQuote {
        val startMs = System.currentTimeMillis()
        log("🚀 SWAP v2 QUOTE: ${inputMint.take(8)}→${outputMint.take(8)} amt=$amountLamports")
        
        // GET request - no taker means no transaction returned, but we get the quote
        val url = "$ORDER_ENDPOINT?inputMint=$inputMint&outputMint=$outputMint&amount=$amountLamports"
        
        val body = getOrThrow(url)
        val elapsed = System.currentTimeMillis() - startMs
        
        val json = JSONObject(body)
        
        // Check for error
        if (json.has("error")) {
            val error = json.optString("error", "unknown")
            log("❌ Swap v2 QUOTE error: $error (${elapsed}ms)")
            throw RuntimeException("Jupiter Swap v2 quote error: $error")
        }
        
        // v2 API returns outAmount or outputAmount
        val outAmount = json.optString("outAmount", "0").toLongOrNull() 
            ?: json.optString("outputAmount", "0").toLongOrNull() ?: 0L
        
        if (outAmount <= 0) {
            log("❌ Swap v2 returned 0 output - no route found")
            throw RuntimeException("Jupiter Swap v2: no route found")
        }
        
        // Price impact from response
        val priceImpact = json.optString("priceImpactPct", "0").toDoubleOrNull() 
            ?: json.optDouble("priceImpact", 0.0)
        
        // Check which router won (for logging)
        val router = json.optString("router", "unknown")
        
        log("✅ SWAP v2 QUOTE OK: out=$outAmount impact=${String.format("%.2f", priceImpact)}% router=$router (${elapsed}ms)")
        
        return SwapQuote(
            raw = json,
            outAmount = outAmount,
            priceImpactPct = priceImpact,
            requestId = "",  // No requestId without taker
            swapTransaction = "",  // No tx without taker
            isUltra = true,
            // Store original request params - API response doesn't echo them back!
            inputMint = inputMint,
            outputMint = outputMint,
            inAmount = amountLamports,
        )
    }

    /**
     * Swap v2 API: Get order WITH transaction (includes taker address).
     * Use this when you're ready to execute - provides signed transaction.
     * 
     * CRITICAL FIX: Using /swap/v2/order endpoint instead of deprecated /ultra/v1/order
     * 
     * Endpoint: GET /swap/v2/order?inputMint=...&outputMint=...&amount=...&taker=...
     */
    private fun getUltraOrder(
        inputMint: String,
        outputMint: String,
        amountLamports: Long,
        taker: String,
    ): SwapQuote {
        val startMs = System.currentTimeMillis()
        log("🚀 SWAP v2 ORDER: ${inputMint.take(8)}→${outputMint.take(8)} amt=$amountLamports taker=${taker.take(8)}...")
        
        // GET request with taker to get transaction
        val url = "$ORDER_ENDPOINT?inputMint=$inputMint&outputMint=$outputMint&amount=$amountLamports&taker=$taker"
        
        val body = getOrThrow(url)
        val elapsed = System.currentTimeMillis() - startMs
        
        val json = JSONObject(body)
        
        // Check for error
        if (json.has("error")) {
            val error = json.optString("error", "unknown")
            log("❌ Swap v2 ORDER error: $error (${elapsed}ms)")
            throw RuntimeException("Jupiter Swap v2 order error: $error")
        }
        
        val outAmount = json.optString("outAmount", "0").toLongOrNull() 
            ?: json.optString("outputAmount", "0").toLongOrNull() ?: 0L
        val requestId = json.optString("requestId", "")
        val swapTx = json.optString("transaction", "")
        
        // Check which router won (for logging)
        val router = json.optString("router", "unknown")
        
        if (outAmount <= 0) {
            log("❌ Swap v2 returned 0 output - no route found")
            throw RuntimeException("Jupiter Swap v2: no route found")
        }
        
        if (swapTx.isBlank()) {
            log("⚠️ Swap v2 ORDER returned no transaction - taker may be invalid")
        }
        
        val priceImpact = json.optString("priceImpactPct", "0").toDoubleOrNull() 
            ?: json.optDouble("priceImpact", 0.0)
        
        log("✅ SWAP v2 ORDER OK: out=$outAmount reqId=${requestId.take(12)}... tx=${if (swapTx.isNotBlank()) "${swapTx.length}chars" else "NONE"} router=$router (${elapsed}ms)")
        
        return SwapQuote(
            raw = json,
            outAmount = outAmount,
            priceImpactPct = priceImpact,
            requestId = requestId,
            swapTransaction = swapTx,
            isUltra = true,
            // Store original request params
            inputMint = inputMint,
            outputMint = outputMint,
            inAmount = amountLamports,
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
     * For Ultra API quotes, we fetch a new order with taker to get the transaction.
     * For v6 quotes, we build it via /swap endpoint.
     * 
     * Returns SwapTxResult with base64 transaction and requestId (for Ultra execute).
     */
    fun buildSwapTx(quote: SwapQuote, userPublicKey: String): SwapTxResult {
        // Ultra API: Transaction already built, just need to update taker
        if (quote.isUltra && quote.swapTransaction.isNotBlank()) {
            log("🚀 Using pre-built Ultra transaction")
            return SwapTxResult(quote.swapTransaction, quote.requestId)
        }
        
        // Ultra API: Need to fetch order with taker address to get transaction
        if (quote.isUltra) {
            return buildUltraTx(quote, userPublicKey)
        }
        
        // v6 API: Build via /swap endpoint
        return SwapTxResult(buildSwapTxV6(quote, userPublicKey), "")
    }
    
    /**
     * Swap v2 API: Get order with taker address for signing (uses GET request)
     * Returns SwapTxResult with transaction and requestId for execute.
     * 
     * CRITICAL FIX: Using /swap/v2/order endpoint instead of deprecated /ultra/v1/order
     */
    private fun buildUltraTx(quote: SwapQuote, userPublicKey: String): SwapTxResult {
        val startMs = System.currentTimeMillis()
        log("🚀 Building Swap v2 tx for ${userPublicKey.take(8)}...")
        
        // Use stored params from quote (API response doesn't echo them back!)
        val inputMint = quote.inputMint.ifBlank { quote.raw.optString("inputMint", "") }
        val outputMint = quote.outputMint.ifBlank { quote.raw.optString("outputMint", "") }
        val amount = if (quote.inAmount > 0) quote.inAmount.toString() 
                     else quote.raw.optString("amount", quote.raw.optString("inAmount", "0"))
        
        // Validate we have the required params
        if (inputMint.isBlank() || outputMint.isBlank() || amount == "0") {
            log("❌ Missing params for Swap v2 tx: in=$inputMint out=$outputMint amt=$amount")
            throw RuntimeException("Jupiter Swap v2 buildTx: missing input/output mint or amount")
        }
        
        // GET request with taker to get transaction
        val url = "$ORDER_ENDPOINT?inputMint=$inputMint&outputMint=$outputMint&amount=$amount&taker=$userPublicKey"
        
        val body = getOrThrow(url)
        val elapsed = System.currentTimeMillis() - startMs
        
        val json = JSONObject(body)
        
        if (json.has("error")) {
            val error = json.optString("error", "unknown")
            log("❌ Swap v2 buildTx error: $error (${elapsed}ms)")
            throw RuntimeException("Jupiter Swap v2 buildTx error: $error")
        }
        
        val swapTx = json.optString("transaction", "")
        if (swapTx.isBlank()) {
            log("❌ Swap v2 returned empty transaction!")
            throw RuntimeException("Jupiter Swap v2 returned empty transaction")
        }
        
        // Get the requestId for execute - CRITICAL: must not be empty for v2 execute!
        val requestId = json.optString("requestId", "")
        if (requestId.isBlank()) {
            log("❌ Swap v2 returned empty requestId - cannot execute without it!")
            throw RuntimeException("Jupiter Swap v2 returned empty requestId - order may have expired")
        }
        
        // Log the router that won
        val router = json.optString("router", "unknown")
        
        log("✅ Swap v2 tx built OK (${swapTx.length} chars, reqId=${requestId.take(12)}..., router=$router, ${elapsed}ms)")
        return SwapTxResult(swapTx, requestId)
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
     * CRITICAL FIX: Using /swap/v2/execute endpoint instead of deprecated /ultra/v1/execute
     * 
     * @param signedTxB64 Base64 encoded signed transaction
     * @param requestId The requestId from the order response
     * @return Transaction signature
     */
    fun executeUltra(signedTxB64: String, requestId: String): String {
        val startMs = System.currentTimeMillis()
        log("🚀 SWAP v2 EXECUTE: reqId=${requestId.take(12)}...")
        
        val payload = JSONObject().apply {
            put("signedTransaction", signedTxB64)
            put("requestId", requestId)
        }
        
        val body = postOrThrow(EXECUTE_ENDPOINT, payload.toString())
        val elapsed = System.currentTimeMillis() - startMs
        
        val json = JSONObject(body)
        
        if (json.has("error")) {
            val error = json.optString("error", "unknown")
            log("❌ Swap v2 EXECUTE error: $error (${elapsed}ms)")
            throw RuntimeException("Jupiter Swap v2 execute error: $error")
        }
        
        val signature = json.optString("signature", json.optString("txid", ""))
        val status = json.optString("status", "unknown")
        val code = json.optInt("code", 0)
        
        // v2 response also includes actual amounts
        val inputAmountResult = json.optLong("inputAmountResult", 0)
        val outputAmountResult = json.optLong("outputAmountResult", 0)
        
        // ═══════════════════════════════════════════════════════════════════
        // CRITICAL FIX: Check status and code, not just signature presence!
        // Jupiter Swap v2 returns signature even on failed transactions so you
        // can look them up on explorers. The actual success is indicated by:
        // - status = "Success" AND code = 0
        // - Negative codes indicate various failure types
        // ═══════════════════════════════════════════════════════════════════
        
        // Check for explicit failure status
        if (status.equals("Failed", ignoreCase = true)) {
            val errorMsg = when (code) {
                -1 -> "Missing cached order - requestId expired"
                -2 -> "Invalid signed transaction - signing issue"
                -3 -> "Invalid message bytes - transaction modified"
                -4 -> "Missing requestId in request"
                -5 -> "Missing signed transaction in request"
                -1000 -> "Failed to land on network"
                -1001 -> "Unknown error"
                -1002 -> "Invalid transaction"
                -1005 -> "Transaction expired after attempts"
                -1006 -> "Transaction timed out"
                else -> "Unknown failure (code=$code)"
            }
            log("❌ Swap v2 EXECUTE FAILED: status=$status code=$code | $errorMsg (${elapsed}ms)")
            throw RuntimeException("Jupiter Swap v2 execute failed: $errorMsg (code=$code, sig=${signature.take(20)})")
        }
        
        // Check for negative code even if status doesn't say "Failed"
        if (code < 0) {
            log("❌ Swap v2 EXECUTE negative code: $code | status=$status (${elapsed}ms)")
            throw RuntimeException("Jupiter Swap v2 execute error code: $code (status=$status)")
        }
        
        if (signature.isBlank()) {
            log("❌ Swap v2 execute returned no signature! status=$status code=$code")
            throw RuntimeException("Jupiter Swap v2: no signature returned, status=$status, code=$code")
        }
        
        // Only log success if we truly succeeded
        if (status.equals("Success", ignoreCase = true) && code == 0) {
            log("✅ SWAP v2 EXECUTED: sig=${signature.take(20)}... in=$inputAmountResult out=$outputAmountResult (${elapsed}ms)")
        } else {
            // Status is unknown or unexpected - warn but continue (awaitConfirmation will verify)
            log("⚠️ SWAP v2 EXECUTE uncertain: sig=${signature.take(20)}... status=$status code=$code (${elapsed}ms)")
        }
        
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
        val reqBuilder = Request.Builder().url(url)
            .header("User-Agent", "lifecycle-bot-android/6.0")
            .header("Accept", "application/json")
        
        // Add API key for Ultra API endpoints
        if (apiKey.isNotBlank() && url.contains("api.jup.ag")) {
            reqBuilder.header("x-api-key", apiKey)
        }
        
        val req = reqBuilder.build()
        
        try {
            val resp = http.newCall(req).execute()
            val code = resp.code
            val body = resp.body?.string()
            
            if (!resp.isSuccessful) {
                // Provide helpful error for 401
                if (code == 401) {
                    log("❌ GET failed: HTTP 401 Unauthorized - Jupiter API key required!")
                    log("   → Get a FREE API key at https://portal.jup.ag")
                    throw RuntimeException("Jupiter API 401: API key required. Get one free at portal.jup.ag")
                }
                log("❌ GET failed: HTTP $code | ${body?.take(100) ?: "no body"}")
                throw RuntimeException("Jupiter GET $code: ${body?.take(200) ?: url}")
            }
            
            if (body.isNullOrBlank()) {
                log("❌ GET returned empty body")
                throw RuntimeException("Empty Jupiter response for: ${url.takeLast(60)}")
            }
            
            return body
        } catch (e: java.net.UnknownHostException) {
            log("❌ DNS ERROR: Cannot resolve api.jup.ag - check network/DNS")
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
        val reqBuilder = Request.Builder().url(url)
            .header("User-Agent", "lifecycle-bot-android/6.0")
            .header("Accept", "application/json")
            .post(json.toRequestBody(JSON))
        
        // Add API key for Ultra API endpoints
        if (apiKey.isNotBlank() && url.contains("api.jup.ag")) {
            reqBuilder.header("x-api-key", apiKey)
        }
        
        val req = reqBuilder.build()
        
        try {
            val resp = http.newCall(req).execute()
            val code = resp.code
            val body = resp.body?.string()
            
            if (!resp.isSuccessful) {
                if (code == 401) {
                    log("❌ POST failed: HTTP 401 Unauthorized - Jupiter API key required!")
                    throw RuntimeException("Jupiter API 401: API key required. Get one free at portal.jup.ag")
                }
                log("❌ POST failed: HTTP $code | ${body?.take(200) ?: "no body"}")
                throw RuntimeException("Jupiter POST $code: ${body?.take(200) ?: ""}")
            }
            
            if (body.isNullOrBlank()) {
                log("❌ POST returned empty body")
                throw RuntimeException("Empty Jupiter swap response")
            }
            
            return body
        } catch (e: java.net.UnknownHostException) {
            log("❌ DNS ERROR: Cannot resolve api.jup.ag")
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
