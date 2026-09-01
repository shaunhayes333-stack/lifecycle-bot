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
 *   cashSol             — PaperCapitalAuthority6577.cashSol()
 *   realizedPnlSol      — PaperCapitalAuthority6577.realizedPnlSol()
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
        // V5.0.6523 §HEADER_ROW_PARITY — split by mode so the paper hero
        // does not double-count live open positions (operator screenshot
        // Feb 2026: header +483◎ vs paper rows summing to only +55.83◎).
        // The UI row list filters state.openPositions by isPaperPosition
        // == config.paperMode; the hero authority MUST expose the same
        // partition so header numbers match the row list byte-for-byte.
        val paperOpenCount: Int = openCount,
        val paperTotalExposureSol: Double = totalExposureSol,
        val paperTotalUnrealizedSol: Double = totalUnrealizedSol,
        val liveOpenCount: Int = 0,
        val liveTotalExposureSol: Double = 0.0,
        val liveTotalUnrealizedSol: Double = 0.0,
        // V5.0.6616 §JOURNAL_BALANCE_HERO_SINGLE_AUTHORITY_REPAIR —
        //   carry the journal-authoritative economic revision so hero
        //   consumers can prove all three screens rendered the same
        //   causal snapshot. source names the authority that supplied
        //   the cash/equity numbers.
        val journalRevision: Long = -1L,
        val source: String = "CANONICAL_CAPITAL_AUTHORITY_6450",
    ) {
        fun openCountFor(paperMode: Boolean): Int = if (paperMode) paperOpenCount else liveOpenCount
        fun totalExposureSolFor(paperMode: Boolean): Double = if (paperMode) paperTotalExposureSol else liveTotalExposureSol
        fun totalUnrealizedSolFor(paperMode: Boolean): Double = if (paperMode) paperTotalUnrealizedSol else liveTotalUnrealizedSol
    }

    private val cached = AtomicReference<Hero?>(null)
    private val refreshes = AtomicLong(0L)
    private val cacheHits = AtomicLong(0L)
    private val cacheMisses = AtomicLong(0L)
    // V5.0.6523 — rate-limit the HERO_UNREALIZED_BREAKDOWN_6523 diagnostic
    // so operator dumps get one line every 5s instead of 2/s at REFRESH_MS.
    private val lastDiagnosticEmitMs = AtomicLong(0L)
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
        // V5.0.6523 §HEADER_ROW_PARITY — track paper vs live separately so
        // the UI can bind whichever total matches its filtered row list.
        var paperOpenCount = 0
        var paperTotalExposureSol = 0.0
        var paperTotalUnrealizedSol = 0.0
        var liveOpenCount = 0
        var liveTotalExposureSol = 0.0
        var liveTotalUnrealizedSol = 0.0
        // V5.0.6523 diagnostic — capture per-position contributions so
        // the operator can see WHICH positions produced the header total
        // whenever it surprises them (screenshot Feb 2026: paper header
        // +483◎ vs paper rows summing to +55.83◎).
        data class Contrib(
            val mint: String, val symbol: String, val paper: Boolean,
            val costSol: Double, val pnlPct: Double, val contribSol: Double,
            val entrySource: String, val currentSource: String,
        )
        val contribs = ArrayList<Contrib>(values.size)
        for (ts in values) {
            try {
                val pos = ts.position
                if (com.lifecyclebot.engine.PositionCloseLedger.isClosed(ts.mint)) continue
                if (!QuantityInvariantAuthority6500.isRuntimeOpenEligible6636(ts.mint, pos)) continue
                openCount++
                totalExposureSol += pos.costSol
                val verdict = try {
                    com.lifecyclebot.engine.OpenPnlSanity.inspect(
                        ts,
                        "HeroSnapshotAuthority6503/${ts.symbol}/${ts.mint.take(8)}",
                        emit = false,
                    )
                } catch (_: Throwable) { null }
                val posUnrealizedSol = if (verdict != null && verdict.ok) pos.costSol * verdict.pnlPct / 100.0 else 0.0
                if (verdict != null && verdict.ok) {
                    totalUnrealizedSol += posUnrealizedSol
                }
                if (pos.isPaperPosition) {
                    paperOpenCount++
                    paperTotalExposureSol += pos.costSol
                    paperTotalUnrealizedSol += posUnrealizedSol
                } else {
                    liveOpenCount++
                    liveTotalExposureSol += pos.costSol
                    liveTotalUnrealizedSol += posUnrealizedSol
                }
                contribs.add(Contrib(
                    mint = ts.mint, symbol = ts.symbol, paper = pos.isPaperPosition,
                    costSol = pos.costSol, pnlPct = verdict?.pnlPct ?: 0.0,
                    contribSol = posUnrealizedSol,
                    entrySource = pos.entryPriceSource,
                    currentSource = ts.lastPriceSource,
                ))
            } catch (_: Throwable) {}
        }
        try {
            val now = System.currentTimeMillis()
            val shouldEmit = kotlin.math.abs(paperTotalUnrealizedSol) > 100.0 ||
                kotlin.math.abs(liveTotalUnrealizedSol) > 100.0 ||
                (paperOpenCount > 0 && liveOpenCount > 0)
            if (shouldEmit && now - lastDiagnosticEmitMs.get() > 5_000L) {
                lastDiagnosticEmitMs.set(now)
                val top = contribs.sortedByDescending { kotlin.math.abs(it.contribSol) }.take(6)
                val summary = top.joinToString(" · ") { c ->
                    "${c.symbol.ifBlank { c.mint.take(6) }}|${if (c.paper) "P" else "L"}|" +
                        "cost=${"%.4f".format(c.costSol)}|pct=${"%.1f".format(c.pnlPct)}|" +
                        "contrib=${"%+.4f".format(c.contribSol)}|" +
                        "eSrc=${c.entrySource.take(12)}|cSrc=${c.currentSource.take(12)}"
                }
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "HERO_UNREALIZED_BREAKDOWN_6523",
                    "paperOpen=$paperOpenCount paperExp=${"%.4f".format(paperTotalExposureSol)} " +
                        "paperUpnl=${"%+.4f".format(paperTotalUnrealizedSol)} " +
                        "liveOpen=$liveOpenCount liveExp=${"%.4f".format(liveTotalExposureSol)} " +
                        "liveUpnl=${"%+.4f".format(liveTotalUnrealizedSol)} " +
                        "totalOpen=$openCount total=${"%.4f".format(totalExposureSol)} " +
                        "upnl=${"%+.4f".format(totalUnrealizedSol)} top6=[$summary]",
                )
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HERO_UNREALIZED_BREAKDOWN_6523")
            }
        } catch (_: Throwable) {}
        val equitySol = try {
            // V5.0.6508e — CONSERVATIVE (see MainActivity).
            // Hero background snapshot uses totalEquitySol; divergence
            // vs authoritativeEquitySol surfaces via counter only.
            //
            // V5.0.6616 §JOURNAL_BALANCE_HERO_SINGLE_AUTHORITY_REPAIR —
            //   prefer the revision-tracked JournalEconomicAuthority6616
            //   snapshot so the hero cache carries the same journal
            //   revision every UI surface renders. Falls back to the
            //   canonical capital snapshot when the authority has not
            //   published yet (pre-restore or live-only sessions).
            val jSnap6616 = try { JournalEconomicAuthority6616.currentSnapshot() } catch (_: Throwable) { null }
            if (jSnap6616 != null) {
                jSnap6616.equitySol
            } else {
                val snap6508 = CanonicalCapitalAuthority6450.snapshot()
                val delta6508 = kotlin.math.abs(snap6508.totalEquitySol - snap6508.authoritativeEquitySol)
                if (snap6508.totalEquitySol > 0.001 &&
                    delta6508 / snap6508.totalEquitySol > 0.05) {
                    try {
                        com.lifecyclebot.engine.PipelineHealthCollector
                            .labelInc("HERO_EQUITY_AUTHORITATIVE_DIVERGENCE_6508")
                    } catch (_: Throwable) {}
                }
                snap6508.totalEquitySol
            }
        } catch (_: Throwable) { 0.0 }
        val cashSol = try {
            // V5.0.6616 — journal-authoritative cash preferred.
            JournalEconomicAuthority6616.currentSnapshot()?.cashSol
                ?: PaperCapitalAuthority6577.cashSol()
        } catch (_: Throwable) { 0.0 }
        val realizedPnlSol = try {
            JournalEconomicAuthority6616.currentSnapshot()?.realizedPnlSol
                ?: PaperCapitalAuthority6577.realizedPnlSol()
        } catch (_: Throwable) { 0.0 }
        val journalRev6616 = try {
            JournalEconomicAuthority6616.currentSnapshot()?.revision ?: -1L
        } catch (_: Throwable) { -1L }
        val heroSource6616 = try {
            JournalEconomicAuthority6616.currentSnapshot()?.source
                ?: "CANONICAL_CAPITAL_AUTHORITY_6450"
        } catch (_: Throwable) { "CANONICAL_CAPITAL_AUTHORITY_6450" }
        cached.set(
            Hero(
                openCount = openCount,
                totalExposureSol = totalExposureSol,
                totalUnrealizedSol = totalUnrealizedSol,
                equitySol = equitySol,
                cashSol = cashSol,
                realizedPnlSol = realizedPnlSol,
                atMs = System.currentTimeMillis(),
                paperOpenCount = paperOpenCount,
                paperTotalExposureSol = paperTotalExposureSol,
                paperTotalUnrealizedSol = paperTotalUnrealizedSol,
                liveOpenCount = liveOpenCount,
                liveTotalExposureSol = liveTotalExposureSol,
                liveTotalUnrealizedSol = liveTotalUnrealizedSol,
                journalRevision = journalRev6616,
                source = heroSource6616,
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
