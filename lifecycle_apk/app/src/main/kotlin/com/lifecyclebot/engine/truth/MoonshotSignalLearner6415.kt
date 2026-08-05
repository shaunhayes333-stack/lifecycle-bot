package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6415 §A — MOONSHOT SIGNAL LEARNER.
 *
 * OPERATOR DIRECTIVE (Feb 2026):
 * "several tokens have 26x or better this week we need to find them
 *  before 25k buy a good sized chunk and hold for huge profits.
 *  it needs to be SMART, LEARN and INTEGRATE ACROSS THE STACK."
 *
 * DESIGN
 * ──────
 *   • Per-signal Bayesian W/L counters. Each signal tracks its own
 *     "when I fired 1, what happened?" outcome distribution.
 *   • Winner threshold: pnlPct >= +50% (probation-safe positive).
 *     Loser threshold: pnlPct <= -20%.
 *     Neutral: everything in between (excluded from learning to
 *     preserve signal).
 *   • recordOutcome() called from the sell terminal path.
 *   • signalWeight(name) returns a multiplier in [0.25, 2.0] based
 *     on win-rate lift vs baseline. Cold start = 1.0 (equal weight).
 *   • Ceiling 2.0 / floor 0.25 keeps a single bad streak from
 *     zeroing out a signal permanently.
 *
 * This module is stateful in-memory. A real persistence layer
 * (SQLite backing) lands in V5.0.6416+; for now the learner
 * accumulates during the session and resets on restart. Every
 * process gets a fresh window, which is actually helpful given
 * regime shifts in meme-market behaviour.
 */
object MoonshotSignalLearner6415 {

    private const val WIN_THRESHOLD_PCT = 50.0
    private const val LOSS_THRESHOLD_PCT = -20.0
    private const val MIN_SAMPLE = 8

    private data class SignalStat(
        val wins: AtomicLong = AtomicLong(0L),
        val losses: AtomicLong = AtomicLong(0L),
        val samples: AtomicLong = AtomicLong(0L),
    )

    private val stats = ConcurrentHashMap<String, SignalStat>()
    private val globalWins = AtomicLong(0L)
    private val globalLosses = AtomicLong(0L)

    fun recordOutcome(mint: String, symbol: String, tier: String, signalsFired: Set<String>, pnlPct: Double) {
        val classify = when {
            pnlPct >= WIN_THRESHOLD_PCT -> 1
            pnlPct <= LOSS_THRESHOLD_PCT -> -1
            else -> 0
        }
        if (classify == 0) return
        if (classify == 1) globalWins.incrementAndGet() else globalLosses.incrementAndGet()
        for (sig in signalsFired) {
            val s = stats.getOrPut(sig) { SignalStat() }
            s.samples.incrementAndGet()
            if (classify == 1) s.wins.incrementAndGet() else s.losses.incrementAndGet()
        }
        try {
            ForensicLogger.lifecycle(
                "MOONSHOT_LEARNER_OUTCOME_6415",
                "mint=${mint.take(10)} sym=$symbol tier=$tier pnlPct=${"%.1f".format(pnlPct)} " +
                    "class=${if (classify == 1) "WIN" else "LOSS"} signals=[${signalsFired.joinToString(",")}] " +
                    "globalW/L=${globalWins.get()}/${globalLosses.get()}",
            )
            PipelineHealthCollector.labelInc("MOONSHOT_LEARNER_OUTCOME_6415")
            PipelineHealthCollector.labelInc(if (classify == 1) "MOONSHOT_LEARNER_WIN_6415" else "MOONSHOT_LEARNER_LOSS_6415")
        } catch (_: Throwable) {}
    }

    /**
     * Returns a multiplier in [0.25, 2.0] representing how much this
     * signal has been over-predicting winners vs the global baseline.
     * Cold start returns 1.0.
     */
    fun signalWeight(signal: String): Double {
        val s = stats[signal] ?: return 1.0
        val n = s.samples.get()
        if (n < MIN_SAMPLE) return 1.0
        val globalN = (globalWins.get() + globalLosses.get()).coerceAtLeast(1L)
        val globalWinRate = globalWins.get().toDouble() / globalN.toDouble()
        val sigWinRate = s.wins.get().toDouble() / n.toDouble()
        if (globalWinRate <= 0.0) return 1.0
        val lift = sigWinRate / globalWinRate
        return lift.coerceIn(0.25, 2.0)
    }

    fun statusLine(): String {
        val n = stats.size
        val gW = globalWins.get()
        val gL = globalLosses.get()
        val topSignals = stats.entries.sortedByDescending {
            if (it.value.samples.get() < MIN_SAMPLE) 0.0 else signalWeight(it.key)
        }.take(3)
        val top = topSignals.joinToString(",") {
            "${it.key.take(20)}(w=${"%.2f".format(signalWeight(it.key))} n=${it.value.samples.get()})"
        }
        return "signals=$n globalW/L=$gW/$gL top=[$top]"
    }

    internal fun resetForTest() {
        stats.clear()
        globalWins.set(0L)
        globalLosses.set(0L)
    }
}
