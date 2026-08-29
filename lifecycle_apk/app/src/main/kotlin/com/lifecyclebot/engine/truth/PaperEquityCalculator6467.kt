package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6467 §P0 (item 10) — SINGLE PAPER EQUITY CALCULATOR.
 * equity = cash + markedOpenPositionValue. Realized PnL already embedded
 * in cash (never added twice). Baseline = paperSimulatedBalance.
 */
object PaperEquityCalculator6467 {
    data class Snapshot(
        val cashSol: Double, val markedOpenValueSol: Double,
        val equitySol: Double, val baselineEquitySol: Double,
        val conservationDelta: Double,
    )
    private val lastSnap = AtomicReference<Snapshot?>(null)
    private val calcs = AtomicLong(0L)
    private val violations = AtomicLong(0L)

    fun compute(baselineSol: Double, markedOpenValueSol: Double): Snapshot {
        calcs.incrementAndGet()
        val cash = try { PaperCapitalAuthority6577.cashSol() } catch (_: Throwable) { baselineSol }
        val mv = if (markedOpenValueSol.isFinite() && markedOpenValueSol >= 0.0) markedOpenValueSol else 0.0
        val equity = cash + mv
        val realized = try { PaperCapitalAuthority6577.realizedPnlSol() } catch (_: Throwable) { 0.0 }
        val expected = baselineSol + realized
        val delta = equity - expected - mv // mv is unrealized, not in expected
        val snap = Snapshot(cash, mv, equity, baselineSol, delta)
        lastSnap.set(snap)
        if (kotlin.math.abs(delta) > 0.02) {
            RootCauseIncidentLifecycle6510.open("PAPER_EQUITY_CONSERVATION_VIOLATION_6467", "delta=$delta")
            violations.incrementAndGet()
            try {
                ForensicLogger.lifecycle("PAPER_EQUITY_CONSERVATION_VIOLATION_6467",
                    "cash=${"%.4f".format(cash)} mv=${"%.4f".format(mv)} equity=${"%.4f".format(equity)} " +
                    "baseline=${"%.4f".format(baselineSol)} realized=${"%.4f".format(realized)} delta=${"%.4f".format(delta)}")
                PipelineHealthCollector.labelInc("PAPER_EQUITY_CONSERVATION_VIOLATION_6467")
            } catch (_: Throwable) {}
        } else {
            RootCauseIncidentLifecycle6510.resolve("PAPER_EQUITY_CONSERVATION_VIOLATION_6467", "conservation_delta_within_tolerance:$delta")
        }
        return snap
    }

    /**
     * V5.0.6550c — GROWTH COMPOUND RING driver. Feed the just-computed
     * equity + a fresh SOL/USD quote into GrowthCompoundRing6550 so
     * the milestone tape stays live. Ring is READ-ONLY over this snap;
     * bad SOL/USD holds prior USD equity instead of driving false
     * milestones.
     */
    fun observeGrowthRing(snap: Snapshot, solPriceUsd: Double) {
        try { GrowthCompoundRing6550.observe(snap.equitySol, solPriceUsd) } catch (_: Throwable) {}
    }

    fun lastSnapshot(): Snapshot? = lastSnap.get()
    fun statusLine(): String {
        val s = lastSnap.get() ?: return "no_calc calcs=${calcs.get()} violations=${violations.get()}"
        return "equity=${"%.4f".format(s.equitySol)} cash=${"%.4f".format(s.cashSol)} mv=${"%.4f".format(s.markedOpenValueSol)} " +
            "baseline=${"%.4f".format(s.baselineEquitySol)} delta=${"%.4f".format(s.conservationDelta)} " +
            "calcs=${calcs.get()} violations=${violations.get()}"
    }
    internal fun resetForTest() { lastSnap.set(null); calcs.set(0L); violations.set(0L) }
}
