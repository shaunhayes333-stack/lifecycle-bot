package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6550c — GROWTH COMPOUND RING.
 *
 * Operator directive: "Turn the freshly-flowing paper buys into a
 * live-shadow $50 → $1M compounding scoreboard operators can watch grow."
 *
 * This is a READ-ONLY projection over PaperEquityCalculator6467. It
 * takes each canonical equity snapshot, converts SOL → USD via the
 * cached SOL/USD, and reports:
 *   * currentEquityUsd
 *   * multiple / progressBps against a $50 → $1,000,000 target
 *   * peakEquityUsd (all-time water mark) + drawdownFromPeakBps
 *   * every crossed compounding milestone ($100, $500, $1k, $5k, $10k,
 *     $50k, $100k, $500k, $1M) with the wall-clock timestamp of the
 *     first crossing, so operator can watch the ring illuminate as
 *     each rung is hit.
 *
 * Zero authority impact — sizing, gates, learning, exits and mark
 * authority do NOT read from this object. It only observes.
 *
 * Consumers:
 *   * Dashboard/HUD via `snapshot()`
 *   * `statusLine()` for pipeline health dumps
 *   * `MILESTONE_CROSSED_6550c|<amount>` counters (via labelInc) for
 *     alerting.
 */
object GrowthCompoundRing6550 {

    const val START_USD: Double = 50.0
    const val TARGET_USD: Double = 1_000_000.0

    /** In display order, ascending. */
    val MILESTONES_USD: List<Double> = listOf(
        100.0, 250.0, 500.0, 1_000.0, 2_500.0, 5_000.0, 10_000.0,
        25_000.0, 50_000.0, 100_000.0, 250_000.0, 500_000.0, 1_000_000.0,
    )

    data class Milestone(
        val amountUsd: Double,
        val label: String,      // e.g. "$1K" / "$100K" / "$1M"
        val crossed: Boolean,
        val crossedAtMs: Long?, // null if not yet crossed
    )

    data class RingSnapshot(
        val currentEquityUsd: Double,
        val currentEquitySol: Double,
        val solPriceUsd: Double,
        val peakEquityUsd: Double,
        val peakEquityAtMs: Long,
        val drawdownFromPeakBps: Int,  // 0 = at peak, 10_000 = -100%
        val startUsd: Double,
        val targetUsd: Double,
        val multipleFromStart: Double, // currentUsd / startUsd
        val progressBps: Int,          // 0..10_000, 10_000 = at target
        val milestones: List<Milestone>,
        val nextMilestoneUsd: Double?, // null if at $1M
        val nextMilestoneRemainingUsd: Double?,
        val bumpsRecorded: Long,
        val ringDrawnAtMs: Long,
    )

    private val peakEquity = AtomicReference(0.0)
    private val peakEquityAtMs = AtomicLong(0L)
    private val milestoneTimes = java.util.concurrent.ConcurrentHashMap<Double, Long>()
    private val bumps = AtomicLong(0L)
    private val lastSnap = AtomicReference<RingSnapshot?>(null)

