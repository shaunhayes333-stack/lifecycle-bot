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
        @Volatile var peakInHold = false
        @Volatile var holdingMode8239 = ""
        @Volatile var holdingPositionId8239 = ""
        @Volatile var holdingEntryAtMs8239 = 0L
        @Volatile var holdingCloseAtMs8239 = 0L
        @Volatile var peakValid8239 = true
        @Volatile var peakCorrection8239 = ""
        fun hold8239(): RunnerEvidence8239.Hold = if (!peakValid8239) RunnerEvidence8239.Hold.UNKNOWN else
            RunnerEvidence8239.hold(peakAtMs, holdingEntryAtMs8239, holdingCloseAtMs8239, holdingMode8239, holdingPositionId8239)
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
        if (peakAtMs <= 0L || closeAtMs <= 0L || holdingTimeMs <= 0L) return false
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
        if (!bankedPct.isFinite() || !peakOverStartPct.isFinite() || !entryVsStartPct.isFinite() || entryVsStartPct <= -100.0) return Double.NaN
        val e = entryVsStartPct
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
            r.peakAtMs = t.peakAtMs8239
            r.holdingMode8239 = t.holdingMode8239
            r.holdingPositionId8239 = t.holdingPositionId8239
            r.holdingEntryAtMs8239 = t.holdingEntryAtMs8239
            r.holdingCloseAtMs8239 = t.holdingCloseAtMs8239
            r.entryPx = t.holdingEntryPx8239
            r.bankedPct = t.bankedPct8239
            r.peakValid8239 = t.peakValid8239
            r.peakCorrection8239 = t.peakCorrection8239
            r.live = r.holdingMode8239.equals("live", true)
            r.peakInHold = r.hold8239() == RunnerEvidence8239.Hold.HELD
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
    fun onRunnerPeak8017(mint: String, symbol: String, lane: String, startPx: Double, peakPct: Double, refusedBy: String,
                        nowMs: Long = System.currentTimeMillis(), peakObservedAtMs8239: Long = 0L) {
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
        if (rebased8237.isFinite() && (!r.peakValid8239 || rebased8237 > r.peakPct)) {
            r.peakPct = rebased8237
            r.peakAtMs = peakObservedAtMs8239.takeIf { it > 0L && it <= nowMs } ?: 0L
            r.peakValid8239 = true
            r.peakCorrection8239 = ""
        }
        // V5.0.8055 — a lane's native opinion (TREASURY "role floor" on a $30K meme) is not what stopped the buy: the
        // first label of a runner is often a lane that would never own it. A gate refusal (watch first, Cortex, net
        // edge ...) from a later label of the same coin names the real stop.
        if (betterMissReason8055(r.refusedBy, refusedBy)) r.refusedBy = refusedBy
        // A holding at grading time cannot capture a peak observed before its own entry.
        try {
            val ts = com.lifecyclebot.engine.BotService.status.tokens[mint]
            val pos = ts?.position
            val canonical = pos?.positionId?.takeIf { it.isNotBlank() }?.let { CanonicalPositionAuthority6441.getPosition(it) }
            if (pos != null && pos.isOpen && canonical != null && canonical.mint == mint &&
                canonical.mode.equals(if (pos.isPaperPosition) "paper" else "live", true) && canonical.entryPriceUsd > 0.0 &&
                (r.holdingPositionId8239.isBlank() || r.holdingPositionId8239 == canonical.positionId) &&
                RunnerEvidence8239.hold(r.peakAtMs, canonical.openedAtMs, 0L, canonical.mode, canonical.positionId) == RunnerEvidence8239.Hold.HELD) {
                r.held = true; r.live = canonical.mode.equals("live", true); r.entryPx = canonical.entryPriceUsd
                r.holdingMode8239 = canonical.mode.uppercase(); r.holdingPositionId8239 = canonical.positionId
                r.holdingEntryAtMs8239 = canonical.openedAtMs; r.holdingCloseAtMs8239 = 0L
                val px = ts.lastPrice
                if (px > 0.0) r.openNowPct = (px / canonical.entryPriceUsd - 1.0) * 100.0
            }
        } catch (_: Throwable) {}
        r.peakInHold = r.hold8239() == RunnerEvidence8239.Hold.HELD
        // V5.0.8144 — the runner's decision trace is frozen the first time (no-op after); later labels refine it.
        try {
            AdmissionRunnerTrace8144.capture8144(mint, symbol, lane, r.startPx, r.peakPct, r.refusedBy, downstream8047[mint], r.held, nowMs)
            AdmissionRunnerTrace8144.update8144(mint, r.refusedBy, downstream8047[mint], r.held)
            AdmissionRunnerTrace8144.updateRunnerEvidence8239(r)
        } catch (_: Throwable) {}
    }

    /** CanonicalFinalizedTradeBus6464: a position closed — if it was a runner, that is what the bot banked. */
    fun onClose8017(env: CanonicalFinalizedTradeBus6464.Envelope) {
        val r = runs[env.mint] ?: return
        if (!env.terminal || env.positionId.isBlank() || env.mode.uppercase() !in setOf("LIVE", "PAPER")) return
        if (r.holdingPositionId8239.isNotBlank() && (r.holdingPositionId8239 != env.positionId || !r.holdingMode8239.equals(env.mode, true))) return
        val canonical = try { CanonicalPositionAuthority6441.getPosition(env.positionId)?.takeIf { it.mint == env.mint && it.mode.equals(env.mode, true) } } catch (_: Throwable) { null }
        val entryAt = canonical?.openedAtMs ?: env.holdingTimeMs.takeIf { it > 0L }?.let { env.atMs - it } ?: 0L
        // An unbound historical held flag does not authorize attributing a new close of the same mint.
        if (r.holdingPositionId8239.isBlank() && RunnerEvidence8239.hold(r.peakAtMs, entryAt, env.atMs, env.mode, env.positionId) != RunnerEvidence8239.Hold.HELD) return
        r.held = true
        r.live = env.mode.equals("live", true)
        r.holdingMode8239 = env.mode.uppercase(); r.holdingPositionId8239 = env.positionId
        r.holdingEntryAtMs8239 = entryAt; r.holdingCloseAtMs8239 = env.atMs
        canonical?.entryPriceUsd?.takeIf { it > 0.0 }?.let { r.entryPx = it }
        if (env.realizedReturnPct.isFinite()) r.bankedPct = env.realizedReturnPct
        r.openNowPct = Double.NaN
        r.peakInHold = r.hold8239() == RunnerEvidence8239.Hold.HELD
        if (!r.peakInHold) try { PipelineHealthCollector.labelInc("RUNNER_PEAK_OUTSIDE_HOLD_8237") } catch (_: Throwable) {}
        try { AdmissionRunnerTrace8144.updateRunnerEvidence8239(r) } catch (_: Throwable) {}
    }

    /** A source/units correction invalidates only the named observation. Its prior value stays in the audit. */
    fun invalidatePeakEvidence8239(mint: String, observedAtMs: Long, sourceReason: String) {
        if (sourceReason.isBlank()) return
        try { AdmissionRunnerTrace8144.restore8169() } catch (_: Throwable) {}
        val r = runs[mint] ?: return
        if (!RunnerEvidence8239.invalidates(r.peakAtMs, observedAtMs)) return
        r.peakValid8239 = false; r.peakInHold = false; r.peakCorrection8239 = sourceReason.take(120)
        try { AdmissionRunnerTrace8144.updateRunnerEvidence8239(r) } catch (_: Throwable) {}
    }

    /** Only the proven position's entry basis changes; the mint's observed absolute peak stays intact. */
    fun rebaseHolding8239(positionId: String, newEntryUsd: Double) {
        if (positionId.isBlank() || !newEntryUsd.isFinite() || newEntryUsd <= 0.0) return
        for (r in runs.values.filter { it.holdingPositionId8239 == positionId }) {
            r.entryPx = newEntryUsd
            try { AdmissionRunnerTrace8144.updateRunnerEvidence8239(r) } catch (_: Throwable) {}
        }
    }

    fun statusLine(): String {
        val now = System.currentTimeMillis()
        try { AdmissionRunnerTrace8144.restore8169(now) } catch (_: Throwable) {}  // V5.0.8169 — survive the restart
        val recent = runs.values.filter { now - it.atMs <= KEEP_MS }.sortedByDescending { it.peakPct }
        if (recent.isEmpty()) return "runners(4x+,24h)=0 restoredRuns8169=$restoredRuns8169 | ${try { AdmissionRunnerTrace8144.statusLine8144() } catch (_: Throwable) { "trace8144 unavailable" }}"
        val held = recent.filter { it.held && it.hold8239() == RunnerEvidence8239.Hold.HELD }
        val unknown = recent.count { !it.peakValid8239 || it.peakAtMs <= 0L || (it.held && it.hold8239() == RunnerEvidence8239.Hold.UNKNOWN) }
        val liveHeld = held.count { it.holdingMode8239 == "LIVE" }
        val entries = held.mapNotNull { entryVsStart8017(it.entryPx, it.startPx).takeIf { v -> v.isFinite() } }.sorted()
        val shares = held.filter { it.peakInHold }.mapNotNull { bankedShare8017(it.bankedPct, it.peakPct, entryVsStart8017(it.entryPx, it.startPx)).takeIf { v -> v.isFinite() } }.sorted()
        fun med(xs: List<Double>) = if (xs.isEmpty()) "-" else "%+.0f%%".format(xs[xs.size / 2])
        val rows = recent.take(8).joinToString(" · ") { r ->
            val e = entryVsStart8017(r.entryPx, r.startPx)
            val modeTag = r.holdingMode8239.ifBlank { "MODE_UNKNOWN" }
            when {
                !r.peakValid8239 -> "${r.symbol.take(10)} historicalPeak=+${r.peakPct.toInt()}% INVALIDATED(${r.peakCorrection8239.take(36)})"
                r.peakAtMs <= 0L || (r.held && r.hold8239() == RunnerEvidence8239.Hold.UNKNOWN) -> "${r.symbol.take(10)} +${r.peakPct.toInt()}% HOLD_HISTORY_UNKNOWN"
                !r.held -> "${r.symbol.take(10)} +${r.peakPct.toInt()}% MISSED(${missedWhy8047(r.refusedBy, downstream8047[r.mint]).take(40)})"
                r.bankedPct.isFinite() && !r.peakInHold -> "${r.symbol.take(10)} +${r.peakPct.toInt()}% PEAK_OUTSIDE_HOLD banked ${"%+.0f".format(r.bankedPct)}%${" " + modeTag}"
                r.bankedPct.isFinite() -> "${r.symbol.take(10)} +${r.peakPct.toInt()}% in@${if (e.isFinite()) "%+.0f%%".format(e) else "?"} banked ${"%+.0f".format(r.bankedPct)}% (${"%.0f".format((bankedShare8017(r.bankedPct, r.peakPct, e).takeIf { it.isFinite() } ?: 0.0) * 100)}% of run)${" " + modeTag}"
                else -> "${r.symbol.take(10)} +${r.peakPct.toInt()}% in@${if (e.isFinite()) "%+.0f%%".format(e) else "?"} HOLDING ${if (r.openNowPct.isFinite()) "%+.0f%%".format(r.openNowPct) else ""}${" " + modeTag}"
            }
        }
        return "runners(4x+,24h)=${recent.size} held=${held.size} (live ${liveHeld}) missed=${recent.count { !it.held && it.peakValid8239 && it.peakAtMs > 0L }} historyUnknown=$unknown restoredRuns8169=$restoredRuns8169 " +
            "entryVsStart(median)=${med(entries)} bankedShareOfRun(median)=${if (shares.isEmpty()) "-" else "%.0f%%".format(shares[shares.size / 2] * 100)} | $rows" +
            " | ${try { AdmissionRunnerTrace8144.statusLine8144() } catch (_: Throwable) { "trace8144 unavailable" }}"  // V5.0.8144
    }
}
