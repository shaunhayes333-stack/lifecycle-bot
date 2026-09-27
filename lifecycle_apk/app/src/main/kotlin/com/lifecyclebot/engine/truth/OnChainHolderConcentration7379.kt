package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ApiBackoff
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.HealthAwareHttp
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.RuntimeProviderAuthority6685
import com.lifecyclebot.network.SharedHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * V5.0.7379 — top-10 holder concentration read from chain, for when rugcheck has none.
 *
 * Holder data came from Birdeye (key dead, 401) and rugcheck's topHolders.top10Pct,
 * which fresh launches often lack. The pre-trade gate then carried
 * HOLDER_DATA_UNKNOWN / HOLDER_DISTRIBUTION_PENDING on nearly every fresh meme.
 *
 * getTokenLargestAccounts + getTokenSupply answer this on any Solana RPC, free. The
 * catch is that the largest token accounts of a fresh token are its pump.fun bonding
 * curve or its pool vaults, and counting them would read 80%+ and hard-block every
 * launch. Those accounts are owned by program-derived addresses, which by
 * construction are NOT points on the ed25519 curve; a person's wallet always is. So
 * only token accounts whose owner is on-curve are counted, summed per owner, top 10
 * of those as a percentage of supply — the same quantity rugcheck's top10Pct reports.
 *
 * Null when the chain could not be read; the caller keeps its "unknown" path.
 */
object OnChainHolderConcentration7379 {

    private val http by lazy {
        SharedHttpClient.builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private data class Cached(val pct: Double, val atMs: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val missAt = ConcurrentHashMap<String, Long>()
    private const val TTL_MS = 10 * 60_000L
    private const val MISS_RETRY_MS = 60_000L

    fun top10Pct(mint: String): Double? {
        if (mint.isBlank()) return null
        val now = System.currentTimeMillis()
        cache[mint]?.let { if (now - it.atMs < TTL_MS) return it.pct }
        missAt[mint]?.let { if (now - it < MISS_RETRY_MS) return null }
        val pct = try { compute(mint) } catch (_: Throwable) { null }
        if (pct == null) {
            if (missAt.size > 5_000) missAt.clear()
            missAt[mint] = now
            try { PipelineHealthCollector.labelInc("ONCHAIN_HOLDER_CONCENTRATION_UNAVAILABLE_7379") } catch (_: Throwable) {}
            return null
        }
        if (cache.size > 5_000) cache.clear()
        cache[mint] = Cached(pct, now)
        try { PipelineHealthCollector.labelInc("ONCHAIN_HOLDER_CONCENTRATION_RESOLVED_7379") } catch (_: Throwable) {}
        return pct
    }

    private fun compute(mint: String): Double? {
        val supplyRaw = rpc("getTokenSupply", JSONArray().put(mint))
            ?.optJSONObject("value")?.optString("amount", "")
            ?.toBigIntegerOrNull()?.takeIf { it.signum() > 0 } ?: return null
        val largest = rpc("getTokenLargestAccounts", JSONArray().put(mint))
            ?.optJSONArray("value") ?: return null
        if (largest.length() == 0) return null
        val accounts = (0 until largest.length()).mapNotNull { i ->
            val o = largest.optJSONObject(i) ?: return@mapNotNull null
            val addr = o.optString("address", "")
            val amt = o.optString("amount", "").toBigIntegerOrNull() ?: return@mapNotNull null
            if (addr.isBlank() || amt.signum() <= 0) null else addr to amt
        }
        if (accounts.isEmpty()) return null
        val infos = rpc(
            "getMultipleAccounts",
            JSONArray().put(JSONArray(accounts.map { it.first })).put(JSONObject().put("encoding", "jsonParsed")),
        )?.optJSONArray("value") ?: return null
        val byOwner = HashMap<String, BigInteger>()
        var excludedPda = 0
        for (i in accounts.indices) {
            val owner = infos.optJSONObject(i)?.optJSONObject("data")?.optJSONObject("parsed")
                ?.optJSONObject("info")?.optString("owner", "").orEmpty()
            if (owner.isBlank()) continue
            if (!isOnCurve(owner)) { excludedPda++; continue }
            byOwner[owner] = (byOwner[owner] ?: BigInteger.ZERO).add(accounts[i].second)
        }
        val top10 = byOwner.values.sortedDescending().take(10).fold(BigInteger.ZERO) { a, b -> a.add(b) }
        val pct = top10.toBigDecimal().multiply(java.math.BigDecimal(100))
            .divide(supplyRaw.toBigDecimal(), 4, java.math.RoundingMode.HALF_UP).toDouble().coerceIn(0.0, 100.0)
        try {
            ForensicLogger.lifecycle(
                "ONCHAIN_HOLDER_CONCENTRATION_7379",
                "mint=${mint.take(10)} top10Pct=${"%.1f".format(pct)} wallets=${byOwner.size} programAccountsExcluded=$excludedPda",
            )
        } catch (_: Throwable) {}
        return pct
    }

    private fun rpc(method: String, params: JSONArray): JSONObject? {
        val helius = RuntimeProviderAuthority6685.configuredHeliusRpc()
        val endpoints = LinkedHashSet<String>().apply {
            if (helius.isNotBlank()) add(helius)
            addAll(RuntimeProviderAuthority6685.rpcCandidates())
        }.take(4)
        val body = JSONObject().put("jsonrpc", "2.0").put("id", "holders-7379")
            .put("method", method).put("params", params).toString()
        for (rpc in endpoints) {
            val key = if (rpc.contains("helius", true)) "helius" else
                "solana_rpc:" + (try { java.net.URI(rpc).host } catch (_: Throwable) { null } ?: rpc)
            if (ApiBackoff.isLockedOut(key)) continue
            try {
                val req = Request.Builder().url(rpc)
                    .post(body.toRequestBody("application/json".toMediaType())).build()
                HealthAwareHttp.execute(http, req, host = key).use { resp ->
                    if (!resp.isSuccessful) return@use
                    val json = JSONObject(resp.body?.string().orEmpty())
                    if (json.has("error")) return@use
                    json.optJSONObject("result")?.let { return it }
                }
            } catch (_: Throwable) { /* next endpoint */ }
        }
        return null
    }

    // ── ed25519: is this 32-byte public key a point on the curve? ──────────────
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val D: BigInteger = BigInteger.valueOf(-121665).mod(P)
        .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)

    /** True for a normal wallet key; false for a program-derived address (off-curve). */
    internal fun isOnCurve(base58: String): Boolean {
        val bytes = try { io.github.novacrypto.base58.Base58.base58Decode(base58) } catch (_: Throwable) { return true }
        if (bytes.size != 32) return true
        val le = bytes.copyOf()
        le[31] = (le[31].toInt() and 0x7f).toByte()
        val y = BigInteger(1, le.reversedArray())
        if (y >= P) return false
        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(BigInteger.ONE).mod(P)
        val v = D.multiply(y2).add(BigInteger.ONE).mod(P)
        val x2 = u.multiply(v.modInverse(P)).mod(P)
        if (x2.signum() == 0) return true
        // Euler's criterion: x2 is a square mod p iff x2^((p-1)/2) == 1.
        return x2.modPow(P.subtract(BigInteger.ONE).shiftRight(1), P) == BigInteger.ONE
    }
}