    /**
     * Fold a fresh equity reading into the ring. Safe to call from any
     * thread. Emits milestone-crossed counters on the first crossing.
     * If solPriceUsd is not finite/positive, the ring uses the previous
     * USD equity as a hold — this prevents a bad SOL feed from spuriously
     * driving the milestone tape.
     */
    fun observe(equitySol: Double, solPriceUsd: Double): RingSnapshot {
        bumps.incrementAndGet()
        val prev = lastSnap.get()
        val equityUsd = if (solPriceUsd.isFinite() && solPriceUsd > 0.0 && equitySol.isFinite())
            equitySol * solPriceUsd
        else prev?.currentEquityUsd ?: 0.0

        val now = System.currentTimeMillis()
        // Peak / drawdown.
        var currentPeak = peakEquity.get()
        while (equityUsd > currentPeak) {
            if (peakEquity.compareAndSet(currentPeak, equityUsd)) {
                peakEquityAtMs.set(now)
                break
            }
            currentPeak = peakEquity.get()
        }
        val peak = peakEquity.get().coerceAtLeast(START_USD)
        val ddBps = if (peak <= 0.0) 0 else
            ((peak - equityUsd) / peak * 10_000.0).toInt().coerceIn(0, 10_000)

        // Milestones.
        for (m in MILESTONES_USD) {
            if (equityUsd >= m && !milestoneTimes.containsKey(m)) {
                milestoneTimes.putIfAbsent(m, now)
                try {
                    val label = milestoneLabel(m)
                    PipelineHealthCollector.labelInc("GROWTH_MILESTONE_CROSSED_6550c|$label")
                    PipelineHealthCollector.labelInc("GROWTH_MILESTONE_CROSSED_6550c")
                    ForensicLogger.lifecycle(
                        "GROWTH_MILESTONE_CROSSED_6550c",
                        "milestone=$label amountUsd=$m equityUsd=${"%.2f".format(equityUsd)} " +
                            "multiple=${"%.2f".format(equityUsd / START_USD)}x " +
                            "elapsedMs=${now - (milestoneTimes[START_USD] ?: now)} " +
                            "peakUsd=${"%.2f".format(peak)} drawdownBps=$ddBps",
                    )
                } catch (_: Throwable) {}
            }
        }

        val nextMs = MILESTONES_USD.firstOrNull { equityUsd < it }
        val milestones = MILESTONES_USD.map { m ->
            Milestone(
                amountUsd = m,
                label = milestoneLabel(m),
                crossed = equityUsd >= m,
                crossedAtMs = milestoneTimes[m],
            )
        }
        val multiple = if (START_USD > 0) equityUsd / START_USD else 0.0
        val progressBps = if (TARGET_USD <= START_USD) 0
        else ((equityUsd - START_USD) / (TARGET_USD - START_USD) * 10_000.0)
            .toInt().coerceIn(0, 10_000)
        val snap = RingSnapshot(
            currentEquityUsd = equityUsd,
            currentEquitySol = equitySol,
            solPriceUsd = solPriceUsd,
            peakEquityUsd = peak,
            peakEquityAtMs = peakEquityAtMs.get(),
            drawdownFromPeakBps = ddBps,
            startUsd = START_USD,
            targetUsd = TARGET_USD,
            multipleFromStart = multiple,
            progressBps = progressBps,
            milestones = milestones,
            nextMilestoneUsd = nextMs,
            nextMilestoneRemainingUsd = nextMs?.let { it - equityUsd },
            bumpsRecorded = bumps.get(),
            ringDrawnAtMs = now,
        )
        lastSnap.set(snap)
        return snap
    }

    fun snapshot(): RingSnapshot? = lastSnap.get()

    fun statusLine(): String {
        val s = lastSnap.get() ?: return "no_ring_yet bumps=${bumps.get()}"
        val crossed = s.milestones.count { it.crossed }
        val nextTag = s.nextMilestoneUsd?.let { "next=${milestoneLabel(it)}@\$${it.toInt()} rem=\$${"%.2f".format(s.nextMilestoneRemainingUsd ?: 0.0)}" } ?: "next=TARGET_REACHED"
        return buildString {
            append("equityUsd=\$").append("%.2f".format(s.currentEquityUsd))
            append(" mult=").append("%.2fx".format(s.multipleFromStart))
            append(" progressBps=").append(s.progressBps)
            append(" peakUsd=\$").append("%.2f".format(s.peakEquityUsd))
            append(" ddBps=").append(s.drawdownFromPeakBps)
            append(" milestones=").append(crossed).append('/').append(MILESTONES_USD.size)
            append(' ').append(nextTag)
            append(" bumps=").append(s.bumpsRecorded)
        }
    }

    private fun milestoneLabel(usd: Double): String = when {
        usd >= 1_000_000.0 -> "\$${(usd / 1_000_000.0).toInt()}M"
        usd >= 1_000.0     -> "\$${(usd / 1_000.0).toInt()}K"
        else               -> "\$${usd.toInt()}"
    }

    internal fun resetForTest() {
        peakEquity.set(0.0)
        peakEquityAtMs.set(0L)
        milestoneTimes.clear()
        bumps.set(0L)
        lastSnap.set(null)
    }
}
