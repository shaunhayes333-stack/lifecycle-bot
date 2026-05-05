package com.lifecyclebot.network

import com.lifecyclebot.engine.ErrorLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * V5.9.488 — PumpPortal Lightning local-trading API.
 *
 * Direct sell route for pump.fun mints, bypassing Jupiter entirely.
 * Used as the FINAL FALLBACK in Executor.liveSell after all 5 Jupiter
 * escalation attempts have failed. Critical for unsticking pump.fun
 * memecoins that Jupiter routes can't dump (RFQ providers reject the
 * SELL side, and Metis dynamicSlippage simulator consistently lowballs
 * the picked slip — see V5.9.487 for the partial fix).
 *
 * Endpoint (no API key required):
 *   POST https://pumpportal.fun/api/trade-local
 *   Body: {
 *     publicKey:        <wallet pubkey b58>,
 *     action:           "sell",
 *     mint:             <token mint b58>,
 *     amount:           <Long token-units OR "100%">,
 *     denominatedInSol: "false",                 // amount is token units, not SOL
 *     slippage:         <Int 0-100, percent>,
 *     priorityFee:      <Double SOL, optional>,
 *     pool:             "auto"                   // bondingCurve OR PumpSwap, auto-detect
 *   }
 *   Returns: raw bytes of an UNSIGNED VersionedTransaction
 *
 * Operator signs locally with SolanaWallet.signAndSend(...) using the
 * normal Jito + RPC pipeline — same path as every other sell, just
 * with a tx that pump.fun built instead of Jupiter.
 *
 * Fees: 0.5% on trade value (taken by pump.fun, on top of the 1%
 * pump.fun bonding curve / PumpSwap fee).
 */
object PumpFunDirectApi {
    private const val TAG = "PumpFunDirectApi"
    private const val URL = "https://pumpportal.fun/api/trade-local"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Heuristic: pump.fun launches mints whose b58 string ends with
     * "pump". Some mints (post-graduation PumpSwap) keep the suffix.
     * If false, this API will return an error and the caller should
     * not invoke it — Jupiter is the right venue for non-pump mints.
     */
    fun isPumpFunMint(mint: String): Boolean =
        mint.endsWith("pump", ignoreCase = true)

    data class BuiltTx(val txBase64: String, val pickedSlippagePct: Int)

    /**
     * Build an unsigned pump.fun SELL transaction.
     *
     * @param publicKeyB58 wallet public key in base58
     * @param mint pump.fun token mint
     * @param tokenAmount integer token units to sell (NOT SOL).
     *        Pass null to use `"100%"` (sell entire wallet balance).
     * @param slippagePercent int in [1, 99]. Percentage tolerance —
     *        pump.fun uses percent, not bps like Jupiter.
     * @param priorityFeeSol optional priority fee in SOL.
     *
     * @return BuiltTx with the base64-encoded unsigned versioned tx
     *         ready for SolanaWallet.signAndSend(...).
     * @throws RuntimeException with a human-readable reason on failure.
     */
    fun buildSellTx(
        publicKeyB58: String,
        mint: String,
        tokenAmount: Long?,
        slippagePercent: Int,
        priorityFeeSol: Double = 0.0001,
    ): BuiltTx {
        val slip = slippagePercent.coerceIn(1, 99)
        val payload = JSONObject().apply {
            put("publicKey", publicKeyB58)
            put("action", "sell")
            put("mint", mint)
            put("amount", tokenAmount?.toString() ?: "100%")
            put("denominatedInSol", "false")
            put("slippage", slip)
            put("priorityFee", priorityFeeSol)
            put("pool", "auto")
        }

        val req = Request.Builder()
            .url(URL)
            .header("Content-Type", "application/json")
            .header("Accept", "application/octet-stream")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        ErrorLogger.info(TAG,
            "🚀 PUMP DIRECT SELL → mint=${mint.take(8)}… amount=${tokenAmount ?: "100%"} slip=$slip%")

        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body
            if (body == null) {
                throw RuntimeException("PumpPortal returned empty body (HTTP ${resp.code})")
            }
            // Successful response is raw VersionedTransaction bytes.
            // Errors come back as JSON ({"errors": [...]}).
            val ct = resp.header("Content-Type", "")?.lowercase() ?: ""
            if (!resp.isSuccessful) {
                val text = body.string().take(200)
                throw RuntimeException("PumpPortal HTTP ${resp.code}: $text")
            }
            // PumpPortal returns octet-stream on success and JSON on error.
            // Read bytes once, then decide.
            val bytes = body.bytes()
            if (ct.contains("json") || (bytes.isNotEmpty() && bytes[0].toInt() == '{'.code)) {
                val text = String(bytes).take(300)
                throw RuntimeException("PumpPortal JSON error: $text")
            }
            if (bytes.size < 64) {
                throw RuntimeException("PumpPortal returned tx too small (${bytes.size} bytes)")
            }
            val txB64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            ErrorLogger.info(TAG,
                "✅ PUMP DIRECT TX BUILT: bytes=${bytes.size} b64Len=${txB64.length} slip=$slip%")
            return BuiltTx(txBase64 = txB64, pickedSlippagePct = slip)
        }
    }
}
