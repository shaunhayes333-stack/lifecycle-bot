package com.lifecyclebot.network

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * V5.0.7311 §A THIRD SELL BUILDER.
 *
 * The live exit had two transaction builders — PumpPortal (pool=auto) and
 * the Jupiter ladder. On 5.0.7309 one 503 from each left TTP's stop with
 * nothing to build a transaction, so no sender (Helius / Jito / RPC) was ever
 * reached. Raydium's trade API builds a swap independently of both.
 *
 * Flow (token -> SOL, exact-in):
 *   1. GET  transaction-v1.raydium.io/compute/swap-base-in   (route + quote)
 *   2. GET  api-v3.raydium.io/main/auto-fee                  (CU price)
 *   3. POST transaction-v1.raydium.io/transaction/swap-base-in (v0 tx(s))
 * The source token account comes from getTokenAccountsByOwner filtered by
 * mint. Every returned transaction carries a SetComputeUnitPrice, so the
 * caller can wrap it in the Helius Sender envelope and broadcast through
 * Helius Sender first, then Jito / RPC.
 *
 * Exit calls are made with allowDuringLockout = true: a host backoff must
 * never be the reason a stop cannot leave the wallet.
 */
object RaydiumSellRoute7311 {
    private const val HOST = "raydium_swap"
    private const val COMPUTE_URL = "https://transaction-v1.raydium.io/compute/swap-base-in"
    private const val BUILD_URL = "https://transaction-v1.raydium.io/transaction/swap-base-in"
    private const val FEE_URL = "https://api-v3.raydium.io/main/auto-fee"
    private const val FALLBACK_CU_PRICE = 200_000L
    private val JSON = "application/json".toMediaType()

    private val http by lazy {
        SharedHttpClient.builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    data class Built(val transactions: List<String>, val outLamports: Long, val cuPrice: Long)

    private fun get(url: String): JSONObject {
        val req = Request.Builder().url(url).get().build()
        com.lifecyclebot.engine.HealthAwareHttp.execute(http, req, host = HOST, allowDuringLockout = true).use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("Raydium GET ${resp.code}: ${body.take(200)}")
            return JSONObject(body)
        }
    }

    private fun post(url: String, payload: JSONObject): JSONObject {
        val req = Request.Builder().url(url).post(payload.toString().toRequestBody(JSON)).build()
        com.lifecyclebot.engine.HealthAwareHttp.execute(http, req, host = HOST, allowDuringLockout = true).use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("Raydium POST ${resp.code}: ${body.take(200)}")
            return JSONObject(body)
        }
    }

    /** Pure: the CU price to request, from Raydium's auto-fee response. */
    fun cuPriceFrom(feeJson: JSONObject?): Long {
        val h = feeJson?.optJSONObject("data")?.optJSONObject("default")?.optLong("h", 0L) ?: 0L
        return if (h > 0L) h else FALLBACK_CU_PRICE
    }

    /** Pure: base64 transactions from the build response, in send order. */
    private fun transactionsFrom(buildJson: JSONObject): List<String> {
        if (!buildJson.optBoolean("success", false)) return emptyList()
        val arr = buildJson.optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.optString("transaction", "")?.takeIf { it.isNotBlank() }
        }
    }

    /** The owner's token account holding [mint], via getTokenAccountsByOwner. */
    private fun sourceTokenAccount(wallet: SolanaWallet, mint: String): String? {
        val res = wallet.rpcCall(
            "getTokenAccountsByOwner",
            JSONArray()
                .put(wallet.publicKeyB58)
                .put(JSONObject().put("mint", mint))
                .put(JSONObject().put("encoding", "jsonParsed").put("commitment", "confirmed")),
        )
        val arr = res.optJSONObject("result")?.optJSONArray("value") ?: return null
        var best: String? = null
        var bestRaw = -1L
        for (i in 0 until arr.length()) {
            val acct = arr.optJSONObject(i) ?: continue
            val raw = acct.optJSONObject("account")?.optJSONObject("data")?.optJSONObject("parsed")
                ?.optJSONObject("info")?.optJSONObject("tokenAmount")?.optString("amount", "0")?.toLongOrNull() ?: 0L
            if (raw > bestRaw) { bestRaw = raw; best = acct.optString("pubkey", "").takeIf { it.isNotBlank() } }
        }
        return best
    }

    /** Build token -> SOL sell transaction(s). Throws with a named reason on any miss. */
    fun buildSell(wallet: SolanaWallet, mint: String, rawAmount: Long, slippageBps: Int): Built {
        require(rawAmount > 0L) { "raydium_sell_zero_amount" }
        val quote = get(
            "$COMPUTE_URL?inputMint=$mint&outputMint=${JupiterApi.SOL_MINT}" +
                "&amount=$rawAmount&slippageBps=${slippageBps.coerceIn(50, 5_000)}&txVersion=V0",
        )
        if (!quote.optBoolean("success", false)) {
            throw RuntimeException("Raydium no route: ${quote.optString("msg", quote.toString()).take(160)}")
        }
        val outLamports = quote.optJSONObject("data")?.optString("outputAmount", "0")?.toLongOrNull() ?: 0L
        val cuPrice = cuPriceFrom(try { get(FEE_URL) } catch (_: Throwable) { null })
        val inputAccount = sourceTokenAccount(wallet, mint)
            ?: throw RuntimeException("Raydium sell: no token account for ${mint.take(10)}")
        val built = post(
            BUILD_URL,
            JSONObject()
                .put("computeUnitPriceMicroLamports", cuPrice.toString())
                .put("swapResponse", quote)
                .put("txVersion", "V0")
                .put("wallet", wallet.publicKeyB58)
                .put("wrapSol", false)
                .put("unwrapSol", true)
                .put("inputAccount", inputAccount),
        )
        val txs = transactionsFrom(built)
        if (txs.isEmpty()) throw RuntimeException("Raydium build returned no transaction: ${built.toString().take(160)}")
        try {
            PipelineHealthCollector.labelInc("RAYDIUM_SELL_BUILT_7311")
            ForensicLogger.lifecycle(
                "RAYDIUM_SELL_BUILT_7311",
                "mint=${mint.take(10)} raw=$rawAmount outLamports=$outLamports txs=${txs.size} cuPrice=$cuPrice slipBps=$slippageBps",
            )
        } catch (_: Throwable) {}
        return Built(txs, outLamports, cuPrice)
    }
}
