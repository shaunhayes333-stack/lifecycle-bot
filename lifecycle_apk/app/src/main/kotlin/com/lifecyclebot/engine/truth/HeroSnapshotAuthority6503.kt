package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.BotStatus
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6503 §2 — HERO SNAPSHOT AUTHORITY.
 *
 * OPERATOR MANDATE (verbatim, 6501/6502 evidence):
 *
 *   "6 ANR hints and massive frame drops (max gap 3017ms) caused by
 *    heavy getOpenPositions() aggregation on the main thread during
 *    onCreate. Move MainActivity hero aggregation and
 *    PipelineHealthActivity report generation completely off
 *    Dispatchers.Main to Dispatchers.Default. Use a snapshot state
 *    holder (StateFlow)."
 *
 * DESIGN
 * ──────
 * Every `REFRESH_MS` a background loop on `Dispatchers.Default` walks
 * `status.tokens.values` ONCE, computes the aggregate the hero tiles
 * previously derived synchronously on Main:
 *
 *   openCount           — # positions with position.isOpen
 *   totalExposureSol    — Σ position.costSol over open positions
 *   totalUnrealizedSol  — Σ costSol × (mark−entry)/entry via OpenPnlSanity
 *   equitySol           — CanonicalCapitalAuthority6450.equitySol()
 *   cashSol             — PaperAccountLedger6430.cashSol()
 *   realizedPnlSol      — PaperAccountLedger6430.realizedPnlSol()
 *
 * The result is stored in an `AtomicReference<Hero?>`. Main-thread
 * readers get O(1) atomic load. Cache is stale after `STALE_TTL_MS`
 * (caller falls back to inline compute — never returns stale hero
 * numbers to the operator).
 *
 * PAPER + LIVE PARITY
 * ───────────────────
 * The authority is mode-agnostic — the token map iteration returns
 * the currently visible open positions regardless of paperMode. Hero
 * tiles bind whichever mode the operator has selected in the UI; the
 * authority's job is only to compute the aggregates ONCE off-Main.
 * This matches the $50→$1M autonomous intelligent trading mantra
 * (V5.7+ correctness mandate) — the same source of truth feeds paper
 * runners today and live runners the moment live gating opens.
 */
object HeroSnapshotAuthority6503 {

    private const val REFRESH_MS = 500L
    private const val STALE_TTL_MS = 5_000L

    data class Hero(
        val openCount: Int,
        val totalExposureSol: Double,
        val totalUnrealizedSol: Double,
        val equitySol: Double,
        val cashSol: Double,
        val realizedPnlSol: Double,
        val atMs: Long,
    )

    private val cached = AtomicReference<Hero?>(null)
    private val refreshes = AtomicLong(0L)
    private val cacheHits = AtomicLong(0L)
    private val cacheMisses = AtomicLong(0L)
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    @Synchronized
    fun start(status: BotStatus) {
        if (job?.isActive == true) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        job = s.launch {
            while (isActive) {
                try { refresh(status) } catch (_: Throwable) {}
                delay(REFRESH_MS)
            }
        }
        try {
            ForensicLogger.lifecycle("HERO_SNAPSHOT_AUTHORITY_STARTED_6503", "refreshMs=$REFRESH_MS")
            PipelineHealthCollector.labelInc("HERO_SNAPSHOT_AUTHORITY_STARTED_6503")
        } catch (_: Throwable) {}
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        scope = null
    }

    private fun refresh(status: BotStatus) {
        refreshes.incrementAndGet()
        // Materialise the ConcurrentHashMap live view ONCE off-Main.
        val values = try { ArrayList(status.tokens.values) } catch (_: Throwable) { arrayListOf() }
        var openCount = 0
        var totalExposureSol = 0.0
        var totalUnrealizedSol = 0.0
        for (ts in values) {
            try {
                val pos = ts.position
                if (com.lifecyclebot.engine.PositionCloseLedger.isClosed(ts.mint)) continue
                if (!pos.isOpen) continue
                openCount++
                totalExposureSol += pos.costSol
                val verdict = try {
                    com.lifecyclebot.engine.OpenPnlSanity.inspect(
                        ts,
                        "HeroSnapshotAuthority6503/${ts.symbol}/${ts.mint.take(8)}",
                        emit = false,
                    )
                } catch (_: Throwable) { null }
                if (verdict != null && verdict.ok) {
                    totalUnrealizedSol += pos.costSol * verdict.pnlPct / 100.0
                }
            } catch (_: Throwable) {}
        }
        val equitySol = try {
            CanonicalCapitalAuthority6450.equitySol()
        } catch (_: Throwable) { 0.0 }
        val cashSol = try {
            PaperAccountLedger6430.cashSol()
        } catch (_: Throwable) { 0.0 }
        val realizedPnlSol = try {
            PaperAccountLedger6430.realizedPnlSol()
        } catch (_: Throwable) { 0.0 }
        cached.set(
            Hero(
                openCount = openCount,
                totalExposureSol = totalExposureSol,
                totalUnrealizedSol = totalUnrealizedSol,
                equitySol = equitySol,
                cashSol = cashSol,
                realizedPnlSol = realizedPnlSol,
                atMs = System.currentTimeMillis(),
            )
        )
    }

    /**
     * Fast path used by MainActivity hero tiles / status bars.
     * Returns the cached hero when fresh; null when the authority is
     * not started or the cache is stale (caller falls through to
     * inline compute — never a silent lie).
     */
    fun current(): Hero? {
        val snap = cached.get()
        if (snap == null) {
            cacheMisses.incrementAndGet()
            return null
        }
        if (System.currentTimeMillis() - snap.atMs > STALE_TTL_MS) {
            cacheMisses.incrementAndGet()
            return null
        }
        cacheHits.incrementAndGet()
        return snap
    }

    fun statusLine(): String =
        "refreshes=${refreshes.get()} cacheHits=${cacheHits.get()} cacheMisses=${cacheMisses.get()} " +
            (cached.get()?.let {
                "open=${it.openCount} exp=${"%.4f".format(it.totalExposureSol)} " +
                    "upnl=${"%.4f".format(it.totalUnrealizedSol)} eq=${"%.4f".format(it.equitySol)} " +
                    "cash=${"%.4f".format(it.cashSol)} realized=${"%.4f".format(it.realizedPnlSol)}"
            } ?: "cache=empty")

    internal fun resetForTest() {
        stop()
        cached.set(null)
        refreshes.set(0L); cacheHits.set(0L); cacheMisses.set(0L)
    }
}
