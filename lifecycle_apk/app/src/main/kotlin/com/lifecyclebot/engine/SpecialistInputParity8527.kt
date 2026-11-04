package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.v3.scoring.ManipulatedTraderAI
import com.lifecyclebot.v3.scoring.ShitCoinExpress

/** Shared native producers for the preview and the actual door. No centered momentum, BP-derived price change,
 * watchlist-age fiction or bundle-category percentage may be substituted on the downstream path. */
internal object SpecialistInputParity8527 {
    data class ExpressInputs(val momentumPct: Double, val change5mPct: Double, val volumeRatio: Double,
                             val buyPressurePct: Double, val ageMinutes: Double)

    fun expressInputs8527(read: SpecialistMarketInputs8522.Read): ExpressInputs = ExpressInputs(
        read.continuation.pct, read.change5mPct ?: 0.0, read.history.volumeRatio ?: 1.0,
        read.buyPressurePct ?: 50.0, read.age.inputMinutes)

    /** Preserve stronger native structural refusals; apply precisely the same birth contract as the preview. */
    internal fun ageDecision8527(lane: String, eligible: Boolean, reason: String,
                                age: NativeLaneAge8521.Evidence): Pair<Boolean, String> {
        val reviewed = NativeLaneAge8521.review8521(SpecialistBrainBridge7542.Opinion(
            lane, eligible, 0, 0, 0.0, reason, "NONE", "", ""), age)
        return reviewed.eligible to reviewed.reason
    }

    internal fun observedReason8527(ts: TokenState, lane: String, eligible: Boolean, reason: String,
                                    read: SpecialistMarketInputs8522.Read): String {
        if (eligible) return reason
        val observations = LaneInputTruth8236.observed8236(ts, read.history.prices.size).copy(
            buyPressure = read.buyPressurePct != null,
            momentum = read.change5mPct != null || read.history.recentPrices.size >= 2 || read.continuation.observed)
        return LaneInputTruth8236.deferFor8236(lane, reason, observations)
            ?: ContinuationRead8348.deferMomentum8348(reason, if (lane == "EXPRESS") read.continuation.observed else read.momentumPct != null)
            ?: reason
    }

    fun express8527(ts: TokenState, read: SpecialistMarketInputs8522.Read): ShitCoinExpress.ExpressSignal {
        val i = expressInputs8527(read)
        val raw = ShitCoinExpress.evaluate(ts.mint, ts.symbol, read.price, ts.lastMcap, ts.lastLiquidityUsd,
            i.momentumPct, i.buyPressurePct, i.volumeRatio, i.change5mPct,
            ts.source.contains("TREND", true) || ts.toolAffinity.any { it.contains("TREND", true) },
            ts.source.contains("BOOST", true) || ts.toolAffinity.any { it.contains("BOOST", true) }, i.ageMinutes)
        val (eligible, reason) = ageDecision8527("EXPRESS", raw.shouldRide, raw.reason, read.age)
        return raw.copy(shouldRide = eligible, reason = observedReason8527(ts, "EXPRESS", eligible, reason, read))
    }

    fun manipulated8527(ts: TokenState, read: SpecialistMarketInputs8522.Read, paper: Boolean): ManipulatedTraderAI.ManipSignal {
        val raw = ManipulatedTraderAI.evaluate(ts.mint, ts.symbol, read.price, ts.lastMcap, ts.lastLiquidityUsd,
            read.momentumPct ?: 0.0, read.buyPressurePct ?: 50.0,
            ts.safety.firstBlockSupplyPct.takeIf { it >= 0.0 } ?: 0.0, ts.source, read.age.inputMinutes,
            ts.safety.rugcheckScore.takeIf { it >= 0 } ?: 1, paper)
        val (eligible, reason) = ageDecision8527("MANIPULATED", raw.shouldEnter, raw.reason, read.age)
        return raw.copy(shouldEnter = eligible, reason = observedReason8527(ts, "MANIPULATED", eligible, reason, read))
    }
}
