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
)

class JupiterApi {

    companion object {
        const val SOL_MINT = "So11111111111111111111111111111111111111112"
        private const val BASE = "https://quote-api.jup.ag/v6"
        private const val TAG = "JupiterApi"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)   // Increased from 10s
        .readTimeout(20, TimeUnit.SECONDS)      // Increased from 15s
        .writeTimeout(15, TimeUnit.SECONDS)     // Added write timeout
        .retryOnConnectionFailure(true)         // Auto-retry on connection issues
        .build()

    private val JSON = "application/json".toMediaType()

    /**
     * Get a Jupiter quote with enhanced logging.
     * @param amountLamports  for SOL-in swaps; for token-in swaps use raw token units
     */
    fun getQuote(
        inputMint: String,
        outputMint: String,
        amountLamports: Long,
        slippageBps: Int,
    ): SwapQuote {
        val url = "$BASE/quote?inputMint=$inputMint&outputMint=$outputMint" +
                  "&amount=$amountLamports&slippageBps=$slippageBps"
        
        val startMs = System.currentTimeMillis()
        log("📊 GET quote: ${outputMint.take(8)}... amt=$amountLamports slip=$slippageBps")
        
        val body = getOrThrow(url)
        val elapsed = System.currentTimeMillis() - startMs
        
        val j = JSONObject(body)
        
        // Check for Jupiter error responses
        if (j.has("error")) {
            val error = j.optString("error", "unknown")
            log("❌ Quote ERROR: $error (${elapsed}ms)")
            throw RuntimeException("Jupiter quote error: $error")
        }
        
        val outAmount = j.optString("outAmount", "0").toLongOrNull() ?: 0L
        val priceImpact = j.optString("priceImpactPct", "0").toDoubleOrNull() ?: 0.0
        
        // Validate we got a usable quote
        if (outAmount <= 0) {
            log("❌ Quote returned 0 output - token may be illiquid or dead")
            throw RuntimeException("Jupiter returned 0 output - no liquidity")
        }
        
        log("✅ Quote OK: out=$outAmount impact=${String.format("%.2f", priceImpact)}% (${elapsed}ms)")
        
        return SwapQuote(
            raw             = j,
            outAmount       = outAmount,
            priceImpactPct  = priceImpact,
        )
    }

    /**
     * Build a versioned transaction for the swap.
     * Returns base64-encoded transaction bytes ready for signing.
     */
    fun buildSwapTx(quote: SwapQuote, userPublicKey: String): String {
        val startMs = System.currentTimeMillis()
        log("🔧 Building swap tx for ${userPublicKey.take(8)}...")
        
        val payload = JSONObject().apply {
            put("quoteResponse",              quote.raw)
            put("userPublicKey",              userPublicKey)
            put("wrapAndUnwrapSol",           true)
            put("dynamicComputeUnitLimit",    true)
            put("prioritizationFeeLamports",  "auto")
        }
        
        val body = postOrThrow("$BASE/swap", payload.toString())
        val elapsed = System.currentTimeMillis() - startMs
        
        val json = JSONObject(body)
        
        // Check for error response
        if (json.has("error")) {
            val error = json.optString("error", "unknown")
            log("❌ BuildTx ERROR: $error (${elapsed}ms)")
            throw RuntimeException("Jupiter buildSwapTx error: $error")
        }
        
        val swapTx = json.optString("swapTransaction", "")
        if (swapTx.isBlank()) {
            log("❌ BuildTx returned empty transaction!")
            throw RuntimeException("Jupiter returned empty swapTransaction")
        }
        
        log("✅ Swap tx built OK (${swapTx.length} chars, ${elapsed}ms)")
        return swapTx
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
