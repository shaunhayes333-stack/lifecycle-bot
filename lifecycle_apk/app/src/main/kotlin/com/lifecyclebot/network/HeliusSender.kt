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
 *   POST https://sender.helius-rpc.com/fast?swqos_only=true
 *   { jsonrpc, id, method:"sendTransaction",
 *     params:[ <base64 signed tx>, { encoding:"base64", skipPreflight:true, maxRetries:0 } ] }
 *
 * Sender is available without API credits, but is not economically free: the
 * signed tx must carry a Sender tip (≥0.000005 SOL for swqos_only). Helius
 * recommends a priority fee for landing quality; AATE's stricter internal
 * contract requires both. Jupiter builds the compute-unit price and
 * HeliusSenderEnvelope7250 appends a published Helius tip transfer before
 * wallet signing. This send-only class accepts only transactions whose caller
 * has received that proven compatibility stamp.
 *
 * This is a SEND-ONLY helper: it returns the signature string on accept. The
 * caller still confirms on-chain via the normal getSignatureStatuses poll —
 * a Sender "accept" is submission, not landing.
 */
object HeliusSender {
    private const val TAG = "HeliusSender"

    // V5.0.7248 — SWQOS transit is the correct fit for the existing bounded
    // 0.0002 SOL tip policy. Sender Max now requires a 0.001 SOL minimum tip,
    // which is inappropriate for a 0.15 SOL wallet and small meme entries.
    private const val URL = "https://sender.helius-rpc.com/fast?swqos_only=true"

    /** V5.0.8169 — the documented minimum tip of the endpoint mode in use (SWQOS-only 5,000 lamports; default 0.001 SOL). */
    fun minTipLamports8169(): Long = com.lifecyclebot.engine.CostLanding8169.senderMinTipLamports8169(URL)

    private val httpClient: OkHttpClient by lazy {
        SharedHttpClient.builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    // Only SolanaWallet calls Sender for transactions explicitly tagged
    // senderCompatible=true after binary envelope validation.
    @Volatile var senderEnabled: Boolean = true

    @Volatile var lastError: String? = null
        private set
    @Volatile var sentCount: Long = 0L
        private set

    /**
     * Submit an already-signed, already-tipped base64 tx through Sender.
     * @return signature on accept, or null on any error (caller falls back to
     *         the legacy Jito-bundle / RPC broadcast path).
     */
    fun send(signedTxBase64: String): String? = send8085(signedTxBase64, null).first

    /**
     * V5.0.8085 — typed Sender outcome. ACCEPTED only for a JSON-RPC result (equal to [localSignature] when given, or
     * an already-processed error for these bytes); REJECTED when Sender provably did not take them (disabled, refused
     * connection, HTTP 4xx, JSON-RPC error); UNKNOWN when they may have been forwarded (timeout, 5xx, blank result).
     */
    fun send8085(signedTxBase64: String, localSignature: String?): Pair<String?, com.lifecyclebot.network.SignedTxLedger8085.Submit> {
        val U = com.lifecyclebot.network.SignedTxLedger8085.Submit.UNKNOWN
        val R = com.lifecyclebot.network.SignedTxLedger8085.Submit.REJECTED
        val A = com.lifecyclebot.network.SignedTxLedger8085.Submit.ACCEPTED
        // Fail closed unless caller has built a correctly tipped tx and the global
        // sender switch is enabled. Caller-side senderCompatible prevents priority-
        // fee-only txs from ever reaching this method.
        if (!senderEnabled) {
            lastError = "SENDER_DISABLED_NO_TIP_INJECTION"
            return null to R
        }
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_SENDER_ATTEMPT_7248") } catch (_: Throwable) {}
        return try {
            val params = JSONArray()
                .put(signedTxBase64)
                .put(
                    JSONObject()
                        .put("encoding", "base64")
                        .put("skipPreflight", true)   // latency policy; local simulation already ran
                        .put("maxRetries", 0)         // AATE owns fallback/retry routing
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
                    try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_SENDER_FAIL_7248") } catch (_: Throwable) {}
                    lastError = "HTTP ${resp.code}: ${text.take(180)}"
                    ErrorLogger.warn(TAG, "Sender HTTP ${resp.code}: ${text.take(200)}")
                    return null to (if (resp.code >= 500) U else R)
                }
                val json = JSONObject(text)
                val err = json.optJSONObject("error")
                if (err != null) {
                    try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_SENDER_FAIL_7248") } catch (_: Throwable) {}
                    lastError = err.optString("message", "unknown sender error")
                    ErrorLogger.warn(TAG, "Sender error: ${lastError}")
                    if (com.lifecyclebot.network.SignedTxLedger8085.alreadyProcessed(lastError.orEmpty())) return localSignature to A
                    return null to R
                }
                val sig = json.optString("result", "")
                if (sig.isBlank()) {
                    lastError = "blank result"
                    return null to U
                }
                if (localSignature != null && sig != localSignature) {
                    lastError = "signature mismatch"
                    try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("TX_SIGNATURE_MISMATCH_8085") } catch (_: Throwable) {}
                    return null to U
                }
                sentCount++
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_SENDER_ACCEPT_7248") } catch (_: Throwable) {}
                ErrorLogger.info(TAG, "⚡ Sender accepted: ${sig.take(20)}… (sent=$sentCount)")
                sig to A
            }
        } catch (e: Exception) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_SENDER_FAIL_7248") } catch (_: Throwable) {}
            lastError = e.message
            ErrorLogger.warn(TAG, "Sender exception: ${e.message}")
            null to (if (e is java.net.ConnectException || e is java.net.UnknownHostException) R else U)
        }
    }
}
