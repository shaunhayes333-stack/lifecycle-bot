/*
 * V5.9.495z10 — HostWalletTokenTracker (operator spec May 2026).
 *
 * Canonical lifecycle ledger for every token the host wallet has ever
 * touched. Independent of scanner watchlist, GlobalTradeRegistry,
 * status.tokens, and per-trader registries — those layers are auxiliary
 * views; this tracker is wallet-truth.
 *
 * Solves the "STRIKE / WCOR drift" bug:
 *   • Phantom wallet shows STRIKE + WCOR.
 *   • Forensics: STRIKE BUY VERIFIED LANDED.
 *   • Dashboard says Open=0.
 *   • Pipeline says positions=0.
 *   • V3 says WCOR ALREADY_OPEN.
 *
 * Root cause: the watchlist cleanup loop drops mints from status.tokens
 * faster than WalletReconciler can re-create them, so the dashboard's
 * computed Open count loses sync with the on-chain wallet. This tracker
 * gives every subsystem a single source of truth keyed strictly by mint.
 *
 * Lifecycle:
 *   BUY_PENDING → BUY_CONFIRMED → OPEN_TRACKING
 *                                      ↓
 *                               EXIT_SIGNALLED
 *                                      ↓
 *                              SELL_PENDING → SELL_VERIFYING
 *                                      ↓
 *                              SOLD_CONFIRMED → CLOSED
 *
 * Wallet reconciliation runs every loop and is monotonic: a wallet
 * reconcile NEVER overwrites a HIGHER state with a lower one (e.g. a
 * stale RPC poll cannot demote OPEN_TRACKING back to UNKNOWN).
 */
