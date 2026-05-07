package com.lifecyclebot.engine.execution

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.HostWalletTokenTracker
import com.lifecyclebot.engine.LiveTradeLogStore
import com.lifecyclebot.network.SolanaWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.495z22 — Position ↔ Wallet Reconciler (item B of the abcd refactor).
 *
 * Operator spec: every 60s, walk the open positions reported by every
 * trader lane and compare each `(symbol, resolvedMint)` against the live
 * host-wallet balance. If the UI says we hold SYMBOL but the on-chain
 * wallet has zero of the resolved mint, mark the position as
 * "PHANTOM_POSITION" — fires a critical alert plus a red-pill UI badge.
 *
 * Output:
 *   • A snapshot of the latest reconciliation (counts, phantom mints)
 *     readable by the diagnostics screen.
 *   • Forensic events PHANTOM_POSITION_DETECTED and
 *     PHANTOM_POSITION_CLEARED routed through LiveTradeLogStore so the
 *     existing forensics tab shows them inline.
 *
 * Soft-coupled: no compile-time dependency on CryptoAltTrader / Executor.
 * Whoever holds positions exposes them via the `Source` interface or the
 * `report(...)` static call — keeps the reconciler 100% additive.
 */
object PositionWalletReconciler {

    private const val TAG = "PositionReconciler"
    private const val INTERVAL_MS = 60_000L
    private const val MIN_INTERVAL_MS = 15_000L            // floor for forced ticks
    private const val PHANTOM_GRACE_MS = 90_000L           // ignore positions <90s old (settlement window)

    /** Pluggable position source — any trader can register one. */
    fun interface Source {
        fun snapshot(): List<ReportedPosition>
    }

    data class ReportedPosition(
        val laneTag: String,            // "MEME" / "PERPS_CRYPTOALT" / "STOCK" / etc.
        val intendedSymbol: String,
        val resolvedMint: String?,      // null if the lane never resolved one
        val openedAtMs: Long,
        val sizeUiAmount: Double,       // expected token holding (or notional in SOL for paper)
    )

    enum class Verdict { HEALTHY, PHANTOM, NO_MINT, GRACE, INCONCLUSIVE }

    data class Finding(
        val laneTag: String,
        val symbol: String,
        val mint: String?,
        val walletUiAmount: Double,
        val expectedUiAmount: Double,
        val verdict: Verdict,
        val ageMs: Long,
        val checkedAtMs: Long = System.currentTimeMillis(),
    )

    data class Snapshot(
        val totalChecked: Int,
        val healthy: Int,
        val phantoms: Int,
        val noMint: Int,
        val grace: Int,
        val inconclusive: Int,
        val phantomFindings: List<Finding>,
        val tickAtMs: Long,
    )

    private val sources = ConcurrentHashMap<String, Source>()
    @Volatile private var walletRef: SolanaWallet? = null
    @Volatile private var job: Job? = null
    @Volatile private var lastSnapshot: Snapshot = Snapshot(0, 0, 0, 0, 0, 0, emptyList(), 0L)
    private val lastTickMs = AtomicLong(0L)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Latch — once a (lane, mint) is flagged PHANTOM we don't spam alerts every tick. */
    private val phantomLatch = ConcurrentHashMap<String, Long>()

    fun registerSource(name: String, source: Source) {
        sources[name] = source
        ErrorLogger.info(TAG, "registered source: $name (total=${sources.size})")
    }

    fun unregisterSource(name: String) { sources.remove(name) }

    fun start(wallet: SolanaWallet) {
        walletRef = wallet
        if (job?.isActive == true) return
        job = scope.launch {
            ErrorLogger.info(TAG, "▶ reconciler loop started (interval=${INTERVAL_MS}ms)")
            while (isActive) {
                try { tick() } catch (e: Throwable) {
                    ErrorLogger.warn(TAG, "tick err: ${e.message}")
                }
                delay(INTERVAL_MS)
            }
        }
    }

    fun stop() {
        try { job?.cancel() } catch (_: Throwable) {}
        job = null
        walletRef = null
    }

    /** External force-tick (e.g. operator opened the diagnostics screen). */
    fun forceTick() {
        val now = System.currentTimeMillis()
        if (now - lastTickMs.get() < MIN_INTERVAL_MS) return
        scope.launch {
            try { tick() } catch (e: Throwable) {
                ErrorLogger.warn(TAG, "forceTick err: ${e.message}")
            }
        }
    }

    fun snapshot(): Snapshot = lastSnapshot

    // ─── internals ────────────────────────────────────────────────────────────

