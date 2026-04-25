package com.lifecyclebot.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * WALLET TOKEN MEMORY — V5.9.256
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * PURPOSE
 * ───────
 * Persistent journal of every token the bot has ever bought, surviving across
 * app restarts, updates, and crashes. On startup, the StartupReconciler walks
 * the live wallet, sees tokens that are in memory but NOT in status.tokens
 * (scanner hasn't re-discovered them yet), and uses this journal to reconstruct
 * a full TokenState + Position so the bot can manage exits immediately.
 *
 * WITHOUT THIS:
 *   Restart → scanner not running yet → status.tokens is empty → StartupReconciler
 *   sees live wallet tokens → can't find them in status.tokens → falls to
 *   "UNKNOWN MINT" path → token is abandoned until scanner re-finds it (could
 *   be minutes or never if the token stopped trending). Positions can go to zero
 *   while the bot stares at an empty watchlist.
 *
 * WITH THIS:
 *   Restart → scan wallet → find MEXUNC in wallet → look up WalletTokenMemory →
 *   found: { symbol, entryPrice, layer, size, buyCostSol } → reconstruct
 *   TokenState + Position → bot immediately manages TP/SL.
 *
 * STORAGE
 * ───────
 * JSON file in app's private files dir: wallet_token_memory.json
 * Survives app updates (not in cache). Max 500 entries — oldest pruned on overflow.
 * Also exports a snapshot to SharedPreferences as fallback if file is corrupted.
 *
 * ENTRY LIFECYCLE
 * ───────────────
 * 1. Bot buys a token → recordBuy() called in Executor post-buy
 * 2. Bot exits a token → recordExit() called in Executor post-sell
 *    → entry stays but isOpen=false (history for learning)
 * 3. On startup → getOpenEntries() returns all isOpen=true entries
 * 4. StartupReconciler cross-references with live wallet → adopt if present
 * 5. Entries older than 7 days with isOpen=false are pruned automatically
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object WalletTokenMemory {

    private const val TAG = "WalletTokenMemory"
    private const val FILE_NAME = "wallet_token_memory.json"
    private const val PREFS_NAME = "wallet_token_memory_prefs"
    private const val PREFS_KEY  = "token_memory_json"
    private const val MAX_ENTRIES = 500
    private const val MAX_CLOSED_AGE_MS = 7L * 24 * 60 * 60 * 1000  // 7 days

    data class TokenMemoryEntry(
        val mint: String,
        val symbol: String,
        val name: String,
        // Buy context
        val buyCostSol: Double,           // SOL spent (for pnl reconstruction)
        val entryPrice: Double,           // price at buy
        val entryTime: Long,
        val qtyToken: Double,             // tokens received
        val layer: String,                // SHITCOIN / QUALITY / MOONSHOT / EXPRESS / TREASURY
        val tradingMode: String,          // STANDARD / MOONSHOT / etc.
        val entryPhase: String,
        val entryScore: Double,
        val entryLiquidityUsd: Double,
        val entryMcap: Double,
        val isPaperPosition: Boolean,
        // Position flags
        val isTreasuryPosition: Boolean,
        val treasuryTakeProfit: Double,
        val treasuryStopLoss: Double,
        val isBlueChipPosition: Boolean,
        val isShitCoinPosition: Boolean,
        // State
        var isOpen: Boolean,
        var exitTime: Long = 0L,
        var exitPrice: Double = 0.0,
        var exitPnlPct: Double = 0.0,
        var exitReason: String = "",
        // Source
        val source: String = "",          // scanner source (PUMP_FUN_NEW etc)
        val pairAddress: String = "",
    )

    @Volatile private var ctx: Context? = null
    private val entries = ConcurrentHashMap<String, TokenMemoryEntry>()  // key = mint
    @Volatile private var loaded = false

    // ─────────────────────────────────────────────────────────────────────────
    // INIT
    // ─────────────────────────────────────────────────────────────────────────

    fun init(context: Context) {
        ctx = context.applicationContext
        load()
        prune()
        ErrorLogger.info(TAG, "✅ WalletTokenMemory loaded | ${entries.size} entries (${getOpenEntries().size} open)")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API — called by Executor
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Record a successful buy. Call this immediately after tokens are confirmed
     * on-chain (post phantom guard, not speculatively at tx send time).
     */
    fun recordBuy(ts: com.lifecyclebot.data.TokenState) {
        val pos = ts.position
        if (!pos.isOpen) return
        val entry = TokenMemoryEntry(
            mint               = ts.mint,
            symbol             = ts.symbol,
            name               = ts.name,
            buyCostSol         = pos.costSol,
            entryPrice         = pos.entryPrice,
            entryTime          = pos.entryTime,
            qtyToken           = pos.qtyToken,
            layer              = resolveLayer(pos),
            tradingMode        = pos.tradingMode,
            entryPhase         = pos.entryPhase,
            entryScore         = pos.entryScore,
            entryLiquidityUsd  = pos.entryLiquidityUsd,
            entryMcap          = pos.entryMcap,
            isPaperPosition    = pos.isPaperPosition,
            isTreasuryPosition = pos.isTreasuryPosition,
            treasuryTakeProfit = pos.treasuryTakeProfit,
            treasuryStopLoss   = pos.treasuryStopLoss,
            isBlueChipPosition = pos.isBlueChipPosition,
            isShitCoinPosition = pos.isShitCoinPosition,
            isOpen             = true,
            source             = ts.source,
            pairAddress        = ts.pairAddress,
        )
        entries[ts.mint] = entry
        save()
        ErrorLogger.info(TAG, "📝 BUY recorded: ${ts.symbol} | ${pos.costSol} SOL @ ${pos.entryPrice} | layer=${entry.layer}")
    }

    /**
     * Mark a position as closed. Call this after every sell (TP, SL, phantom, manual).
     */
    fun recordExit(mint: String, symbol: String, exitPrice: Double, pnlPct: Double, reason: String) {
        val entry = entries[mint] ?: run {
            ErrorLogger.warn(TAG, "recordExit: no memory entry for $symbol ($mint)")
            return
        }
        entries[mint] = entry.copy(
            isOpen      = false,
            exitTime    = System.currentTimeMillis(),
            exitPrice   = exitPrice,
            exitPnlPct  = pnlPct,
            exitReason  = reason,
        )
        save()
        ErrorLogger.info(TAG, "📝 EXIT recorded: $symbol | pnl=${pnlPct}% | reason=$reason")
    }

    /**
     * Get all entries that are still open (bot thinks it holds these).
     */
    fun getOpenEntries(): List<TokenMemoryEntry> =
        entries.values.filter { it.isOpen }.sortedByDescending { it.entryTime }

    /**
     * Get entry for a specific mint (open or closed).
     */
    fun getEntry(mint: String): TokenMemoryEntry? = entries[mint]

    /**
     * Check if we have memory of buying this token and it's still open.
     */
    fun isKnownOpenPosition(mint: String): Boolean = entries[mint]?.isOpen == true

    /**
     * All entries (open + closed) for diagnostics / UI.
     */
    fun getAllEntries(): List<TokenMemoryEntry> =
        entries.values.sortedByDescending { it.entryTime }

    // ─────────────────────────────────────────────────────────────────────────
    // STARTUP RECOVERY — called by StartupReconciler
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Cross-reference wallet token accounts with memory journal.
     * Returns list of TokenState entries reconstructed from memory for tokens
     * that are in the wallet but NOT yet in status.tokens.
     *
     * Called by StartupReconciler after getTokenAccounts() but before
     * the scanner has had a chance to re-discover tokens.
     */
    fun reconstructMissingPositions(
        walletTokens: Map<String, Double>,       // mint → qty on-chain
        statusTokens: Map<String, com.lifecyclebot.data.TokenState>,  // already tracked
        onLog: (String) -> Unit,
    ): List<com.lifecyclebot.data.TokenState> {

        val reconstructed = mutableListOf<com.lifecyclebot.data.TokenState>()

        for ((mint, onChainQty) in walletTokens) {
            if (onChainQty <= 0.0) continue
            if (mint in statusTokens) continue          // already tracked, reconciler handles it
            if (mint == "So11111111111111111111111111111111111111112") continue  // SOL itself

            val mem = entries[mint] ?: continue         // no memory of buying this — truly unknown

            if (!mem.isOpen) {
                // We closed this position but tokens are still in wallet?
                // Could be a partial fill or failed sell. Re-open it.
                onLog("⚠️ MEMORY: ${mem.symbol} marked closed but ${"%.4f".format(onChainQty)} tokens on-chain — re-opening")
                entries[mint] = mem.copy(isOpen = true)
                save()
            }

            // Reconstruct a minimal TokenState from memory
            val pos = com.lifecyclebot.data.Position(
                qtyToken           = onChainQty,         // use LIVE qty from wallet, not memory
                entryPrice         = mem.entryPrice,
                entryTime          = mem.entryTime,
                costSol            = mem.buyCostSol,
                highestPrice       = mem.entryPrice,     // conservative — we don't know peak
                lowestPrice        = mem.entryPrice,
                peakGainPct        = 0.0,
                entryPhase         = mem.entryPhase.ifBlank { "memory_recovery" },
                entryScore         = mem.entryScore,
                entryLiquidityUsd  = mem.entryLiquidityUsd,
                entryMcap          = mem.entryMcap,
                isPaperPosition    = mem.isPaperPosition,
                tradingMode        = mem.tradingMode,
                isTreasuryPosition = mem.isTreasuryPosition,
                treasuryTakeProfit = mem.treasuryTakeProfit,
                treasuryStopLoss   = mem.treasuryStopLoss,
                isBlueChipPosition = mem.isBlueChipPosition,
                isShitCoinPosition = mem.isShitCoinPosition,
            )

            val ts = com.lifecyclebot.data.TokenState(
                mint        = mint,
                symbol      = mem.symbol,
                name        = mem.name,
                pairAddress = mem.pairAddress,
                source      = mem.source.ifBlank { "MEMORY_RECOVERY" },
                lastPrice   = mem.entryPrice,   // last known, will update on first tick
                position    = pos,
                phase       = "hold",
            )

            reconstructed += ts
            onLog("📥 MEMORY RECOVERY: ${mem.symbol} | ${"%.4f".format(onChainQty)} tokens | entry=${mem.entryPrice} | layer=${mem.layer} | held ${((System.currentTimeMillis() - mem.entryTime) / 60_000)}min")
        }

        if (reconstructed.isNotEmpty()) {
            onLog("📥 WalletTokenMemory recovered ${reconstructed.size} position(s) not yet in scanner memory")
        }

        return reconstructed
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PERSISTENCE
    // ─────────────────────────────────────────────────────────────────────────

    private fun file(): File? = ctx?.filesDir?.let { File(it, FILE_NAME) }

    private fun load() {
        entries.clear()
        // Try file first
        try {
            val f = file()
            if (f != null && f.exists()) {
                val json = f.readText()
                parseJson(json)
                return
            }
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "File load failed, falling back to prefs: ${e.message}")
        }
        // Fallback: SharedPreferences
        try {
            val prefs = ctx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
            val json = prefs.getString(PREFS_KEY, null) ?: return
            parseJson(json)
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "Prefs load also failed: ${e.message}")
        }
    }

    private fun parseJson(json: String) {
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                val entry = TokenMemoryEntry(
                    mint               = o.getString("mint"),
                    symbol             = o.optString("symbol", "???"),
                    name               = o.optString("name", ""),
                    buyCostSol         = o.optDouble("buyCostSol", 0.0),
                    entryPrice         = o.optDouble("entryPrice", 0.0),
                    entryTime          = o.optLong("entryTime", 0L),
                    qtyToken           = o.optDouble("qtyToken", 0.0),
                    layer              = o.optString("layer", "UNKNOWN"),
                    tradingMode        = o.optString("tradingMode", "STANDARD"),
                    entryPhase         = o.optString("entryPhase", ""),
                    entryScore         = o.optDouble("entryScore", 50.0),
                    entryLiquidityUsd  = o.optDouble("entryLiquidityUsd", 0.0),
                    entryMcap          = o.optDouble("entryMcap", 0.0),
                    isPaperPosition    = o.optBoolean("isPaperPosition", true),
                    isTreasuryPosition = o.optBoolean("isTreasuryPosition", false),
                    treasuryTakeProfit = o.optDouble("treasuryTakeProfit", 0.0),
                    treasuryStopLoss   = o.optDouble("treasuryStopLoss", 0.0),
                    isBlueChipPosition = o.optBoolean("isBlueChipPosition", false),
                    isShitCoinPosition = o.optBoolean("isShitCoinPosition", false),
                    isOpen             = o.optBoolean("isOpen", true),
                    exitTime           = o.optLong("exitTime", 0L),
                    exitPrice          = o.optDouble("exitPrice", 0.0),
                    exitPnlPct         = o.optDouble("exitPnlPct", 0.0),
                    exitReason         = o.optString("exitReason", ""),
                    source             = o.optString("source", ""),
                    pairAddress        = o.optString("pairAddress", ""),
                )
                entries[entry.mint] = entry
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "Failed to parse entry: ${e.message}")
            }
        }
    }

    @Synchronized
    fun save() {
        try {
            prune()
            val arr = JSONArray()
            for (entry in entries.values) {
                arr.put(JSONObject().apply {
                    put("mint",               entry.mint)
                    put("symbol",             entry.symbol)
                    put("name",               entry.name)
                    put("buyCostSol",         entry.buyCostSol)
                    put("entryPrice",         entry.entryPrice)
                    put("entryTime",          entry.entryTime)
                    put("qtyToken",           entry.qtyToken)
                    put("layer",              entry.layer)
                    put("tradingMode",        entry.tradingMode)
                    put("entryPhase",         entry.entryPhase)
                    put("entryScore",         entry.entryScore)
                    put("entryLiquidityUsd",  entry.entryLiquidityUsd)
                    put("entryMcap",          entry.entryMcap)
                    put("isPaperPosition",    entry.isPaperPosition)
                    put("isTreasuryPosition", entry.isTreasuryPosition)
                    put("treasuryTakeProfit", entry.treasuryTakeProfit)
                    put("treasuryStopLoss",   entry.treasuryStopLoss)
                    put("isBlueChipPosition", entry.isBlueChipPosition)
                    put("isShitCoinPosition", entry.isShitCoinPosition)
                    put("isOpen",             entry.isOpen)
                    put("exitTime",           entry.exitTime)
                    put("exitPrice",          entry.exitPrice)
                    put("exitPnlPct",         entry.exitPnlPct)
                    put("exitReason",         entry.exitReason)
                    put("source",             entry.source)
                    put("pairAddress",        entry.pairAddress)
                })
            }
            val json = arr.toString()
            // Write to file (primary)
            file()?.writeText(json)
            // Mirror to prefs (fallback)
            ctx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()?.putString(PREFS_KEY, json)?.apply()
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Save failed: ${e.message}", e)
        }
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        // Remove closed entries older than 7 days
        entries.entries.removeIf { (_, v) ->
            !v.isOpen && (now - (v.exitTime.takeIf { it > 0 } ?: v.entryTime)) > MAX_CLOSED_AGE_MS
        }
        // If still over max, remove oldest closed first, then oldest open
        if (entries.size > MAX_ENTRIES) {
            val sorted = entries.values.sortedWith(
                compareBy({ if (it.isOpen) 1 else 0 }, { it.entryTime })
            )
            val toRemove = sorted.take(entries.size - MAX_ENTRIES)
            toRemove.forEach { entries.remove(it.mint) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private fun resolveLayer(pos: com.lifecyclebot.data.Position): String = when {
        pos.isTreasuryPosition  -> "TREASURY"
        pos.isBlueChipPosition  -> "BLUECHIP"
        pos.isShitCoinPosition  -> "SHITCOIN"
        pos.tradingMode == "MOONSHOT" -> "MOONSHOT"
        else                    -> "STANDARD"
    }

    fun getStats(): String {
        val open   = entries.values.count { it.isOpen }
        val closed = entries.values.count { !it.isOpen }
        val layers = entries.values.filter { it.isOpen }.groupBy { it.layer }
            .map { "${it.key}=${it.value.size}" }.joinToString(", ")
        return "open=$open | closed=$closed | layers=[$layers]"
    }
}
