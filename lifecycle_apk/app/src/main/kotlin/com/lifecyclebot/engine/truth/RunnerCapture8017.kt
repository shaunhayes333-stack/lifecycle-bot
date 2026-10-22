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

    class Run(val mint: String, val symbol: String, val lane: String, val startPx: Double, val atMs: Long, @Volatile var refusedBy: String) {
        @Volatile var peakPct = 0.0
        @Volatile var held = false
        @Volatile var live = false
        @Volatile var entryPx = 0.0
        @Volatile var bankedPct = Double.NaN
        @Volatile var openNowPct = Double.NaN
        /** V5.0.8237 — when the stored peak was observed, and whether it fell inside the bot's own holding window. */
        @Volatile var peakAtMs = 0L
        @Volatile var peakInHold = true
        /** V5.0.8297 — the close that ended the bot's position in this runner (0 = none yet) and how long it was held. */
        @Volatile var closeAtMs = 0L
        @Volatile var holdMs = 0L
    }

    /**
     * Pure (V5.0.8237): a peak % stated over [obsStartPx] re-expressed over this run's own [runStartPx]. The forward
     * labeler keeps one observation per (mint, lane) and each starts at its own decision price; the run keeps the
     * FIRST start. 8236 forensic: YEARN printed +1909% — a later lane's % kept over an earlier lane's start.
     */
    fun rebasedPeakPct8237(peakPct: Double, obsStartPx: Double, runStartPx: Double): Double {
        if (!peakPct.isFinite()) return Double.NaN
        if (!(obsStartPx > 0.0) || !(runStartPx > 0.0) || obsStartPx == runStartPx) return peakPct
        val peakPx = obsStartPx * (1.0 + peakPct / 100.0)
        return (peakPx / runStartPx - 1.0) * 100.0
    }

    /** Pure (V5.0.8237): was the run's peak observed while the bot held the coin (entry..close)? */
    fun peakInHold8237(peakAtMs: Long, closeAtMs: Long, holdingTimeMs: Long): Boolean {
        if (peakAtMs <= 0L || closeAtMs <= 0L || holdingTimeMs <= 0L) return true
        return peakAtMs in (closeAtMs - holdingTimeMs)..closeAtMs
    }

    private val runs = ConcurrentHashMap<String, Run>()
    private val seen = AtomicLong(0)

    // V5.0.8047 — 5.0.8046: DeepSeek +939%, Van Cleef +846%, Gemini +642% read "MISSED(?)": the edge gate ADMITTED
    // them (Field Manual: ENTER Van Cleef) and the buy died downstream (sizing, buy door) without the runner report
    // saying where. The last downstream refusal per mint is kept so a missed admit names what stopped it.
    private val downstream8047 = ConcurrentHashMap<String, String>()

    /** Sizing / the live buy door refused [mint] after the gates admitted it. */
    fun noteDownstream8047(mint: String, reason: String) {
        if (mint.isBlank() || reason.isBlank()) return
        if (downstream8047.size > 3_000) downstream8047.clear()
        downstream8047[mint] = reason.substringBefore(':').trim().take(40)
    }

    /** Pure (V5.0.8055): should [candidate] replace [current] as a missed runner's reason? A gate refusal beats a native lane opinion. */
    fun betterMissReason8055(current: String, candidate: String): Boolean =
        candidate.isNotBlank() && !candidate.startsWith("NATIVE_") && candidate != "SHADOW_NOT_PRIMARY_8029" &&
            (current.isBlank() || current.startsWith("NATIVE_") || current == "SHADOW_NOT_PRIMARY_8029")

    /** Pure: the label a missed runner prints — its gate refusal, else what stopped the admitted buy. */
    fun missedWhy8047(refusedBy: String, downstream: String?): String = when {
        refusedBy.isNotBlank() -> refusedBy
        !downstream.isNullOrBlank() -> "ADMITTED>$downstream"
        else -> "ADMITTED>NO_BUY_ATTEMPT"
    }

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

    /**
     * V5.0.8169 — AdmissionRunnerTrace8144.restore8169: the runners of the last 24 h, rebuilt from the exported decision
     * traces after a restart (held flag, refusal and downstream stop as recorded). A run already seen this process wins.
     */
    internal fun restore8169(traces: List<AdmissionRunnerTrace8144.Trace8144>, nowMs: Long) {
        for (t in restorableRuns8169(traces, nowMs)) {
            val r = Run(t.mint, t.symbol, t.lane, t.markPx.takeIf { it.isFinite() } ?: 0.0, t.detectedAtMs, t.refusedBy)
            r.peakPct = t.peakPct
            r.held = t.held
            if (runs.putIfAbsent(t.mint, r) == null) {
                restoredRuns8169++
                if (t.downstream.isNotBlank()) downstream8047.putIfAbsent(t.mint, t.downstream)
            }
        }
    }

    @Volatile private var restoredRuns8169 = 0

    /** Pure (V5.0.8169): restored traces that are still runners of the last 24 h (finite peak at the bar). */
    fun restorableRuns8169(traces: List<AdmissionRunnerTrace8144.Trace8144>, nowMs: Long): List<AdmissionRunnerTrace8144.Trace8144> =
        traces.filter { it.mint.isNotBlank() && it.peakPct.isFinite() && it.peakPct >= RUNNER_PEAK_PCT_8017 && nowMs - it.detectedAtMs in 0L..KEEP_MS }
            .take(MAX_RUNS)

    /** ForwardReturnLabeler7731: a followed coin's peak passed (or rose further past) the runner bar. */
    fun onRunnerPeak8017(mint: String, symbol: String, lane: String, startPx: Double, peakPct: Double, refusedBy: String, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank() || !peakPct.isFinite() || peakPct < RUNNER_PEAK_PCT_8017) return
        try { AdmissionRunnerTrace8144.restore8169(nowMs) } catch (_: Throwable) {}  // V5.0.8169 — restored runs first, never duplicated
        val r = runs.getOrPut(mint) {
            if (runs.size >= MAX_RUNS) runs.entries.removeIf { nowMs - it.value.atMs > KEEP_MS }
            if (runs.size >= MAX_RUNS) runs.entries.minByOrNull { it.value.atMs }?.key?.let { runs.remove(it) }
            seen.incrementAndGet()
            try { PipelineHealthCollector.labelInc("RUNNER_SEEN_8017") } catch (_: Throwable) {}
            Run(mint, symbol, lane, startPx, nowMs, refusedBy)
        }
        // V5.0.8237 — every observation's % is read over THIS run's start before it can raise the peak.
        val rebased8237 = rebasedPeakPct8237(peakPct, startPx, r.startPx)
        if (rebased8237 != peakPct) try { PipelineHealthCollector.labelInc("RUNNER_PEAK_REBASED_TO_RUN_START_8237") } catch (_: Throwable) {}
        if (rebased8237.isFinite() && rebased8237 > r.peakPct) { r.peakPct = rebased8237; r.peakAtMs = nowMs }
        // V5.0.8055 — a lane's native opinion (TREASURY "role floor" on a $30K meme) is not what stopped the buy: the
        // first label of a runner is often a lane that would never own it. A gate refusal (watch first, Cortex, net
        // edge ...) from a later label of the same coin names the real stop.
        if (betterMissReason8055(r.refusedBy, refusedBy)) r.refusedBy = refusedBy
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
        // V5.0.8144 — the runner's decision trace is frozen the first time (no-op after); later labels refine it.
        try {
            AdmissionRunnerTrace8144.capture8144(mint, symbol, lane, startPx, peakPct, r.refusedBy, downstream8047[mint], r.held, nowMs)
            AdmissionRunnerTrace8144.update8144(mint, r.refusedBy, downstream8047[mint], r.held)
        } catch (_: Throwable) {}
    }

    /** CanonicalFinalizedTradeBus6464: a position closed — if it was a runner, that is what the bot banked. */
    fun onClose8017(env: CanonicalFinalizedTradeBus6464.Envelope) {
        try { RunnerFunnel8297.onAnyClose8297(env) } catch (_: Throwable) {}  // V5.0.8297 — runner-admit outcomes / capped wins
        val r = runs[env.mint] ?: return
        if (env.terminal) { r.closeAtMs = env.atMs; r.holdMs = env.holdingTimeMs }  // V5.0.8297
        r.held = true
        r.live = r.live || env.mode.equals("live", ignoreCase = true)
        if (env.realizedReturnPct.isFinite()) r.bankedPct = env.realizedReturnPct
        r.openNowPct = Double.NaN
        // V5.0.8237 — a peak before the entry or after the close is not a run the position could have banked.
        r.peakInHold = peakInHold8237(r.peakAtMs, env.atMs, env.holdingTimeMs)
        if (!r.peakInHold) try { PipelineHealthCollector.labelInc("RUNNER_PEAK_OUTSIDE_HOLD_8237") } catch (_: Throwable) {}
        try { AdmissionRunnerTrace8144.update8144(env.mint, r.refusedBy, downstream8047[env.mint], true) } catch (_: Throwable) {}  // V5.0.8144
    }

    /** V5.0.8297 — [r]'s first losing stage, from the run's own record and its frozen decision trace. */
    private fun attribution8297(r: Run, trace: AdmissionRunnerTrace8144.Trace8144?): RunnerFunnel8297.Attribution8297 {
        val down = downstream8047[r.mint].orEmpty()
        val binding = trace?.let { AdmissionRunnerTrace8144.refusingGate8144(it.copy(refusedBy = r.refusedBy)) } ?: r.refusedBy
        val evaluated = trace != null && (trace.nativeAtMs > 0L || trace.admission.isNotBlank() || trace.fdgVerdict.isNotBlank())
        return RunnerFunnel8297.attribute8297(r.held, r.bankedPct, r.peakPct, entryVsStart8017(r.entryPx, r.startPx),
            r.peakAtMs, r.closeAtMs, r.holdMs, evaluated || r.refusedBy.isNotBlank(), binding, down)
    }

    /** V5.0.8297 — the per-stage attribution of every runner of the window, plus the runner-admit outcome ledger. */
    private fun funnelLine8297(recent: List<Run>): String {
        val traces = try { AdmissionRunnerTrace8144.traces8144().associateBy { it.mint } } catch (_: Throwable) { emptyMap() }
        val list = recent.map { attribution8297(it, traces[it.mint]) }
        return "${RunnerFunnel8297.summary8297(list)} ${RunnerFunnel8297.outcomesLine8297()} ${RunnerDefer8297.statusLine8297()}"
    }

    fun statusLine(): String {
        val now = System.currentTimeMillis()
        try { AdmissionRunnerTrace8144.restore8169(now) } catch (_: Throwable) {}  // V5.0.8169 — survive the restart
        val recent = runs.values.filter { now - it.atMs <= KEEP_MS }.sortedByDescending { it.peakPct }
        val funnel8331 = try { com.lifecyclebot.engine.RunnerFunnel8331.statusLine8331() } catch (_: Throwable) { "runnerFunnel8331 unavailable" }  // V5.0.8331
        if (recent.isEmpty()) return "runners(4x+,24h)=0 restoredRuns8169=$restoredRuns8169 | $funnel8331 | ${try { AdmissionRunnerTrace8144.statusLine8144() } catch (_: Throwable) { "trace8144 unavailable" }}"
        val held = recent.filter { it.held }
        val liveHeld = held.count { it.live }
        val entries = held.mapNotNull { entryVsStart8017(it.entryPx, it.startPx).takeIf { v -> v.isFinite() } }.sorted()
        val shares = held.filter { it.peakInHold }.mapNotNull { bankedShare8017(it.bankedPct, it.peakPct, entryVsStart8017(it.entryPx, it.startPx)).takeIf { v -> v.isFinite() } }.sorted()
        fun med(xs: List<Double>) = if (xs.isEmpty()) "-" else "%+.0f%%".format(xs[xs.size / 2])
        val rows = recent.take(8).joinToString(" · ") { r ->
            val e = entryVsStart8017(r.entryPx, r.startPx)
            when {
                !r.held -> "${r.symbol.take(10)} +${r.peakPct.toInt()}% MISSED(${missedWhy8047(r.refusedBy, downstream8047[r.mint]).take(40)})"
                r.bankedPct.isFinite() && !r.peakInHold -> "${r.symbol.take(10)} +${r.peakPct.toInt()}% PEAK_OUTSIDE_HOLD(${attribution8297(r, null).stage.name}) banked ${"%+.0f".format(r.bankedPct)}%${if (r.live) " LIVE" else " paper"}"
                r.bankedPct.isFinite() -> "${r.symbol.take(10)} +${r.peakPct.toInt()}% in@${if (e.isFinite()) "%+.0f%%".format(e) else "?"} banked ${"%+.0f".format(r.bankedPct)}% (${"%.0f".format((bankedShare8017(r.bankedPct, r.peakPct, e).takeIf { it.isFinite() } ?: 0.0) * 100)}% of run)${if (r.live) " LIVE" else " paper"}"
                else -> "${r.symbol.take(10)} +${r.peakPct.toInt()}% in@${if (e.isFinite()) "%+.0f%%".format(e) else "?"} HOLDING ${if (r.openNowPct.isFinite()) "%+.0f%%".format(r.openNowPct) else ""}${if (r.live) " LIVE" else " paper"}"
            }
        }
        return "runners(4x+,24h)=${recent.size} held=${held.size} (live ${liveHeld}) missed=${recent.size - held.size} restoredRuns8169=$restoredRuns8169 " +
            "entryVsStart(median)=${med(entries)} bankedShareOfRun(median)=${if (shares.isEmpty()) "-" else "%.0f%%".format(shares[shares.size / 2] * 100)} | " +
            "${try { funnelLine8297(recent) } catch (_: Throwable) { "funnel8297 unavailable" }} | $funnel8331 | $rows" +
            " | ${try { AdmissionRunnerTrace8144.statusLine8144() } catch (_: Throwable) { "trace8144 unavailable" }}"  // V5.0.8144
    }
}
