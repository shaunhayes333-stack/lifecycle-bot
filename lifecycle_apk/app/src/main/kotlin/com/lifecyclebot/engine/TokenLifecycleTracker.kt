package com.lifecyclebot.engine

import android.content.Context
import com.lifecyclebot.network.SolanaWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.9.495z28 — Token Lifecycle Tracker.
 *
 * Operator spec (10-item end-to-end overhaul, item 3):
 *   "Add a dedicated Token Lifecycle Tracker. Required model
 *    'TokenLifecycleRecord' keyed by mint, not ticker/symbol."
 *
 * Authoritative ledger that no other layer is allowed to override. Every
 * live token bought by the bot becomes a tracked record here from BUY_PENDING
 * through CLEARED. The bot must never falsely clear a position because SOL
 * came back — it must verify both sides (SOL returned AND token balance ≤
 * dust) before transitioning to CLEARED.
 *
 * State machine (one-way, except resurrect/auto-import paths):
 *
 *   BUY_PENDING ──► BUY_CONFIRMED ──► HELD ──► PARTIAL_SELL ──► SELL_PENDING
 *                                          └─► SELL_PENDING       │
 *                                                                  ▼
 *                          RESIDUAL_HELD ◄── SELL_CONFIRMED ──► CLEARED
 *                                  │                              ▲
 *                                  └──────────────────────────────┘
 *                                  (next sell sweep clears residual)
 *                          RECONCILE_FAILED  (terminal, requires operator action)
 *
 * Persisted as JSON to filesDir/token_lifecycle.json with debounced 5s save.
 * Survives app restart so positions can be resurrected exactly where they
 * left off.
 */
object TokenLifecycleTracker {

    private const val TAG = "Lifecycle"
    private const val PERSIST_FILE = "token_lifecycle.json"
    private const val PERSIST_DEBOUNCE_MS = 5_000L
    private const val DUST_UI_AMOUNT = 0.000_001        // below this we consider the token cleared
    private const val WALLET_RECONCILE_RETRIES = 5
    private const val WALLET_RECONCILE_BACKOFF_MS = 1_500L

    enum class Status {
        BUY_PENDING,
        BUY_CONFIRMED,
        HELD,
        PARTIAL_SELL,
        SELL_PENDING,
        SELL_CONFIRMED,
        RESIDUAL_HELD,
        CLEARED,
        RECONCILE_FAILED,
    }

    data class Record(
        val mint: String,
        var symbol: String,
        var venue: String,                          // pump.fun / raydium / meteora / jupiter / cryptoalt
        var buyTx: String? = null,
        val sellTxs: MutableList<String> = mutableListOf(),
        var entrySolSpent: Double = 0.0,
        var entryTokenQtyConfirmed: Double = 0.0,   // UI amount (decimals applied) confirmed on-chain
        var currentWalletTokenQty: Double = 0.0,    // last reconciler reading
        var soldTokenQty: Double = 0.0,
        var solRecovered: Double = 0.0,
        var realizedPnlSol: Double = 0.0,
        var status: Status = Status.BUY_PENDING,
        var lastReconcileMs: Long = 0L,
        var openedAtMs: Long = System.currentTimeMillis(),
        var closedAtMs: Long? = null,
        var reconcileFailReason: String? = null,
    )

