package com.lifecyclebot.network

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.HealthAwareHttp
import com.lifecyclebot.engine.PipelineHealthCollector
import okhttp3.Request
import org.json.JSONObject

/**
 * V5.0.6996 — keyless batch mark sources, so the stack is never price-dry.
 *
 * WHY THIS EXISTS — the operator's 5.0.6993 snapshot, read end to end:
 *
 *     dexscreener   sr=0%   s=0   4xx=5       <- ZERO successful calls, all session
 *     QUOTE_STALE_6452: 31,496
 *     Exit scheduler: eval=58,066  SL=0  TP=0  TRAIL=0  CATA=6
 *     Open positions: 97      POSITION_HARD_CAP = 100
 *     ORDER_SIZE_BLOCKED_EXIT_THROUGHPUT_6758: 1,924
 *
 * Read as one chain: DexScreener — the ONLY batch mark source this app has —
 * took five 4xx early, armed a long ApiBackoff lockout and never landed a
 * single successful call again. With no fresh marks, 58,066 exit evaluations
 * fired zero stop-losses, zero take-profits and zero trails; only catastrophic
 * exits, which bypass the mark requirement, got through at all. Positions
 * could not close, inventory climbed to 97 of a 100 hard cap, and every lane
 * went SIZING_CHOKED behind POSITION_HARD_CAP_EXIT_THROUGHPUT.
 *
 * One dead provider stopped the entire bot trading. That is a single point of
 * failure on the most load-bearing read in the system, and the fix is not to
 * make DexScreener more reliable — it is to stop being alone.
 *
 * WHAT MAKES A SOURCE ELIGIBLE HERE
 *   - no API key, no account, no signup (the operator's standing requirement)
 *   - batch by mint, because per-mint polling is what starved the rate limiter
 *     in the first place (see V5.0.6970)
 *   - a different origin and a different rate-limit budget from DexScreener,
 *     or it fails at the same moment for the same reason and adds nothing
 *
 * DefiLlama's coins API satisfies all three: unauthenticated, batches many
 * mints per request, and is an entirely separate infrastructure from the DEX
 * aggregators. Jupiter's price surface is the second, already healthy at
 * sr=96% in the same snapshot that shows DexScreener at zero — it was sitting
 * right there, working, while positions went unpriced.
 *
 * DOCTRINE: read-only, fail-soft, never throws. An empty map means "no marks
 * this pass", which every caller already handles, and never means "the token
 * is worthless" — V5.0.6982 is the standing lesson on that distinction.
 */
object KeylessPriceSources6996 {

    private const val TAG = "KeylessPrice6996"

    /** DefiLlama batches comfortably; keep well inside URL length limits. */
    private const val LLAMA_CHUNK = 40

    private val http = SharedHttpClient.builder().build()

    /**
     * Batch USD marks from DefiLlama. Keyless.
     *
     * GET https://coins.llama.fi/prices/current/solana:MINT,solana:MINT,...
     *  -> { "coins": { "solana:MINT": { "price": 1.23, "confidence": 0.99 } } }
     *
     * `confidence` is DefiLlama's own view of how trustworthy the quote is.
     * Anything it flags as low confidence is dropped rather than fed to the
     * exit engine, because a bad mark triggers a real stop-loss and a missing
     * mark only delays one.
     */
    fun defiLlamaBatch(mints: List<String>): Map<String, Double> {
        if (mints.isEmpty()) return emptyMap()
        val out = HashMap<String, Double>(mints.size)
        try {
            mints.chunked(LLAMA_CHUNK).forEach { chunk ->
                val keys = chunk.joinToString(",") { "solana:$it" }
                val url = "https://coins.llama.fi/prices/current/$keys"
                val body = get(url, "defillama") ?: return@forEach
                val coins = JSONObject(body).optJSONObject("coins") ?: return@forEach
                for (mint in chunk) {
                    val o = coins.optJSONObject("solana:$mint") ?: continue
                    val price = o.optDouble("price", 0.0)
                    if (!price.isFinite() || price <= 0.0) continue
                    // Absent confidence is treated as acceptable; DefiLlama
                    // omits it for many well-known assets.
                    val conf = o.optDouble("confidence", 1.0)
                    if (conf.isFinite() && conf < 0.70) continue
                    out[mint] = price
                }
            }
        } catch (t: Throwable) {
            ErrorLogger.debug(TAG, "defillama batch failed: ${t.message?.take(140)}")
        }
        if (out.isNotEmpty()) {
            try { PipelineHealthCollector.labelInc("KEYLESS_MARK_DEFILLAMA_6996") } catch (_: Throwable) {}
        }
        return out
    }

    /**
     * Batch USD marks from Jupiter's price surface. Keyless.
     *
     * Jupiter was at sr=96% with 217 successful calls in the very snapshot
     * where DexScreener sat at zero, so this is the source that was available
     * and unused while 97 positions went unpriced.
     */
    fun jupiterBatch(mints: List<String>): Map<String, Double> {
        if (mints.isEmpty()) return emptyMap()
        val out = HashMap<String, Double>(mints.size)
        try {
            mints.chunked(50).forEach { chunk ->
                val ids = chunk.joinToString(",")
                val body = get("https://lite-api.jup.ag/price/v3?ids=$ids", "jupiter") ?: return@forEach
                val root = JSONObject(body)
                // v3 returns a bare mint->object map; older shapes nest under "data".
                val data = root.optJSONObject("data") ?: root
                for (mint in chunk) {
                    val o = data.optJSONObject(mint) ?: continue
                    val price = o.optDouble("usdPrice", o.optDouble("price", 0.0))
                    if (!price.isFinite() || price <= 0.0) continue
                    out[mint] = price
                }
            }
        } catch (t: Throwable) {
            ErrorLogger.debug(TAG, "jupiter batch failed: ${t.message?.take(140)}")
        }
        if (out.isNotEmpty()) {
            try { PipelineHealthCollector.labelInc("KEYLESS_MARK_JUPITER_6996") } catch (_: Throwable) {}
        }
        return out
    }

    /**
     * Every keyless source in turn, for the mints still unpriced.
     *
     * Ordered by independence from whatever just failed rather than by
     * preference: DefiLlama first because it shares no infrastructure with the
     * DEX aggregators, Jupiter second because it is the most reliable observed
     * provider in this codebase but is still a Solana-routing service and can
     * fail alongside them.
     *
     * Callers pass the mints DexScreener did NOT answer for, so a healthy
     * DexScreener costs nothing here.
     */
    fun fillMissing(missing: List<String>): Map<String, Double> {
        if (missing.isEmpty()) return emptyMap()
        val out = HashMap<String, Double>(missing.size)
        try {
            out.putAll(defiLlamaBatch(missing))
            val still = missing.filter { it !in out.keys }
            if (still.isNotEmpty()) out.putAll(jupiterBatch(still))
        } catch (_: Throwable) {}
        if (out.isNotEmpty()) {
            try {
                PipelineHealthCollector.labelInc("KEYLESS_MARK_RESCUE_6996")
                ErrorLogger.info(
                    TAG,
                    "keyless mark rescue: ${out.size}/${missing.size} priced after primary source returned nothing",
                )
            } catch (_: Throwable) {}
        }
        return out
    }

    private fun get(url: String, host: String): String? = try {
        val req = Request.Builder().url(url)
            .header("User-Agent", "lifecycle-bot-android/6.0")
            .header("Accept", "application/json")
            .build()
        HealthAwareHttp.execute(http, req, host = host).use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (_: Exception) { null }
}
