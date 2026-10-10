package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8017 — runner capture: for every coin the bot saw that ran 4x or more, did the bot hold it,
 * where did it get in compared with where the coin started, and what share of the run did it bank?
 *
 * The forward labeler sees every runner (it follows every coin the bot looks at); this joins those
 * runners to the bot's own positions (open at detection, or closed through the canonical finalized
 * bus) so the diag answers the one question that decides what to fix next: entries, holds or exits.
 *
 *  - start   = the coin's price when the bot first judged it (the forward label's decision mark)
 *  - peak    = the highest price the labeler saw, as % over start
 *  - entry   = the bot's average entry, as % over start (negative = it bought below the start mark)
 *  - banked  = the realised whole-position return; share = banked / (peak over the bot's own entry)
 */
object RunnerCapture8017 {
    const val RUNNER_PEAK_PCT_8017 = 400.0
    private const val KEEP_MS = 24L * 3_600_000L
    private const val MAX_RUNS = 300

    class Run(val mint: String, val symbol: String, val lane: String, val startPx: Double, val atMs: Long, val refusedBy: String) {
        @Volatile var peakPct = 0.0
        @Volatile var held = false
        @Volatile var live = false
        @Volatile var entryPx = 0.0
        @Volatile var bankedPct = Double.NaN
        @Volatile var openNowPct = Double.NaN
    }

    private val runs = ConcurrentHashMap<String, Run>()
    private val seen = AtomicLong(0)

    /** Pure: the entry's distance above (+) or below (-) the coin's start mark, in %. */
    fun entryVsStart8017(entryPx: Double, startPx: Double): Double =
        if (entryPx > 0.0 && startPx > 0.0) (entryPx / startPx - 1.0) * 100.0 else Double.NaN

    /** Pure: the share of the run the bot could have had from its own entry that it actually banked. */
    fun bankedShare8017(bankedPct: Double, peakOverStartPct: Double, entryVsStartPct: Double): Double {
        if (!bankedPct.isFinite() || !peakOverStartPct.isFinite()) return Double.NaN
        val e = if (entryVsStartPct.isFinite()) entryVsStartPct else 0.0
        val available = ((1.0 + peakOverStartPct / 100.0) / (1.0 + e / 100.0) - 1.0) * 100.0
        return if (available > 0.0) bankedPct / available else Double.NaN
    }

    /** ForwardReturnLabeler7731: a followed coin's peak passed (or rose further past) the runner bar. */
    fun onRunnerPeak8017(mint: String, symbol: String, lane: String, startPx: Double, peakPct: Double, refusedBy: String, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank() || !peakPct.isFinite() || peakPct < RUNNER_PEAK_PCT_8017) return
        val r = runs.getOrPut(mint) {
            if (runs.size >= MAX_RUNS) runs.entries.removeIf { nowMs - it.value.atMs > KEEP_MS }
            if (runs.size >= MAX_RUNS) runs.entries.minByOrNull { it.value.atMs }?.key?.let { runs.remove(it) }
            seen.incrementAndGet()
            try { PipelineHealthCollector.labelInc("RUNNER_SEEN_8017") } catch (_: Throwable) {}
            Run(mint, symbol, lane, startPx, nowMs, refusedBy)
        }
        if (peakPct > r.peakPct) r.peakPct = peakPct
        // Is the bot holding it right now?
        try {
            val ts = com.lifecyclebot.engine.BotService.status.tokens[mint]
            val pos = ts?.position
            if (pos != null && pos.isOpen && pos.entryPrice > 0.0) {
                r.held = true; r.live = !pos.isPaperPosition; r.entryPx = pos.entryPrice
                val px = ts.lastPrice
                if (px > 0.0) r.openNowPct = (px / pos.entryPrice - 1.0) * 100.0
            }
        } catch (_: Throwable) {}
    }

    /** CanonicalFinalizedTradeBus6464: a position closed — if it was a runner, that is what the bot banked. */
    fun onClose8017(env: CanonicalFinalizedTradeBus6464.Envelope) {
        val r = runs[env.mint] ?: return
        r.held = true
        r.live = r.live || env.mode.equals("live", ignoreCase = true)
        if (env.realizedReturnPct.isFinite()) r.bankedPct = env.realizedReturnPct
        r.openNowPct = Double.NaN
    }

    fun statusLine(): String {
        val now = System.currentTimeMillis()
        val recent = runs.values.filter { now - it.atMs <= KEEP_MS }.sortedByDescending { it.peakPct }
        if (recent.isEmpty()) return "runners(4x+,24h)=0"
        val held = recent.filter { it.held }
        val liveHeld = held.count { it.live }
        val entries = held.mapNotNull { entryVsStart8017(it.entryPx, it.startPx).takeIf { v -> v.isFinite() } }.sorted()
        val shares = held.mapNotNull { bankedShare8017(it.bankedPct, it.peakPct, entryVsStart8017(it.entryPx, it.startPx)).takeIf { v -> v.isFinite() } }.sorted()
        fun med(xs: List<Double>) = if (xs.isEmpty()) "-" else "%+.0f%%".format(xs[xs.size / 2])
        val rows = recent.take(8).joinToString(" · ") { r ->
            val e = entryVsStart8017(r.entryPx, r.startPx)
            when {
                !r.held -> "${r.symbol.take(10)} +${r.peakPct.toInt()}% MISSED(${r.refusedBy.ifBlank { "?" }.take(24)})"
                r.bankedPct.isFinite() -> "${r.symbol.take(10)} +${r.peakPct.toInt()}% in@${if (e.isFinite()) "%+.0f%%".format(e) else "?"} banked ${"%+.0f".format(r.bankedPct)}% (${"%.0f".format((bankedShare8017(r.bankedPct, r.peakPct, e).takeIf { it.isFinite() } ?: 0.0) * 100)}% of run)${if (r.live) " LIVE" else " paper"}"
                else -> "${r.symbol.take(10)} +${r.peakPct.toInt()}% in@${if (e.isFinite()) "%+.0f%%".format(e) else "?"} HOLDING ${if (r.openNowPct.isFinite()) "%+.0f%%".format(r.openNowPct) else ""}${if (r.live) " LIVE" else " paper"}"
            }
        }
        return "runners(4x+,24h)=${recent.size} held=${held.size} (live ${liveHeld}) missed=${recent.size - held.size} " +
            "entryVsStart(median)=${med(entries)} bankedShareOfRun(median)=${if (shares.isEmpty()) "-" else "%.0f%%".format(shares[shares.size / 2] * 100)} | $rows"
    }
}
