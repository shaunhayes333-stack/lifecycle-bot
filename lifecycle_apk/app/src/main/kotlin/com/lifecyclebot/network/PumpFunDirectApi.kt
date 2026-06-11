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
        // V5.9.1524 — Sender tip floor (priorityFee == Jito tip on PumpPortal).
        val effPriorityFee = priorityFeeSol.coerceAtLeast(0.0002)
        val payload = JSONObject().apply {
            put("publicKey", publicKeyB58)
            put("action", "buy")
            put("mint", mint)
            put("amount", solAmount)        // SOL (denominatedInSol=true)
            put("denominatedInSol", true)
            put("slippage", slip)
            put("priorityFee", effPriorityFee)
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

        com.lifecyclebot.engine.HealthAwareHttp.execute(httpClient, req, host = "pumpfun").use { resp ->
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
        tokenAmount: Long?,
        slippagePercent: Int,
        priorityFeeSol: Double = 0.0001,
        amountString: String = "100%",   // legacy default — ONLY used when no raw authority exists
        pool: String = "auto",
        decimals: Int = 6,               // V5.9.1524 — token decimals for canonical UI conversion
        allowEmergencyOverride: Boolean = false, // V5.9.1524 — operator-explicit panic only
    ): BuiltTx {
        // ── V5.9.1524 — CANONICAL SELL PAYLOAD (operator spec items 1, 2).
        // ROOT CAUSE of HTTP 400 / amount="100%": this builder previously
        // IGNORED the resolved tokenAmount and always shipped "100%". PumpPortal
        // rejected it as a bad payload, which then entered the retry loop. We now
        // build the EXACT amount from validated wallet/host authority and only
        // ever send "100%" when there is genuinely no raw amount (and we flag it).

        // Item 2 — slippage HARD CAP at 5% for all auto sells. Emergency exits
        // may NOT exceed 5% unless the caller passes an explicit operator override.
        val SELL_SLIP_CAP_PCT = 5
        val rawSlip = slippagePercent.coerceIn(1, 99)
        val slip = if (allowEmergencyOverride) rawSlip else rawSlip.coerceAtMost(SELL_SLIP_CAP_PCT)
        if (rawSlip > slip) {
            ErrorLogger.warn(TAG,
                "🛡️ SELL_SLIP_CAPPED ${mint.take(8)}…: requested=$rawSlip% → capped=$slip% (5% live cap, no override)")
        }

        // V5.9.1524 — Helius Sender REQUIRES a Jito tip >= 0.0002 SOL in the tx.
        // PumpPortal uses priorityFee AS the Jito tip, so floor it here. This
        // guarantees every Sender-broadcast sell lands instead of being dropped.
        val SENDER_MIN_TIP_SOL = 0.0002
        val effPriorityFee = priorityFeeSol.coerceAtLeast(SENDER_MIN_TIP_SOL)

        // Item 1 — validated amount authority. Prefer the exact raw token units.
        val safeDecimals = decimals.coerceIn(0, 18)
        val amountField: Any
        val amountKind: String
        when {
            tokenAmount != null && tokenAmount > 0L -> {
                // Canonical RAW base-unit amount (matches denominatedInSol=false,
                // PumpPortal expects token UI units as a numeric string OR a "%").
                // We send the validated UI amount derived from raw/decimals so the
                // venue can never over- or under-shoot the wallet balance.
                val uiAmount = tokenAmount.toBigDecimal()
                    .movePointLeft(safeDecimals)
                    .stripTrailingZeros()
                    .toPlainString()
                if (uiAmount.toBigDecimalOrNull()?.signum() != 1) {
                    com.lifecyclebot.engine.sell.SellForensics.inc(
                        com.lifecyclebot.engine.sell.SellForensics.SELL_PAYLOAD_INVALID,
                        "mint=${mint.take(10)} raw=$tokenAmount decimals=$safeDecimals → ui=$uiAmount non-positive")
                    throw PumpSellPayloadInvalid(
                        "PumpPortal SELL: invalid canonical amount raw=$tokenAmount decimals=$safeDecimals ui=$uiAmount")
                }
                amountField = uiAmount
                amountKind = "CANONICAL_UI"
                com.lifecyclebot.engine.sell.SellForensics.inc(
                    com.lifecyclebot.engine.sell.SellForensics.SELL_AMOUNT_CANONICAL_RAW,
                    "mint=${mint.take(10)} raw=$tokenAmount decimals=$safeDecimals")
                com.lifecyclebot.engine.sell.SellForensics.inc(
                    com.lifecyclebot.engine.sell.SellForensics.SELL_AMOUNT_CANONICAL_UI,
                    "mint=${mint.take(10)} ui=$uiAmount")
            }
            amountString.trim().endsWith("%") -> {
                // No raw authority but an explicit percentage exit was requested.
                // Allowed ONLY for full exits ("100%") — partial % exits are banned
                // upstream (PumpPortalKillSwitch) and must never reach here.
                val pctStr = amountString.trim()
                if (pctStr != "100%") {
                    com.lifecyclebot.engine.sell.SellForensics.inc(
                        com.lifecyclebot.engine.sell.SellForensics.SELL_PAYLOAD_INVALID,
                        "mint=${mint.take(10)} rejected partial-% without raw authority: $pctStr")
                    throw PumpSellPayloadInvalid(
                        "PumpPortal SELL: partial percent '$pctStr' requires resolved raw amount")
                }
                amountField = "100%"
                amountKind = "FULL_PERCENT_NO_RAW"
                ErrorLogger.warn(TAG,
                    "⚠️ PUMP SELL using 100% fallback (no raw amount authority) mint=${mint.take(8)}…")
            }
            else -> {
                com.lifecyclebot.engine.sell.SellForensics.inc(
                    com.lifecyclebot.engine.sell.SellForensics.SELL_PAYLOAD_INVALID,
                    "mint=${mint.take(10)} no raw amount + non-% amountString='$amountString'")
                throw PumpSellPayloadInvalid(
                    "PumpPortal SELL: no validated amount (raw=$tokenAmount, amountString='$amountString')")
            }
        }
        com.lifecyclebot.engine.sell.SellForensics.inc(
            com.lifecyclebot.engine.sell.SellForensics.SELL_PAYLOAD_VALIDATED,
            "mint=${mint.take(10)} kind=$amountKind amount=$amountField slip=$slip%")

        val payload = JSONObject().apply {
            put("publicKey", publicKeyB58)
            put("action", "sell")
            put("mint", mint)
            put("amount", amountField)
            put("denominatedInSol", false)
            put("slippage", slip)
            put("priorityFee", effPriorityFee)
            put("pool", pool.ifBlank { "auto" })
        }

        val req = Request.Builder()
            .url(URL)
            .header("Content-Type", "application/json")
            .header("Accept", "application/octet-stream")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        ErrorLogger.info(TAG,
            "🚀 PUMP DIRECT SELL → mint=${mint.take(8)}… amount=$amountField pool=$pool slip=$slip%")

        com.lifecyclebot.engine.HealthAwareHttp.execute(httpClient, req, host = "pumpfun").use { resp ->
            val body = resp.body
            if (body == null) {
                throw RuntimeException("PumpPortal returned empty body (HTTP ${resp.code})")
            }
            val ct = resp.header("Content-Type", "")?.lowercase() ?: ""
            // Read bytes once. PumpPortal returns octet-stream on success
            // and JSON (or plain text) on error. resp.code is the discriminator.
            val bytes = body.bytes()
            if (!resp.isSuccessful) {
                val text = try { String(bytes) } catch (_: Throwable) { "<binary ${bytes.size}B>" }
                ErrorLogger.warn(TAG,
                    "PumpPortal HTTP ${resp.code} payload=${payload.toString().take(200)}  body=${text.take(300)}")
                // V5.9.1524 — a 400 (and 4xx generally) is a PAYLOAD/BUILD error,
                // NOT a temporary network fault. Throw a typed exception so the
                // caller fails over immediately instead of requeuing a malformed
                // payload (operator spec items 3 & 5).
                if (resp.code in 400..499) {
                    com.lifecyclebot.engine.sell.SellForensics.inc(
                        com.lifecyclebot.engine.sell.SellForensics.SELL_ROUTE_DIRECT_REJECTED_400,
                        "mint=${mint.take(10)} http=${resp.code} body=${text.take(120)}")
                    throw PumpSellBadRequest(resp.code,
                        "PumpPortal HTTP ${resp.code}: ${text.take(180).ifBlank { resp.message }}")
                }
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

/** V5.9.1524 — PumpPortal 4xx: payload/build error, never retry as-is. */
class PumpSellBadRequest(val httpCode: Int, message: String) : RuntimeException(message)

/** V5.9.1524 — payload failed local validation before any network call. */
class PumpSellPayloadInvalid(message: String) : RuntimeException(message)
