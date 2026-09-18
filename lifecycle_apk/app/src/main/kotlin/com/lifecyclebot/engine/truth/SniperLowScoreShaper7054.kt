package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.7054 §TRADE_QUALITY_DIRECTIVE — PROJECT_SNIPER low-score shaping.
 *
 * Implements the operator's directive §1-§4 and §8. LANE-LOCAL: every read and
 * every decision here is gated on lane == PROJECT_SNIPER and score <= 25. It is
 * not a gate, it is a size multiplier, and it never returns zero — "Never set
 * size to zero solely because the cohort historically underperformed."
 *
 * EXPLICITLY NOT TOUCHED, per the directive: QUALITY behaviour, global FDG
 * thresholds, the global position cap, global buy cadence, runner/ultra-runner
 * banking, peak-lock, protective exits, partial-sell accounting, and the
 * trading gates of every other lane.
 *
 * CALIBRATION, AND WHY IT STARTS AT THE TOP OF THE RANGE
 * =====================================================
 * The directive's suggested bands are ranges. This starts at the CONSERVATIVE
 * end of each (least reduction) rather than the aggressive end:
 *
 *   S0-10  weak evidence (<=1)     x0.35   (directive range 0.20-0.35)
 *   S0-10  corroborated (>=2)      x0.50   (directive 0.50)
 *   S11-25 weak evidence (<=1)     x0.65   (directive range 0.50-0.65)
 *   S11-25 evidence 2-3            x0.80   (directive range 0.80-1.00)
 *   S11-25 corroborated (>=4)      x1.00   (unchanged)
 *   S26+                           x1.00   (untouched, per directive)
 *
 * Because the cohort this shapes against is still recovering from a known
 * mis-attribution. V5.0.7051 established that promotion credits a runner to the
 * lane it was promoted INTO, so PROJECT_SNIPER's own record loses its winners
 * while keeping every loss. 5.0.7047 shows both halves of that contradiction at
 * once: S11-25 losses=9 wins=2 alongside meanPnl=+20.26% and EV=+12.07%/trade.
 * Two wins in eleven cannot produce a +20% mean.
 *
 * 7051 ships the measurement (EntryCohortAttribution7051.statusLine7051 reports
 * promotionLeaks and leakWins) but it needs a run to populate. Until leakWins
 * is known, shaping hard against that cohort risks §6 — "do not kill runners
 * early" — by shrinking exactly the entries that become runners. Starting at
 * the top of each band means a wrong assumption costs a small reduction rather
 * than a lost runner, and the numbers tighten once the leak is measured.
 *
 * This is stated so the next reader knows the constants are a deliberate first
 * step with a review condition attached, not a tuned result.
 */
object SniperLowScoreShaper7054 {

    private const val LANE = "PROJECT_SNIPER"

    /** Directive §3 — bounded exploration so regime change is still detectable. */
    private const val EXPLORATION_SHARE = 0.12          // within the 10-15% band
    private const val EXPLORATION_SIZE_MULT = 0.15      // minimum-size probe

    enum class Decision { FULL_ALLOW, SHAPED, PROBE, EXPLORATION_PROBE }

    data class Shaping(
        val decision: Decision,
        val sizeMult: Double,
        val evidenceCount: Int,
        val scoreBand: String,
        val reasons: List<String>,
    )

    private val shaped = java.util.concurrent.atomic.AtomicLong(0L)
    private val probes = java.util.concurrent.atomic.AtomicLong(0L)
    private val fullAllows = java.util.concurrent.atomic.AtomicLong(0L)
    private val explorations = java.util.concurrent.atomic.AtomicLong(0L)

    private fun band(score: Int): String = when {
        score <= 10 -> "S0-10"
        score <= 25 -> "S11-25"
        else -> "S26+"
    }

    /**
     * Directive §4 — count independent causal evidence. Each source is read
     * defensively; a source that throws contributes nothing rather than
     * failing the entry, because an unavailable brain is not evidence against
     * a candidate.
     */
    private fun countEvidence(ts: TokenState, score: Int, regime: String, sourceFamily: String): Pair<Int, MutableList<String>> {
        val reasons = mutableListOf<String>()
        var n = 0

        // 1. Canonical mark valid — there is a price we have not refused.
        try {
            if (ts.lastPrice.isFinite() && ts.lastPrice > 0.0 &&
                com.lifecyclebot.engine.OpenPnlSanity.pricingTruth(
                    ts, "SniperShaper7054/${ts.mint.take(8)}", emit = false,
                ).trusted
            ) { n++; reasons += "mark" }
        } catch (_: Throwable) {}

        // 2. Liquidity / sellability — an exit has somewhere to go.
        try {
            if (ts.lastLiquidityUsd.isFinite() && ts.lastLiquidityUsd >= 5_000.0) { n++; reasons += "liq" }
        } catch (_: Throwable) {}

        // 3. Source freshness — the candidate is inside its own launch window.
        try {
            val ageMin = (System.currentTimeMillis() - ts.addedToWatchlistAt) / 60_000.0
            if (ageMin in 0.0..15.0) { n++; reasons += "fresh" }
        } catch (_: Throwable) {}

        // 4 + 5. Predictive oracle ADMIT, and positive forward EV. Two separate
        // reads of the same stack, counted separately because the directive
        // lists them separately and they can disagree.
        try {
            val f = PredictiveEntryOracle6915.evaluate(
                lane = LANE, score = score, sourceFamily = sourceFamily, regime = regime,
                mint = ts.mint, symbol = ts.symbol, liquidityUsd = ts.lastLiquidityUsd,
            )
            if (f.verdict == PredictiveEntryOracle6915.Verdict.ADMIT) { n++; reasons += "oracleAdmit" }
            if (f.expectancyPct > 0.0) { n++; reasons += "fwdEV+" }
        } catch (_: Throwable) {}

        // 6. Learned cohort evidence materially above baseline.
        try {
            val agg = com.lifecyclebot.engine.ForwardOutcomeModel.cohortEvidence6911(LANE, score)
            if (agg.samples >= 5L && agg.expectedPnlPct > 0.0) { n++; reasons += "cohort(n=${agg.samples})" }
        } catch (_: Throwable) {}

        // 7. Regime compatibility — not shaping into a declared downturn.
        try {
            if (!regime.equals("DUMP", true)) { n++; reasons += "regime" }
        } catch (_: Throwable) {}

        // 8. No losing-streak cooling on this cohort. Read through
        // cooldownRemainingSec, which is the reflex's real per-lane state —
        // NOT shouldBlockNewBuys(), which this session's audit found returns
        // false unconditionally and would have counted as evidence every time.
        try {
            if (LosingStreakReflex6439.cooldownRemainingSec(LANE) <= 0L) { n++; reasons += "noStreak" }
        } catch (_: Throwable) {}

        return n to reasons
    }

