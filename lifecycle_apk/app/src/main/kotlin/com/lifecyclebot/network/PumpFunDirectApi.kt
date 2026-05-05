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
     *
     * V5.9.495 — kept for forensics/log tagging only. Routing is no
     * longer gated on this. PumpPortal Lightning's pool="auto" routes
     * through pump.fun bonding curve, PumpSwap AMM, AND Raydium pools,
     * so it can build BUY/SELL txs for almost anything liquid on
     * Solana — not just pump.fun mints. Operator directive:
     *   "we now know what works ie pumpfun for the entire sol network
     *    basically the Jupiter Ultra then the other callbacks"
     * Universal PUMP-FIRST routing is enforced at every call site in
     * Executor.kt; this helper is now informational only.
     */
    fun isPumpFunMint(mint: String): Boolean =
        mint.endsWith("pump", ignoreCase = true)

    data class BuiltTx(val txBase64: String, val pickedSlippagePct: Int)

    /**
     * V5.9.495 — Universal PumpPortal BUY tx builder. PumpPortal's
     * pool="auto" routes through bonding curve, PumpSwap AMM, AND
     * Raydium pools, so we can use it as a primary entry venue for
     * basically the whole SOL network. If it can't route the mint
     * (deep-graduated to Orca/Meteora only, or insufficient liquidity)
     * it returns HTTP 400 and the caller falls back to Jupiter.
     *
     * Caller signs the returned tx via `SolanaWallet.signAndSend(...)`
     * (same Jito + RPC pipeline every other broadcast uses).
     *
     * @param publicKeyB58 wallet public key in base58
     * @param mint target token mint
     * @param solAmount SOL to spend (denominatedInSol=true)
     * @param slippagePercent int in [1, 99]. Percentage tolerance —
     *        pump.fun uses percent, not bps like Jupiter. We pass
     *        whatever the caller picked (typically 5–30 for buys).
     * @param priorityFeeSol optional priority fee in SOL.
     */
    fun buildBuyTx(
        publicKeyB58: String,
        mint: String,
        solAmount: Double,
        slippagePercent: Int,
        priorityFeeSol: Double = 0.0001,
    ): BuiltTx {
        val slip = slippagePercent.coerceIn(1, 99)
        if (solAmount <= 0.0 || solAmount.isNaN() || solAmount.isInfinite()) {
            throw RuntimeException("PumpPortal BUY: invalid solAmount=$solAmount")
        }
        val payload = JSONObject().apply {
            put("publicKey", publicKeyB58)
            put("action", "buy")
            put("mint", mint)
            put("amount", solAmount)        // SOL (denominatedInSol=true)
            put("denominatedInSol", true)
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
            "🚀 PUMP DIRECT BUY → mint=${mint.take(8)}… sol=${"%.4f".format(solAmount)} slip=$slip%")

        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body
                ?: throw RuntimeException("PumpPortal returned empty body (HTTP ${resp.code})")
            val ct = resp.header("Content-Type", "")?.lowercase() ?: ""
            val bytes = body.bytes()
            if (!resp.isSuccessful) {
                val text = try { String(bytes) } catch (_: Throwable) { "<binary ${bytes.size}B>" }
                ErrorLogger.warn(TAG,
                    "PumpPortal BUY HTTP ${resp.code} payload=${payload.toString().take(200)}  body=${text.take(300)}")
                throw RuntimeException(
                    "PumpPortal BUY HTTP ${resp.code}: ${text.take(180).ifBlank { resp.message }}"
                )
            }
            if (ct.contains("json") || (bytes.isNotEmpty() && bytes[0].toInt() == '{'.code)) {
                val text = try { String(bytes) } catch (_: Throwable) { "<binary>" }
                throw RuntimeException("PumpPortal BUY JSON error (HTTP 200): ${text.take(300)}")
            }
            if (bytes.size < 64) {
                throw RuntimeException("PumpPortal BUY returned tx too small (${bytes.size} bytes)")
            }
            val txB64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            ErrorLogger.info(TAG,
                "✅ PUMP DIRECT BUY TX BUILT: bytes=${bytes.size} b64Len=${txB64.length} slip=$slip%")
            return BuiltTx(txBase64 = txB64, pickedSlippagePct = slip)
        }
    }

    /**
     * Build an unsigned pump.fun SELL transaction.
     *
     * V5.9.490 — operator forensics show V5.9.488 was returning HTTP 400
     * 'Bad Request' from PumpPortal. Two issues identified:
     *   1) `denominatedInSol` was a STRING ("false") — PumpPortal's docs
     *      say boolean. Some payloads are accepted, others 400. Switched
     *      to a real JSON boolean.
     *   2) Absolute token amount was being passed in 9-decimal lamport
     *      scale (Jupiter convention) but pump.fun tokens use 6 decimals
     *      natively. The mismatch was sending "26023201291000" → PumpPortal
     *      interprets as 26023201.29 tokens → exceeds wallet balance →
     *      400 'insufficient'.
     *
     * Since the fallback only fires after Jupiter has fully exhausted, we
     * always want to dump the entire bag. The `tokenAmount` parameter is
     * kept on the API for future granular use but we now ALWAYS pass
     * "100%" to PumpPortal — bullet-proof and matches operator intent.
     *
     * @param publicKeyB58 wallet public key in base58
     * @param mint pump.fun token mint
     * @param tokenAmount currently IGNORED — we always send "100%". Kept
     *        on the signature for backward compatibility.
     * @param slippagePercent int in [1, 99]. Percentage tolerance —
     *        pump.fun uses percent, not bps like Jupiter.
     * @param priorityFeeSol optional priority fee in SOL.
     */
    fun buildSellTx(
        publicKeyB58: String,
        mint: String,
        @Suppress("UNUSED_PARAMETER") tokenAmount: Long?,
        slippagePercent: Int,
        priorityFeeSol: Double = 0.0001,
    ): BuiltTx {
        val slip = slippagePercent.coerceIn(1, 99)
        val payload = JSONObject().apply {
            put("publicKey", publicKeyB58)
            put("action", "sell")
            put("mint", mint)
            put("amount", "100%")             // V5.9.490 — always drain
            put("denominatedInSol", false)    // V5.9.490 — boolean, not string
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
            "🚀 PUMP DIRECT SELL → mint=${mint.take(8)}… amount=100% slip=$slip%")

        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body
            if (body == null) {
                throw RuntimeException("PumpPortal returned empty body (HTTP ${resp.code})")
            }
            val ct = resp.header("Content-Type", "")?.lowercase() ?: ""
            // Read bytes once. PumpPortal returns octet-stream on success
            // and JSON (or plain text) on error. resp.code is the discriminator.
            val bytes = body.bytes()
            if (!resp.isSuccessful) {
                // V5.9.490 — surface FULL response body (up to 500 chars)
                // so the operator's forensics actually shows WHY PumpPortal
                // 400'd instead of just "Bad Request".
                val text = try { String(bytes) } catch (_: Throwable) { "<binary ${bytes.size}B>" }
                ErrorLogger.warn(TAG,
                    "PumpPortal HTTP ${resp.code} payload=${payload.toString().take(200)}  body=${text.take(300)}")
                throw RuntimeException(
                    "PumpPortal HTTP ${resp.code}: ${text.take(180).ifBlank { resp.message }}"
                )
            }
            // 2xx but maybe still JSON error (PumpPortal occasionally returns 200
            // with an error JSON body — handle defensively).
            if (ct.contains("json") || (bytes.isNotEmpty() && bytes[0].toInt() == '{'.code)) {
                val text = try { String(bytes) } catch (_: Throwable) { "<binary>" }
                throw RuntimeException("PumpPortal JSON error (HTTP 200): ${text.take(300)}")
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
