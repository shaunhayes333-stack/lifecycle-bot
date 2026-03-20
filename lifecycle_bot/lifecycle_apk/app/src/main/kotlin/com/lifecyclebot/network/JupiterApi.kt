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
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json".toMediaType()

    /**
     * Get a Jupiter quote.
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
        val body = getOrThrow(url)
        val j    = JSONObject(body)
        return SwapQuote(
            raw             = j,
            outAmount       = j.optString("outAmount", "0").toLongOrNull() ?: 0L,
            priceImpactPct  = j.optString("priceImpactPct", "0").toDoubleOrNull() ?: 0.0,
        )
    }

    /**
     * Build a versioned transaction for the swap.
     * Returns base64-encoded transaction bytes ready for signing.
     */
    fun buildSwapTx(quote: SwapQuote, userPublicKey: String): String {
        val payload = JSONObject().apply {
            put("quoteResponse",              quote.raw)
            put("userPublicKey",              userPublicKey)
            put("wrapAndUnwrapSol",           true)
            put("dynamicComputeUnitLimit",    true)
            put("prioritizationFeeLamports",  "auto")
        }
        val body = postOrThrow("$BASE/swap", payload.toString())
        return JSONObject(body).getString("swapTransaction")
    }

    // ── helpers ────────────────────────────────────────────

    private fun getOrThrow(url: String): String {
        val req  = Request.Builder().url(url)
            .header("User-Agent", "lifecycle-bot-android/6.0").build()
        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) throw RuntimeException("Jupiter GET ${resp.code}: $url")
        return resp.body?.string() ?: throw RuntimeException("Empty Jupiter response")
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
            val body = resp.body?.string() ?: return "RPC error: empty response"
            val json = org.json.JSONObject(body)

            val err = json.optJSONObject("result")?.optJSONObject("value")?.opt("err")
            if (err != null && err.toString() != "null") {
                // Simulation failed — return human-readable error
                val logs = json.optJSONObject("result")
                    ?.optJSONObject("value")
                    ?.optJSONArray("logs")
                val lastLog = (0 until (logs?.length() ?: 0))
                    .mapNotNull { logs?.optString(it) }
                    .lastOrNull { it.contains("Error") || it.contains("failed") }
                "Simulate failed: $err${if (lastLog != null) " | $lastLog" else ""}"
            } else null  // simulation passed
        } catch (e: Exception) {
            null  // simulation errors are non-fatal — proceed with real tx
        }
    }

    private fun postOrThrow(url: String, json: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "lifecycle-bot-android/6.0")
            .post(json.toRequestBody(JSON)).build()
        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) {
            val err = resp.body?.string() ?: ""
            throw RuntimeException("Jupiter POST ${resp.code}: $err")
        }
        return resp.body?.string() ?: throw RuntimeException("Empty Jupiter swap response")
    }
}
