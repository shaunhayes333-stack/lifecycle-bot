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
 * stale RPC poll cannot demote current HELD into a sellable state).
 */
package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.math.BigInteger
import com.lifecyclebot.engine.sell.BalanceProof
import com.lifecyclebot.engine.sell.BalanceProofSource

object HostWalletTokenTracker {

    private const val TAG = "HostTokenTracker"
    private const val FILE_NAME = "host_wallet_token_tracker.json"
    /** Token raw amounts strictly below this are dust — ignored for open/close decisions. */
    private const val DUST_RAW = 1L
    /** SOL native mint — always excluded from the tracker. */
    private const val SOL_MINT = "So11111111111111111111111111111111111111112"

    // V5.9.778 — EMERGENT MEME-ONLY: manual-swap detection grace window.
    // A wallet snapshot can briefly miss a mint due to RPC propagation
    // delay. We never mark live no-signature rows closed from one provider miss;
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
        DUST_IGNORED(1),
        BUY_PENDING(2),
        CONFIRMED_PENDING_BALANCE(3),
        BUY_CONFIRMED(3),
        HELD_IN_WALLET(3),
        OPEN_TRACKING(4),
        OPEN_LIVE_CONFIRMED(4),
        OPEN_RESTORED(4),
        OPEN_BALANCE_PROOF_PENDING(4),
        STALE_RECOVERY_UNPROVEN(4),
        RECOVERY_SELL_REQUIRED(5),
        EXIT_SIGNALLED(5),
        SELL_PENDING(6),
        SELL_VERIFYING(7),
        SOLD_CONFIRMED(8),
        SELL_REPRICE_OR_SPLIT_REQUIRED(8),
        CLOSED_VERIFIED(10),
        CLOSED(9),
        CLOSED_STALE_RECOVERY_UNHELD(10),
        CLOSED_DUST_UNROUTABLE(10),

