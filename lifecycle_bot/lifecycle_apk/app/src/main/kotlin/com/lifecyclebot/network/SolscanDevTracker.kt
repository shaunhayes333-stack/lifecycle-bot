package com.lifecyclebot.network

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Solscan dev wallet tracker.
 *
 * Uses Solscan's free public API to:
 *   1. Monitor a dev wallet's token balance changes
 *   2. Detect when the dev starts selling their allocation
 *   3. Track SOL inflows to dev wallet (selling into SOL = rug prep)
 *
 * Free API: https://public-api.solscan.io
 * No key required for basic endpoints. Rate limit: 5 req/sec.
 *
 * Dev sell detection is one of the most reliable exit signals
 * for meme coins — when the dev sells, get out fast.
 */
class SolscanDevTracker {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val BASE = "https://public-api.solscan.io"

    // Cache: wallet+mint → last known token balance
    private val lastKnownBalance = mutableMapOf<String, Double>()
    // Cache: wallet → last tx signature we've seen
    private val lastSeenSig = mutableMapOf<String, String>()

    data class DevActivity(
        val wallet: String,
        val mint: String,
        val currentTokenBalance: Double,
        val previousTokenBalance: Double,
        val solBalance: Double,
        val recentSellSol: Double,         // SOL received from selling tokens recently
        val isSelling: Boolean,            // actively reducing token position
        val sellPct: Double,               // what % of their position they've sold
        val alertLevel: String,            // "none" | "watch" | "warning" | "critical"
    )

    // ── check dev wallet ──────────────────────────────────────────────

    /**
     * Check if the dev wallet is selling their token allocation.
     * Call this every few minutes while in a position.
     */
    fun checkDevWallet(devWallet: String, tokenMint: String): DevActivity? {
        if (devWallet.isBlank() || tokenMint.isBlank()) return null

        val tokenBalance = getTokenBalance(devWallet, tokenMint) ?: return null
        val solBalance   = getSolBalance(devWallet) ?: 0.0
        val key          = "$devWallet:$tokenMint"
        val prevBalance  = lastKnownBalance[key] ?: tokenBalance

        val sold         = (prevBalance - tokenBalance).coerceAtLeast(0.0)
        val sellPct      = if (prevBalance > 0) sold / prevBalance else 0.0

        // Update tracking
        lastKnownBalance[key] = tokenBalance

        val alertLevel = when {
            sellPct >= 0.50 -> "critical"    // dev sold 50%+ of position
            sellPct >= 0.25 -> "warning"     // dev sold 25%+
            sellPct >= 0.10 -> "watch"       // dev sold 10%+
            else            -> "none"
        }

        return DevActivity(
            wallet               = devWallet,
            mint                 = tokenMint,
            currentTokenBalance  = tokenBalance,
            previousTokenBalance = prevBalance,
            solBalance           = solBalance,
            recentSellSol        = 0.0,  // would need tx history to calculate precisely
            isSelling            = sellPct > 0.05,
            sellPct              = sellPct,
            alertLevel           = alertLevel,
        )
    }

    /**
     * Get recent transactions for a wallet.
     * Used to detect if dev just received large SOL amounts (selling tokens).
     */
    fun getRecentTransactions(wallet: String, limit: Int = 10): List<DevTx> {
        val url  = "$BASE/v2/account/transactions?account=$wallet&limit=$limit"
        val body = get(url) ?: return emptyList()
        return try {
            val json  = JSONObject(body)
            val items = json.optJSONArray("data") ?: return emptyList()
            (0 until items.length()).mapNotNull { i ->
                val item = items.optJSONObject(i) ?: return@mapNotNull null
                val sig  = item.optString("txHash", "")
                if (sig == lastSeenSig[wallet]) return emptyList() // caught up
                DevTx(
                    signature  = sig,
                    blockTime  = item.optLong("blockTime", 0L),
                    fee        = item.optDouble("fee", 0.0) / 1e9,
                    status     = item.optString("status", ""),
                )
            }.also {
                it.firstOrNull()?.signature?.let { sig -> lastSeenSig[wallet] = sig }
            }
        } catch (_: Exception) { emptyList() }
    }

    // ── token balance ─────────────────────────────────────────────────

    private fun getTokenBalance(wallet: String, mint: String): Double? {
        val url  = "$BASE/v2/account/token-accounts?address=$wallet&type=token"
        val body = get(url) ?: return null
        return try {
            val items = JSONObject(body).optJSONArray("data") ?: return null
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                if (item.optString("tokenAddress", "") == mint) {
                    return item.optJSONObject("tokenAmount")
                        ?.optDouble("uiAmount", 0.0)
                }
            }
            0.0  // wallet holds none
        } catch (_: Exception) { null }
    }

    // ── SOL balance ───────────────────────────────────────────────────

    private fun getSolBalance(wallet: String): Double? {
        val url  = "$BASE/v2/account?address=$wallet"
        val body = get(url) ?: return null
        return try {
            val lamports = JSONObject(body).optJSONObject("data")
                ?.optLong("lamports", 0L) ?: 0L
            lamports / 1e9
        } catch (_: Exception) { null }
    }

    // ── helper ────────────────────────────────────────────────────────

    private fun get(url: String): String? = try {
        val req  = Request.Builder().url(url)
            .header("accept", "application/json")
            .header("User-Agent", "LifecycleBot/1.0")
            .build()
        val resp = http.newCall(req).execute()
        if (resp.isSuccessful) resp.body?.string() else null
    } catch (_: Exception) { null }

    data class DevTx(
        val signature: String,
        val blockTime: Long,
        val fee: Double,
        val status: String,
    )
}
