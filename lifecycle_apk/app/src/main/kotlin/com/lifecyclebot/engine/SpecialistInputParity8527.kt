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


    data class ShitcoinInputs(val momentumPct: Double, val bundlePct: Double)

    /** Signed, dated momentum and measured first-block concentration; a risk category is not a percentage. */
    fun shitcoinInputs8527(read: SpecialistMarketInputs8522.Read, measuredBundlePct: Double): ShitcoinInputs =
        ShitcoinInputs(read.momentumPct?.takeIf { it.isFinite() } ?: 0.0,
            measuredBundlePct.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0)


    data class ShitcoinEvidence(
        val buyPressurePct: Double, val volatility: Double, val socialScore: Int,
        val hasWebsite: Boolean, val hasTwitter: Boolean, val hasTelegram: Boolean,
        val devWallet: String?, val devHoldPct: Double, val graduationProgress: Double,
        val graduationObserved: Boolean,
    )

    internal fun shitcoinEvidence8527(
        buyPressurePct: Double?, volatility: Double?, socialScore: Double?,
        website: String?, twitter: String?, telegram: String?, devWallet: String?,
        migrated: Boolean,
    ): ShitcoinEvidence = ShitcoinEvidence(
        buyPressurePct?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: 50.0,
        volatility?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
        socialScore?.takeIf { it.isFinite() }?.toInt()?.coerceIn(0, 100) ?: 0,
        !website.isNullOrBlank(), !twitter.isNullOrBlank(), !telegram.isNullOrBlank(),
        devWallet?.takeIf { it.isNotBlank() },
        // No creator-ownership observation exists here. First-block supply is a bundle metric, not developer holdings.
        0.0,
        // The legacy API uses zero as a neutral/unobserved feature; it grants no graduation score.
        // BondingCurveTracker/meta progress is market-cap-derived and must not impersonate measured curve completion.
        if (migrated) 100.0 else 0.0, migrated,
    )

    fun shitcoinEvidence8527(ts: TokenState, read: SpecialistMarketInputs8522.Read): ShitcoinEvidence {
        val socialMeta7556 = try { BirdeyeMetaDataProvider.peekCached(ts.mint) } catch (_: Throwable) { null }
        return shitcoinEvidence8527(
            read.buyPressurePct, ts.volatility, ts.sentiment.score.takeIf { !ts.sentiment.isStale },
            socialMeta7556?.website, socialMeta7556?.twitter, socialMeta7556?.telegram,
            ts.tokenMap.creatorOrDevWallet.takeIf { it.isNotBlank() } ?: OperatorRegistry.getDevWallet(ts.mint),
            ts.tokenMap.migratedOrGraduated)
    }

    data class MoonshotInputs(val volumeScore: Int, val phase: String)

    internal fun moonshotInputs8527(measuredVolumeScore: Double, volumeObserved: Boolean,
                                   canonicalPhase: String?): MoonshotInputs =
        MoonshotInputs(if (volumeObserved && measuredVolumeScore.isFinite())
            measuredVolumeScore.toInt().coerceIn(0, 100) else 0,
            canonicalPhase?.takeIf { it.isNotBlank() } ?: "MATURE_OR_UNKNOWN")

    /** No V3-total-as-volume substitution and no stale textual phase on the execution side. */
    fun moonshotInputs8527(ts: TokenState): MoonshotInputs = moonshotInputs8527(
        ts.meta.volScore,
        try { com.lifecyclebot.v3.bridge.V3Adapter.dataKnowledge7327(ts).volume } catch (_: Throwable) { false },
        try { com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.snapshot(ts).phase.name }
        catch (_: Throwable) { null })

    internal fun namedDevSelling8527(vararg evidence: String): Boolean {
        val named = evidence.joinToString(" ").uppercase()
        return named.contains("DEV_SELL") || named.contains("DEV SELL")
    }

    /** Bundle risk remains a separate safety fact; it cannot invent evidence that the developer sold. */
    fun namedDevSelling8527(ts: TokenState): Boolean = namedDevSelling8527(
        ts.safety.summary, ts.safety.bundleReason, ts.safety.hardBlockReasons.joinToString(" "))

    internal fun topHolderPct8527(tokenPct: Double?, mapPct: Double?, safetyPct: Double,
                                  safetyObservedAtMs: Long, nowMs: Long): Double =
        tokenPct?.takeIf { it.isFinite() && it in 0.0..100.0 }
            ?: mapPct?.takeIf { it.isFinite() && it in 0.0..100.0 }
            ?: safetyPct.takeIf { safetyObservedAtMs in 1L..nowMs && it.isFinite() && it in 0.0..100.0 }
            ?: 20.0

    /** Same native neutral fallback in both modes; a default safety zero is not a measured distribution. */
    fun topHolderPct8527(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Double =
        topHolderPct8527(ts.topHolderPct, ts.tokenMap.topHolderConcentrationPct, ts.safety.topHolderPct,
            ts.safety.topHolderObservedAtMs8522, nowMs)

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
            ts.source.contains("BOOST", true) || ts.toolAffinity.any { it.contains("BOOST", true) }, i.ageMinutes,
            momentumObserved = read.continuation.observed)
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
