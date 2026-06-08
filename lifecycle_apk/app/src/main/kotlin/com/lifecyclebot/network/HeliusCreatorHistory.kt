package com.lifecyclebot.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Helius creator history checker.
 *
 * Given a token's creator (dev) wallet address, we:
 *   1. Fetch all tokens this wallet has previously created via Helius DAS API
 *   2. For each previous token, check its current status on Rugcheck
 *   3. Build a rug history score: how many of their past tokens rugged?
 *
 * This is one of the most powerful pre-trade checks available:
 * a dev who has rugged 3 times is almost certainly going to rug again.
 *
 * Helius DAS API: free tier, 100k credits/day
 * Endpoint: https://mainnet.helius-rpc.com/?api-key=YOUR_KEY
 */
class HeliusCreatorHistory(private val apiKey: String) {

    private val http = SharedHttpClient.builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json".toMediaType()
    // V5.9.949 — shared static cache. Was per-instance, which meant every
    // DataOrchestrator reboot started cold and re-paid ~11 round-trips
    // (Helius DAS + up to 10 rugcheck queries) per dev wallet. Now backed
    // by a companion-scope ConcurrentHashMap so an instance switch keeps
    // the cache hot, and LearningPersistence persists it across restarts.
    private val cache get() = sharedCache

    data class CreatorReport(
        val walletAddress: String,
        val tokensCreated: Int,
        val ruggedTokens: Int,               // confirmed rugged (rugcheck score < 40)
        val suspiciousTokens: Int,            // borderline (40-70)
        val avgRugcheckScore: Double,
        val rugRate: Double,                  // 0.0 to 1.0
        val isKnownRugger: Boolean,           // rugged 2+ times
        val previousTokens: List<PrevToken>,
        val checkedAt: Long = System.currentTimeMillis(),
    ) {
        val isStale get() = System.currentTimeMillis() - checkedAt > 30 * 60_000L
        val riskLevel: String get() = when {
            isKnownRugger          -> "HIGH — known rugger"
            rugRate > 0.5          -> "HIGH — >50% rug rate"
            suspiciousTokens >= 2  -> "MEDIUM — multiple suspicious tokens"
            tokensCreated == 0     -> "UNKNOWN — no history"
            else                   -> "LOW — clean history"
        }
    }

    data class PrevToken(
        val mint: String,
        val name: String,
        val rugcheckScore: Int,
        val isRugged: Boolean,
    )

    // ── public interface ──────────────────────────────────────────────

    fun getCreatorReport(devWalletAddress: String): CreatorReport {
        if (devWalletAddress.isBlank()) return emptyReport(devWalletAddress)
        val cached = cache[devWalletAddress]
        if (cached != null && !cached.isStale) return cached

        val tokens = fetchCreatedTokens(devWalletAddress)
        if (tokens.isEmpty()) {
            val r = emptyReport(devWalletAddress)
            cache[devWalletAddress] = r
            return r
        }

        // Check each token on rugcheck (limit to last 10 to avoid hammering API)
        val checked = tokens.take(10).map { mint ->
            val score = fetchRugcheckScore(mint.first)
            PrevToken(
                mint           = mint.first,
                name           = mint.second,
                rugcheckScore  = score,
                isRugged       = score in 0..39,
            )
        }

        val rugged     = checked.count { it.isRugged }
        val suspicious = checked.count { it.rugcheckScore in 40..69 }
        val avg        = if (checked.isNotEmpty())
            checked.filter { it.rugcheckScore >= 0 }.map { it.rugcheckScore.toDouble() }.average()
        else -1.0

        val report = CreatorReport(
            walletAddress     = devWalletAddress,
            tokensCreated     = tokens.size,
            ruggedTokens      = rugged,
            suspiciousTokens  = suspicious,
            avgRugcheckScore  = avg,
            rugRate           = if (checked.isNotEmpty()) rugged.toDouble() / checked.size else 0.0,
            isKnownRugger     = rugged >= 2,
            previousTokens    = checked,
        )
        cache[devWalletAddress] = report
        return report
    }

    // ── Helius DAS: fetch all tokens minted by this wallet ────────────

