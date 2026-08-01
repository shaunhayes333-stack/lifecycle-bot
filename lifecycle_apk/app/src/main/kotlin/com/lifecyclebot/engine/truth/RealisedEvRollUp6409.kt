package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6409 §4 — REALISED-EV ROLL-UP.
 *
 * OPERATOR DIRECTIVE (Feb 2026)
 * ─────────────────────────────
 * "After every 10 closed trades, log wallet Δ vs baseline so the
 *  operator can see the $50 → $1,000,000 trajectory in a single
 *  counter without exporting the full journal."
 *
 * DESIGN
 * ──────
 * • On the first closed trade, capture the current wallet SOL as
 *   the baseline.
 * • Every 10 closed trades thereafter, emit REALISED_EV_ROLLUP_6409
 *   with:
 *       baseline SOL, current SOL, delta SOL, delta%, closed trade count.
 * • Counter is process-local (survives across ticks; resets on
 *   restart, which is intentional so each session shows its own
 *   compounding trajectory).
 *
 * Report-only. Zero authority over sizing/gates/execution.
 */
object RealisedEvRollUp6409 {

    private const val CADENCE: Long = 10L

    private val baselineSol = AtomicReference<Double?>(null)
    private val closedCount = AtomicLong(0L)
    private val lastEmitDelta = AtomicReference<Double>(0.0)
    private val lastEmitAtClosed = AtomicLong(0L)

    /**
     * Feed a closed-trade tick with the current wallet SOL balance.
     * Emits a roll-up log line every [CADENCE] closes.
     */
    fun onTradeClosed(walletSol: Double) {
        if (!walletSol.isFinite() || walletSol < 0.0) return
        baselineSol.compareAndSet(null, walletSol)
        val n = closedCount.incrementAndGet()
        if (n % CADENCE == 0L) {
            val base = baselineSol.get() ?: walletSol
            val delta = walletSol - base
            val deltaPct = if (base > 0.0) (delta / base) * 100.0 else 0.0
            lastEmitDelta.set(delta)
            lastEmitAtClosed.set(n)
            try {
                ForensicLogger.lifecycle(
                    "REALISED_EV_ROLLUP_6409",
                    "closed=$n baselineSol=${"%.4f".format(base)} currentSol=${"%.4f".format(walletSol)} " +
                        "deltaSol=${"%.4f".format(delta)} deltaPct=${"%.2f".format(deltaPct)}",
                )
                PipelineHealthCollector.labelInc("REALISED_EV_ROLLUP_6409_EMIT")
            } catch (_: Throwable) {}
        }
    }

    fun statusLine(): String {
        val base = baselineSol.get() ?: 0.0
        val n = closedCount.get()
        val delta = lastEmitDelta.get()
        val lastAt = lastEmitAtClosed.get()
        return "closed=$n baselineSol=${"%.4f".format(base)} lastDeltaSol=${"%.4f".format(delta)} lastAtClosed=$lastAt"
    }

    internal fun resetForTest() {
        baselineSol.set(null)
        closedCount.set(0L)
        lastEmitDelta.set(0.0)
        lastEmitAtClosed.set(0L)
    }
}