package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object HostWalletTokenTracker {

    private const val TAG = "HostTokenTracker"
    private const val FILE_NAME = "host_wallet_token_tracker.json"
    /** Token raw amounts strictly below this are dust — ignored for open/close decisions. */
    private const val DUST_RAW = 1L
    /** SOL native mint — always excluded from the tracker. */
    private const val SOL_MINT = "So11111111111111111111111111111111111111112"

    enum class PositionSource {
        BOT_BUY,
        TX_PARSE,
        WALLET_RECONCILED,
        MANUAL_IMPORT,
        RECOVERED_AFTER_RESTART,
    }

    enum class PositionStatus(val priority: Int) {
        // Higher priority cannot be downgraded by lower priority.
        UNKNOWN_NEEDS_RECONCILE(0),
        DUST_IGNORED(1),
        BUY_PENDING(2),
        BUY_CONFIRMED(3),
        HELD_IN_WALLET(3),
        OPEN_TRACKING(4),
        EXIT_SIGNALLED(5),
        SELL_PENDING(6),
        SELL_VERIFYING(7),
        SOLD_CONFIRMED(8),
        CLOSED(9),
    }

    /** Statuses considered "open" for dashboard / exit-monitor / cleanup-protect. */
    private val OPEN_STATUSES: Set<PositionStatus> = setOf(
        PositionStatus.BUY_CONFIRMED,
        PositionStatus.HELD_IN_WALLET,
        PositionStatus.OPEN_TRACKING,
        PositionStatus.EXIT_SIGNALLED,
        PositionStatus.SELL_PENDING,
        PositionStatus.SELL_VERIFYING,
    )

    /** V5.9.601 — any of these means a sell/reconcile lifecycle is in flight. */
    private val SELL_IN_FLIGHT_STATUSES: Set<PositionStatus> = setOf(
        PositionStatus.EXIT_SIGNALLED,
        PositionStatus.SELL_PENDING,
        PositionStatus.SELL_VERIFYING,
    )

    data class TrackedTokenPosition(
        val mint: String,
        var symbol: String?,
        var name: String?,
        var source: PositionSource,
        var status: PositionStatus,

        var buySignature: String?,
        var sellSignature: String?,

        var buyTimeMs: Long?,
        var firstSeenWalletMs: Long,
        var lastSeenWalletMs: Long,

        var entryPriceUsd: Double?,
        var entrySol: Double?,
        var entryMarketCap: Double?,

        var rawAmount: String,
        var decimals: Int,
        var uiAmount: Double,

        var currentPriceUsd: Double?,
        var currentValueSol: Double?,
        var currentValueAud: Double?,

        var highestPriceUsd: Double?,
        var lowestPriceUsd: Double?,
        var maxGainPct: Double,
        var maxDrawdownPct: Double,

        var takeProfitPct: Double,
        var stopLossPct: Double,
        var trailingStopPct: Double?,

        var venue: String?,
        var pool: String?,

        var lastPriceUpdateMs: Long?,
        var lastWalletReconcileMs: Long?,
        var lastExitCheckMs: Long?,

        var activeSellAttemptId: String?,
        val notes: MutableList<String> = mutableListOf(),
    )

    @Volatile private var ctx: android.content.Context? = null
    private val positions = ConcurrentHashMap<String, TrackedTokenPosition>()
    @Volatile private var loaded = false

    // ─────────────────────────────────────────────────────────────────
    // Init / persistence
    // ─────────────────────────────────────────────────────────────────

    fun init(context: android.content.Context) {
        ctx = context.applicationContext
        load()
        ErrorLogger.info(TAG, "✅ HostWalletTokenTracker loaded | ${positions.size} mints | open=${getOpenCount()}")
    }

    private fun file(): File? = ctx?.filesDir?.let { File(it, FILE_NAME) }

    @Synchronized
    private fun save() {
        try {
            val arr = JSONArray()
            for (p in positions.values) {
                arr.put(JSONObject().apply {
                    put("mint", p.mint)
                    p.symbol?.let { put("symbol", it) }
                    p.name?.let { put("name", it) }
                    put("source", p.source.name)
                    put("status", p.status.name)
                    p.buySignature?.let { put("buySig", it) }
                    p.sellSignature?.let { put("sellSig", it) }
                    p.buyTimeMs?.let { put("buyTimeMs", it) }
                    put("firstSeenWalletMs", p.firstSeenWalletMs)
                    put("lastSeenWalletMs", p.lastSeenWalletMs)
                    p.entryPriceUsd?.let { put("entryPriceUsd", it) }
                    p.entrySol?.let { put("entrySol", it) }
                    put("rawAmount", p.rawAmount)
                    put("decimals", p.decimals)
                    put("uiAmount", p.uiAmount)
                    put("maxGainPct", p.maxGainPct)
                    put("maxDrawdownPct", p.maxDrawdownPct)
                    put("takeProfitPct", p.takeProfitPct)
                    put("stopLossPct", p.stopLossPct)
                    p.trailingStopPct?.let { put("trailingStopPct", it) }
                    p.venue?.let { put("venue", it) }
                })
            }
            file()?.writeText(arr.toString())
        } catch (_: Exception) { /* never crash on persistence */ }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        try {
            val f = file() ?: return
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val mint = o.optString("mint", "").takeIf { it.isNotBlank() } ?: continue
                positions[mint] = TrackedTokenPosition(
                    mint = mint,
                    symbol = o.optString("symbol", "").takeIf { it.isNotBlank() },
                    name = o.optString("name", "").takeIf { it.isNotBlank() },
                    source = runCatching { PositionSource.valueOf(o.optString("source", "RECOVERED_AFTER_RESTART")) }
                        .getOrDefault(PositionSource.RECOVERED_AFTER_RESTART),
                    status = runCatching { PositionStatus.valueOf(o.optString("status", "UNKNOWN_NEEDS_RECONCILE")) }
                        .getOrDefault(PositionStatus.UNKNOWN_NEEDS_RECONCILE),
                    buySignature = o.optString("buySig", "").takeIf { it.isNotBlank() },
                    sellSignature = o.optString("sellSig", "").takeIf { it.isNotBlank() },
                    buyTimeMs = if (o.has("buyTimeMs")) o.optLong("buyTimeMs") else null,
                    firstSeenWalletMs = o.optLong("firstSeenWalletMs", System.currentTimeMillis()),
                    lastSeenWalletMs = o.optLong("lastSeenWalletMs", System.currentTimeMillis()),
                    entryPriceUsd = if (o.has("entryPriceUsd")) o.optDouble("entryPriceUsd") else null,
                    entrySol = if (o.has("entrySol")) o.optDouble("entrySol") else null,
                    entryMarketCap = null,
                    rawAmount = o.optString("rawAmount", "0"),
                    decimals = o.optInt("decimals", 9),
                    uiAmount = o.optDouble("uiAmount", 0.0),
                    currentPriceUsd = null,
                    currentValueSol = null,
                    currentValueAud = null,
                    highestPriceUsd = null,
                    lowestPriceUsd = null,
                    maxGainPct = o.optDouble("maxGainPct", 0.0),
                    maxDrawdownPct = o.optDouble("maxDrawdownPct", 0.0),
                    takeProfitPct = o.optDouble("takeProfitPct", 0.0),
                    stopLossPct = o.optDouble("stopLossPct", 0.0),
                    trailingStopPct = if (o.has("trailingStopPct")) o.optDouble("trailingStopPct") else null,
                    venue = o.optString("venue", "").takeIf { it.isNotBlank() },
                    pool = null,
                    lastPriceUpdateMs = null,
                    lastWalletReconcileMs = null,
                    lastExitCheckMs = null,
                    activeSellAttemptId = null,
                    notes = mutableListOf("Recovered from disk"),
                )
            }
        } catch (_: Exception) { /* corrupted blob — start clean */ }
    }

    // ─────────────────────────────────────────────────────────────────
    // BUY flow
    // ─────────────────────────────────────────────────────────────────

    /** Called the instant a buy is broadcast — speculative, not yet confirmed. */
    fun recordBuyPending(mint: String, symbol: String?, sig: String?) {
        if (mint.isBlank() || mint == SOL_MINT) return
        val now = System.currentTimeMillis()
        val existing = positions[mint]
        if (existing != null && existing.status.priority >= PositionStatus.BUY_PENDING.priority) {
            // Don't downgrade an already-confirmed position back to pending.
            existing.buySignature = existing.buySignature ?: sig
            return
        }
        val p = existing ?: TrackedTokenPosition(
            mint = mint, symbol = symbol, name = null,
            source = PositionSource.BOT_BUY,
            status = PositionStatus.BUY_PENDING,
            buySignature = sig, sellSignature = null,
            buyTimeMs = now, firstSeenWalletMs = now, lastSeenWalletMs = now,
            entryPriceUsd = null, entrySol = null, entryMarketCap = null,
            rawAmount = "0", decimals = 9, uiAmount = 0.0,
            currentPriceUsd = null, currentValueSol = null, currentValueAud = null,
            highestPriceUsd = null, lowestPriceUsd = null,
            maxGainPct = 0.0, maxDrawdownPct = 0.0,
            takeProfitPct = 0.0, stopLossPct = 0.0, trailingStopPct = null,
            venue = null, pool = null,
            lastPriceUpdateMs = null, lastWalletReconcileMs = null, lastExitCheckMs = null,
            activeSellAttemptId = null,
        )
        p.status = PositionStatus.BUY_PENDING
        p.buySignature = sig ?: p.buySignature
        positions[mint] = p
        emitForensic(LiveTradeLogStore.Phase.TOKEN_TRACKER_BUY_PENDING, mint, symbol, sig,
            "Tracker BUY_PENDING ${symbol ?: mint.take(6)}")
        save()
    }

    /**
     * Called from Executor immediately after a buy is verified on-chain
     * (BUY_VERIFIED_LANDED / BUY_TX_PARSE_OK / phantom rescue).
     * Promotes the tracked position to OPEN_TRACKING.
     */
    fun recordBuyConfirmed(ts: TokenState, sig: String? = null) {
        if (ts.mint.isBlank() || ts.mint == SOL_MINT) return
        if (!ts.position.isOpen) return
        val now = System.currentTimeMillis()
        val pos = ts.position
        val existing = positions[ts.mint]
        val p = existing ?: TrackedTokenPosition(
            mint = ts.mint, symbol = ts.symbol, name = ts.name,
            source = PositionSource.BOT_BUY,
            status = PositionStatus.BUY_CONFIRMED,
            buySignature = sig, sellSignature = null,
            buyTimeMs = pos.entryTime, firstSeenWalletMs = now, lastSeenWalletMs = now,
            entryPriceUsd = pos.entryPrice, entrySol = pos.costSol, entryMarketCap = pos.entryMcap,
            rawAmount = "0", decimals = 9, uiAmount = pos.qtyToken,
            currentPriceUsd = ts.lastPrice, currentValueSol = null, currentValueAud = null,
            highestPriceUsd = pos.highestPrice, lowestPriceUsd = pos.lowestPrice,
            maxGainPct = pos.peakGainPct, maxDrawdownPct = 0.0,
            takeProfitPct = pos.treasuryTakeProfit, stopLossPct = pos.treasuryStopLoss, trailingStopPct = null,
            venue = ts.source, pool = ts.pairAddress.takeIf { it.isNotBlank() },
            lastPriceUpdateMs = now, lastWalletReconcileMs = null, lastExitCheckMs = null,
            activeSellAttemptId = null,
        )
        // Monotonic: promote to OPEN_TRACKING (priority 4) — never downgrade.
        if (p.status.priority < PositionStatus.OPEN_TRACKING.priority) {
            p.status = PositionStatus.OPEN_TRACKING
        }
        p.symbol = ts.symbol.takeIf { it.isNotBlank() } ?: p.symbol
        p.name = ts.name.takeIf { it.isNotBlank() } ?: p.name
        p.source = PositionSource.TX_PARSE
        p.buySignature = sig ?: p.buySignature
        p.buyTimeMs = pos.entryTime.takeIf { it > 0 } ?: p.buyTimeMs
        p.uiAmount = pos.qtyToken
        p.entryPriceUsd = pos.entryPrice.takeIf { it > 0 } ?: p.entryPriceUsd
        p.entrySol = pos.costSol.takeIf { it > 0 } ?: p.entrySol
        p.lastSeenWalletMs = now
        positions[ts.mint] = p
        emitForensic(LiveTradeLogStore.Phase.TOKEN_TRACKER_BUY_CONFIRMED, ts.mint, ts.symbol, sig,
            "Tracker BUY_CONFIRMED ${ts.symbol} qty=${pos.qtyToken} entry=${pos.entryPrice}")
        emitForensic(LiveTradeLogStore.Phase.TOKEN_TRACKER_OPEN_TRACKING, ts.mint, ts.symbol, sig,
            "→ OPEN_TRACKING")
        save()
    }

    // ─────────────────────────────────────────────────────────────────
    // SELL flow
    // ─────────────────────────────────────────────────────────────────

    /** Called the moment a sell decision fires (TP/SL/trail/manual). */
    fun recordExitSignalled(mint: String, symbol: String?, sellAttemptId: String?, reason: String?) {
        val p = positions[mint] ?: return
        if (p.status.priority < PositionStatus.EXIT_SIGNALLED.priority) {
            p.status = PositionStatus.EXIT_SIGNALLED
        }
        p.activeSellAttemptId = sellAttemptId
        emitForensic(LiveTradeLogStore.Phase.TOKEN_TRACKER_EXIT_SIGNALLED, mint, symbol, null,
            "Tracker EXIT_SIGNALLED ${symbol ?: mint.take(6)} reason=${reason ?: "?"}")
        save()
    }

    /** Called when a sell tx is broadcast and accepted by the network. */
    fun recordSellPending(mint: String, sig: String?) {
        val p = positions[mint] ?: return
        if (sig.isNullOrBlank()) {
            // V5.9.607 — SELL_PENDING only after a real signature exists.
            // No-signature sell attempts are failures, not pending/verifying.
            p.status = PositionStatus.OPEN_TRACKING
            p.activeSellAttemptId = null
            emitForensic(LiveTradeLogStore.Phase.WARNING, mint, p.symbol, null,
                "SELL_PENDING_REJECTED_NO_SIGNATURE ${p.symbol ?: mint.take(6)} — restored OPEN_TRACKING")
            save()
            return
        }
        if (p.status.priority < PositionStatus.SELL_PENDING.priority) {
            p.status = PositionStatus.SELL_PENDING
        }
        p.sellSignature = sig
        p.lastExitCheckMs = System.currentTimeMillis()
        emitForensic(LiveTradeLogStore.Phase.TOKEN_TRACKER_SELL_PENDING, mint, p.symbol, sig,
            "Tracker SELL_PENDING ${p.symbol ?: mint.take(6)}")
        save()
    }

    /**
     * Called when sell tx parse succeeds. If the remaining wallet balance is
     * dust the position transitions to SOLD_CONFIRMED → CLOSED. Otherwise
     * we keep tracking the residual qty.
     */
    fun recordSellConfirmed(mint: String, symbol: String?, exitPrice: Double, pnlPct: Double, reason: String?) {
        val p = positions[mint] ?: return
        // V5.9.601: host-wallet tracker is live-wallet truth only. Paper exits
        // must never close, verify, profit-mark, or otherwise mutate a live
        // wallet position.
        if (reason?.contains("PAPER", ignoreCase = true) == true) {
            emitForensic(LiveTradeLogStore.Phase.WARNING, mint, symbol ?: p.symbol, p.sellSignature,
                "PAPER_EXIT_BLOCKED_FROM_HOST_TRACKER ${symbol ?: p.symbol ?: mint.take(6)} reason=$reason")
            return
        }
        // SELL_VERIFYING is only valid after a real signature exists.
        if (p.sellSignature.isNullOrBlank()) {
            p.status = PositionStatus.OPEN_TRACKING
            p.activeSellAttemptId = null
            p.notes.add("sell failed/no signature reason=${reason ?: "?"}")
            emitForensic(LiveTradeLogStore.Phase.WARNING, mint, symbol ?: p.symbol, null,
                "SELL_CONFIRMED_REJECTED_NO_SIGNATURE ${symbol ?: p.symbol ?: mint.take(6)} — restored OPEN_TRACKING")
            save()
            return
        }
        // If the wallet snapshot has already shown 0, recordWalletEmpty()
        // will collapse this to SOLD_CONFIRMED. Until then, mark verifying.
        if (p.status.priority < PositionStatus.SELL_VERIFYING.priority) {
            p.status = PositionStatus.SELL_VERIFYING
        }
        p.lastExitCheckMs = System.currentTimeMillis()
        p.notes.add("sell exit reason=${reason ?: "?"} pnl=${pnlPct}%")
        emitForensic(LiveTradeLogStore.Phase.TOKEN_TRACKER_SELL_CONFIRMED, mint, symbol ?: p.symbol, p.sellSignature,
            "Tracker SELL_CONFIRMED ${symbol ?: p.symbol ?: mint.take(6)} exit=$exitPrice pnl=${pnlPct}% reason=${reason ?: "?"}")
        // If a confirmed live wallet snapshot already shows empty, close immediately.
        if (p.uiAmount <= 0.0) {
            p.status = PositionStatus.CLOSED
            emitForensic(LiveTradeLogStore.Phase.TOKEN_TRACKER_CLOSED, mint, symbol ?: p.symbol, p.sellSignature,
                "Tracker CLOSED ${symbol ?: p.symbol ?: mint.take(6)}")
        }
        save()
    }

    // ─────────────────────────────────────────────────────────────────
    // Wallet reconcile
    // ─────────────────────────────────────────────────────────────────

    /**
     * Called by WalletReconciler with the live on-chain wallet snapshot.
     * Resolves: (a) orphan recovery — wallet has tokens we don't track;
     * (b) zombie closure — we track tokens the wallet no longer holds.
     *
     * Monotonic guarantees:
     *   - Will never demote SELL_VERIFYING / SOLD_CONFIRMED back to OPEN.
     *   - Will never overwrite a higher status (e.g. EXIT_SIGNALLED) with
     *     a lower one (HELD_IN_WALLET).
     */
    fun applyWalletSnapshot(walletMints: Map<String, Pair<Double, Int>>) {
        if (walletMints.isEmpty()) return  // RPC blip — defended elsewhere too
        val now = System.currentTimeMillis()

        // Pass 1: orphan recovery / refresh existing.
        for ((mint, pair) in walletMints) {
            if (mint == SOL_MINT) continue
            val (uiAmount, decimals) = pair
            val rawApprox = (uiAmount * Math.pow(10.0, decimals.toDouble())).toLong()
            val existing = positions[mint]
            if (existing != null) {
                existing.uiAmount = uiAmount
                existing.decimals = decimals
                existing.rawAmount = rawApprox.toString()
                existing.lastSeenWalletMs = now
                existing.lastWalletReconcileMs = now
                if (rawApprox > DUST_RAW && existing.status == PositionStatus.SELL_VERIFYING && existing.sellSignature.isNullOrBlank()) {
                    // V5.9.607 — SELL_VERIFYING is only valid after a real tx
                    // signature exists. Forensics showed stuck rows with
                    // last_sell_signature="" while wallet_uiAmount was still
                    // present. Restore to OPEN_TRACKING so exit monitoring
                    // continues instead of freezing the position forever.
                    existing.status = PositionStatus.OPEN_TRACKING
                    existing.activeSellAttemptId = null
                    emitForensic(LiveTradeLogStore.Phase.TOKEN_TRACKER_OPEN_TRACKING, mint, existing.symbol, null,
                        "SELL_VERIFYING without signature + wallet still holds qty=$uiAmount → restored OPEN_TRACKING")
                } else if (rawApprox > DUST_RAW && existing.status == PositionStatus.SELL_VERIFYING && !existing.sellSignature.isNullOrBlank()) {
                    val started = existing.lastExitCheckMs ?: now
                    if (now - started > 120_000L) {
                        existing.status = PositionStatus.OPEN_TRACKING
                        existing.activeSellAttemptId = null
                        emitForensic(LiveTradeLogStore.Phase.WARNING, mint, existing.symbol, existing.sellSignature,
                            "SELL_VERIFYING_TIMEOUT wallet still holds qty=$uiAmount after ${(now-started)/1000}s → restored OPEN_TRACKING")
                    }
                } else if (rawApprox > DUST_RAW &&
                    existing.status in setOf(
                        PositionStatus.BUY_PENDING,
                        PositionStatus.BUY_CONFIRMED,
                        PositionStatus.HELD_IN_WALLET,
                        PositionStatus.UNKNOWN_NEEDS_RECONCILE,
                    )
                ) {
                    existing.status = PositionStatus.OPEN_TRACKING
                    emitForensic(LiveTradeLogStore.Phase.TOKEN_TRACKER_OPEN_TRACKING, mint, existing.symbol, null,
                        "Wallet truth: ${existing.symbol ?: mint.take(6)} promoted to OPEN_TRACKING qty=$uiAmount")
                }
                continue
            }
            if (rawApprox <= DUST_RAW) continue
            // True orphan — bot has no record of buying this. Recover.
            val recovered = TrackedTokenPosition(
                mint = mint, symbol = "RECOVERED_${mint.take(6)}", name = "Wallet Recovered",
                source = PositionSource.WALLET_RECONCILED,
                status = PositionStatus.OPEN_TRACKING,
                buySignature = null, sellSignature = null,
                buyTimeMs = null, firstSeenWalletMs = now, lastSeenWalletMs = now,
                entryPriceUsd = null, entrySol = null, entryMarketCap = null,
                rawAmount = rawApprox.toString(), decimals = decimals, uiAmount = uiAmount,
                currentPriceUsd = null, currentValueSol = null, currentValueAud = null,
                highestPriceUsd = null, lowestPriceUsd = null,
                maxGainPct = 0.0, maxDrawdownPct = 0.0,
                takeProfitPct = 0.0, stopLossPct = 0.0, trailingStopPct = null,
                venue = null, pool = null,
                lastPriceUpdateMs = null, lastWalletReconcileMs = now, lastExitCheckMs = null,
                activeSellAttemptId = null,
                notes = mutableListOf("Recovered from host wallet; was missing from bot state"),
            )
            positions[mint] = recovered
            emitForensic(LiveTradeLogStore.Phase.TOKEN_TRACKER_RECOVERED_FROM_WALLET, mint, recovered.symbol, null,
                "Tracker RECOVERED_FROM_WALLET qty=$uiAmount mint=${mint.take(8)}…")
        }

        // Pass 2: zombie closure — open positions whose wallet balance is now zero.
        for (p in positions.values.toList()) {
            if (p.status !in OPEN_STATUSES) continue
            val pair = walletMints[p.mint]
            val walletUi = pair?.first ?: 0.0
            if (walletUi > 0.0) continue
            // Wallet shows zero. If we have a sell tx parse already confirmed,
            // collapse to SOLD_CONFIRMED → CLOSED. Otherwise mark
            // UNKNOWN_NEEDS_RECONCILE (don't delete — operator spec: do not
            // immediately delete).
            if (p.status == PositionStatus.SELL_VERIFYING || p.sellSignature != null) {
                p.status = PositionStatus.SOLD_CONFIRMED
                emitForensic(LiveTradeLogStore.Phase.TOKEN_TRACKER_SELL_CONFIRMED, p.mint, p.symbol, p.sellSignature,
                    "Tracker wallet=0 + sell sig present → SOLD_CONFIRMED")
                p.status = PositionStatus.CLOSED
                p.uiAmount = 0.0
                emitForensic(LiveTradeLogStore.Phase.TOKEN_TRACKER_CLOSED, p.mint, p.symbol, p.sellSignature,
                    "Tracker CLOSED ${p.symbol ?: p.mint.take(6)}")
            } else {
                p.status = PositionStatus.UNKNOWN_NEEDS_RECONCILE
                p.uiAmount = 0.0
                emitForensic(LiveTradeLogStore.Phase.POSITION_COUNT_RECONCILED, p.mint, p.symbol, null,
                    "Tracker wallet=0 but no sell sig — UNKNOWN_NEEDS_RECONCILE")
            }
        }

        save()
    }

    // ─────────────────────────────────────────────────────────────────
    // Public read API — dashboards / exit engine / cleanup
    // ─────────────────────────────────────────────────────────────────

    /** True if this mint is currently tracked as open (any of the OPEN_STATUSES). */
    fun hasOpenPosition(mint: String): Boolean {
        if (mint.isBlank()) return false
        val p = positions[mint] ?: return false
        return p.status in OPEN_STATUSES
    }

    /** All currently-open tracked positions, used by exit engine fallback loop. */
    fun getOpenTrackedPositions(): List<TrackedTokenPosition> =
        positions.values.filter { it.status in OPEN_STATUSES }.toList()

    /** Open count for dashboard "Open" tile. */
    fun getOpenCount(): Int = positions.values.count { it.status in OPEN_STATUSES }

    /** Snapshot of every tracked position (open + closed) — diagnostics. */
    /** Count positions with actual wallet token amount above dust. */
    fun getActuallyHeldCount(): Int = positions.values.count { it.uiAmount > 0.000001 && it.status in OPEN_STATUSES }

    /** True only when wallet-truth says this mint has a non-dust token amount. */
    fun isActuallyHeld(mint: String): Boolean {
        val p = positions[mint] ?: return false
        return p.uiAmount > 0.000001 && p.status in OPEN_STATUSES
    }

    /** V5.9.612 AntiChoke: wallet snapshot proved zero; unblock internal ghost state. */
    fun markUnheldByAntiChoke(mint: String, reason: String): Boolean {
        val p = positions[mint] ?: return false
        if (p.uiAmount > 0.000001) return false
        p.status = PositionStatus.CLOSED
        p.activeSellAttemptId = null
        p.notes.add("anti-choke closed unheld: $reason")
        emitForensic(LiveTradeLogStore.Phase.POSITION_COUNT_RECONCILED, mint, p.symbol, p.sellSignature,
            "AntiChoke closed unheld tracker row: $reason")
        save()
        return true
    }

    /** Snapshot of every tracked position (open + closed) — diagnostics. */
    fun snapshot(): List<TrackedTokenPosition> = positions.values.toList()

    /** V5.9.601: true when auto-sell must not start another executor job. */
    fun isSellInFlight(mint: String): Boolean {
        if (mint.isBlank()) return false
        val p = positions[mint] ?: return false
        return p.status in SELL_IN_FLIGHT_STATUSES || !p.activeSellAttemptId.isNullOrBlank()
    }

    fun sellBlockReason(mint: String): String? {
        val p = positions[mint] ?: return null
        return if (isSellInFlight(mint)) "${p.status.name} attempt=${p.activeSellAttemptId ?: p.sellSignature ?: "?"}" else null
    }

    fun getEntry(mint: String): TrackedTokenPosition? = positions[mint]

    /** Update price/value during exit-monitor tick. */
    fun recordPriceUpdate(mint: String, priceUsd: Double, gainPct: Double) {
        val p = positions[mint] ?: return
        p.currentPriceUsd = priceUsd
        p.lastPriceUpdateMs = System.currentTimeMillis()
        p.maxGainPct = maxOf(p.maxGainPct, gainPct)
        if (gainPct < 0.0) p.maxDrawdownPct = minOf(p.maxDrawdownPct, gainPct)
        if (p.highestPriceUsd == null || priceUsd > (p.highestPriceUsd ?: 0.0)) p.highestPriceUsd = priceUsd
        if (p.lowestPriceUsd == null || priceUsd < (p.lowestPriceUsd ?: Double.MAX_VALUE)) p.lowestPriceUsd = priceUsd
    }

    /** Single forensic line emitter — log to ErrorLogger AND LiveTradeLogStore. */
    private fun emitForensic(
        phase: LiveTradeLogStore.Phase,
        mint: String,
        symbol: String?,
        sig: String?,
        message: String,
    ) {
        try {
            LiveTradeLogStore.log(
                tradeKey = "TRACKER_${mint.take(16)}",
                mint = mint,
                symbol = symbol ?: mint.take(6),
                side = "INFO",
                phase = phase,
                message = message,
                sig = sig,
                traderTag = "TRACKER",
            )
        } catch (_: Throwable) {}
        try { ErrorLogger.info(TAG, message) } catch (_: Throwable) {}
    }

    /** One-line digest for diagnostics. */
    fun getStats(): String {
        val open = getOpenCount()
        val closed = positions.values.count { it.status == PositionStatus.CLOSED }
        val unknown = positions.values.count { it.status == PositionStatus.UNKNOWN_NEEDS_RECONCILE }
        return "open=$open · closed=$closed · unknown=$unknown · total=${positions.size}"
    }

    /**
     * V5.9.661c — wipe every tracked position. Called from
     * BotService.stopBot() so the UI's "Open" counter (which unions
     * getOpenTrackedPositions() with the other lane stores) drops to
     * 0 alongside the rest of the position state on stop.
     *
     * Live-mode safety note: in live mode the bot's stopBot() calls
     * liveSweepWalletTokens() FIRST, which broadcasts on-chain swaps
     * for every non-stablecoin holding. This clearAll() runs only
     * after that sweep, so we are not lying about wallet contents —
     * the next applyWalletSnapshot tick from the wallet poller will
     * re-import any leftovers (e.g. stablecoins) on the next start.
     */
    @Synchronized
    fun clearAll() {
        val n = positions.size
        positions.clear()
        try { com.lifecyclebot.engine.ErrorLogger.info(TAG, "🧹 HostWalletTokenTracker.clearAll(): wiped $n entries") } catch (_: Throwable) {}
    }

    /**
     * V5.9.777 — EMERGENT MEME-ONLY stop-bot live preservation.
     *
     * Operator forensics_20260516_014510.json: WC2026, early, GOAT
     * all reached BUY_VERIFIED_LANDED + TOKEN_TRACKER_OPEN_TRACKING
     * but then VANISHED from the host tracker. Root cause:
     * BotService.stopBot() unconditionally called clearAll(), wiping
     * every live OPEN_TRACKING position alongside paper artefacts.
     *
     * This helper removes ONLY paper/closed/dust artefacts and
     * leaves live wallet-backed positions intact so a stop/start
     * cycle (or an OS-driven service kill) does not nuke the live
     * MEME book. Live mints are preserved across stopBot() —
     * applyWalletSnapshot() will reconcile them on the next start.
     *
     * Statuses preserved (live wallet truth):
     *   BUY_CONFIRMED, HELD_IN_WALLET, OPEN_TRACKING,
     *   EXIT_SIGNALLED, SELL_PENDING, SELL_VERIFYING
     *
     * Statuses cleared:
     *   UNKNOWN_NEEDS_RECONCILE, DUST_IGNORED, BUY_PENDING,
     *   SOLD_CONFIRMED, CLOSED — plus everything originating from
     *   PositionSource.BOT_BUY in PAPER context (those statuses
     *   don't survive past CLOSED so this is implicit).
     */
    @Synchronized
    fun clearPaperOnly() {
        val before = positions.size
        val livePreserved = OPEN_STATUSES + setOf(
            PositionStatus.SELL_PENDING,
            PositionStatus.SELL_VERIFYING,
        )
        val it = positions.entries.iterator()
        var removed = 0
        var kept = 0
        while (it.hasNext()) {
            val e = it.next()
            if (e.value.status in livePreserved) {
                kept++
            } else {
                it.remove()
                removed++
            }
        }
        try {
            com.lifecyclebot.engine.ErrorLogger.info(
                TAG,
                "🧹 HostWalletTokenTracker.clearPaperOnly(): before=$before removed=$removed liveKept=$kept",
            )
        } catch (_: Throwable) {}
    }
}
