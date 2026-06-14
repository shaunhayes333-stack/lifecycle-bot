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

    // V5.9.778 — EMERGENT MEME-ONLY: manual-swap detection grace window.
    // A wallet snapshot can briefly miss a mint due to RPC propagation
    // delay. We only mark CLOSED_EXTERNALLY_MANUAL_SWAP when the token
    // has been absent for at least this long since the last positive
    // sighting, otherwise a single empty snapshot would terminally
    // close a healthy position.
    private const val MANUAL_SWAP_GRACE_MS = 90_000L

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

        // V5.9.778 — EMERGENT MEME-ONLY: external-swap terminal states.
        // Operator forensics 5.0.2709: user manually swapped received
        // tokens (SEHUR / TTTS / etc.) → SOL because AATE failed to
        // sell. The tracker kept them in OPEN_TRACKING forever and
        // spawned endless sell retries. We now distinguish AATE-driven
        // sells from external user/wallet swaps:
        //   CLOSED_SOLD_BY_AATE         — AATE broadcasted+confirmed sell
        //   CLOSED_EXTERNALLY_MANUAL_SWAP — wallet snapshot shows the
        //       mint vanished while sellSignature was empty, i.e. user
        //       (or another tool) swapped it externally. No retries.
        CLOSED_SOLD_BY_AATE(10),
        CLOSED_EXTERNALLY_MANUAL_SWAP(11),
    }

    /** Statuses considered "open" for dashboard / exit-monitor / cleanup-protect. */
    internal val OPEN_STATUSES: Set<PositionStatus> = setOf(
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
        // V5.9.1521 — wall-clock when the current sell attempt was signalled.
        // Used by isSellInFlight's stale-TTL self-heal so a sell attempt that
        // never cleared (failed swap / RPC timeout / restart mid-sell) cannot
        // permanently block future stop-loss attempts on the same mint.
        var sellAttemptStartedMs: Long = 0L,
        val notes: MutableList<String> = mutableListOf(),
        // V5.9.1496 — zero-balance close debounce. Spec: require 2 consecutive
        // zero-balance confirmations OR a confirmed sell signature before CLOSED,
        // so a single transient RPC miss can't strand or falsely-close a row.
        var consecutiveZeroConfirms: Int = 0,
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
                    put("zeroConfirms", p.consecutiveZeroConfirms)  // V5.9.1496
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
                    consecutiveZeroConfirms = o.optInt("zeroConfirms", 0),  // V5.9.1496
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
        // V5.9.1369 — GHOST POSITION FIX. This tracker is LIVE-WALLET TRUTH ONLY
        // (recordSellConfirmed already early-returns on PAPER exits, line ~393). But
        // recordBuyConfirmed had NO paper guard, so paper BUYS entered the tracker as
        // OPEN_TRACKING while paper SELLS were forbidden from closing them — a monotonic
        // ghost accumulator. getOpenCount() climbed forever (operator saw 7 real opens
        // but UI showed 24 via maxOf(host, lifecycle, ui, cashgen) in MainActivity).
        // A paper position has no on-chain balance, so applyWalletSnapshot can never
        // collapse it to CLOSED either. Fix: paper positions NEVER enter the live-wallet
        // tracker. Symmetric with the paper-exit block — keeps this ledger pure live
        // truth (open_count = real on-chain holdings only).
        val isPaper = ts.position.isPaperPosition ||
            (try { RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false })
        if (isPaper) {
            try {
                emitForensic(LiveTradeLogStore.Phase.WARNING, ts.mint, ts.symbol, sig,
                    "PAPER_BUY_NOT_TRACKED_IN_HOST_WALLET ${ts.symbol} — live tracker is on-chain truth only")
            } catch (_: Throwable) {}
            return
        }
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
        // V5.0.3689 — fresh live buy is authoritative OPEN_TRACKING.
        // The old priority-monotonic rule left a same-mint rebuy stuck CLOSED
        // because CLOSED priority(9) > OPEN_TRACKING(4). Result: token landed,
        // row carried uiAmount from the new buy, but sell/UI authority read it
        // as closed/zero and stopped managing it. A verified BUY must reopen
        // the tracker and clear stale sell/close bookkeeping; only a later
        // authoritative sell or confirmed wallet-zero reconcile may close it.
        p.status = PositionStatus.OPEN_TRACKING
        p.sellSignature = null
        p.activeSellAttemptId = null
        p.sellAttemptStartedMs = 0L
        p.consecutiveZeroConfirms = 0
        p.notes.add("fresh live buy reopened tracker gen=${try { BotRuntimeController.currentGeneration() } catch (_: Throwable) { 0L }}")
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
        p.sellAttemptStartedMs = System.currentTimeMillis()   // V5.9.1521 — stale-TTL anchor
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
        val authoritativeParseClose = reason?.let { r ->
            r.contains("TX_PARSE", ignoreCase = true) ||
            r.contains("TOKEN_CONSUMED", ignoreCase = true) ||
            r.contains("ATA_CLOSED", ignoreCase = true) ||
            r.contains("ACCOUNT_CLOSED", ignoreCase = true) ||
            r.contains("SOL_RETURNED", ignoreCase = true) ||
            r.contains("FULL_TOKEN_CONSUMPTION", ignoreCase = true)
        } == true
        // V5.9.1582 — TX_PARSE/ATA/token-consumed close is authoritative even if
        // the tracker row is missing sellSignature. Do NOT resurrect OPEN_TRACKING
        // just because a later signature field is blank; that poisoned live buys
        // via HOST_TRACKER_DESYNC / ORPHAN_LIVE_POSITIONS after successful exits.
        if (p.sellSignature.isNullOrBlank() && authoritativeParseClose) {
            p.status = PositionStatus.CLOSED
            p.activeSellAttemptId = null
            p.consecutiveZeroConfirms = 0
            p.uiAmount = 0.0
            p.rawAmount = "0"
            p.notes.add("authoritative tx-parse close reason=${reason ?: "?"}")
            emitForensic(LiveTradeLogStore.Phase.TOKEN_TRACKER_CLOSED, mint, symbol ?: p.symbol, null,
                "Tracker CLOSED_BY_TX_PARSE_NO_SIGNATURE ${symbol ?: p.symbol ?: mint.take(6)} reason=${reason ?: "?"}")
            save()
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


    /**
     * V5.9.1582 — authoritative sell finalization from tx parse / ATA close / SOL return.
     * This is stronger than tracker signature bookkeeping. Once called, wallet reconcile
     * must not restore OPEN_TRACKING unless a later wallet snapshot proves non-dust balance.
     */
    @Synchronized
    fun recordAuthoritativeTxParseClose(mint: String, symbol: String?, sig: String?, reason: String) {
        val p = positions[mint] ?: return
        p.sellSignature = sig?.takeIf { it.isNotBlank() } ?: p.sellSignature
        p.status = PositionStatus.CLOSED
        p.activeSellAttemptId = null
        p.consecutiveZeroConfirms = 0
        p.uiAmount = 0.0
        p.rawAmount = "0"
        p.notes.add("authoritative close: $reason")
        emitForensic(LiveTradeLogStore.Phase.TOKEN_TRACKER_CLOSED, mint, symbol ?: p.symbol, p.sellSignature,
            "Tracker CLOSED_BY_AUTHORITATIVE_TX_PARSE ${symbol ?: p.symbol ?: mint.take(6)} reason=$reason")
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
                // V5.9.1496 — balance returned → cancel any pending zero-close debounce.
                if (uiAmount > 0.000001) existing.consecutiveZeroConfirms = 0
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
                        PositionStatus.CLOSED,
                        PositionStatus.CLOSED_SOLD_BY_AATE,
                    )
                ) {
                    // V5.9.1527 — SELL SOURCE FIX (spec item 1 + 9 + acceptance E/H):
                    // A position marked CLOSED while the wallet STILL HOLDS the token
                    // (> dust) is an impossible-by-invariant state. Root cause was the
                    // optimistic empty-map close path stamping CLOSED on a bare/absent
                    // sell signature (MARS: CLOSED + 984,443 tokens, last_sell_sig="").
                    // Wallet balance is ground truth → REOPEN to OPEN_TRACKING so the
                    // exit monitor re-engages and a real sell can complete. Clear the
                    // stale (unverified) sell signature so the close lifecycle restarts
                    // clean. This is the parity repair: walletHeldMints will now match
                    // canonical open/held after this snapshot.
                    existing.status = PositionStatus.OPEN_TRACKING
                    existing.sellSignature = null
                    existing.activeSellAttemptId = null
                    existing.consecutiveZeroConfirms = 0
                    existing.notes.add("REOPENED: WALLET_BALANCE_STILL_HELD qty=$uiAmount (was CLOSED with no zero-balance proof)")
                    emitForensic(LiveTradeLogStore.Phase.TOKEN_TRACKER_OPEN_TRACKING, mint, existing.symbol, null,
                        "WALLET_BALANCE_STILL_HELD → REOPENED ${existing.symbol ?: mint.take(6)} qty=$uiAmount (CLOSED-but-held invariant repair)")
                    try {
                        com.lifecyclebot.engine.ForensicLogger.lifecycle(
                            "RECONCILE_REOPEN_WALLET_BALANCE_STILL_HELD",
                            "mint=${mint.take(10)} symbol=${existing.symbol ?: "?"} qty=$uiAmount prevStatus=CLOSED"
                        )
                    } catch (_: Throwable) {}
                    // Release any terminal sell-job + close-lease so the lifecycle can restart.
                    try { com.lifecyclebot.engine.sell.SellExecutionLocks.release(mint) } catch (_: Throwable) {}
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
                    // V5.9.1081 — confirms a live position via wallet balance
                    // reconciliation (no signature available — synced from chain state).
                    try {
                        com.lifecyclebot.engine.ForensicLogger.lifecycle(
                            "LIVE_POSITION_CONFIRMED_FROM_WALLET",
                            "mint=${mint.take(10)} symbol=${existing.symbol ?: "?"} qty=$uiAmount"
                        )
                    } catch (_: Throwable) {}
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
            // V5.9.1081 — operator-spec'd forensic markers so the next pipeline
            // snapshot can prove orphans are being attached + monitored.
            try {
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "ORPHAN_WALLET_TOKEN_ATTACHED",
                    "mint=${mint.take(10)} qty=$uiAmount decimals=$decimals"
                )
            } catch (_: Throwable) {}
            try {
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "ORPHAN_WALLET_TOKEN_MONITORED_FOR_EXIT",
                    "mint=${mint.take(10)} status=OPEN_TRACKING source=WALLET_RECONCILED"
                )
            } catch (_: Throwable) {}
        }

        // Pass 2: zombie closure — open positions whose wallet balance is now zero.
        for (p in positions.values.toList()) {
            if (p.status !in OPEN_STATUSES) continue
            val pair = walletMints[p.mint]
            val walletUi = pair?.first ?: 0.0
            if (walletUi > 0.0) continue
            // Wallet shows zero. Distinguish three cases:
            //  (a) AATE broadcasted a sell (sellSignature != null) →
            //      CLOSED_SOLD_BY_AATE then CLOSED.
            //  (b) Position was in SELL_VERIFYING but never got a sig →
            //      treat as SOLD_CONFIRMED → CLOSED (tx confirmed off-band).
            //  (c) sellSignature is null AND activeSellAttemptId is null
            //      AND the token had previously been seen in the wallet
            //      → user/external tool swapped it manually. Mark
            //      CLOSED_EXTERNALLY_MANUAL_SWAP; do NOT spawn retries.
            //  (d) Otherwise → UNKNOWN_NEEDS_RECONCILE (transient RPC).
            //
            // V5.9.778 — EMERGENT MEME-ONLY: case (c) is the new branch
            // operator demanded — manual wallet swap terminal close.
            val freshBuyAgeMs = p.buyTimeMs?.let { now - it } ?: Long.MAX_VALUE
            if (p.sellSignature.isNullOrBlank() && p.status in OPEN_STATUSES && freshBuyAgeMs in 0L..180_000L) {
                // V5.0.3689 — live-buy indexer grace. A fresh TX_PARSE-confirmed buy
                // may not appear in bulk wallet maps immediately. Do not convert
                // OPEN_TRACKING to CLOSED/ui=0 without a sell signature or mature,
                // repeated zero-balance reconcile. Keep authority open so exits/UI
                // do not lose a wallet-held token during indexer lag.
                p.status = PositionStatus.OPEN_TRACKING
                p.consecutiveZeroConfirms = 0
                emitForensic(LiveTradeLogStore.Phase.WARNING, p.mint, p.symbol, null,
                    "FRESH_BUY_ZERO_RECONCILE_DEFERRED ageMs=$freshBuyAgeMs — keeping OPEN_TRACKING")
                save()
                continue
            }
            if (p.status == PositionStatus.SELL_VERIFYING || p.sellSignature != null) {
                p.status = PositionStatus.CLOSED_SOLD_BY_AATE
                emitForensic(LiveTradeLogStore.Phase.TOKEN_TRACKER_SELL_CONFIRMED, p.mint, p.symbol, p.sellSignature,
                    "Tracker wallet=0 + AATE sell sig present → CLOSED_SOLD_BY_AATE")
                p.status = PositionStatus.CLOSED
                p.uiAmount = 0.0
                emitForensic(LiveTradeLogStore.Phase.TOKEN_TRACKER_CLOSED, p.mint, p.symbol, p.sellSignature,
                    "Tracker CLOSED ${p.symbol ?: p.mint.take(6)}")
            } else if (
                p.activeSellAttemptId == null &&
                p.sellSignature.isNullOrBlank() &&
                p.firstSeenWalletMs > 0L &&
                p.lastSeenWalletMs > 0L &&
                (now - p.lastSeenWalletMs) >= MANUAL_SWAP_GRACE_MS
            ) {
                // Case (c) — externally swapped (user manually sold to SOL).
                // The grace window (90 s default) protects against transient
                // RPC blips so we don't flip a healthy position to a terminal
                // status from a single empty snapshot.
                p.status = PositionStatus.CLOSED_EXTERNALLY_MANUAL_SWAP
                p.uiAmount = 0.0
                p.lastWalletReconcileMs = now
                emitForensic(LiveTradeLogStore.Phase.TOKEN_TRACKER_CLOSED, p.mint, p.symbol, null,
                    "MANUAL_SWAP_DETECTED ${p.symbol ?: p.mint.take(6)} — wallet=0 with no AATE sell sig; user swapped externally. No retries.")
                try {
                    com.lifecyclebot.engine.ForensicLogger.lifecycle(
                        "MANUAL_WALLET_SWAP_DETECTED",
                        "mint=${p.mint.take(10)} symbol=${p.symbol ?: ""} firstSeenAt=${p.firstSeenWalletMs} lastSeenAt=${p.lastSeenWalletMs}",
                    )
                } catch (_: Throwable) {}
            } else {
                p.status = PositionStatus.UNKNOWN_NEEDS_RECONCILE
                p.uiAmount = 0.0
                emitForensic(LiveTradeLogStore.Phase.POSITION_COUNT_RECONCILED, p.mint, p.symbol, null,
                    "Tracker wallet=0 but no sell sig + in-flight attempt — UNKNOWN_NEEDS_RECONCILE")
            }
        }

        // V5.9.1501 — after wallet truth is applied, reap any zero-balance open
        // rows that escaped Pass-2 (in-flight sells that landed off-band, RPC
        // blips, sig-less closes). This is what keeps the dashboard "Open" tile
        // equal to wallet-truth instead of drifting to "1/31".
        try { reapZeroBalanceGhosts() } catch (_: Throwable) {}

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

    /** All currently-open tracked positions, used by exit engine fallback loop.
     *  WALLET-TRUTH filtered: zero-balance rows are ghosts and excluded unless a
     *  sell is in flight (the exit engine still needs to finish those). */
    fun getOpenTrackedPositions(): List<TrackedTokenPosition> =
        positions.values.filter {
            it.status in OPEN_STATUSES && (it.uiAmount > 0.000001 || it.status in SELL_IN_FLIGHT_STATUSES)
        }.toList()

    /** Open count for dashboard "Open" tile. WALLET-TRUTH: a row in an OPEN
     *  status but holding ZERO tokens is a ghost (resolved/sold) and is NOT
     *  counted — unless a sell is genuinely in flight (counted until it lands).
     *  This is what stops the "1/31" ghost inflation at the source. */
    fun getOpenCount(): Int = positions.values.count {
        it.status in OPEN_STATUSES && (it.uiAmount > 0.000001 || it.status in SELL_IN_FLIGHT_STATUSES)
    }

    /** Snapshot of every tracked position (open + closed) — diagnostics. */
    /** Count positions with actual wallet token amount above dust. */
    fun getActuallyHeldCount(): Int = positions.values.count { it.uiAmount > 0.000001 && it.status in OPEN_STATUSES }

    /** V5.9.1509 — CANONICAL WALLET-TRUTH HELD SET. Operator hard rule: "unless
     *  the bot has the position currently held it shouldn't acknowledge a position
     *  is open." Returns the set of mints the wallet ACTUALLY holds above dust
     *  (plus sells genuinely in flight, which still hold tokens until the swap
     *  lands). The UI open-count and any "managed open" union MUST intersect
     *  against this set so AI-side active maps that believe a position is open
     *  cannot inflate the count past on-chain reality. */
    fun getActuallyHeldMints(): Set<String> =
        positions.values.asSequence()
            .filter { it.status in OPEN_STATUSES && (it.uiAmount > 0.000001 || it.status in SELL_IN_FLIGHT_STATUSES) }
            .map { it.mint }
            .toCollection(HashSet())


    // V5.9.1501 — ZERO-BALANCE GHOST REAPER (root cause of "1/31 open").
    // A row in an OPEN_STATUS with uiAmount==0 holds NO wallet tokens — it is a
    // resolved/sold position whose Pass-2 close was skipped (e.g. an in-flight
    // sell attempt that landed off-band, an RPC blip during the close, or a
    // SELL_PENDING/EXIT_SIGNALLED that completed without a captured sig). These
    // accumulated forever and inflated every open counter (getOpenCount /
    // getOpenTrackedPositions) and the dashboard tile, while wallet-truth held 1.
    // A row that has been zero-balance for longer than this grace AND has no
    // genuinely in-flight sell lifecycle is reaped to CLOSED. Grace protects a
    // freshly-opened buy whose wallet hasn't hydrated yet (lastSeenWalletMs==0).
    private const val GHOST_REAP_GRACE_MS = 60_000L

    /** Returns number of ghost rows reaped to CLOSED. Safe to call every tick. */
    /**
     * V5.9.1507 — STARTUP HARD GHOST RECONCILE. Operator: "it should instantly
     * refresh to 0 on bot start." On Start we take ONE authoritative wallet
     * snapshot and terminally close every tracked OPEN position the wallet does
     * NOT actually hold (balance==0 / mint absent). Genuinely-held on-chain
     * tokens are KEPT (live positions stay sacred). This collapses the inflated
     * "0/36" open count to wallet-truth the instant the bot starts, instead of
     * waiting for the slow periodic reconciler. Also prunes the parallel
     * GlobalTradeRegistry so the PAPER-mode counter agrees.
     *
     * walletMints: fresh on-chain snapshot (mint -> (uiAmount, decimals)).
     * Pass an EMPTY map ONLY when you intend a full wipe (cold start, no holds).
     */
    fun forceStartupGhostReconcile(walletMints: Map<String, Pair<Double, Int>>): Int {
        var closed = 0
        for (p in positions.values.toList()) {
            if (p.status !in OPEN_STATUSES) continue
            val held = walletMints[p.mint]?.first ?: 0.0
            if (held > 0.000001) {
                // genuinely held — keep it, refresh wallet truth.
                p.uiAmount = held
                p.lastSeenWalletMs = System.currentTimeMillis()
                p.lastWalletReconcileMs = System.currentTimeMillis()
                continue
            }
            // Not held on-chain → terminal ghost. Close it now.
            p.status = PositionStatus.CLOSED
            p.uiAmount = 0.0
            p.activeSellAttemptId = null
            p.notes.add("startup ghost reconcile: wallet holds 0 at bot start")
            try { com.lifecyclebot.engine.PositionCloseLedger.markClosed(p.mint, "STARTUP_GHOST_RECONCILE", 0) } catch (_: Throwable) {}
            try {
                for (ln in listOf("SHITCOIN","MOONSHOT","QUALITY","EXPRESS","CYCLIC","BLUE_CHIP","MANIPULATED","CORE","V3","DIP_HUNTER","PROJECT_SNIPER")) {
                    com.lifecyclebot.engine.LaneExecutionCoordinator.releaseIfPrimary(p.mint, ln, "STARTUP_GHOST_RECONCILE")
                }
            } catch (_: Throwable) {}
            emitForensic(LiveTradeLogStore.Phase.POSITION_COUNT_RECONCILED, p.mint, p.symbol, null,
                "STARTUP_GHOST_RECONCILE wallet=0 → CLOSED (${p.symbol ?: p.mint.take(6)})")
            closed++
        }
        // Also collapse the parallel paper-mode registry to ledger-truth.
        try { com.lifecyclebot.engine.GlobalTradeRegistry.pruneClosedPositions() } catch (_: Throwable) {}
        // And the third count source: TokenLifecycleTracker non-terminal records
        // that the wallet does not hold. The UI "Open" tile is maxOf() across all
        // three stores, so ALL must collapse or the inflated number persists.
        try {
            for (r in com.lifecyclebot.engine.TokenLifecycleTracker.all()) {
                val held = walletMints[r.mint]?.first ?: 0.0
                if (held <= 0.000001) {
                    try { com.lifecyclebot.engine.TokenLifecycleTracker.forceClearUnheld(r.mint, "STARTUP_GHOST_RECONCILE") } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
        if (closed > 0) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("STARTUP_GHOST_RECONCILED") } catch (_: Throwable) {}
            save()
            try {
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "STARTUP_GHOST_RECONCILE_DONE",
                    "closed=$closed walletHeld=${walletMints.size} openAfter=${getOpenCount()}"
                )
            } catch (_: Throwable) {}
        }
        return closed
    }

    fun reapZeroBalanceGhosts(): Int {
        val now = System.currentTimeMillis()
        var reaped = 0
        for (p in positions.values.toList()) {
            if (p.status !in OPEN_STATUSES) continue
            if (p.uiAmount > 0.000001) continue                  // wallet-truth holds tokens — real
            if (p.status in SELL_IN_FLIGHT_STATUSES && p.lastSeenWalletMs > 0L &&
                (now - p.lastSeenWalletMs) < GHOST_REAP_GRACE_MS) continue  // genuine in-flight sell
            // Never reaped while still inside the open grace window after first
            // wallet sighting (protects a just-confirmed buy mid-hydration).
            val seenAnchor = maxOf(p.lastSeenWalletMs, p.lastWalletReconcileMs ?: 0L, p.buyTimeMs ?: 0L)
            if (seenAnchor > 0L && (now - seenAnchor) < GHOST_REAP_GRACE_MS) continue
            p.status = PositionStatus.CLOSED
            p.uiAmount = 0.0
            p.activeSellAttemptId = null
            p.notes.add("ghost reaped: zero wallet balance, no in-flight sell")
            try {
                com.lifecyclebot.engine.PositionCloseLedger.markClosed(p.mint, "GHOST_REAP_ZERO_BALANCE", 0)
            } catch (_: Throwable) {}
            // V5.9.1505 — a reaped dead position must (1) release any lane-primary
            // election it still holds so the slot frees, and (2) arm a re-entry
            // lockout so the bot does not instantly re-buy the same dead mint
            // next tick (operator: "just re-enters the same shit on repeat").
            try {
                val fam = (p.symbol ?: "").uppercase().trim().filter { it.isLetterOrDigit() }.take(8)
                com.lifecyclebot.engine.ReEntryLockout.onClose(p.mint, fam, "GHOST_REAP_ZERO_BALANCE", 0.0)
            } catch (_: Throwable) {}
            try {
                for (ln in listOf("SHITCOIN","MOONSHOT","QUALITY","EXPRESS","CYCLIC","BLUE_CHIP","MANIPULATED","CORE","V3","DIP_HUNTER","PROJECT_SNIPER")) {
                    com.lifecyclebot.engine.LaneExecutionCoordinator.releaseIfPrimary(p.mint, ln, "GHOST_REAP_FREE_SLOT")
                }
            } catch (_: Throwable) {}
            emitForensic(LiveTradeLogStore.Phase.POSITION_COUNT_RECONCILED, p.mint, p.symbol, p.sellSignature,
                "GHOST_REAPED zero-balance open row → CLOSED (${p.symbol ?: p.mint.take(6)})")
            reaped++
        }
        // V5.9.1505 — TERMINAL SWEEP for UNKNOWN_NEEDS_RECONCILE zero rows.
        // Pass-2 case (d) parks zero-balance positions with a wedged in-flight
        // sell attempt into UNKNOWN_NEEDS_RECONCILE. Nothing ever closed them, so
        // they accumulated forever holding lane-primary locks and polluting
        // re-entry/duplicate logic — the source of the "open count out by 30 and
        // increasing" drift. Any such row that has been zero-balance for >3 min
        // is dead: terminally close it, free its lane slots, arm re-entry lock.
        val unknownStaleMs = 180_000L
        for (p in positions.values.toList()) {
            if (p.status != PositionStatus.UNKNOWN_NEEDS_RECONCILE) continue
            if (p.uiAmount > 0.000001) continue
            val anchor2 = maxOf(p.lastWalletReconcileMs ?: 0L, p.lastSeenWalletMs, p.buyTimeMs ?: 0L)
            if (anchor2 > 0L && (now - anchor2) < unknownStaleMs) continue
            p.status = PositionStatus.CLOSED
            p.activeSellAttemptId = null
            p.notes.add("unknown-reconcile reaped: zero balance >3min, no resolution")
            try { com.lifecyclebot.engine.PositionCloseLedger.markClosed(p.mint, "UNKNOWN_RECONCILE_STALE_REAP", 0) } catch (_: Throwable) {}
            try {
                val fam = (p.symbol ?: "").uppercase().trim().filter { it.isLetterOrDigit() }.take(8)
                com.lifecyclebot.engine.ReEntryLockout.onClose(p.mint, fam, "GHOST_REAP_ZERO_BALANCE", 0.0)
            } catch (_: Throwable) {}
            try {
                for (ln in listOf("SHITCOIN","MOONSHOT","QUALITY","EXPRESS","CYCLIC","BLUE_CHIP","MANIPULATED","CORE","V3","DIP_HUNTER","PROJECT_SNIPER")) {
                    com.lifecyclebot.engine.LaneExecutionCoordinator.releaseIfPrimary(p.mint, ln, "UNKNOWN_STALE_FREE_SLOT")
                }
            } catch (_: Throwable) {}
            emitForensic(LiveTradeLogStore.Phase.POSITION_COUNT_RECONCILED, p.mint, p.symbol, null,
                "UNKNOWN_RECONCILE_STALE_REAPED → CLOSED (${p.symbol ?: p.mint.take(6)})")
            reaped++
        }

        if (reaped > 0) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ZERO_BALANCE_GHOST_REAPED") } catch (_: Throwable) {}
            save()
        }
        return reaped
    }

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

    /**
     * V5.9.1496 — debounced zero-balance close for LiveWalletReconciler.
     *
     * Spec (5.0.3501 ZERO-BALANCE OPEN_TRACKING CLEANUP): an OPEN_TRACKING row
     * seen at balance=0.0 must be CLOSED only after EITHER two consecutive
     * zero-balance confirmations OR one confirmed sell signature — never on a
     * single transient RPC read. On close we stamp the authoritative
     * PositionCloseLedger, drop the row from the open set, and return the
     * tracked position so the caller can release lane primary + write the
     * learning close outcome.
     *
     * @param hasConfirmedSellSig true if a real sell signature exists for this
     *        mint (immediate close path — no second confirmation needed).
     * @return the closed TrackedTokenPosition if it transitioned to CLOSED this
     *         call, or null if it is still pending a second confirmation / not
     *         tracked / already non-zero.
     */
    @Synchronized
    fun confirmZeroBalanceClose(
        mint: String,
        hasConfirmedSellSig: Boolean,
        reason: String,
    ): TrackedTokenPosition? {
        val p = positions[mint] ?: return null
        if (p.status == PositionStatus.CLOSED) return null
        // Defensive: only act on genuinely-empty rows. V5.9.1585: when the
        // live reconciler explicitly passes WALLET_BALANCE_ZERO, that observation
        // is fresher than the tracker's cached uiAmount. Update wallet truth first;
        // otherwise OPEN_TRACKING rows with stale uiAmount keep hostLive=1 forever
        // and poison buys with HOST_TRACKER_DESYNC / ORPHAN_LIVE_POSITIONS.
        if (p.uiAmount > 0.000001) {
            if (reason.contains("WALLET_BALANCE_ZERO", ignoreCase = true) || reason.contains("RECONCILER", ignoreCase = true)) {
                p.uiAmount = 0.0
                p.rawAmount = "0"
                p.lastWalletReconcileMs = System.currentTimeMillis()
            } else {
                p.consecutiveZeroConfirms = 0
                return null
            }
        }
        val confirms = p.consecutiveZeroConfirms + 1
        p.consecutiveZeroConfirms = confirms
        val canClose = hasConfirmedSellSig || confirms >= 2
        if (!canClose) {
            // First zero sighting without a sell sig — defer one tick.
            try {
                ForensicLogger.lifecycle(
                    "RECONCILER_ZERO_BALANCE_PENDING",
                    "mint=${mint.take(10)} symbol=${p.symbol} confirms=$confirms need=2 sig=${p.sellSignature ?: "none"}",
                )
            } catch (_: Throwable) {}
            save()
            return null
        }
        // Authoritative close.
        p.status = PositionStatus.CLOSED
        p.activeSellAttemptId = null
        p.consecutiveZeroConfirms = 0
        p.notes.add("zero-balance close ($reason) confirms=$confirms sig=${if (hasConfirmedSellSig) "yes" else "no"}")
        val pnlPct = try {
            val entry = p.entryPriceUsd ?: 0.0
            val cur = p.currentPriceUsd ?: 0.0
            if (entry > 0.0 && cur > 0.0) (((cur - entry) / entry) * 100.0).toInt() else 0
        } catch (_: Throwable) { 0 }
        // V5.9.1526 — route reconciler close through the authority. Here the
        // wallet balance is conclusively zero (this branch only runs on a real
        // zero read with >=2 confirms or a confirmed sig). If we hold a sell
        // signature this is a true sold-close; otherwise it's an explicit
        // zero-balance reap (tokens gone by other means) — tagged distinctly so
        // the regression guard does not flag it as a "CLOSED without signature".
        try {
            val cid = if (hasConfirmedSellSig) {
                com.lifecyclebot.engine.sell.CanonicalCloseAuthority.evaluate(
                    mint = mint, symbol = p.symbol ?: "?",
                    sellSignature = p.sellSignature, signatureConfirmed = true,
                    walletBalanceUi = 0.0, reason = "RECONCILER_ZERO_$reason", pnlPct = pnlPct,
                ).closeId ?: com.lifecyclebot.engine.PositionCloseLedger.markClosed(mint, "RECONCILER_ZERO_$reason", pnlPct)
            } else {
                // No sig — explicit reap of a genuinely empty wallet slot.
                com.lifecyclebot.engine.PositionCloseLedger.markClosed(mint, "RECONCILER_REAP_NOSIG_$reason", pnlPct)
            }
            ForensicLogger.lifecycle(
                "POSITION_CLOSE_LEDGER_STAMPED",
                "mint=${mint.take(10)} closeId=$cid reason=RECONCILER_ZERO_$reason source=reconciler sig=${if (hasConfirmedSellSig) "yes" else "reap"}",
            )
        } catch (_: Throwable) {}
        emitForensic(LiveTradeLogStore.Phase.POSITION_COUNT_RECONCILED, mint, p.symbol, p.sellSignature,
            "Zero-balance CLOSED by reconciler: $reason (confirms=$confirms)")
        save()
        return p
    }

    /** Snapshot of every tracked position (open + closed) — diagnostics. */
    /**
     * V5.9.1526 — close-authority audit for the regression guard. Scans tracker
     * records for invariant violations:
     *  - closedWithNonDustBalance: a CLOSED/SOLD position still holding > dust
     *  - closedWithoutSig: a CLOSED-by-sell position with no sell signature
     *    (CLOSED_EXTERNALLY_MANUAL_SWAP and reaps are excluded — they are not
     *     sell-claimed closes)
     *  - duplicateOpenMints: should always be 0 (map is keyed by mint) — kept
     *    as a defensive sentinel in case a future store-merge regresses.
     */
    data class CloseAudit(
        val closedWithNonDustBalance: Int,
        val closedWithoutSig: Int,
        val duplicateOpenMints: Int,
    )
    fun closeAuthorityAudit(): CloseAudit {
        var nonDust = 0
        var noSig = 0
        val seenOpen = HashSet<String>()
        var dupOpen = 0
        for (p in positions.values) {
            val isClosedStatus = p.status == PositionStatus.CLOSED ||
                p.status == PositionStatus.CLOSED_SOLD_BY_AATE
            if (isClosedStatus && p.uiAmount > 0.000001) nonDust++
            // sell-claimed close (AATE sold it) must carry a signature
            if (p.status == PositionStatus.CLOSED_SOLD_BY_AATE && p.sellSignature.isNullOrBlank()) noSig++
            if (p.status in OPEN_STATUSES) {
                if (!seenOpen.add(p.mint)) dupOpen++
            }
        }
        return CloseAudit(nonDust, noSig, dupOpen)
    }

    fun snapshot(): List<TrackedTokenPosition> = positions.values.toList()

    /** V5.9.601: true when auto-sell must not start another executor job. */
    /** V5.9.1521 — a sell attempt older than this is presumed dead. Any real
     *  Jupiter/Raydium swap + verify completes well under this; beyond it the
     *  in-flight state is a leak (failed swap, RPC timeout, restart mid-sell)
     *  and MUST NOT keep blocking the unconditional -15% hard-floor stop. */
    private const val SELL_IN_FLIGHT_STALE_MS = 90_000L

    fun isSellInFlight(mint: String): Boolean {
        if (mint.isBlank()) return false
        val p = positions[mint] ?: return false
        val flagged = p.status in SELL_IN_FLIGHT_STATUSES || !p.activeSellAttemptId.isNullOrBlank()
        if (!flagged) return false
        // Stale-TTL self-heal: if the attempt was signalled long ago and never
        // cleared, evict the leaked in-flight state so a fresh stop can fire.
        val startedMs = p.sellAttemptStartedMs
        if (startedMs > 0L && System.currentTimeMillis() - startedMs > SELL_IN_FLIGHT_STALE_MS) {
            try {
                ErrorLogger.warn(TAG,
                    "♻️ [SELL_IN_FLIGHT_STALE] ${p.symbol ?: mint.take(6)} — attempt ${(System.currentTimeMillis()-startedMs)/1000}s old, evicting leaked in-flight block so stop-loss can re-fire")
            } catch (_: Throwable) {}
            // Roll status back to a non-in-flight state and clear the attempt id
            // so the next requestSell proceeds. Wallet reconcile / verify watchdog
            // will re-establish truth; we only unblock the SELL path here.
            if (p.status in SELL_IN_FLIGHT_STATUSES) p.status = PositionStatus.OPEN_TRACKING
            p.activeSellAttemptId = null
            p.sellAttemptStartedMs = 0L
            return false
        }
        return true
    }

    fun sellBlockReason(mint: String): String? {
        val p = positions[mint] ?: return null
        return if (isSellInFlight(mint)) "${p.status.name} attempt=${p.activeSellAttemptId ?: p.sellSignature ?: "?"}" else null
    }

    /** V5.9.1522 — explicitly clear an in-flight sell marker after a route
     *  failure with no scheduled retry, so the next stop-loss attempt is not
     *  blocked by a leaked SELL_BLOCKED_ALREADY_IN_PROGRESS. Rolls a sell-in-
     *  flight status back to OPEN_TRACKING (truth is re-established by the next
     *  wallet reconcile tick) and nulls the attempt id + timestamp. */
    fun clearSellInFlight(mint: String, reason: String) {
        val p = positions[mint] ?: return
        val wasFlagged = p.status in SELL_IN_FLIGHT_STATUSES || !p.activeSellAttemptId.isNullOrBlank()
        if (!wasFlagged) return
        if (p.status in SELL_IN_FLIGHT_STATUSES) p.status = PositionStatus.OPEN_TRACKING
        p.activeSellAttemptId = null
        p.sellAttemptStartedMs = 0L
        try {
            ErrorLogger.warn(TAG, "♻️ [CLEAR_SELL_IN_FLIGHT] ${p.symbol ?: mint.take(6)} reason=$reason — lock cleared, stop can re-fire")
        } catch (_: Throwable) {}
        save()
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
