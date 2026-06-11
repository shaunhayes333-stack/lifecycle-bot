package com.lifecyclebot.network

import com.lifecyclebot.engine.ErrorLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * V5.9.1524 — Helius Sender: ultra-low-latency transaction submission.
 *
 *   POST https://sender.helius-rpc.com/fast
 *   { jsonrpc, id, method:"sendTransaction",
 *     params:[ <base64 signed tx>, { encoding:"base64", skipPreflight:true, maxRetries:0 } ] }
 *
 * Sender dual-routes to validators + Jito simultaneously and lands in ~1 slot.
 * No API key, no credits. HARD REQUIREMENT: the signed tx MUST already carry a
 * Jito tip (≥0.0002 SOL dual-route, ≥0.000005 SOL swqos_only) AND a priority
 * fee, and we MUST pass skipPreflight=true. Our routers (PumpPortal priorityFee
 * == Jito tip; Jupiter prioritizationFeeLamports) bake the tip in at build time,
 * so we never do tx surgery here.
 *
 * This is a SEND-ONLY helper: it returns the signature string on accept. The
 * caller still confirms on-chain via the normal getSignatureStatuses poll —
 * a Sender "accept" is submission, not landing.
 */
object HeliusSender {
    private const val TAG = "HeliusSender"

    // Regional HTTP endpoint recommended for backends; /fast auto-routes.
    private const val URL = "https://sender.helius-rpc.com/fast"
    // swqos_only lowers the min tip to 0.000005 SOL; we keep dual-route default
    // because our tip (>=0.0002 SOL) already satisfies it and dual-route lands best.

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    @Volatile var lastError: String? = null
        private set
    @Volatile var sentCount: Long = 0L
        private set

    /**
     * Submit an already-signed, already-tipped base64 tx through Sender.
     * @return signature on accept, or null on any error (caller falls back to
     *         the legacy Jito-bundle / RPC broadcast path).
     */
    fun send(signedTxBase64: String): String? {
        return try {
            val params = JSONArray()
                .put(signedTxBase64)
                .put(
                    JSONObject()
                        .put("encoding", "base64")
                        .put("skipPreflight", true)   // mandatory for Sender
                        .put("maxRetries", 0)         // mandatory for Sender
                )
            val payload = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", System.currentTimeMillis().toString())
                .put("method", "sendTransaction")
                .put("params", params)

            val req = Request.Builder()
                .url(URL)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            com.lifecyclebot.engine.HealthAwareHttp.execute(httpClient, req, host = "helius_sender").use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    lastError = "HTTP ${resp.code}: ${text.take(180)}"
                    ErrorLogger.warn(TAG, "Sender HTTP ${resp.code}: ${text.take(200)}")
                    return null
                }
                val json = JSONObject(text)
                val err = json.optJSONObject("error")
                if (err != null) {
                    lastError = err.optString("message", "unknown sender error")
                    ErrorLogger.warn(TAG, "Sender error: ${lastError}")
                    return null
                }
                val sig = json.optString("result", "")
                if (sig.isBlank()) {
                    lastError = "blank result"
                    return null
                }
                sentCount++
                ErrorLogger.info(TAG, "⚡ Sender accepted: ${sig.take(20)}… (sent=$sentCount)")
                sig
            }
        } catch (e: Exception) {
            lastError = e.message
            ErrorLogger.warn(TAG, "Sender exception: ${e.message}")
            null
        }
    }
}
