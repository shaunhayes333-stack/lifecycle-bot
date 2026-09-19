package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7075 §SUPPLY IS AN ON-CHAIN FACT, NOT SOMETHING TO INFER.
 *
 * Operator: "I want proper fucking data every where. I been pretty clear."
 *
 * THE CIRCULARITY I SHIPPED IN V5.0.7069
 * ======================================
 * 7069 was right that marketCap = price x supply is the identity that makes
 * price and cap check each other. It was wrong about where supply comes from.
 * It INFERRED supply from the first observation carrying both numbers:
 *
 *     supply = mcap / price          <- from an UNVERIFIED price
 *
 * and then used that supply to "verify" every later price. If the first price
 * was wrong, the supply is wrong by the same factor, and the identity then
 * locks the error in and repairs correct prices TOWARDS the wrong one. A check
 * derived from the thing it is checking is not a check.
 *
 * The operator's 5.0.7072 device report shows both halves of that:
 *
 *   supplyCaptured=63           against 2518 live rows — 2.5% coverage, so
 *                               97% of marks were passing unverified
 *   identityBroken=1795         the check firing hard where it DID have supply
 *   8A6dzN partial_25pct        sol=2.0798 from cost=0.0144, a 144x, while
 *                               EXIT_TRIGGER_BASIS_REBASED_4481 reported
 *                               rawGain=14429.6% mcapGain=0.0% on the SAME mint
 *                               seconds later — the cap had not moved at all
 *
 * THE FIX IS TO GO AND GET THE REAL NUMBER.
 * `getTokenSupply` is a standard Solana RPC method. It returns the mint's
 * actual supply and decimals from chain state. It is not an estimate, it does
 * not depend on any price, and it cannot be corrupted by a bad tick. Helius is
 * live on this device at sr=100%, and TelegramScraper has been making the same
 * shape of RPC call for the holder-concentration check for many builds.
 *
 * PROVENANCE IS CARRIED, NOT ASSUMED. TokenMetricsAuthority7069 now knows
 * whether a supply is ONCHAIN or INFERRED, and only an ONCHAIN supply — or an
 * inferred one CORROBORATED by independent observations — is allowed to repair
 * a price. An uncorroborated inference marks the mark unverifiable and changes
 * nothing, which is the honest answer when the second opinion is really just
 * the first one wearing a hat.
 *
 * NOT ON THE HOT PATH. Resolution is async, once per mint, cached forever, and
 * fired from the metrics authority when it first sees a mint it cannot verify.
 * Nothing waits on it: the price returns immediately, unverified, and becomes
 * verifiable when chain state arrives. HOT_PATH_PROVIDER_CALL_SENTINEL_4295
 * exists for exactly the mistake this avoids.
 */
object OnChainSupplyAuthority7075 {

    private const val TAG = "OnChainSupply7075"

    /** One in-flight resolve per mint; a second request is dropped, not queued. */
    private val inFlight = ConcurrentHashMap<String, AtomicBoolean>()

    /** Resolved supplies. Absent means not yet known, never "zero supply". */
    private val resolved = ConcurrentHashMap<String, Double>()

    private val requested = AtomicLong(0L)
    private val fetched = AtomicLong(0L)
    private val failed = AtomicLong(0L)
    private val corrections = AtomicLong(0L)

    @Volatile
    private var rpcUrl: String = ""

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Supply the RPC endpoint once at bootstrap. Until this is set the
     * authority simply reports nothing known — it never guesses a URL and never
     * blocks a caller waiting for one.
     */
    fun installRpc7075(url: String) {
        if (url.isBlank()) return
        rpcUrl = url
        try { PipelineHealthCollector.labelInc("ONCHAIN_SUPPLY_RPC_INSTALLED_7075") } catch (_: Throwable) {}
    }

    /** Resolved on-chain supply in whole tokens, or 0.0 when not yet known. */
    fun supplyOf7075(mint: String): Double {
        val s = resolved[mint] ?: return 0.0
        return if (s.isFinite() && s > 0.0) s else 0.0
    }

    /**
     * Ask for [mint]'s supply. Returns immediately. The caller is never blocked
     * and never receives a value from this call — the answer lands in the cache
     * and in TokenMetricsAuthority7069 when chain state replies.
     */
    fun requestAsync7075(mint: String) {
        if (mint.isBlank() || rpcUrl.isBlank()) return
        if (resolved.containsKey(mint)) return
        val gate = inFlight.computeIfAbsent(mint) { AtomicBoolean(false) }
        if (!gate.compareAndSet(false, true)) return
        requested.incrementAndGet()
        Thread {
            try {
                val supply = fetchSupply(mint)
                if (supply != null && supply.isFinite() && supply > 0.0) {
                    resolved[mint] = supply
                    fetched.incrementAndGet()
                    try {
                        PipelineHealthCollector.labelInc("ONCHAIN_SUPPLY_RESOLVED_7075")
                        val replaced = TokenMetricsAuthority7069.acceptOnChainSupply7075(mint, supply)
                        if (replaced) {
                            corrections.incrementAndGet()
                            PipelineHealthCollector.labelInc("ONCHAIN_SUPPLY_CORRECTED_INFERENCE_7075")
                            ForensicLogger.lifecycle(
                                "ONCHAIN_SUPPLY_CORRECTED_INFERENCE_7075",
                                "mint=${mint.take(10)} onChainSupply=${supply.toLong()} " +
                                    "action=chain_state_replaces_price_derived_guess",
                            )
                        }
                    } catch (_: Throwable) {}
                } else {
                    failed.incrementAndGet()
                    try { PipelineHealthCollector.labelInc("ONCHAIN_SUPPLY_UNRESOLVED_7075") } catch (_: Throwable) {}
                }
            } catch (t: Throwable) {
                failed.incrementAndGet()
                try { ErrorLogger.debug(TAG, "resolve fail ${mint.take(10)}: ${t.message?.take(60)}") } catch (_: Throwable) {}
            } finally {
                gate.set(false)
            }
        }.apply { isDaemon = true; name = "onchain-supply-7075" }.start()
    }

    /**
     * `getTokenSupply` returns amount as a base-unit STRING and the decimals
     * separately. Whole tokens is amount / 10^decimals, and the string is read
     * as a string precisely because a large base-unit amount does not survive a
     * Double round trip — the defect class this whole authority exists to stop.
     */
    private fun fetchSupply(mint: String): Double? {
        val url = rpcUrl
        if (url.isBlank()) return null
        val payload = """{"jsonrpc":"2.0","id":1,"method":"getTokenSupply","params":["$mint"]}"""
        val req = Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val value = JSONObject(body).optJSONObject("result")?.optJSONObject("value") ?: return null
            val decimals = value.optInt("decimals", -1)
            if (decimals !in 0..18) return null
            val raw = value.optString("amount", "")
            if (raw.isBlank()) return null
            val big = try { java.math.BigDecimal(raw) } catch (_: Throwable) { return null }
            val whole = big.movePointLeft(decimals).toDouble()
            return if (whole.isFinite() && whole > 0.0) whole else null
        }
    }

    /** Diagnostic line for the pipeline report. */
    fun status(): String =
        "rpc=${if (rpcUrl.isBlank()) "unset" else "set"} requested=${requested.get()} " +
            "resolved=${fetched.get()} failed=${failed.get()} " +
            "correctedInference=${corrections.get()} cached=${resolved.size}"
}