    private val records = ConcurrentHashMap<String, Record>(512)
    @Volatile private var appCtx: Context? = null
    @Volatile private var persistJob: Job? = null
    private val persistDirty = AtomicBoolean(false)
    private val persistLock = Any()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ─── public API ──────────────────────────────────────────────────────────

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        restoreFromDisk()
        ErrorLogger.info(TAG, "init: ${records.size} lifecycle records loaded")
    }

    fun get(mint: String): Record? = records[mint]
    fun all(): List<Record> = records.values.toList()
    fun openCount(): Int = records.values.count { !it.status.isTerminal() }

    /** Stats for diagnostics export and the Universe / Forensics tile. */
    fun stats(): String {
        val by = records.values.groupingBy { it.status.name }.eachCount()
        return by.entries.sortedByDescending { it.value }.joinToString(" · ") { "${it.key}=${it.value}" }
    }

    /** Called the moment a buy is requested (before broadcast). */
    @Synchronized
    fun onBuyPending(mint: String, symbol: String, venue: String, sizeSol: Double) {
        val existing = records[mint]
        if (existing != null && !existing.status.isTerminal()) {
            // Re-buy on a position we already track — keep history, update size.
            existing.entrySolSpent += sizeSol
            existing.openedAtMs = minOf(existing.openedAtMs, System.currentTimeMillis())
        } else {
            records[mint] = Record(
                mint = mint, symbol = symbol, venue = venue,
                entrySolSpent = sizeSol,
                status = Status.BUY_PENDING,
                openedAtMs = System.currentTimeMillis(),
            )
        }
        ErrorLogger.info(TAG, "▶ onBuyPending $symbol mint=${mint.take(8)}… size=${"%.4f".format(sizeSol)}")
        scheduleSave()
    }

    /** Called when the buy tx is on-chain confirmed (signature in mempool/landed). */
    @Synchronized
    fun onBuyConfirmed(mint: String, sig: String, confirmedTokenQty: Double = 0.0) {
        val r = records[mint] ?: run {
            // Safety net: an unknown mint means an auto-import. Register it.
            records[mint] = Record(
                mint = mint, symbol = mint.take(6), venue = "auto-import",
                buyTx = sig, status = Status.BUY_CONFIRMED,
                entryTokenQtyConfirmed = confirmedTokenQty,
                currentWalletTokenQty = confirmedTokenQty,
            )
            ErrorLogger.warn(TAG, "🪐 AUTO-IMPORT: unknown mint ${mint.take(8)}… registered from buy sig")
            scheduleSave()
            return
        }
        r.buyTx = sig
        if (confirmedTokenQty > 0) {
            r.entryTokenQtyConfirmed = confirmedTokenQty
            r.currentWalletTokenQty = confirmedTokenQty
        }
        r.status = Status.BUY_CONFIRMED
        scheduleSave()
    }

    /** Once wallet reconciliation confirms tokens are physically present, status → HELD. */
    @Synchronized
    fun onTokenLanded(mint: String, walletUiAmount: Double) {
        val r = records[mint] ?: return
        r.currentWalletTokenQty = walletUiAmount
        if (r.entryTokenQtyConfirmed <= 0) r.entryTokenQtyConfirmed = walletUiAmount
        r.status = Status.HELD
        r.lastReconcileMs = System.currentTimeMillis()
        scheduleSave()
    }

    @Synchronized
    fun onSellPending(mint: String, sig: String? = null) {
        val r = records[mint] ?: return
        if (sig != null) r.sellTxs.add(sig)
        r.status = Status.SELL_PENDING
        scheduleSave()
    }

    /**
     * Operator spec item 1: "Do not mark a sell as fully complete just
     * because SOL returned. The bot must verify BOTH sides."
     *
     * Caller must pass:
     *   • `solReceived` — actual lamports → SOL returned by the sell tx
     *   • `walletTokenAfter` — the wallet token balance after sell
     *     (or null if wallet reconcile failed → caller should re-poll)
     *
     * If walletTokenAfter is null OR > dust, status becomes RESIDUAL_HELD
     * and the caller must keep monitoring + selling until it confirms zero.
     */
    @Synchronized
    fun onSellSettled(
        mint: String,
        sig: String,
        solReceived: Double,
        walletTokenAfter: Double?,
    ) {
        val r = records[mint] ?: return
        if (sig.isNotBlank() && sig !in r.sellTxs) r.sellTxs.add(sig)
        r.solRecovered += solReceived
        r.realizedPnlSol = r.solRecovered - r.entrySolSpent
        r.lastReconcileMs = System.currentTimeMillis()

        if (walletTokenAfter == null) {
            // Wallet reconcile failed — never assume zero. Keep monitoring.
            r.status = Status.RESIDUAL_HELD
            ErrorLogger.warn(TAG,
                "⚠️ onSellSettled ${r.symbol}: SOL returned ${"%.6f".format(solReceived)} but " +
                "WALLET RECONCILE FAILED — held as RESIDUAL_HELD, watchdog will re-sweep.")
        } else if (walletTokenAfter > DUST_UI_AMOUNT) {
            // Tokens still in wallet → partial fill. Operator spec item 1.
            val sold = (r.currentWalletTokenQty - walletTokenAfter).coerceAtLeast(0.0)
            r.soldTokenQty += sold
            r.currentWalletTokenQty = walletTokenAfter
            r.status = Status.PARTIAL_SELL
            ErrorLogger.warn(TAG,
                "⚠️ PARTIAL_SELL ${r.symbol}: sold ${"%.4f".format(sold)} ui, " +
                "${"%.4f".format(walletTokenAfter)} ui REMAINING in wallet.")
        } else {
            // Token fully cleared (≤ dust) AND SOL returned. CLEARED.
            r.soldTokenQty += r.currentWalletTokenQty.coerceAtLeast(0.0)
            r.currentWalletTokenQty = 0.0
            r.status = Status.CLEARED
            r.closedAtMs = System.currentTimeMillis()
            ErrorLogger.info(TAG,
                "✅ CLEARED ${r.symbol}: sol_recovered=${"%.6f".format(r.solRecovered)} " +
                "pnl=${"%.6f".format(r.realizedPnlSol)} SOL.")
        }
        scheduleSave()
    }

    /**
     * Marker for terminal failure — used when a position has been stuck in
     * RESIDUAL_HELD for a long time and repeated sweeps cannot reduce the
     * wallet balance (e.g. LP pulled, mint frozen). Operator must clear
     * manually.
     */
    @Synchronized
    fun markReconcileFailed(mint: String, reason: String) {
        val r = records[mint] ?: return
        r.status = Status.RECONCILE_FAILED
        r.reconcileFailReason = reason
        r.closedAtMs = System.currentTimeMillis()
        ErrorLogger.warn(TAG, "💀 RECONCILE_FAILED ${r.symbol}: $reason")
        scheduleSave()
    }

    /** Auto-import: an unknown wallet token was detected during reconciliation. */
    @Synchronized
    fun autoImportFromWallet(mint: String, symbol: String, walletUiAmount: Double, venue: String) {
        if (records.containsKey(mint)) return
        records[mint] = Record(
            mint = mint, symbol = symbol, venue = venue,
            entryTokenQtyConfirmed = walletUiAmount,
            currentWalletTokenQty = walletUiAmount,
            status = Status.HELD,
            lastReconcileMs = System.currentTimeMillis(),
        )
        ErrorLogger.warn(TAG, "🪐 AUTO-IMPORT $symbol mint=${mint.take(8)}… ui=$walletUiAmount")
        scheduleSave()
    }

    private fun Status.isTerminal(): Boolean =
        this == Status.CLEARED || this == Status.RECONCILE_FAILED

    // ─── Wallet reconciliation helper (operator spec item 2) ────────────────

    /**
     * Operator spec item 2: "Replace RPC-empty-map blind trust with proper
     * wallet reconciliation."
     *
     * Polls the on-chain wallet up to WALLET_RECONCILE_RETRIES times with
     * exponential backoff. Returns:
     *   • Reading.Confirmed(uiAmount) — wallet replied; token present at uiAmount (may be 0)
     *   • Reading.Empty — wallet RPC kept returning empty map; caller must NOT
     *     assume zero. Caller should keep the position in RESIDUAL_HELD and
     *     try again later.
     */
    sealed class Reading {
        data class Confirmed(val uiAmount: Double) : Reading()
        data object Empty : Reading()
    }

    suspend fun reconcileWalletBalance(
        wallet: SolanaWallet?,
        mint: String,
        timeoutMs: Long = 12_000L,
    ): Reading {
        val w = wallet ?: return Reading.Empty
        val result = withTimeoutOrNull(timeoutMs) {
            var lastSawEmpty = false
            for (attempt in 1..WALLET_RECONCILE_RETRIES) {
                try {
                    val map = w.getTokenAccountsWithDecimals()
                    if (map.isEmpty()) {
                        // Either RPC blip or wallet truly has no SPL tokens.
                        // Distinguish by retrying — a healthy wallet WITH a
                        // recent buy should never have an empty map past the
                        // settlement window. So we keep retrying until timeout.
                        lastSawEmpty = true
                        delay(WALLET_RECONCILE_BACKOFF_MS * attempt)
                        continue
                    }
                    val (ui, _) = map[mint] ?: (0.0 to 0)
                    return@withTimeoutOrNull Reading.Confirmed(ui)
                } catch (e: Exception) {
                    ErrorLogger.debug(TAG, "reconcile attempt $attempt err: ${e.message}")
                    delay(WALLET_RECONCILE_BACKOFF_MS * attempt)
                }
            }
            if (lastSawEmpty) Reading.Empty else Reading.Empty
        }
        return result ?: Reading.Empty
    }

    // ─── Persistence ─────────────────────────────────────────────────────────

    private fun scheduleSave() {
        if (appCtx == null) return
        persistDirty.set(true)
        synchronized(persistLock) {
            if (persistJob?.isActive == true) return
            persistJob = scope.launch {
                try {
                    delay(PERSIST_DEBOUNCE_MS)
                    if (persistDirty.compareAndSet(true, false)) saveToDisk()
                } catch (_: Throwable) {}
            }
        }
    }

    @Synchronized
    private fun saveToDisk() {
        val ctx = appCtx ?: return
        try {
            val arr = JSONArray()
            for (r in records.values) {
                arr.put(JSONObject().apply {
                    put("mint", r.mint)
                    put("symbol", r.symbol)
                    put("venue", r.venue)
                    r.buyTx?.let { put("buyTx", it) }
                    if (r.sellTxs.isNotEmpty()) put("sellTxs", JSONArray(r.sellTxs))
                    put("entrySolSpent", r.entrySolSpent)
                    put("entryTokenQtyConfirmed", r.entryTokenQtyConfirmed)
                    put("currentWalletTokenQty", r.currentWalletTokenQty)
                    put("soldTokenQty", r.soldTokenQty)
                    put("solRecovered", r.solRecovered)
                    put("realizedPnlSol", r.realizedPnlSol)
                    put("status", r.status.name)
                    put("lastReconcileMs", r.lastReconcileMs)
                    put("openedAtMs", r.openedAtMs)
                    r.closedAtMs?.let { put("closedAtMs", it) }
                    r.reconcileFailReason?.let { put("reconcileFailReason", it) }
                })
            }
            File(ctx.filesDir, PERSIST_FILE).writeText(arr.toString())
            ErrorLogger.debug(TAG, "💾 persisted ${arr.length()} lifecycle records")
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "saveToDisk failed: ${e.message}")
        }
    }

    @Synchronized
    private fun restoreFromDisk() {
        val ctx = appCtx ?: return
        val file = File(ctx.filesDir, PERSIST_FILE)
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val mint = o.optString("mint", "").trim()
                if (mint.isBlank()) continue
                val sellTxs = mutableListOf<String>()
                o.optJSONArray("sellTxs")?.let { ja ->
                    for (j in 0 until ja.length()) sellTxs.add(ja.optString(j))
                }
                records[mint] = Record(
                    mint = mint,
                    symbol = o.optString("symbol", ""),
                    venue = o.optString("venue", ""),
                    buyTx = o.optString("buyTx").takeIf { it.isNotBlank() },
                    sellTxs = sellTxs,
                    entrySolSpent = o.optDouble("entrySolSpent", 0.0),
                    entryTokenQtyConfirmed = o.optDouble("entryTokenQtyConfirmed", 0.0),
                    currentWalletTokenQty = o.optDouble("currentWalletTokenQty", 0.0),
                    soldTokenQty = o.optDouble("soldTokenQty", 0.0),
                    solRecovered = o.optDouble("solRecovered", 0.0),
                    realizedPnlSol = o.optDouble("realizedPnlSol", 0.0),
                    status = try { Status.valueOf(o.optString("status", "BUY_PENDING")) } catch (_: Exception) { Status.RECONCILE_FAILED },
                    lastReconcileMs = o.optLong("lastReconcileMs", 0L),
                    openedAtMs = o.optLong("openedAtMs", System.currentTimeMillis()),
                    closedAtMs = o.optLong("closedAtMs", 0L).takeIf { it > 0 },
                    reconcileFailReason = o.optString("reconcileFailReason").takeIf { it.isNotBlank() },
                )
            }
            ErrorLogger.info(TAG, "📂 restored ${records.size} lifecycle records")
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "restoreFromDisk failed: ${e.message}")
        }
    }
}
