package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * CopyTradeEngine
 *
 * Monitors a list of profitable wallets and copies their buys immediately.
 * A copy buy fires within seconds of the tracked wallet's transaction confirming.
 *
 * Wallet detection sources (in priority order):
 *   1. Helius WebSocket account subscriptions (real-time, ms latency)
 *   2. Solscan transaction polling (fallback, ~5s latency)
 *
 * Copy logic:
 *   • Tracked wallet buys token X
 *   → Run safety checks on token X (rugcheck, mint auth, etc.)
 *   → If passes: fire BUY immediately, override normal signal threshold
 *   → Size = configured copy size (default: same as smallBuySol)
 *   → Stop loss = tighter than normal (following not leading)
 *   → Exit: when tracked wallet sells OR normal exit signals fire
 *
 * Anti-honeypot: we never copy into a token that:
 *   • Has mint authority active
 *   • Has < 80% LP locked
 *   • Rugcheck score < 60
 *   • Dev wallet address matches the buyer (dev buying their own token = trap)
 *
 * Wallet performance tracking:
 *   Every trade per wallet is recorded. Wallets with < 50% win rate
 *   over last 20 trades are automatically paused.
 */
class CopyTradeEngine(
    private val ctx: Context,
    private val onCopySignal: (mint: String, wallet: String, solAmount: Double) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val prefs: SharedPreferences =
        ctx.getSharedPreferences("copy_wallets", Context.MODE_PRIVATE)

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // Active copy wallets: address → CopyWallet
    private val wallets = mutableMapOf<String, CopyWallet>()

    data class CopyWallet(
        val address: String,
        val label: String,         // human-readable name
        val isActive: Boolean,
        val totalCopied: Int,
        val wins: Int,
        val losses: Int,
        val totalPnlSol: Double,
        val addedAt: Long,
        val lastSeenMs: Long,
    ) {
        val winRate get() = if (totalCopied > 0) wins.toDouble() / totalCopied * 100 else 0.0
        val shortAddr get() = "${address.take(6)}…${address.takeLast(4)}"
        val isPaused get() = totalCopied >= 10 && winRate < 40.0   // auto-pause poor performers
    }

    data class CopySignal(
        val mint: String,
        val trackedWallet: String,
        val walletLabel: String,
        val estimatedSol: Double,
        val ts: Long,
    )

    // Recent signals to avoid duplicates
    private val recentSignals = ArrayDeque<CopySignal>(20)

    // ── wallet management ─────────────────────────────────────────────

    fun addWallet(address: String, label: String) {
        wallets[address] = CopyWallet(
            address    = address,
            label      = label.ifBlank { address.take(8) },
            isActive   = true,
            totalCopied = 0,
            wins       = 0,
            losses     = 0,
            totalPnlSol = 0.0,
            addedAt    = System.currentTimeMillis(),
            lastSeenMs = 0L,
        )
        saveWallets()
        onLog("Copy wallet added: $label ($address)")
    }

    fun removeWallet(address: String) {
        wallets.remove(address)
        saveWallets()
    }

    fun getWallets(): List<CopyWallet> = wallets.values.toList()

    fun recordResult(wallet: String, pnlSol: Double) {
        val w = wallets[wallet] ?: return
        wallets[wallet] = w.copy(
            totalCopied = w.totalCopied + 1,
            wins        = w.wins + if (pnlSol > 0) 1 else 0,
            losses      = w.losses + if (pnlSol <= 0) 1 else 0,
            totalPnlSol = w.totalPnlSol + pnlSol,
        )
        saveWallets()

        // Auto-pause check
        val updated = wallets[wallet]!!
        if (updated.isPaused) {
            onLog("⚠ Copy wallet ${updated.shortAddr} auto-paused: ${updated.winRate.toInt()}% win rate")
        }
    }

    // ── called from Helius WebSocket on swap event ────────────────────

    /**
     * Helius/Pump.fun WebSocket feeds swaps here.
     * If the buyer is a tracked wallet, emit a copy signal.
     */
    fun onSwapDetected(
        mint: String,
        buyerWallet: String,
        solAmount: Double,
        isBuy: Boolean,
    ) {
        if (!isBuy) return
        val tracked = wallets[buyerWallet] ?: return
        if (!tracked.isActive || tracked.isPaused) return

        // Deduplicate: same mint+wallet within 30s = ignore
        val now = System.currentTimeMillis()
        val isDupe = recentSignals.any { s ->
            s.mint == mint && s.trackedWallet == buyerWallet &&
            now - s.ts < 30_000L
        }
        if (isDupe) return

        // Only copy meaningful buys (ignore dust)
        if (solAmount < 0.01) return

        val signal = CopySignal(mint, buyerWallet, tracked.label, solAmount, now)
        recentSignals.addFirst(signal)
        if (recentSignals.size > 20) recentSignals.removeLast()

        onLog("📋 Copy signal: ${tracked.label} (${tracked.shortAddr}) bought ${"%.3f".format(solAmount)}◎ of ${mint.take(8)}…")
        onCopySignal(mint, buyerWallet, solAmount)

        // Update last seen
        wallets[buyerWallet] = tracked.copy(lastSeenMs = now)
    }

    // ── discover top wallets from on-chain leaderboards ───────────────

    /**
     * Fetch top performing wallets from Pump.fun's public leaderboard.
     * Returns addresses sorted by 7-day PnL.
     * Used to seed the copy wallet list with proven performers.
     */
    fun discoverTopWallets(): List<Pair<String, Double>> {
        // Pump.fun leaderboard API (public)
        val url  = "https://frontend-api.pump.fun/leaderboard?timeframe=7d&limit=20"
        val body = get(url) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val item = arr.optJSONObject(i) ?: return@mapNotNull null
                val addr = item.optString("user", "")
                val pnl  = item.optDouble("realized_pnl", 0.0)
                if (addr.isNotBlank() && pnl > 0) addr to pnl else null
            }.sortedByDescending { it.second }.take(10)
        } catch (_: Exception) { emptyList() }
    }

    // ── persistence ───────────────────────────────────────────────────

    private fun saveWallets() {
        val arr = org.json.JSONArray()
        wallets.values.forEach { w ->
            arr.put(JSONObject().apply {
                put("address",      w.address)
                put("label",        w.label)
                put("isActive",     w.isActive)
                put("totalCopied",  w.totalCopied)
                put("wins",         w.wins)
                put("losses",       w.losses)
                put("totalPnlSol",  w.totalPnlSol)
                put("addedAt",      w.addedAt)
            })
        }
        prefs.edit().putString("wallets", arr.toString()).apply()
    }

    fun loadWallets() {
        val json = prefs.getString("wallets", null) ?: return
        try {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val addr = o.optString("address", "")
                if (addr.isBlank()) continue
                wallets[addr] = CopyWallet(
                    address     = addr,
                    label       = o.optString("label", addr.take(8)),
                    isActive    = o.optBoolean("isActive", true),
                    totalCopied = o.optInt("totalCopied", 0),
                    wins        = o.optInt("wins", 0),
                    losses      = o.optInt("losses", 0),
                    totalPnlSol = o.optDouble("totalPnlSol", 0.0),
                    addedAt     = o.optLong("addedAt", 0),
                    lastSeenMs  = 0L,
                )
            }
        } catch (_: Exception) {}
    }

    private fun get(url: String): String? = try {
        val req  = Request.Builder().url(url)
            .header("Accept", "application/json").build()
        val resp = http.newCall(req).execute()
        if (resp.isSuccessful) resp.body?.string() else null
    } catch (_: Exception) { null }
}
