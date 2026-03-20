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

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json".toMediaType()
    // Cache: wallet → result
    private val cache = mutableMapOf<String, CreatorReport>()

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
        if (apiKey.isBlank()) return emptyList()
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
            JSONObject(body).optInt("score", -1)
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

    private fun post(url: String, body: JSONObject): String? = try {
        val req = Request.Builder().url(url)
            .post(body.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .build()
        val resp = http.newCall(req).execute()
        if (resp.isSuccessful) resp.body?.string() else null
    } catch (_: Exception) { null }
}