    private fun fetchCreatedTokens(wallet: String): List<Pair<String, String>> {
        if (!com.lifecyclebot.engine.KeyValidator.isUsableEnhancedHeliusKey(apiKey)) return emptyList()  // V5.9.1340
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", "creator-check")
            put("method", "getAssetsByCreator")
            put("params", JSONObject().apply {
                put("creatorAddress", wallet)
                put("onlyVerified", false)
                put("page", 1)
                put("limit", 20)
            })
        }
        val body = post("https://mainnet.helius-rpc.com/?api-key=$apiKey", payload) ?: return emptyList()
        return try {
            val items = JSONObject(body)
                .optJSONObject("result")
                ?.optJSONArray("items") ?: return emptyList()
            (0 until items.length()).mapNotNull { i ->
                val item  = items.optJSONObject(i) ?: return@mapNotNull null
                val id    = item.optString("id", "")
                val meta  = item.optJSONObject("content")
                    ?.optJSONObject("metadata")
                val name  = meta?.optString("name", "") ?: ""
                if (id.isNotBlank()) id to name else null
            }
        } catch (_: Exception) { emptyList() }
    }

    // ── Rugcheck score for a specific token ───────────────────────────

    private fun fetchRugcheckScore(mint: String): Int {
        return try {
            val req  = Request.Builder()
                .url("https://api.rugcheck.xyz/v1/tokens/$mint/report/summary")
                .header("Accept", "application/json")
                .build()
            val resp = http.newCall(req).execute()
            val body = resp.body?.string() ?: return -1
            val json = JSONObject(body)
            // Use score_normalised (0-100), fallback to raw score only if not present
            json.optInt("score_normalised", json.optInt("score", -1))
        } catch (_: Exception) { -1 }
    }

    // ── helpers ───────────────────────────────────────────────────────

    private fun emptyReport(wallet: String) = CreatorReport(
        walletAddress     = wallet,
        tokensCreated     = 0,
        ruggedTokens      = 0,
        suspiciousTokens  = 0,
        avgRugcheckScore  = -1.0,
        rugRate           = 0.0,
        isKnownRugger     = false,
        previousTokens    = emptyList(),
    )

    private fun post(url: String, body: JSONObject): String? {
        // V5.9.866 — KeyValidator gate. Converted from expression body — early
        // returns aren't legal there.
        if (!com.lifecyclebot.engine.KeyValidator.isUsableEnhancedHeliusKey(apiKey)) return null  // V5.9.1340
        if (!com.lifecyclebot.engine.KeyValidator.isLive("helius")) return null
        // V5.9.1443 — API BACKOFF GATE (reuse the existing breaker that groq +
        // HeliusEnhancedWS already use). 5.0.3444 logged helius 83×4xx / 0 success;
        // each blocking call ate the 5s supervisor-worker budget → workers force-
        // released before any trade decision ("parked at 140"). Fail fast while
        // locked out; ApiBackoff escalates 5s→120s and auto-recovers on success.
        if (com.lifecyclebot.engine.ApiBackoff.isLockedOut("helius")) return null

        return try {
            val effectiveUrl = try { com.lifecyclebot.engine.AutoEndpointMigrator.rewrite(url) } catch (_: Throwable) { url }
            val req = Request.Builder().url(effectiveUrl)
                .post(body.toString().toRequestBody(JSON))
                .header("Content-Type", "application/json")
                .build()
            val helStart = System.currentTimeMillis()
            val resp = try {
                http.newCall(req).execute()
            } catch (e: Exception) {
                try { com.lifecyclebot.engine.ApiHealthMonitor.recordNetworkError("helius", e.message) } catch (_: Throwable) {}
                try { com.lifecyclebot.engine.ApiBackoff.markFailure("helius", 0) } catch (_: Throwable) {}
                throw e
            }
            try { com.lifecyclebot.engine.ApiHealthMonitor.record("helius", resp.code, System.currentTimeMillis() - helStart) } catch (_: Throwable) {}
            try { if (resp.code in 400..599) com.lifecyclebot.engine.ApiBackoff.markFailure("helius", resp.code) else if (resp.code in 200..299) com.lifecyclebot.engine.ApiBackoff.markSuccess("helius") } catch (_: Throwable) {}
            when {
                resp.code in listOf(401, 403) -> {
                    try { com.lifecyclebot.engine.KeyValidator.recordResult("helius", success = false, httpStatus = resp.code) } catch (_: Throwable) {}
                    null
                }
                resp.isSuccessful -> {
                    try { com.lifecyclebot.engine.KeyValidator.recordResult("helius", success = true, httpStatus = resp.code) } catch (_: Throwable) {}
                    resp.body?.string()
                }
                else -> null
            }
        } catch (_: Exception) { null }
    }

    companion object {
        // V5.9.949 — process-wide shared cache + persistence hooks.
        // Used by LearningPersistence to mirror creator-history reports
        // across restarts. Cache grows unbounded by design — rug history
        // is FOREVER true; a wallet that rugged 3 times in 2024 will
        // still have rugged 3 times next year. We never need to re-pay
        // those API round-trips.
        private val sharedCache =
            java.util.concurrent.ConcurrentHashMap<String, CreatorReport>()

        fun exportState(): String {
            val arr = org.json.JSONArray()
            for ((wallet, r) in sharedCache) {
                arr.put(org.json.JSONObject().apply {
                    put("wallet", wallet)
                    put("tokens", r.tokensCreated)
                    put("rugged", r.ruggedTokens)
                    put("suspicious", r.suspiciousTokens)
                    put("avgRug", r.avgRugcheckScore)
                    put("rugRate", r.rugRate)
                    put("knownRugger", r.isKnownRugger)
                    put("checkedAt", r.checkedAt)
                    // previousTokens omitted — large + reconstructible
                })
            }
            return arr.toString()
        }

        fun importState(json: String) {
            try {
                val arr = org.json.JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val w = o.optString("wallet", "")
                    if (w.isBlank()) continue
                    sharedCache[w] = CreatorReport(
                        walletAddress = w,
                        tokensCreated = o.optInt("tokens", 0),
                        ruggedTokens = o.optInt("rugged", 0),
                        suspiciousTokens = o.optInt("suspicious", 0),
                        avgRugcheckScore = o.optDouble("avgRug", -1.0),
                        rugRate = o.optDouble("rugRate", 0.0),
                        isKnownRugger = o.optBoolean("knownRugger", false),
                        previousTokens = emptyList(),
                        checkedAt = o.optLong("checkedAt", System.currentTimeMillis()),
                    )
                }
            } catch (_: Throwable) { /* fail-open */ }
        }
    }

}
