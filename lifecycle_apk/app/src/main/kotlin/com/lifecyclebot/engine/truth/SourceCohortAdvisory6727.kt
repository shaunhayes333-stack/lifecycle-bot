package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6727 — §SOURCE_COHORT_ADVISORY.
 *
 * Operator diagnostic from 6726: "CoinGecko Trending at 75% WR ... Pump.fun-
 * heavy cohorts are around 12-24% WR. Yet intake is overwhelmingly Pump
 * Portal/Pump.fun: 1,140 Pump Portal WS hits plus 651 direct Pump.fun-new
 * versus only 37 CoinGecko Trending and 172 Established."
 *
 * The learning system knows the source edge (per-cohort WR is tracked)
 * but the intake router does not reallocate discovery pressure toward
 * higher-edge sources. This authority collapses the per-source WR into
 * a single canonical advisory the intake source-limiter reads at
 * routing time.
 *
 * Callers record every terminal (win/loss) tagged with its origin
 * source. The authority returns an intake-throttle multiplier per
 * source — 1.0 for winners (75%+ WR), 0.30 for chronic losers
 * (<25% WR at MIN_DECIDED samples), and a linear ramp between.
 *
 * Fail-safe: any source with <MIN_DECIDED samples returns 1.0 (no
 * damping — the learning surface never suppresses cold sources).
 * Reset windows preserved via CanonicalFeedbackAuthority6715
 * conventions so a session flush cleanly resets.
 */
object SourceCohortAdvisory6727 {

    /** Minimum decided closes before the throttle engages. */
    private const val MIN_DECIDED = 15
    /** Winrate at which throttle = 1.0 (no damping). */
    private const val WR_TARGET = 0.50
    /** Floor damping — chronic losers cannot be fully starved. */
    private const val MULT_FLOOR = 0.30

    data class Advisory(
        val source: String,
        val winRatePct: Double,
        val decidedCount: Int,
        val throttleMultiplier: Double,
    )

    private data class Counters(
        val wins: AtomicLong = AtomicLong(0),
        val losses: AtomicLong = AtomicLong(0),
    )

    private val bySource = ConcurrentHashMap<String, Counters>()

    private fun normSource(source: String): String =
        source.trim().uppercase().take(48).ifBlank { "UNKNOWN" }

    fun recordWin(source: String) {
        val s = normSource(source)
        bySource.computeIfAbsent(s) { Counters() }.wins.incrementAndGet()
        try { PipelineHealthCollector.labelInc("SOURCE_COHORT_WIN_6727_$s") } catch (_: Throwable) {}
    }

    fun recordLoss(source: String) {
        val s = normSource(source)
        bySource.computeIfAbsent(s) { Counters() }.losses.incrementAndGet()
        try { PipelineHealthCollector.labelInc("SOURCE_COHORT_LOSS_6727_$s") } catch (_: Throwable) {}
    }

    fun advisory(source: String): Advisory {
        val s = normSource(source)
        val c = bySource[s]
        if (c == null) {
            return Advisory(s, 0.0, 0, 1.0)
        }
        val w = c.wins.get()
        val l = c.losses.get()
        val n = w + l
        if (n < MIN_DECIDED) return Advisory(s, if (n > 0) w.toDouble() / n * 100.0 else 0.0, n.toInt(), 1.0)
        val wr = w.toDouble() / n.toDouble()
        val mult = when {
            wr >= WR_TARGET -> 1.0
            else -> {
                val frac = (wr / WR_TARGET).coerceIn(0.0, 1.0)
                (MULT_FLOOR + (1.0 - MULT_FLOOR) * frac).coerceIn(MULT_FLOOR, 1.0)
            }
        }
        return Advisory(s, wr * 100.0, n.toInt(), mult)
    }

    /** Convenience — throttle multiplier only. Callers gating intake read this. */
    fun throttleMultiplier(source: String): Double = advisory(source).throttleMultiplier

    /** True when a source is producing an advisory-worthy damper (mult < 0.75). */
    fun shouldThrottle(source: String): Boolean = advisory(source).throttleMultiplier < 0.75

    /** Snapshot all known sources for diagnostic display. */
    fun snapshot(): List<Advisory> = bySource.keys.sorted().map { advisory(it) }

    /** Test-only reset — never called from production. */
    internal fun resetForTest6727() { bySource.clear() }
}
