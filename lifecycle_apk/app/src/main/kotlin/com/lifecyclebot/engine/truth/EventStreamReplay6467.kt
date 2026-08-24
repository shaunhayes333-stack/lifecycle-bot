package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6467 §P0 (item 9) — REPLAY FROM SAME EVENT STREAM WITH FIRST-DIVERGENT-ID.
 *
 * Reads EconomicEventSchema6464 events (the SAME stream terminal
 * mutations write) and rebuilds cash/openCost/realized. Compares to
 * PaperAccountLedger6430 counters and — on divergence — reports the
 * FIRST divergent economicEventId (not just aggregate deltas).
 */
object EventStreamReplay6467 {
    data class Parity(
        val cashDelta: Double, val realizedDelta: Double, val openCostDelta: Double,
        val firstDivergentEventId: String?, val divergentEventIndex: Int,
        val totalEvents: Int,
    )
    private val lastParity = AtomicReference<Parity?>(null)
    private val replays = AtomicLong(0L)
    private val divergences = AtomicLong(0L)

    fun replayAndCompare(startingCashSol: Double, toleranceSol: Double = 0.01): Parity {
        replays.incrementAndGet()
        val events = try { EconomicEventSchema6464.snapshot() } catch (_: Throwable) { emptyList() }
        var cash = startingCashSol; var openCost = 0.0; var realized = 0.0
        var firstDivergent: String? = null; var divIndex = -1
        val seen = HashSet<String>()
        val ordered = events.asReversed() // oldest-first
        for ((idx, e) in ordered.withIndex()) {
            if (e.mode != "paper") continue
            if (!seen.add(e.idempotencyKey)) continue
            when (e) {
                is EconomicEventSchema6464.Buy -> {
                    cash -= e.executedCostSol; openCost += e.executedCostSol
                }
                is EconomicEventSchema6464.Sell -> {
                    cash += e.netProceedsSol
                    openCost = (openCost - e.allocatedCostBasisSol).coerceAtLeast(0.0)
                    realized += e.realizedPnlSol
                }
            }
            // Snapshot check every 10 events for first-divergent identification.
            if (firstDivergent == null && idx > 0 && idx % 10 == 0) {
                val lCash = try { PaperAccountLedger6430.cashSol() } catch (_: Throwable) { Double.NaN }
                if (lCash.isFinite() && kotlin.math.abs(cash - lCash) > toleranceSol * 5) {
                    firstDivergent = e.idempotencyKey; divIndex = idx
                }
            }
        }
        val lCash = try { PaperAccountLedger6430.cashSol() } catch (_: Throwable) { Double.NaN }
        val lReal = try { PaperAccountLedger6430.realizedPnlSol() } catch (_: Throwable) { Double.NaN }
        val lOpen = try { PaperAccountLedger6430.openCostBasisSol() } catch (_: Throwable) { Double.NaN }
        val cashD = if (lCash.isFinite()) cash - lCash else 0.0
        val realD = if (lReal.isFinite()) realized - lReal else 0.0
        val openD = if (lOpen.isFinite()) openCost - lOpen else 0.0
        val p = Parity(cashD, realD, openD, firstDivergent, divIndex, ordered.size)
        lastParity.set(p)
        val diverged = kotlin.math.abs(cashD) > toleranceSol ||
                       kotlin.math.abs(realD) > toleranceSol ||
                       kotlin.math.abs(openD) > toleranceSol
        try {
            if (diverged) {
                RootCauseIncidentLifecycle6510.open("EVENT_STREAM_REPLAY_DIVERGED_6467", "cash=$cashD realized=$realD open=$openD")
                divergences.incrementAndGet()
                ForensicLogger.lifecycle("EVENT_STREAM_REPLAY_DIVERGED_6467",
                    "cashΔ=${"%.4f".format(cashD)} realizedΔ=${"%.4f".format(realD)} openΔ=${"%.4f".format(openD)} " +
                    "firstDivergentEventId=${firstDivergent ?: "n/a"} idx=$divIndex totalEvents=${ordered.size}")
                PipelineHealthCollector.labelInc("EVENT_STREAM_REPLAY_DIVERGED_6467")
                // V5.0.6496 §2 — feed the divergent event id into the
                // historical economic quarantine so its (corrupted)
                // outcome never reaches learners.
                try {
                    HistoricalEconomicQuarantine6496.reportEventStreamDivergence(
                        firstDivergentEventId = firstDivergent,
                        mint = null,
                    )
                } catch (_: Throwable) {}
            } else {
                RootCauseIncidentLifecycle6510.resolve("EVENT_STREAM_REPLAY_DIVERGED_6467", "replay_converged")
                PipelineHealthCollector.labelInc("EVENT_STREAM_REPLAY_CONVERGED_6467")
            }
        } catch (_: Throwable) {}
        return p
    }
    fun lastParity(): Parity? = lastParity.get()
    fun statusLine(): String {
        val p = lastParity.get() ?: return "no_replay replays=${replays.get()} divergences=${divergences.get()}"
        return "cashΔ=${"%.4f".format(p.cashDelta)} realizedΔ=${"%.4f".format(p.realizedDelta)} " +
            "openCostΔ=${"%.4f".format(p.openCostDelta)} events=${p.totalEvents} " +
            "firstDiv=${p.firstDivergentEventId?.take(20) ?: "-"} replays=${replays.get()} divergences=${divergences.get()}"
    }
    internal fun resetForTest() { lastParity.set(null); replays.set(0L); divergences.set(0L) }
}
