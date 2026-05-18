package com.lifecyclebot.engine

import com.lifecyclebot.network.SharedHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * V5.9.909 — BIRDEYE TOKEN SECURITY PROVIDER (no API key required).
 *
 * Endpoint:
 *   GET https://public-api.birdeye.so/defi/token_security?address={mint}
 *   Headers: x-chain: solana
 *
 * Operator verified (V5.9.909 paste) that this endpoint returns full
 * on-chain security profile data WITHOUT an API key.
 *
 * PURPOSE
 *   Provides a per-token TRUST score (0.0-1.0) derived from on-chain
 *   security signals that no other source exposes:
 *     - freezeAuthority (kill switch)
 *     - mutableMetadata (honey trap)
 *     - top10HolderPercent (concentration risk)
 *     - creatorPercentage (dev exit liquidity)
 *     - transferFeeEnable / transferFeeData (honeypot detector)
 *     - nonTransferable (literal honeypot)
 *     - isToken2022 (extension risk)
 *     - jupStrictList (Jupiter curated whitelist)
 *     - lockInfo (LP lock status)
 *     - fakeToken (birdeye-flagged duplicate)
 *
 * DOCTRINE (memory #86 #87)
 *   Soft-shape multiplier source. Trust score in [0.5, 1.0]:
 *     1.0 = neutral / clean / unknown (FAIL-OPEN)
 *     0.7 = moderate risk
 *     0.5 = severe risk (floor — never veto via this path)
 *
 *   Hard vetoes for confirmed rug stay in quickRugcheck / FDG.
 *   FAIL-OPEN: every error returns 1.0 neutral. Bot NEVER stops
 *   trading because birdeye had a hiccup (active-instruction #2).
 *
 * CACHING
 *   30-minute TTL per mint. Cache trims at 5000 entries.
 *
 * USAGE PATTERN (consumers wire in V5.9.910+)
 *   suspend fun beforeBuy(mint: String, baseSize: Double): Double {
 *     val trust = BirdeyeSecurityProvider.getTrust(mint)
 *     return baseSize * trust  // composes multiplicatively
 *   }
 *
 *   Per active-instruction #2 (fail-open) and #8 (background threads),
 *   getTrust() always runs on Dispatchers.IO with timeout.
 */
object BirdeyeSecurityProvider {
    private const val TAG = "BirdeyeSecurity"
    private const val CACHE_TTL_MS = 30L * 60_000L
    private const val MAX_CACHE_SIZE = 5_000
    private const val REQUEST_TIMEOUT_MS = 1500L
    private const val NEUTRAL_TRUST = 1.0
    private const val MIN_TRUST = 0.5

    private data class CachedSecurity(
        val trust: Double,
        val timestamp: Long,
        val freezeAuth: Boolean,
        val mutableMeta: Boolean,
        val honeypot: Boolean,
        val top10Pct: Double,
        val jupStrict: Boolean,
    )

    private val cache = ConcurrentHashMap<String, CachedSecurity>()

    private val http = SharedHttpClient.builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(1, TimeUnit.SECONDS)
        .build()

    /**
     * Returns trust score in [MIN_TRUST, 1.0]. Fail-open on any error.
     * Safe to call from any thread.
     */
    suspend fun getTrust(mint: String): Double {
        if (mint.isBlank()) return NEUTRAL_TRUST
        val cached = cache[mint]
        val now = System.currentTimeMillis()
        if (cached != null && (now - cached.timestamp) < CACHE_TTL_MS) {
            return cached.trust
        }

        return try {
            val sec = withContext(Dispatchers.IO) {
                withTimeoutOrNull(REQUEST_TIMEOUT_MS) { fetchAndScore(mint) }
            }
            sec?.trust ?: NEUTRAL_TRUST
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "getTrust fail-open for ${mint.take(10)}: ${e.message?.take(40)}")
            NEUTRAL_TRUST
        }
    }

    /** Returns cached snapshot, null if not yet fetched / expired. Does NOT trigger a fetch. */
    fun peekCached(mint: String): SecuritySnapshot? {
        val c = cache[mint] ?: return null
        val now = System.currentTimeMillis()
        if ((now - c.timestamp) >= CACHE_TTL_MS) return null
        return SecuritySnapshot(
            trust = c.trust,
            freezeAuth = c.freezeAuth,
            mutableMeta = c.mutableMeta,
            honeypot = c.honeypot,
            top10Pct = c.top10Pct,
            jupStrict = c.jupStrict,
        )
    }

    /** Public snapshot for telemetry consumers. */
    data class SecuritySnapshot(
        val trust: Double,
        val freezeAuth: Boolean,
        val mutableMeta: Boolean,
        val honeypot: Boolean,
        val top10Pct: Double,
        val jupStrict: Boolean,
    )

    private fun fetchAndScore(mint: String, apiKey: String): CachedSecurity? {
        val url = "https://public-api.birdeye.so/defi/token_security?address=$mint"
        val reqBuilder = Request.Builder().url(url)
            .header("x-chain", "solana")
            .header("accept", "application/json")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            )
        if (apiKey.isNotBlank()) {
            reqBuilder.header("X-API-KEY", apiKey)
        }
        val req = reqBuilder.build()

        val body = try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    ErrorLogger.debug(TAG, "HTTP ${resp.code} for ${mint.take(10)}")
                    return null
                }
                resp.body?.string()
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "fetch error for ${mint.take(10)}: ${e.message?.take(40)}")
            return null
        } ?: return null

        if (!body.trim().startsWith("{")) return null
        val root = try { JSONObject(body) } catch (_: Exception) { return null }
        if (!root.optBoolean("success", false)) return null
        val data = root.optJSONObject("data") ?: return null

        // SCORING MODEL — start at 1.0 neutral, multiply down per risk factor.
        // Floor at MIN_TRUST so this path NEVER vetoes.
        var trust = 1.0

        // === HONEYPOT SIGNALS ===
        val nonTransferable = data.optBoolean("nonTransferable", false)
        if (nonTransferable) trust *= 0.60

        val freezeAuth = data.opt("freezeAuthority")?.toString()?.let {
            it.isNotBlank() && it != "null"
        } ?: false
        if (freezeAuth) trust *= 0.65

        val transferFeeEnable = data.opt("transferFeeEnable")?.toString()?.let {
            it == "true"
        } ?: false
        if (transferFeeEnable) trust *= 0.75

        // === STRUCTURAL RISK ===
        val mutableMeta = data.optBoolean("mutableMetadata", false)
        if (mutableMeta) trust *= 0.92

        val isToken2022 = data.optBoolean("isToken2022", false)
        if (isToken2022) trust *= 0.95

        // === CONCENTRATION RISK ===
        val top10Pct = data.optDouble("top10HolderPercent", 0.0)
            .let { if (it.isFinite()) it else 0.0 }
        when {
            top10Pct > 0.80 -> trust *= 0.70
            top10Pct > 0.60 -> trust *= 0.85
            top10Pct > 0.40 -> trust *= 0.92
        }

        val creatorPct = data.optDouble("creatorPercentage", 0.0)
            .let { if (it.isFinite()) it else 0.0 }
        when {
            creatorPct > 0.10 -> trust *= 0.75
            creatorPct > 0.05 -> trust *= 0.88
        }

        // === LP LOCK ===
        val hasLockInfo = data.opt("lockInfo")?.toString()?.let {
            it.isNotBlank() && it != "null" && it != "{}"
        } ?: false
        if (!hasLockInfo) trust *= 0.95

        // === BIRDEYE-FLAGGED REPUTATION ===
        val fakeToken = data.optBoolean("fakeToken", false)
        if (fakeToken) trust *= 0.55

        val jupStrict = data.optBoolean("jupStrictList", false)
        if (jupStrict) trust = (trust * 1.10).coerceAtMost(1.0)

        // === FLOOR ===
        trust = trust.coerceIn(MIN_TRUST, 1.0)

        val honeypot = nonTransferable || transferFeeEnable

        val entry = CachedSecurity(
            trust = trust,
            timestamp = System.currentTimeMillis(),
            freezeAuth = freezeAuth,
            mutableMeta = mutableMeta,
            honeypot = honeypot,
            top10Pct = top10Pct,
            jupStrict = jupStrict,
        )

        if (cache.size >= MAX_CACHE_SIZE) {
            val cutoff = cache.values.minByOrNull { it.timestamp }?.timestamp ?: 0L
            val toRemove = mutableListOf<String>()
            for ((k, v) in cache) {
                if (v.timestamp <= cutoff + 60_000L) toRemove += k
                if (toRemove.size > 500) break
            }
            for (k in toRemove) cache.remove(k)
        }
        cache[mint] = entry

        if (trust < 0.80) {
            ErrorLogger.info(
                TAG,
                "🛡 ${mint.take(10)}: trust=${"%.2f".format(trust)} " +
                    "freeze=$freezeAuth honey=$honeypot " +
                    "top10=${"%.0f".format(top10Pct * 100)}% jupStrict=$jupStrict"
            )
        }

        return entry
    }

    fun clearCache() { cache.clear() }
    fun cacheSize(): Int = cache.size
}