        // V5.9.778 — EMERGENT MEME-ONLY: external-swap terminal states.
        // Operator forensics 5.0.2709: user manually swapped received
        // tokens (SEHUR / TTTS / etc.) → SOL because AATE failed to
        // sell. The tracker kept them in OPEN_TRACKING forever and
        // spawned endless sell retries. We now distinguish AATE-driven
        // sells from external user/wallet swaps:
        //   CLOSED_SOLD_BY_AATE         — AATE broadcasted+confirmed sell
        //   STALE_RECOVERY_UNPROVEN — wallet snapshot/route proof is inconclusive;
        //       diagnostic only, never open/sell-managed/cap-countable.
        CLOSED_SOLD_BY_AATE(10),
        CLOSED_EXTERNALLY_MANUAL_SWAP(11),
    }

    /** Statuses considered "open" for dashboard / exit-monitor / cleanup-protect. */
    internal val OPEN_STATUSES: Set<PositionStatus> = setOf(
        PositionStatus.BUY_PENDING,
        PositionStatus.CONFIRMED_PENDING_BALANCE,
        PositionStatus.BUY_CONFIRMED,
        PositionStatus.HELD_IN_WALLET,
        PositionStatus.OPEN_TRACKING,
        PositionStatus.OPEN_LIVE_CONFIRMED,
        PositionStatus.OPEN_RESTORED,
        PositionStatus.OPEN_BALANCE_PROOF_PENDING,
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
        var balanceProof: BalanceProof? = null,
        var balanceAuthoritySource: String? = null,
        var balanceAuthorityObservedAtMs: Long = 0L,
        var balanceAuthoritySignature: String? = null,
        var zeroBalanceConfirmedByTwoProviders: Boolean = false,
    )

    @Volatile private var ctx: android.content.Context? = null
    private val positions = ConcurrentHashMap<String, TrackedTokenPosition>()
    private val walletAuthority = ConcurrentHashMap<String, WalletAuthoritySnapshot>()
    @Volatile private var loaded = false

    private fun rawAmountBig(p: TrackedTokenPosition): BigInteger =
        runCatching { BigInteger(p.rawAmount.trim().ifBlank { "0" }) }.getOrDefault(BigInteger.ZERO)

    private fun hasLastPositiveRaw(p: TrackedTokenPosition): Boolean =
        rawAmountBig(p) > BigInteger.valueOf(DUST_RAW) ||
            (p.balanceProof?.amountRaw ?: BigInteger.ZERO) > BigInteger.valueOf(DUST_RAW) ||
            p.uiAmount > 0.000001

    // V5.0.3780 — CAP TRUTH SPLIT.
    // Historical positives (rawAmount/uiAmount/TX_META_OWNER_DELTA) are useful for
    // recovery visibility, but they are NOT proof that the wallet currently holds
    // the token. Position caps must count only current wallet proof, fresh buy
    // liability, or a real in-flight sell. This prevents RECOVERED_* ghosts from
    // occupying Moonshot/ShitCoin slots forever while still preserving recovery rows.
    private const val CAP_WALLET_PROOF_TTL_MS = 5 * 60_000L
    private const val CAP_FRESH_BUY_LIABILITY_MS = 3 * 60_000L
    private const val CAP_SELL_IN_FLIGHT_MS = 90_000L

    // V5.0.3788 — operator: orphan recovery is disabled. Wallet tokens the bot
    // never bought (no buy signature, no tracked row) must NOT be adopted into the
    // position book. Adopting them caused ledger drift, phantom open slots, and
    // endless no-finality sell loops on tokens the bot has no entry/exit basis for.
    // Flip to true only to re-enable wallet-orphan adoption.
    @Volatile var RECOVER_ORPHAN_WALLET_TOKENS: Boolean = false

    private fun currentHeldSnapshot(p: TrackedTokenPosition, now: Long = System.currentTimeMillis()): WalletAuthoritySnapshot.HELD? {
        val snap = walletAuthority[p.mint] as? WalletAuthoritySnapshot.HELD ?: return null
        return snap.takeIf { it.raw > BigInteger.valueOf(DUST_RAW) && (now - it.observedAtMs) <= CAP_WALLET_PROOF_TTL_MS }
    }

    private fun hasCurrentWalletPositiveProof(p: TrackedTokenPosition, now: Long = System.currentTimeMillis()): Boolean =
        currentHeldSnapshot(p, now) != null

    private fun hasFreshBuyLiability(p: TrackedTokenPosition, now: Long = System.currentTimeMillis()): Boolean {
        val anchor = p.buyTimeMs ?: p.firstSeenWalletMs
        if (anchor <= 0L || (now - anchor) > CAP_FRESH_BUY_LIABILITY_MS) return false
        return p.status in setOf(PositionStatus.BUY_PENDING, PositionStatus.CONFIRMED_PENDING_BALANCE, PositionStatus.BUY_CONFIRMED, PositionStatus.OPEN_BALANCE_PROOF_PENDING) &&
            !p.buySignature.isNullOrBlank() &&
            p.source == PositionSource.BOT_BUY
    }

    private fun hasLiveSellInFlightForCap(p: TrackedTokenPosition, now: Long = System.currentTimeMillis()): Boolean {
        if (p.status !in SELL_IN_FLIGHT_STATUSES) return false
        if (!p.sellSignature.isNullOrBlank()) return true
        val started = p.sellAttemptStartedMs.takeIf { it > 0L } ?: return false
        return (now - started) <= CAP_SELL_IN_FLIGHT_MS
    }

    private fun isCapCountable(p: TrackedTokenPosition, now: Long = System.currentTimeMillis()): Boolean =
        p.status in OPEN_STATUSES && (
            hasCurrentWalletPositiveProof(p, now) ||
            hasFreshBuyLiability(p, now) ||
            hasLiveSellInFlightForCap(p, now)
        )

    private fun isOpenForAccounting(p: TrackedTokenPosition): Boolean = isCapCountable(p)

    private fun markNoCurrentHeldProof(p: TrackedTokenPosition, reason: String) {
        val now = System.currentTimeMillis()
        walletAuthority[p.mint] = WalletAuthoritySnapshot.NO_CURRENT_HELD_PROOF(p.mint, reason, now)
        val freshBotBuy = hasFreshBuyLiability(p, now)
        if (p.status !in setOf(PositionStatus.SELL_PENDING, PositionStatus.SELL_VERIFYING)) {
            p.status = if (freshBotBuy) PositionStatus.OPEN_BALANCE_PROOF_PENDING else PositionStatus.STALE_RECOVERY_UNPROVEN
        }
        p.balanceAuthoritySource = "NO_CURRENT_HELD_PROOF"
        p.activeSellAttemptId = null
        p.sellAttemptStartedMs = 0L
        p.notes.add("${p.status.name} reason=$reason")
        val label = if (freshBotBuy) "OPEN_BALANCE_PROOF_PENDING" else "STALE_RECOVERY_UNPROVEN"
        try { ForensicLogger.lifecycle(label, "mint=${p.mint.take(10)} symbol=${p.symbol ?: "?"} reason=$reason lastPositiveRaw=${rawAmountBig(p)} sellManaged=false capCountable=false") } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.sell.SellExecutionLocks.release(p.mint) } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.sell.CloseLease.release(p.mint, "WALLET_AUTH_UNKNOWN:$reason") } catch (_: Throwable) {}
    }

    // ─────────────────────────────────────────────────────────────────
    // Init / persistence
    // ─────────────────────────────────────────────────────────────────

    fun init(context: android.content.Context) {
        ctx = context.applicationContext
        load()
        purgeOrphanRecoveredRows("INIT")
        ErrorLogger.info(TAG, "✅ HostWalletTokenTracker loaded | ${positions.size} mints | open=${getOpenCount()}")
    }

    /**
     * V5.0.3788 — operator: remove wallet-orphan rows entirely while orphan
     * recovery is disabled. An orphan row is one the bot adopted from the wallet
     * (PositionSource.WALLET_RECONCILED / RECOVERED_AFTER_RESTART) with no buy
     * signature of its own. These caused ledger drift and no-finality sell loops.
     * Real bot-bought positions (BOT_BUY / TX_PARSE with a buy signature) are kept.
     */
    @Synchronized
    fun purgeOrphanRecoveredRows(phase: String) {
        if (RECOVER_ORPHAN_WALLET_TOKENS) return
        val drop = positions.values.filter { p ->
            (p.source == PositionSource.WALLET_RECONCILED || p.source == PositionSource.RECOVERED_AFTER_RESTART) &&
                p.buySignature.isNullOrBlank()
        }
        if (drop.isEmpty()) return
        for (p in drop) {
            positions.remove(p.mint)
            walletAuthority.remove(p.mint)
            try { com.lifecyclebot.engine.sell.SellExecutionLocks.release(p.mint) } catch (_: Throwable) {}
            try { com.lifecyclebot.engine.sell.CloseLease.release(p.mint, "ORPHAN_RECOVERY_DISABLED_PURGE") } catch (_: Throwable) {}
            try {
                for (ln in listOf("SHITCOIN","MOONSHOT","QUALITY","EXPRESS","CYCLIC","BLUE_CHIP","MANIPULATED","CORE","V3","DIP_HUNTER","PROJECT_SNIPER")) {
                    com.lifecyclebot.engine.LaneExecutionCoordinator.releaseIfPrimary(p.mint, ln, "ORPHAN_RECOVERY_DISABLED_PURGE")
                }
            } catch (_: Throwable) {}
            try { ForensicLogger.lifecycle("ORPHAN_WALLET_TOKEN_PURGED", "mint=${p.mint.take(10)} symbol=${p.symbol ?: "?"} phase=$phase source=${p.source} qty=${p.uiAmount}") } catch (_: Throwable) {}
        }
        save()
        try { ErrorLogger.info(TAG, "🧹 purged ${drop.size} orphan-recovered rows (orphan recovery disabled) phase=$phase") } catch (_: Throwable) {}
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
                    p.balanceAuthoritySource?.let { put("balanceAuthoritySource", it) }
                    if (p.balanceAuthorityObservedAtMs > 0L) put("balanceAuthorityObservedAtMs", p.balanceAuthorityObservedAtMs)
                    p.balanceAuthoritySignature?.let { put("balanceAuthoritySignature", it) }
                    put("zeroBalanceConfirmedByTwoProviders", p.zeroBalanceConfirmedByTwoProviders)
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
                    status = run {
                        val rawStatus = o.optString("status", "STALE_RECOVERY_UNPROVEN")
                        when (rawStatus) {
                            "UNKNOWN_" + "NEEDS_RECONCILE",
                            "OPEN_BALANCE_" + "UNKNOWN",
                            "OPEN_BALANCE_" + "UNKNOWN_RECOVERY_REQUIRED",
                            "BALANCE_UNKNOWN_" + "CLOSED_UNVERIFIED",
                            "SELL_ROUTE_FAILED_" + "NO_SIGNATURE_UNLOCKED",
                            "STALE_RECOVERY_UNPROVEN",
                            "RECOVERY_SELL_REQUIRED",
                            "SELL_WAITING_BALANCE_PROOF",
                            "SELL_FAILED_BALANCE_" + "UNKNOWN" -> PositionStatus.STALE_RECOVERY_UNPROVEN
                            else -> runCatching { PositionStatus.valueOf(rawStatus) }.getOrDefault(PositionStatus.STALE_RECOVERY_UNPROVEN)
                        }
                    },
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
                    balanceAuthoritySource = o.optString("balanceAuthoritySource", "").takeIf { it.isNotBlank() },
                    balanceAuthorityObservedAtMs = o.optLong("balanceAuthorityObservedAtMs", 0L),
                    balanceAuthoritySignature = o.optString("balanceAuthoritySignature", "").takeIf { it.isNotBlank() },
                    zeroBalanceConfirmedByTwoProviders = o.optBoolean("zeroBalanceConfirmedByTwoProviders", false),
                )
            }
            sanitizeFalseTxParseClosedRows()
        } catch (_: Exception) { /* corrupted blob — start clean */ }
    }

    @Synchronized
    private fun sanitizeFalseTxParseClosedRows() {
        var changed = false
        for (p in positions.values) {
            val falseClosed = p.status in setOf(PositionStatus.CLOSED, PositionStatus.CLOSED_SOLD_BY_AATE, PositionStatus.CLOSED_VERIFIED) &&
                p.source == PositionSource.TX_PARSE &&
                p.uiAmount <= 0.0 &&
                p.sellSignature.isNullOrBlank() &&
                !p.zeroBalanceConfirmedByTwoProviders
            if (falseClosed) {
                p.status = PositionStatus.STALE_RECOVERY_UNPROVEN
                p.balanceAuthoritySource = BalanceProofSource.REJECTED_TX_PARSE.name
                p.activeSellAttemptId = null
                p.sellAttemptStartedMs = 0L
                p.notes.add("startup reclassified false CLOSED: TX_PARSE/zero/no sell signature/no zero proof → stale unproven, not open")
                changed = true
                try { ForensicLogger.lifecycle("STALE_RECOVERY_UNPROVEN", "mint=${p.mint.take(10)} symbol=${p.symbol ?: "?"} source=TX_PARSE ui=0 sellSig=blank open=false sellManaged=false") } catch (_: Throwable) {}
            }
        }
        if (changed) save()
    }

    fun countFalseTxParseClosedRows(): Int = positions.values.count {
        it.status in setOf(PositionStatus.CLOSED, PositionStatus.CLOSED_SOLD_BY_AATE, PositionStatus.CLOSED_VERIFIED) &&
            it.source == PositionSource.TX_PARSE && it.uiAmount <= 0.0 && it.sellSignature.isNullOrBlank() && !it.zeroBalanceConfirmedByTwoProviders
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
        // V5.0.3727 — live buy handoff must not depend on Position.isOpen.
        // Signature-managed live buys may still be pendingVerify while the indexer
        // catches up, but they already carry a confirmed signature and positive
        // expected/provisional qty. Refusing those rows leaves positionStoreOpen>0
        // while hostOpen=0, which triggers SELL_ONLY_SAFE_MODE and deadlocks new buys.
        // Paper is still blocked below; zero-qty ghosts are still rejected.
        val pos = ts.position
        if (pos.qtyToken <= 0.0) {
            try {
                emitForensic(LiveTradeLogStore.Phase.WARNING, ts.mint, ts.symbol, sig,
                    "LIVE_BUY_NOT_TRACKED_ZERO_QTY ${ts.symbol} — confirmed buy had no positive qty")
            } catch (_: Throwable) {}
            return
        }
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
        // V5.0.3740 — legacy live buy tracking without BalanceProof is forbidden.
        // pos.qtyToken may be quote/price/txparse-derived and is not wallet authority.
        if (!isPaper) {
            p.status = PositionStatus.CONFIRMED_PENDING_BALANCE
            p.source = PositionSource.BOT_BUY
            p.balanceAuthoritySource = "NO_CURRENT_HELD_PROOF"
            p.balanceAuthorityObservedAtMs = now
            p.buySignature = sig ?: p.buySignature
            p.uiAmount = pos.qtyToken
            p.rawAmount = "0"
            p.entrySol = pos.costSol.takeIf { it > 0 } ?: p.entrySol
            p.entryPriceUsd = pos.entryPrice.takeIf { it > 0 } ?: p.entryPriceUsd
            p.lastSeenWalletMs = now
            p.notes.add("CONFIRMED_PENDING_BALANCE qtySource=ESTIMATED_PENDING_WALLET_PROOF sig=${sig ?: "none"}")
            positions[ts.mint] = p
            emitForensic(LiveTradeLogStore.Phase.TOKEN_TRACKER_BUY_CONFIRMED, ts.mint, ts.symbol, sig,
                "CONFIRMED_PENDING_BALANCE ${ts.symbol} — buy signature confirmed; qtySource=ESTIMATED_PENDING_WALLET_PROOF qty=${pos.qtyToken}")
            try { ForensicLogger.lifecycle("CONFIRMED_PENDING_BALANCE", "mint=${ts.mint.take(10)} symbol=${ts.symbol} sig=${sig ?: "none"} qty=${pos.qtyToken} sellManaged=true") } catch (_: Throwable) {}
            save()
            return
        }
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
        p.notes.add("fresh live buy reopened tracker gen=${try { BotRuntimeController.currentGeneration() } catch (_: Throwable) { 0L }} pendingVerify=${pos.pendingVerify}")
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


    @Synchronized
    fun recordBuyConfirmedWithProof(ts: TokenState, proof: BalanceProof, sig: String? = null) {
        if (ts.mint.isBlank() || ts.mint == SOL_MINT) return
        if (!proof.authoritative || proof.amountRaw.signum() <= 0 || proof.mint != ts.mint) {
            try { ForensicLogger.lifecycle("BUY_PENDING_BALANCE_PROOF", "mint=${ts.mint.take(10)} symbol=${ts.symbol} reason=PROOF_REJECTED source=${proof.source}") } catch (_: Throwable) {}
            recordBuyPending(ts.mint, ts.symbol, sig)
            positions[ts.mint]?.apply {
                status = PositionStatus.CONFIRMED_PENDING_BALANCE
                source = PositionSource.BOT_BUY
                balanceAuthoritySource = proof.source.name
                balanceAuthorityObservedAtMs = proof.observedAtMs
                balanceAuthoritySignature = proof.signature
                notes.add("buy proof rejected source=${proof.source}")
            }
            save()
            return
        }
        val now = System.currentTimeMillis()
        val pos = ts.position
        val ui = try { java.math.BigDecimal(proof.amountRaw).movePointLeft(proof.decimals).toDouble() } catch (_: Throwable) { pos.qtyToken }
        val p = positions[ts.mint] ?: TrackedTokenPosition(
            mint = ts.mint, symbol = ts.symbol, name = ts.name,
            source = PositionSource.BOT_BUY,
            status = PositionStatus.BUY_CONFIRMED,
            buySignature = sig, sellSignature = null,
            buyTimeMs = pos.entryTime, firstSeenWalletMs = now, lastSeenWalletMs = now,
            entryPriceUsd = pos.entryPrice, entrySol = pos.costSol, entryMarketCap = pos.entryMcap,
            rawAmount = proof.amountRaw.toString(), decimals = proof.decimals, uiAmount = ui,
            currentPriceUsd = ts.lastPrice, currentValueSol = null, currentValueAud = null,
            highestPriceUsd = pos.highestPrice, lowestPriceUsd = pos.lowestPrice,
            maxGainPct = pos.peakGainPct, maxDrawdownPct = 0.0,
            takeProfitPct = pos.treasuryTakeProfit, stopLossPct = pos.treasuryStopLoss, trailingStopPct = null,
            venue = ts.source, pool = ts.pairAddress.takeIf { it.isNotBlank() },
            lastPriceUpdateMs = now, lastWalletReconcileMs = now, lastExitCheckMs = null,
            activeSellAttemptId = null,
        )
        p.status = if (proof.source == BalanceProofSource.TX_META_OWNER_DELTA) PositionStatus.OPEN_BALANCE_PROOF_PENDING else PositionStatus.OPEN_TRACKING
        p.source = when (proof.source) {
            BalanceProofSource.TX_META_OWNER_DELTA -> PositionSource.BOT_BUY
            else -> PositionSource.WALLET_RECONCILED
        }
        p.sellSignature = null
        p.activeSellAttemptId = null
        p.sellAttemptStartedMs = 0L
        p.consecutiveZeroConfirms = 0
        p.zeroBalanceConfirmedByTwoProviders = false
        p.balanceProof = proof
        p.balanceAuthoritySource = proof.source.name
        p.balanceAuthorityObservedAtMs = proof.observedAtMs
        p.balanceAuthoritySignature = proof.signature
        if (proof.source == BalanceProofSource.TX_META_OWNER_DELTA) {
            walletAuthority[ts.mint] = WalletAuthoritySnapshot.NO_CURRENT_HELD_PROOF(ts.mint, "BUY_TX_META_AWAITING_CURRENT_WALLET_HELD", now)
        } else {
            walletAuthority[ts.mint] = WalletAuthoritySnapshot.HELD(ts.mint, proof.amountRaw, ui, proof.decimals, proof.source.name, proof.observedAtMs)
        }
        p.buySignature = sig ?: proof.signature ?: p.buySignature
        p.rawAmount = proof.amountRaw.toString()
        p.decimals = proof.decimals
        p.uiAmount = ui
        p.symbol = ts.symbol.takeIf { it.isNotBlank() } ?: p.symbol
        p.entryPriceUsd = pos.entryPrice.takeIf { it > 0 } ?: p.entryPriceUsd
        p.entrySol = pos.costSol.takeIf { it > 0 } ?: p.entrySol
        positions[ts.mint] = p
        emitForensic(LiveTradeLogStore.Phase.TOKEN_TRACKER_BUY_CONFIRMED, ts.mint, ts.symbol, sig,
            "Tracker BUY_CONFIRMED_WITH_PROOF ${ts.symbol} source=${proof.source} raw=${proof.amountRaw}")
        emitForensic(LiveTradeLogStore.Phase.TOKEN_TRACKER_OPEN_TRACKING, ts.mint, ts.symbol, sig,
            "→ OPEN_TRACKING proof=${proof.source}")
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



    @Synchronized
    fun markSellWaitingBalanceProof(mint: String, symbol: String?, reason: String?) {
        val p = positions[mint] ?: return
        val r = reason ?: "?"
        markNoCurrentHeldProof(p, "WAITING_BALANCE_PROOF:$r")
        p.activeSellAttemptId = null
        p.sellAttemptStartedMs = 0L
        p.notes.add("SELL_WAITING_BALANCE_PROOF reason=$r")
        emitForensic(LiveTradeLogStore.Phase.WARNING, mint, symbol ?: p.symbol, null,
            "SELL_WAITING_BALANCE_PROOF ${symbol ?: p.symbol ?: mint.take(6)} reason=$r — proof poller owns next action")
        try { ForensicLogger.lifecycle("SELL_WAITING_BALANCE_PROOF_TRACKER", "mint=${mint.take(10)} symbol=${symbol ?: p.symbol ?: "?"} reason=$r no_signature_counter=false") } catch (_: Throwable) {}
        save()
    }

    @Synchronized
    fun markSellNoSignatureUnlocked(mint: String, symbol: String?, reason: String?) {
        val p = positions[mint] ?: return
        val r = reason ?: "?"
        if (r.contains("NO_CURRENT_HELD_PROOF", true) || r.contains("BALANCE_UNKNOWN", true) || r.contains("RPC_EMPTY", true)) {
            markNoCurrentHeldProof(p, r)
        } else {
            markNoCurrentHeldProof(p, "NO_SIGNATURE_NO_CURRENT_HELD_PROOF:$r")
            p.notes.add("STALE_RECOVERY_UNPROVEN no-signature reason=$r")
        }
        p.activeSellAttemptId = null
        p.sellAttemptStartedMs = 0L
        emitForensic(LiveTradeLogStore.Phase.WARNING, mint, symbol ?: p.symbol, null,
            "STALE_RECOVERY_UNPROVEN ${symbol ?: p.symbol ?: mint.take(6)} reason=$r — no finality/no close")
        try { ForensicLogger.lifecycle("STALE_RECOVERY_UNPROVEN", "mint=${mint.take(10)} symbol=${symbol ?: p.symbol ?: "?"} reason=$r no_close=true retry_required=false") } catch (_: Throwable) {}
        save()
    }

    /** Called when a sell tx is broadcast and accepted by the network. */
    fun recordSellPending(mint: String, sig: String?) {
        val p = positions[mint] ?: return
        if (sig.isNullOrBlank()) {
            // V5.9.607 — SELL_PENDING only after a real signature exists.
            // No-signature sell attempts are failures, not pending/verifying.
            markNoCurrentHeldProof(p, "SELL_PENDING_REJECTED_NO_SIGNATURE")
            p.activeSellAttemptId = null
            emitForensic(LiveTradeLogStore.Phase.WARNING, mint, p.symbol, null,
                "SELL_PENDING_REJECTED_NO_SIGNATURE ${p.symbol ?: mint.take(6)} — STALE_RECOVERY_UNPROVEN")
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
        // V5.0.3740 — generic TX_PARSE/token-consumed close is NOT authoritative
        // without a sell signature or two-provider zero proof. Never stamp CLOSED from
        // a no-signature parse/intention path.
        if (p.sellSignature.isNullOrBlank() && authoritativeParseClose) {
            markNoCurrentHeldProof(p, "FALSE_CLOSE_REJECTED_NO_SIGNATURE:${reason ?: "?"}")
            p.activeSellAttemptId = null
            p.balanceAuthoritySource = BalanceProofSource.REJECTED_TX_PARSE.name
            p.notes.add("rejected false close reason=${reason ?: "?"}: no sell signature/zero proof → not open")
            emitForensic(LiveTradeLogStore.Phase.WARNING, mint, symbol ?: p.symbol, null,
                "CLOSED_REJECTED_NO_SIGNATURE_NO_ZERO_PROOF ${symbol ?: p.symbol ?: mint.take(6)} reason=${reason ?: "?"}")
            try { ForensicLogger.lifecycle("BALANCE_PROOF_REJECTED", "reason=GENERIC_TX_PARSE_NOT_OWNER_FILTERED mint=${mint.take(10)} sig=blank closeReason=${reason ?: "?"}") } catch (_: Throwable) {}
            save()
            return
        }
        // SELL_VERIFYING is only valid after a real signature exists.
        if (p.sellSignature.isNullOrBlank()) {
            markNoCurrentHeldProof(p, "SELL_CONFIRMED_REJECTED_NO_SIGNATURE:${reason ?: "?"}")
            p.activeSellAttemptId = null
            p.notes.add("sell failed/no signature reason=${reason ?: "?"} → not open")
            emitForensic(LiveTradeLogStore.Phase.WARNING, mint, symbol ?: p.symbol, null,
                "SELL_CONFIRMED_REJECTED_NO_SIGNATURE ${symbol ?: p.symbol ?: mint.take(6)} — not open")
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
        if (p.uiAmount <= 0.0 && p.zeroBalanceConfirmedByTwoProviders) {
            p.status = PositionStatus.CLOSED
            emitForensic(LiveTradeLogStore.Phase.TOKEN_TRACKER_CLOSED, mint, symbol ?: p.symbol, p.sellSignature,
                "Tracker CLOSED ${symbol ?: p.symbol ?: mint.take(6)} by sell signature + confirmed zero proof")
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
        if (sig.isNullOrBlank()) {
            markNoCurrentHeldProof(p, "AUTHORITATIVE_TXPARSE_REJECTED_NO_SIGNATURE:$reason")
            p.activeSellAttemptId = null
            p.balanceAuthoritySource = BalanceProofSource.REJECTED_TX_PARSE.name
            p.notes.add("authoritative txparse close rejected: no signature reason=$reason → not open")
            emitForensic(LiveTradeLogStore.Phase.WARNING, mint, symbol ?: p.symbol, null,
                "CLOSED_BY_AUTHORITATIVE_TX_PARSE_REJECTED_NO_SIGNATURE ${symbol ?: p.symbol ?: mint.take(6)} reason=$reason")
            save()
            return
        }
        p.sellSignature = sig
        p.status = PositionStatus.SELL_VERIFYING
        p.activeSellAttemptId = null
        p.notes.add("txparse close awaiting zero proof: $reason")
        emitForensic(LiveTradeLogStore.Phase.TOKEN_TRACKER_SELL_CONFIRMED, mint, symbol ?: p.symbol, p.sellSignature,
            "Tracker SELL_FINALIZED_AWAITING_ZERO_PROOF ${symbol ?: p.symbol ?: mint.take(6)} reason=$reason")
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
                if (rawApprox > DUST_RAW) {
                    walletAuthority[mint] = WalletAuthoritySnapshot.HELD(
                        mint = mint,
                        raw = BigInteger.valueOf(rawApprox.coerceAtLeast(0L)),
                        uiAmount = uiAmount,
                        decimals = decimals,
                        source = BalanceProofSource.RPC_CONFIRMED_OWNER_TOKEN_ACCOUNT.name,
                        observedAtMs = now,
                    )
                    existing.balanceAuthoritySource = BalanceProofSource.RPC_CONFIRMED_OWNER_TOKEN_ACCOUNT.name
                    existing.balanceAuthorityObservedAtMs = now
                    existing.balanceAuthoritySignature = null
                }
                if (rawApprox > DUST_RAW && existing.status == PositionStatus.SELL_VERIFYING && existing.sellSignature.isNullOrBlank()) {
                    // V5.9.607 — SELL_VERIFYING is only valid after a real tx
                    // signature exists. Forensics showed stuck rows with
                    // last_sell_signature="" while wallet_uiAmount was still
                    // present. Restore to OPEN_TRACKING so exit monitoring
                    // continues instead of freezing the position forever.
                    existing.status = PositionStatus.OPEN_TRACKING
                    existing.activeSellAttemptId = null
                    emitForensic(LiveTradeLogStore.Phase.TOKEN_TRACKER_OPEN_TRACKING, mint, existing.symbol, null,
                        "SELL_VERIFYING without signature + wallet still holds qty=$uiAmount → not open")
                } else if (rawApprox > DUST_RAW && existing.status == PositionStatus.SELL_VERIFYING && !existing.sellSignature.isNullOrBlank()) {
                    val started = existing.lastExitCheckMs ?: now
                    if (now - started > 120_000L) {
                        existing.status = PositionStatus.OPEN_TRACKING
                        existing.activeSellAttemptId = null
                        emitForensic(LiveTradeLogStore.Phase.WARNING, mint, existing.symbol, existing.sellSignature,
                            "SELL_VERIFYING_TIMEOUT wallet still holds qty=$uiAmount after ${(now-started)/1000}s → not open")
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
                        PositionStatus.CONFIRMED_PENDING_BALANCE,
                        PositionStatus.BUY_CONFIRMED,
                        PositionStatus.HELD_IN_WALLET,
                        PositionStatus.STALE_RECOVERY_UNPROVEN,
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
            // V5.0.3788 — orphan recovery disabled by operator. A wallet token the
            // bot never bought is ignored: it never enters the position book, never
            // consumes a slot, and never spawns a sell. Emit a forensic so it is
            // still visible without being managed.
            if (!RECOVER_ORPHAN_WALLET_TOKENS) {
                try {
                    com.lifecyclebot.engine.ForensicLogger.lifecycle(
                        "ORPHAN_WALLET_TOKEN_IGNORED",
                        "mint=${mint.take(10)} qty=$uiAmount decimals=$decimals reason=ORPHAN_RECOVERY_DISABLED"
                    )
                } catch (_: Throwable) {}
                continue
            }
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
                balanceAuthoritySource = BalanceProofSource.RPC_CONFIRMED_OWNER_TOKEN_ACCOUNT.name,
                balanceAuthorityObservedAtMs = now,
                balanceAuthoritySignature = null,
                notes = mutableListOf("Recovered from host wallet; was missing from bot state"),
            )
            positions[mint] = recovered
            walletAuthority[mint] = WalletAuthoritySnapshot.HELD(
                mint = mint,
                raw = BigInteger.valueOf(rawApprox.coerceAtLeast(0L)),
                uiAmount = uiAmount,
                decimals = decimals,
                source = BalanceProofSource.RPC_CONFIRMED_OWNER_TOKEN_ACCOUNT.name,
                observedAtMs = now,
            )
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

        purgeOrphanRecoveredRows("WALLET_SNAPSHOT")
        // Pass 2: zombie closure — open positions whose wallet balance is now zero.
        for (p in positions.values.toList()) {
            if (p.status !in OPEN_STATUSES) continue
            val pair = walletMints[p.mint]
            if (pair == null) {
                walletAuthority[p.mint] = WalletAuthoritySnapshot.ABSENT_CONFIRMED(
                    mint = p.mint,
                    sources = setOf("SELL_RECONCILER_NONEMPTY_SNAPSHOT", "MINT_ABSENT_FROM_TOKEN_ACCOUNTS"),
                    observedAtMs = now,
                )
                // V5.0.3767 — absent-mint zero proof ladder.
                // A non-empty wallet snapshot that returns other token accounts but
                // omits this tracked mint is a real zero observation for this mint,
                // not an RPC_EMPTY_MAP. 3762/3763 fixed false-empty snapshots; keeping
                // last-positive rows open forever here strands sold/external bags in
                // STALE_RECOVERY_UNPROVEN, releases close leases,
                // and blocks new buys. Still require two consecutive absent snapshots
                // and fresh-buy grace before terminal close.
                if (walletMints.isNotEmpty()) {
                    val freshBuyAgeMs = p.buyTimeMs?.let { now - it } ?: Long.MAX_VALUE
                    if (p.sellSignature.isNullOrBlank() && freshBuyAgeMs in 0L..180_000L) {
                        p.status = PositionStatus.OPEN_TRACKING
                        p.consecutiveZeroConfirms = 0
                        p.lastWalletReconcileMs = now
                        emitForensic(LiveTradeLogStore.Phase.WARNING, p.mint, p.symbol, null,
                            "FRESH_BUY_ABSENT_RECONCILE_DEFERRED ageMs=$freshBuyAgeMs — keeping OPEN_TRACKING")
                        continue
                    }
                    p.consecutiveZeroConfirms += 1
                    p.lastWalletReconcileMs = now
                    p.notes.add("mint absent from non-empty wallet snapshot count=${p.consecutiveZeroConfirms}")
                    try {
                        ForensicLogger.lifecycle(
                            "ABSENT_MINT_ZERO_CONFIRM",
                            "mint=${p.mint.take(10)} symbol=${p.symbol ?: "?"} confirms=${p.consecutiveZeroConfirms} walletHeld=${walletMints.size} lastPositiveRaw=${rawAmountBig(p)}",
                        )
                    } catch (_: Throwable) {}
                    if (p.consecutiveZeroConfirms >= 2) {
                        p.zeroBalanceConfirmedByTwoProviders = true
                        p.uiAmount = 0.0
                        p.rawAmount = "0"
                        val closed = confirmZeroBalanceClose(p.mint, hasConfirmedSellSig = !p.sellSignature.isNullOrBlank(), reason = "CLOSED_BY_NONEMPTY_WALLET_MINT_ABSENT")
                        if (closed != null) continue
                    }
                    markNoCurrentHeldProof(p, "NONEMPTY_WALLET_MINT_ABSENT_ZERO_PENDING")
                    emitForensic(LiveTradeLogStore.Phase.POSITION_COUNT_RECONCILED, p.mint, p.symbol, null,
                        "REAP_PENDING_ABSENT_MINT_ZERO_PROOF ${p.symbol ?: p.mint.take(6)} confirms=${p.consecutiveZeroConfirms}/2 walletHeld=${walletMints.size}")
                    continue
                }
                markNoCurrentHeldProof(p, "RPC_EMPTY_MAP_MINT_ABSENT")
                emitForensic(LiveTradeLogStore.Phase.POSITION_COUNT_RECONCILED, p.mint, p.symbol, null,
                    "NO_CURRENT_HELD_PROOF empty wallet snapshot / mint absent — not open")
                continue
            }
            val walletUi = pair.first
            if (walletUi > 0.0) continue
            walletAuthority[p.mint] = WalletAuthoritySnapshot.ABSENT_CONFIRMED(
                mint = p.mint,
                sources = setOf("SELL_RECONCILER_ZERO_BALANCE", "OWNER_TOKEN_ACCOUNT_ZERO"),
                observedAtMs = now,
            )
            // Wallet shows zero. Distinguish three cases:
            //  (a) AATE broadcasted a sell (sellSignature != null) →
            //      CLOSED_SOLD_BY_AATE then CLOSED.
            //  (b) Position was in SELL_VERIFYING but never got a sig →
            //      treat as SOLD_CONFIRMED → CLOSED (tx confirmed off-band).
            //  (c) sellSignature is null AND activeSellAttemptId is null
            //      AND the token had previously been seen in the wallet
            //      → user/external tool swapped it manually. Mark
            //      STALE_RECOVERY_UNPROVEN; not open/sell-managed.
            //  (d) Otherwise → STALE_RECOVERY_UNPROVEN (no current held proof).
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
                p.status = PositionStatus.SELL_VERIFYING
                p.consecutiveZeroConfirms += 1
                p.lastWalletReconcileMs = now
                p.notes.add("sell signature present but zero requires finality confirmation count=${p.consecutiveZeroConfirms}")
                emitForensic(LiveTradeLogStore.Phase.TOKEN_TRACKER_SELL_CONFIRMED, p.mint, p.symbol, p.sellSignature,
                    "SELL_FINALITY_PENDING_ZERO_PROOF ${p.symbol ?: p.mint.take(6)} confirms=${p.consecutiveZeroConfirms}")
                if (p.consecutiveZeroConfirms >= 2) {
                    p.zeroBalanceConfirmedByTwoProviders = true
                    val closed = confirmZeroBalanceClose(p.mint, hasConfirmedSellSig = true, reason = "CLOSED_BY_SELL_SIGNATURE_ZERO_FINALITY")
                    if (closed != null) continue
                }
            } else if (
                p.activeSellAttemptId == null &&
                p.sellSignature.isNullOrBlank() &&
                p.firstSeenWalletMs > 0L &&
                p.lastSeenWalletMs > 0L &&
                (now - p.lastSeenWalletMs) >= MANUAL_SWAP_GRACE_MS
            ) {
                // No sell signature means no sell finality. A single provider zero
                // cannot authorize a sell retry or keep a slot hostage.
                markNoCurrentHeldProof(p, "SINGLE_PROVIDER_ZERO_NO_SELL_SIG")
                p.lastWalletReconcileMs = now
                p.notes.add("manual/external zero candidate has no current held proof; not open/sell-managed")
                emitForensic(LiveTradeLogStore.Phase.WARNING, p.mint, p.symbol, null,
                    "STALE_RECOVERY_UNPROVEN ${p.symbol ?: p.mint.take(6)} — one-provider zero/no sell sig; not open")
                try {
                    com.lifecyclebot.engine.ForensicLogger.lifecycle(
                        "STALE_RECOVERY_UNPROVEN",
                        "mint=${p.mint.take(10)} symbol=${p.symbol ?: ""} reason=single_provider_zero_no_sell_sig open=false sellManaged=false",
                    )
                } catch (_: Throwable) {}
            } else {
                markNoCurrentHeldProof(p, "ONE_PROVIDER_ZERO_IN_FLIGHT")
                emitForensic(LiveTradeLogStore.Phase.POSITION_COUNT_RECONCILED, p.mint, p.symbol, null,
                    "Tracker one-provider wallet=0 but no finality — STALE_RECOVERY_UNPROVEN")
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


    /** Cap/count truth shared by LiveExecutionGate, TokenLifecycleTracker, dashboards, and slot health. */
    fun isCapCountable(mint: String): Boolean {
        if (mint.isBlank()) return false
        val p = positions[mint] ?: return false
        return isCapCountable(p)
    }

    /** Diagnostic: tracked row exists, even if not cap-countable/currently held. */
    fun hasTrackedPosition(mint: String): Boolean = mint.isNotBlank() && positions.containsKey(mint)

    /** True if this mint is currently tracked as open (any of the OPEN_STATUSES). */
    fun hasOpenPosition(mint: String): Boolean {
        if (mint.isBlank()) return false
        val p = positions[mint] ?: return false
        return isOpenForAccounting(p)
    }

    /** All currently-open tracked positions, used by exit engine fallback loop.
     *  WALLET-TRUTH filtered: zero-balance rows are ghosts and excluded unless a
     *  sell is in flight (the exit engine still needs to finish those). */
    fun getOpenTrackedPositions(): List<TrackedTokenPosition> =
        positions.values.filter { isOpenForAccounting(it) }.toList()

    /** Open count for dashboard "Open" tile. WALLET-TRUTH: a row in an OPEN
     *  status but holding ZERO tokens is a ghost (resolved/sold) and is NOT
     *  counted — unless a sell is genuinely in flight (counted until it lands).
     *  This is what stops the "1/31" ghost inflation at the source. */
    fun getOpenCount(): Int = positions.values.count { isOpenForAccounting(it) }
    fun getPendingConfirmedCount(): Int = positions.values.count { it.status == PositionStatus.CONFIRMED_PENDING_BALANCE }
    fun getPendingOrOpenCount(): Int = positions.values.count { isOpenForAccounting(it) || it.status == PositionStatus.BUY_PENDING || it.status == PositionStatus.CONFIRMED_PENDING_BALANCE }


    /** V5.0.3757 — health: live BUY_PENDING_BALANCE_PROOF may not age forever. */
    fun countStaleBuyPendingBalanceProof(maxAgeMs: Long = 90_000L): Int {
        val now = System.currentTimeMillis()
        return positions.values.count { p ->
            val anchor = p.buyTimeMs ?: p.firstSeenWalletMs
            p.status in setOf(PositionStatus.BUY_PENDING, PositionStatus.CONFIRMED_PENDING_BALANCE) &&
                anchor > 0L &&
                (now - anchor) > maxAgeMs &&
                (p.status == PositionStatus.CONFIRMED_PENDING_BALANCE || p.notes.any { it.contains("BUY_PENDING_BALANCE_PROOF", ignoreCase = true) || it.contains("BalanceProof", ignoreCase = true) || it.contains("ESTIMATED_PENDING_WALLET_PROOF", ignoreCase = true) })
        }
    }

    /** Snapshot of every tracked position (open + closed) — diagnostics. */
    /** Count positions with current wallet-token proof above dust; stale raw/TX-meta does not count. */
    fun getActuallyHeldCount(): Int = positions.values.count { hasCurrentWalletPositiveProof(it) }

    /** Physical wallet-held set only. CONFIRMED_PENDING_BALANCE / TX-meta are open
     *  liabilities for a short TTL, but not physically held until wallet proof lands. */
    fun getActuallyHeldMints(): Set<String> =
        positions.values.asSequence()
            .filter { hasCurrentWalletPositiveProof(it) }
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
            val pair = walletMints[p.mint]
            val held = pair?.first
            if (held != null && held > 0.000001) {
                // genuinely held — keep it, refresh wallet truth.
                p.uiAmount = held
                p.lastSeenWalletMs = System.currentTimeMillis()
                p.lastWalletReconcileMs = System.currentTimeMillis()
                p.status = if (hasCurrentWalletPositiveProof(p)) PositionStatus.OPEN_RESTORED else PositionStatus.STALE_RECOVERY_UNPROVEN
                continue
            }
            if (hasLastPositiveRaw(p) || p.sellSignature.isNullOrBlank()) {
                markNoCurrentHeldProof(p, if (pair == null) "STARTUP_NO_CURRENT_HELD_PROOF" else "STARTUP_SINGLE_PROVIDER_ZERO")
                emitForensic(LiveTradeLogStore.Phase.POSITION_COUNT_RECONCILED, p.mint, p.symbol, null,
                    "STALE_RECOVERY_UNPROVEN startup has no current held proof — not open/sell-managed")
                continue
            }
            if (p.zeroBalanceConfirmedByTwoProviders) {
                p.status = PositionStatus.CLOSED
                p.uiAmount = 0.0
                p.activeSellAttemptId = null
                p.notes.add("startup close: independent zero finality already recorded")
                try { com.lifecyclebot.engine.PositionCloseLedger.markClosed(p.mint, "CLOSED_BY_CONFIRMED_ZERO_STARTUP", 0) } catch (_: Throwable) {}
                try {
                    for (ln in listOf("SHITCOIN","MOONSHOT","QUALITY","EXPRESS","CYCLIC","BLUE_CHIP","MANIPULATED","CORE","V3","DIP_HUNTER","PROJECT_SNIPER")) {
                        com.lifecyclebot.engine.LaneExecutionCoordinator.releaseIfPrimary(p.mint, ln, "CLOSED_BY_CONFIRMED_ZERO_STARTUP")
                    }
                } catch (_: Throwable) {}
                emitForensic(LiveTradeLogStore.Phase.POSITION_COUNT_RECONCILED, p.mint, p.symbol, null,
                    "REAP_CLOSED_CONFIRMED_ZERO startup (${p.symbol ?: p.mint.take(6)})")
                closed++
            } else {
                markNoCurrentHeldProof(p, "STARTUP_SELL_SIG_OR_ZERO_NEEDS_FINALITY")
                emitForensic(LiveTradeLogStore.Phase.POSITION_COUNT_RECONCILED, p.mint, p.symbol, null,
                    "NO_CURRENT_HELD_PROOF startup sell/zero candidate lacks finality — not open")
                continue
            }
        }
        // Also collapse the parallel paper-mode registry to ledger-truth.
        try { com.lifecyclebot.engine.GlobalTradeRegistry.pruneClosedPositions() } catch (_: Throwable) {}
        // V5.0.3749 — do NOT clear TokenLifecycleTracker for live rows from
        // startup wallet-map absence. The tracker state machine owns live finality;
        // lifecycle cleanup may only follow a confirmed close ledger stamp.
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
            if (hasCurrentWalletPositiveProof(p)) continue                  // wallet-truth holds tokens — real
            if (hasLastPositiveRaw(p)) {
                markNoCurrentHeldProof(p, "HISTORICAL_RAW_NOT_CURRENT_HELD_PROOF")
                emitForensic(LiveTradeLogStore.Phase.POSITION_COUNT_RECONCILED, p.mint, p.symbol, null,
                    "STALE_RECOVERY_UNPROVEN ${p.symbol ?: p.mint.take(6)} historicalRaw=${rawAmountBig(p)} is not current wallet proof")
            }
            if (p.status in SELL_IN_FLIGHT_STATUSES && p.lastSeenWalletMs > 0L &&
                (now - p.lastSeenWalletMs) < GHOST_REAP_GRACE_MS) continue  // genuine in-flight sell
            // Never reaped while still inside the open grace window after first
            // wallet sighting (protects a just-confirmed buy mid-hydration).
            val seenAnchor = maxOf(p.lastSeenWalletMs, p.lastWalletReconcileMs ?: 0L, p.buyTimeMs ?: 0L)
            if (seenAnchor > 0L && (now - seenAnchor) < GHOST_REAP_GRACE_MS) continue
            if (!p.zeroBalanceConfirmedByTwoProviders && p.sellSignature.isNullOrBlank()) {
                emitForensic(LiveTradeLogStore.Phase.POSITION_COUNT_RECONCILED, p.mint, p.symbol, null,
                    "NO_CURRENT_HELD_PROOF ${p.symbol ?: p.mint.take(6)} no independent zero/sell finality")
                continue
            }
            p.status = PositionStatus.CLOSED
            p.uiAmount = 0.0
            p.activeSellAttemptId = null
            p.notes.add("confirmed-finality reap: zero wallet balance with sell/zero finality")
            try {
                com.lifecyclebot.engine.PositionCloseLedger.markClosed(p.mint, if (p.sellSignature.isNullOrBlank()) "CLOSED_BY_CONFIRMED_ZERO" else "CLOSED_BY_CONFIRMED_SELL", 0)
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
                if (p.sellSignature.isNullOrBlank()) "REAP_CLOSED_CONFIRMED_ZERO (${p.symbol ?: p.mint.take(6)})" else "REAP_CLOSED_CONFIRMED_SELL (${p.symbol ?: p.mint.take(6)})")
            reaped++
        }
        // V5.0.3783 — terminal sweep for stale recovered/unproven rows.
        // No current HELD proof means not open, not sell-managed, and after TTL
        // not even recovery-visible. Historical raw never protects this row.
        val staleRecoveryTtlMs = 180_000L
        for (p in positions.values.toList()) {
            if (p.status != PositionStatus.STALE_RECOVERY_UNPROVEN) continue
            if (hasCurrentWalletPositiveProof(p)) continue
            val anchor2 = maxOf(p.lastWalletReconcileMs ?: 0L, p.lastSeenWalletMs, p.buyTimeMs ?: 0L, p.firstSeenWalletMs)
            if (anchor2 > 0L && (now - anchor2) < staleRecoveryTtlMs) continue
            p.status = PositionStatus.CLOSED_STALE_RECOVERY_UNHELD
            p.uiAmount = 0.0
            p.rawAmount = "0"
            p.activeSellAttemptId = null
            p.sellAttemptStartedMs = 0L
            p.notes.add("closed stale recovery: no current held proof after TTL")
            try { com.lifecyclebot.engine.PositionCloseLedger.markClosed(p.mint, "CLOSED_STALE_RECOVERY_UNHELD", 0) } catch (_: Throwable) {}
            try { com.lifecyclebot.engine.sell.SellExecutionLocks.release(p.mint) } catch (_: Throwable) {}
            try { com.lifecyclebot.engine.sell.CloseLease.release(p.mint, "CLOSED_STALE_RECOVERY_UNHELD") } catch (_: Throwable) {}
            emitForensic(LiveTradeLogStore.Phase.POSITION_COUNT_RECONCILED, p.mint, p.symbol, null,
                "CLOSED_STALE_RECOVERY_UNHELD ${p.symbol ?: p.mint.take(6)} no current held proof")
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
        return hasCurrentWalletPositiveProof(p)
    }

    /** V5.9.612 AntiChoke: wallet snapshot proved zero; unblock internal ghost state. */
    fun markUnheldByAntiChoke(mint: String, reason: String): Boolean {
        val p = positions[mint] ?: return false
        if (p.status in OPEN_STATUSES) {
            markNoCurrentHeldProof(p, "ANTICHOKE_ZERO_REJECTED_NO_FINALITY:$reason")
            save()
            return false
        }
        return false
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
    fun recordIndependentZeroBalanceProof(
        mint: String,
        sources: Set<String>,
        reason: String,
    ): Boolean {
        val p = positions[mint] ?: return false
        val cleanSources = sources.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (cleanSources.size < 2) {
            markNoCurrentHeldProof(p, "ZERO_PROOF_REJECTED_NOT_INDEPENDENT:$reason sources=${cleanSources.joinToString("+")}")
            save()
            return false
        }
        val trustedTerminalZero =
            ("SELL_RECONCILER_NONEMPTY_SNAPSHOT" in cleanSources && "MINT_ABSENT_FROM_TOKEN_ACCOUNTS" in cleanSources) ||
            ("BALANCE_PROOF_POLLER_ZERO_STREAK" in cleanSources && "SELL_AMOUNT_AUTHORITY_NONEMPTY_MINT_ABSENT" in cleanSources) ||
            ("LIVE_POSITION_CLOSE_AUTHORITY" in cleanSources && ("SELL_SIGNATURE_OR_META" in cleanSources || "CONFIRMED_ZERO_BALANCE" in cleanSources))
        if ((p.activeSellAttemptId != null || p.status in setOf(PositionStatus.SELL_PENDING, PositionStatus.SELL_VERIFYING)) && !trustedTerminalZero) {
            markNoCurrentHeldProof(p, "ZERO_PROOF_REJECTED_SELL_ACTIVE:$reason sources=${cleanSources.joinToString("+")}")
            save()
            return false
        }
        p.zeroBalanceConfirmedByTwoProviders = true
        p.consecutiveZeroConfirms = maxOf(p.consecutiveZeroConfirms, 2)
        p.uiAmount = 0.0
        p.rawAmount = "0"
        p.lastWalletReconcileMs = System.currentTimeMillis()
        p.notes.add("independent zero-balance proof reason=$reason sources=${cleanSources.joinToString("+")}")
        try {
            ForensicLogger.lifecycle(
                "INDEPENDENT_ZERO_BALANCE_PROOF",
                "mint=${mint.take(10)} symbol=${p.symbol ?: "?"} sources=${cleanSources.joinToString("+")} reason=$reason",
            )
        } catch (_: Throwable) {}
        save()
        return true
    }

    fun confirmZeroBalanceClose(
        mint: String,
        hasConfirmedSellSig: Boolean,
        reason: String,
    ): TrackedTokenPosition? {
        val p = positions[mint] ?: return null
        if (p.status == PositionStatus.CLOSED) return null
        if (hasConfirmedSellSig && p.sellSignature.isNullOrBlank() && !p.zeroBalanceConfirmedByTwoProviders) {
            markNoCurrentHeldProof(p, "SELL_FINALITY_REJECTED_SIG_FLAG_WITHOUT_SIGNATURE:$reason")
            save()
            return null
        }
        // Defensive: only act on genuinely-empty rows. V5.9.1585: when the
        // live reconciler explicitly passes WALLET_BALANCE_ZERO, that observation
        // is fresher than the tracker's cached uiAmount. Update wallet truth first;
        // otherwise OPEN_TRACKING rows with stale uiAmount keep hostLive=1 forever
        // and poison buys with HOST_TRACKER_DESYNC / ORPHAN_LIVE_POSITIONS.
        if (p.uiAmount > 0.000001 && !hasConfirmedSellSig && !p.zeroBalanceConfirmedByTwoProviders) {
            markNoCurrentHeldProof(p, "ZERO_CLOSE_REJECTED_POSITIVE_CACHE_NO_INDEPENDENT_FINALITY:$reason")
            save()
            return null
        }
        if (!hasConfirmedSellSig && hasLastPositiveRaw(p) && !p.zeroBalanceConfirmedByTwoProviders) {
            markNoCurrentHeldProof(p, "ZERO_CLOSE_REJECTED_LAST_POSITIVE_NO_INDEPENDENT_FINALITY:$reason")
            save()
            return null
        }
        if (p.uiAmount > 0.000001 && (hasConfirmedSellSig || p.zeroBalanceConfirmedByTwoProviders)) {
            p.uiAmount = 0.0
            p.rawAmount = "0"
            p.lastWalletReconcileMs = System.currentTimeMillis()
        }
        val confirms = p.consecutiveZeroConfirms + 1
        p.consecutiveZeroConfirms = confirms
        val canClose = hasConfirmedSellSig || p.zeroBalanceConfirmedByTwoProviders
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
        try { com.lifecyclebot.engine.sell.SellExecutionLocks.release(mint) } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.sell.CloseLease.release(mint, "ZERO_BALANCE_CLOSE:$reason") } catch (_: Throwable) {}
        try {
            for (ln in listOf("SHITCOIN","MOONSHOT","QUALITY","EXPRESS","CYCLIC","BLUE_CHIP","BLUECHIP","MANIPULATED","CORE","V3","DIP_HUNTER","PROJECT_SNIPER","STANDARD")) {
                com.lifecyclebot.engine.LaneExecutionCoordinator.releaseIfPrimary(mint, ln, "ZERO_BALANCE_CLOSE:$reason")
            }
        } catch (_: Throwable) {}
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
                // No sig — only independent zero finality may stamp closed.
                if (p.zeroBalanceConfirmedByTwoProviders) {
                    com.lifecyclebot.engine.PositionCloseLedger.markClosed(mint, "CLOSED_BY_CONFIRMED_ZERO_$reason", pnlPct)
                } else ""
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
     *    (confirmed zero reaps are excluded — they are not sell-claimed closes)
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
            if (p.status in SELL_IN_FLIGHT_STATUSES) {
                p.status = if (hasCurrentWalletPositiveProof(p)) PositionStatus.OPEN_TRACKING else PositionStatus.STALE_RECOVERY_UNPROVEN
            }
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
        if (p.status in SELL_IN_FLIGHT_STATUSES) p.status = if (hasCurrentWalletPositiveProof(p)) PositionStatus.OPEN_TRACKING else PositionStatus.STALE_RECOVERY_UNPROVEN
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
        val staleUnproven = positions.values.count { it.status == PositionStatus.STALE_RECOVERY_UNPROVEN }
        return "open=$open · closed=$closed · staleUnproven=$staleUnproven · total=${positions.size}"
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
     *   STALE_RECOVERY_UNPROVEN, DUST_IGNORED, BUY_PENDING,
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