    private suspend fun tick() {
        lastTickMs.set(System.currentTimeMillis())
        val wallet = walletRef ?: return
        if (sources.isEmpty()) return

        val walletMints: Map<String, Pair<Double, Int>> = try {
            wallet.getTokenAccountsWithDecimals()
        } catch (e: Throwable) {
            ErrorLogger.debug(TAG, "wallet snapshot failed: ${e.message} — skipping tick")
            return
        }

        if (walletMints.isEmpty()) {
            // RPC blip — never make phantom-decisions on an empty map. Same defense
            // pattern as MarketsLiveExecutor V5.9.475 / Executor V5.9.467.
            ErrorLogger.debug(TAG, "wallet map empty (RPC blip) — skip tick")
            return
        }

        val now = System.currentTimeMillis()
        val findings = mutableListOf<Finding>()

        for (src in sources.values) {
            val positions = try { src.snapshot() } catch (_: Throwable) { emptyList() }
            for (pos in positions) {
                val ageMs = now - pos.openedAtMs
                if (ageMs < PHANTOM_GRACE_MS) {
                    findings += Finding(pos.laneTag, pos.intendedSymbol, pos.resolvedMint,
                        0.0, pos.sizeUiAmount, Verdict.GRACE, ageMs)
                    continue
                }
                val mint = pos.resolvedMint
                if (mint.isNullOrBlank() || !MintIntegrityGate.isLikelyMint(mint)) {
                    findings += Finding(pos.laneTag, pos.intendedSymbol, mint,
                        0.0, pos.sizeUiAmount, Verdict.NO_MINT, ageMs)
                    continue
                }
                val walletUi = walletMints[mint]?.first ?: 0.0
                val verdict = when {
                    walletUi <= 0.0 -> Verdict.PHANTOM
                    walletUi > 0.0  -> Verdict.HEALTHY
                    else            -> Verdict.INCONCLUSIVE
                }
                findings += Finding(pos.laneTag, pos.intendedSymbol, mint, walletUi,
                    pos.sizeUiAmount, verdict, ageMs)
            }
        }

        // Apply latch + emit forensics for new phantoms.
        val phantoms = findings.filter { it.verdict == Verdict.PHANTOM }
        for (f in phantoms) {
            val key = "${f.laneTag}:${f.mint}"
            val firstSeen = phantomLatch.putIfAbsent(key, now)
            if (firstSeen == null) {
                emitPhantomDetected(f)
            }
        }
        // Clear latches that are no longer phantom.
        val activePhantomKeys = phantoms.map { "${it.laneTag}:${it.mint}" }.toSet()
        for (k in phantomLatch.keys.toList()) {
            if (k !in activePhantomKeys) {
                val (lane, mint) = k.split(":", limit = 2).let { (it.getOrNull(0) ?: "?") to (it.getOrNull(1) ?: "") }
                phantomLatch.remove(k)
                emitPhantomCleared(lane, mint)
            }
        }

        lastSnapshot = Snapshot(
            totalChecked = findings.size,
            healthy      = findings.count { it.verdict == Verdict.HEALTHY },
            phantoms     = phantoms.size,
            noMint       = findings.count { it.verdict == Verdict.NO_MINT },
            grace        = findings.count { it.verdict == Verdict.GRACE },
            inconclusive = findings.count { it.verdict == Verdict.INCONCLUSIVE },
            phantomFindings = phantoms,
            tickAtMs = now,
        )

        if (phantoms.isNotEmpty() || findings.count { it.verdict == Verdict.NO_MINT } > 0) {
            ErrorLogger.warn(TAG,
                "tick: total=${findings.size} healthy=${lastSnapshot.healthy} " +
                "phantoms=${phantoms.size} noMint=${lastSnapshot.noMint} grace=${lastSnapshot.grace}")
        }
    }

    private fun emitPhantomDetected(f: Finding) {
        val msg = "🚨 PHANTOM POSITION ${f.laneTag} ${f.symbol} | mint=${f.mint?.take(8)}… | " +
                  "wallet=${"%.4f".format(f.walletUiAmount)} expected≈${"%.4f".format(f.expectedUiAmount)} | " +
                  "age=${f.ageMs / 1000}s"
        try {
            LiveTradeLogStore.log(
                tradeKey = "RECON_${f.mint?.take(16) ?: f.symbol}",
                mint     = f.mint ?: "",
                symbol   = f.symbol,
                side     = "INFO",
                phase    = LiveTradeLogStore.Phase.WARNING,
                message  = msg,
                traderTag = "RECONCILER",
            )
        } catch (_: Throwable) {}
        try {
            Forensics.log(
                Forensics.Event.BUY_FAILED_NO_TARGET_TOKEN,
                mint = f.mint ?: "",
                msg  = "PHANTOM_POSITION_DETECTED ${f.laneTag} ${f.symbol}",
            )
        } catch (_: Throwable) {}
        ErrorLogger.warn(TAG, msg)
    }

    private fun emitPhantomCleared(lane: String, mint: String) {
        val msg = "✅ PHANTOM CLEARED ${lane} mint=${mint.take(8)}…"
        try {
            LiveTradeLogStore.log(
                tradeKey = "RECON_${mint.take(16)}",
                mint     = mint,
                symbol   = mint.take(6),
                side     = "INFO",
                phase    = LiveTradeLogStore.Phase.INFO,
                message  = msg,
                traderTag = "RECONCILER",
            )
        } catch (_: Throwable) {}
        ErrorLogger.info(TAG, msg)
    }

    /**
     * Convenience helper for traders that want to *also* sanity-check
     * against HostWalletTokenTracker rather than maintain their own source.
     * Call this once after tracker init and the reconciler will pick up
     * every tracked OPEN_TRACKING entry as a reported position.
     */
    fun installHostTrackerSource() {
        registerSource("HostWalletTokenTracker") {
            try {
                HostWalletTokenTracker.getOpenTrackedPositions().map { p ->
                    ReportedPosition(
                        laneTag        = p.venue ?: "TRACKER",
                        intendedSymbol = p.symbol ?: p.mint.take(6),
                        resolvedMint   = p.mint,
                        openedAtMs     = p.buyTimeMs ?: p.firstSeenWalletMs,
                        sizeUiAmount   = p.uiAmount,
                    )
                }
            } catch (_: Throwable) { emptyList() }
        }
    }
}