    /**
     * The only entry point. Returns null for anything this authority does not
     * own — any other lane, and any score above 25 — so the caller's behaviour
     * is byte-identical outside the shaped window.
     */
    fun shape(ts: TokenState, lane: String, score: Int, regime: String, sourceFamily: String): Shaping? {
        if (!lane.equals(LANE, true)) return null
        if (score > 25) return null

        val scoreBand = band(score)
        val (evidence, reasons) = try { countEvidence(ts, score, regime, sourceFamily) } catch (_: Throwable) { 0 to mutableListOf<String>() }

        // Directive §1: S0-10 defaults to PROBE/SHADOW and only reaches full
        // size on corroboration. Directive §4: evidence >=4 normal, 2-3
        // reduced, <=1 probe only. Combined below, per band.
        val low = scoreBand == "S0-10"
        var decision: Decision
        var mult: Double
        when {
            low && evidence <= 1 -> { decision = Decision.PROBE; mult = 0.35 }
            low -> { decision = Decision.SHAPED; mult = 0.50 }
            evidence <= 1 -> { decision = Decision.SHAPED; mult = 0.65 }
            evidence <= 3 -> { decision = Decision.SHAPED; mult = 0.80 }
            else -> { decision = Decision.FULL_ALLOW; mult = 1.00 }
        }

        // Directive §3 — bounded exploration allowance. A deterministic share
        // of otherwise-probed candidates executes as a minimum-size probe and
        // is TAGGED SEPARATELY, so the model can still detect a regime change
        // in a cohort it has learned to avoid. Deterministic on the mint rather
        // than random so the same candidate cannot flip between ticks.
        if (decision == Decision.PROBE) {
            val pick = ((ts.mint.hashCode() and 0x7FFFFFFF) % 100) / 100.0
            if (pick < EXPLORATION_SHARE) {
                decision = Decision.EXPLORATION_PROBE
                mult = EXPLORATION_SIZE_MULT
                reasons += "explorationAllowance"
            }
        }

        when (decision) {
            Decision.FULL_ALLOW -> fullAllows.incrementAndGet()
            Decision.SHAPED -> shaped.incrementAndGet()
            Decision.PROBE -> probes.incrementAndGet()
            Decision.EXPLORATION_PROBE -> explorations.incrementAndGet()
        }
        return Shaping(decision, mult, evidence, scoreBand, reasons)
    }

    /** Directive §8 — emit with every field it asks for. */
    fun emit(ts: TokenState, s: Shaping, sizeBefore: Double, sizeAfter: Double, regime: String, source: String) {
        try {
            val label = when (s.decision) {
                Decision.FULL_ALLOW -> "SNIPER_LOW_SCORE_FULL_ALLOW"
                Decision.SHAPED -> "SNIPER_LOW_SCORE_SHAPED"
                Decision.PROBE -> "SNIPER_LOW_SCORE_PROBE"
                Decision.EXPLORATION_PROBE -> "SNIPER_LOW_SCORE_EXPLORATION_PROBE_7054"
            }
            PipelineHealthCollector.labelInc(label)
            PipelineHealthCollector.labelInc("SNIPER_EVIDENCE_COUNT_${s.evidenceCount}")
            ForensicLogger.lifecycle(
                label,
                "mint=${ts.mint.take(10)} sym=${ts.symbol} scoreBand=${s.scoreBand} " +
                    "evidenceCount=${s.evidenceCount} sizeBefore=${"%.4f".format(sizeBefore)} " +
                    "sizeAfter=${"%.4f".format(sizeAfter)} decision=${s.decision} " +
                    "regime=$regime source=${source.take(28)} evidence=${s.reasons.joinToString("+")}",
            )
        } catch (_: Throwable) {}
    }

    fun statusLine7054(): String =
        "fullAllow=${fullAllows.get()} shaped=${shaped.get()} probe=${probes.get()} " +
            "exploration=${explorations.get()} " +
            "calibration=CONSERVATIVE_TOP_OF_BAND_pending_7051_leak_measurement"

    internal fun resetForTest() {
        shaped.set(0L); probes.set(0L); fullAllows.set(0L); explorations.set(0L)
    }
}
