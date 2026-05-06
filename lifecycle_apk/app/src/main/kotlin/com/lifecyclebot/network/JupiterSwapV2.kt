/*
 * V5.9.495y — Jupiter Swap V2 client.
 *
 * Implements the spec's required Jupiter migration:
 *   GET  https://api.jup.ag/swap/v2/order
 *   POST https://api.jup.ag/swap/v2/execute
 *
 * Public no-key endpoint (operator confirmed: existing cfg.jupiterApiKey
 * is Ultra-only and not valid for swap v2 endpoints).
 *
 * First call to /order MUST omit slippageBps + priorityFeeLamports +
 * jitoTipLamports — letting Jupiter auto-optimise. Slippage / tip
 * escalation is opt-in for retry-on-failure only (spec item 4 + 10).
 *
 * Insufficient-funds handling lives in the caller (Executor) — this
 * client just surfaces the raw error code so the caller can refresh
 * balances and retry once with capped raw, never escalating slippage.
 */
package com.lifecyclebot.network

import com.lifecyclebot.engine.ErrorLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object JupiterSwapV2 {
    private const val TAG = "JupiterSwapV2"

    private const val ORDER_URL   = "https://api.jup.ag/swap/v2/order"
    private const val EXECUTE_URL = "https://api.jup.ag/swap/v2/execute"

    const val SOL_MINT = "So11111111111111111111111111111111111111112"

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8,  TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    data class Order(
        val requestId: String,
        val swapTransaction: String,    // base64 unsigned tx
        val outAmount: String,          // raw smallest units
        val priceImpactPct: Double,
        val isRfq: Boolean,             // RFQ MM or AMM route
        val rawOrderJson: JSONObject,
    )

    data class ExecuteResult(
        val status: String,             // "Success" | "Failed" | other
        val signature: String?,
        val outputAmountResult: String?,// raw smallest units actually delivered
        val errorCode: String?,
        val rawJson: JSONObject,
    )

    /**
     * Fetch a swap order. First-attempt callers MUST pass nulls for slippage/priority/tip.
     * Spec: `If using slippageBps, remember it forces manual behaviour.`
     */
    fun getOrder(
        inputMint: String,
        outputMint: String,
        amountRaw: String,
        taker: String,
        slippageBps: Int? = null,
        priorityFeeLamports: Long? = null,
        jitoTipLamports: Long? = null,
    ): Order {
        val urlSb = StringBuilder(ORDER_URL).append("?")
            .append("inputMint=$inputMint")
            .append("&outputMint=$outputMint")
            .append("&amount=$amountRaw")
            .append("&taker=$taker")
        slippageBps?.let { urlSb.append("&slippageBps=$it") }
        priorityFeeLamports?.let { urlSb.append("&priorityFeeLamports=$it") }
        jitoTipLamports?.let { urlSb.append("&jitoTipLamports=$it") }
        val req = Request.Builder()
            .url(urlSb.toString())
            .header("Accept", "application/json")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                ErrorLogger.warn(TAG, "Jupiter v2 /order HTTP ${resp.code}: ${body.take(300)}")
                throw RuntimeException("Jupiter v2 order error (HTTP ${resp.code}): ${body.take(180)}")
            }
            val json = try { JSONObject(body) } catch (_: Throwable) {
                throw RuntimeException("Jupiter v2 /order non-JSON body: ${body.take(180)}")
            }
            val errCode = json.optString("errorCode", "")
            if (errCode.isNotBlank() || json.optString("error", "").isNotBlank()) {
                throw RuntimeException("Jupiter v2 order error: ${errCode.ifBlank { json.optString("error") }}")
            }
            val sig = json.optString("requestId", "")
            val tx  = json.optString("swapTransaction", "")
            if (sig.isBlank() || tx.isBlank()) {
                throw RuntimeException("Jupiter v2 /order missing requestId or swapTransaction: ${body.take(180)}")
            }
            val isRfq = json.optBoolean("rfq", false) || json.optString("router", "").contains("rfq", true)
            return Order(
                requestId = sig,
                swapTransaction = tx,
                outAmount = json.optString("outAmount", "0"),
                priceImpactPct = json.optDouble("priceImpactPct", 0.0),
                isRfq = isRfq,
                rawOrderJson = json,
            )
        }
    }

    /**
     * Submit a signed transaction to Jupiter for execution + verification.
     */
    fun execute(requestId: String, signedTxBase64: String): ExecuteResult {
        val payload = JSONObject().apply {
            put("requestId", requestId)
            put("signedTransaction", signedTxBase64)
        }
        val req = Request.Builder()
            .url(EXECUTE_URL)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val json = try { JSONObject(body) } catch (_: Throwable) {
                throw RuntimeException("Jupiter v2 /execute non-JSON body: ${body.take(180)}")
            }
            if (!resp.isSuccessful) {
                ErrorLogger.warn(TAG, "Jupiter v2 /execute HTTP ${resp.code}: ${body.take(300)}")
            }
            val status = json.optString("status", if (resp.isSuccessful) "Success" else "Failed")
            return ExecuteResult(
                status = status,
                signature = json.optString("signature", "").ifBlank { null },
                outputAmountResult = json.optString("outputAmountResult", "").ifBlank { null },
                errorCode = json.optString("errorCode", "").ifBlank { null }
                    ?: json.optString("error", "").ifBlank { null },
                rawJson = json,
            )
        }
    }

    /** Convenience: detect insufficient-funds class errors so callers don't slippage-escalate. */
    fun isInsufficientFundsError(msg: String): Boolean {
        val m = msg.lowercase()
        return m.contains("insufficient funds")  ||
               m.contains("insufficient lamports") ||
               m.contains("insufficient_balance") ||
               m.contains("not enough balance")
    }
}
