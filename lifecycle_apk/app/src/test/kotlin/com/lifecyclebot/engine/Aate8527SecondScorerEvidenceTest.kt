package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.AdmissionBirth8144
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Units and missing-value contract through the actual independent scorer. */
class Aate8527SecondScorerEvidenceTest {
    private fun token() = TokenState(mint = "", symbol = "SECOND8527")

    private fun withVolume(ts: TokenState, volume: Double) {
        ts.history.clear()
        ts.history.addLast(Candle(
            ts = System.currentTimeMillis(), priceUsd = 1.0, marketCap = 100_000.0,
            volumeH1 = volume, volume24h = volume,
        ))
    }

    @Test fun providerRiskIsConvertedOnceAndStoredSafetyIsNotInvertedAgain() {
        val ts = token()
        val cases = listOf(
            Triple(0.0, 100, 5), Triple(1.0, 99, 5), Triple(30.0, 70, 5),
            Triple(70.0, 30, 3), Triple(100.0, 0, 0),
        )
        cases.forEach { (risk, stored, expected) ->
            val report = JSONObject().put("score_normalised", risk).put("risks", org.json.JSONArray())
            val safety = RugcheckScore8399.reportSafety8418(report, ts.mint)
            assertEquals(stored, safety)
            ts.safety = SafetyReport(rugcheckScore = safety)
            assertEquals("provider risk $risk", expected, SecondScorer.score(ts).components.getValue("safety"))
        }
        ts.safety = SafetyReport(rugcheckScore = RugcheckScore8399.reportSafety8418(null, ts.mint))
        assertEquals(0, SecondScorer.score(ts).components.getValue("safety"))
    }

    @Test fun pendingRugUnknownAndOutOfRangeSafetyCannotEarnCleanlinessPoints() {
        val ts = token()
        listOf(
            -1 to 0, 0 to 0, 1 to 0, 2 to 0, 29 to 0, 30 to 3,
            69 to 3, 70 to 5, 100 to 5, 101 to 0, Int.MAX_VALUE to 0,
        ).forEach { (safety, expected) ->
            ts.safety = SafetyReport(rugcheckScore = safety)
            assertEquals("stored safety $safety", expected, SecondScorer.score(ts).components.getValue("safety"))
        }
    }

    @Test fun invalidLiquidityAndVolumeDoNotFallThroughToMaximumBonuses() {
        val ts = token()
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0, 0.0).forEach { invalid ->
            ts.lastLiquidityUsd = invalid
            withVolume(ts, invalid)
            val actual = SecondScorer.score(ts)
            assertEquals(0, actual.components.getValue("liq"))
            assertEquals(0, actual.components.getValue("vol"))
        }
        listOf(1.0 to 5, 2_000.0 to 12, 5_000.0 to 18, 20_000.0 to 22, 100_000.0 to 25)
            .forEach { (usd, expected) ->
                ts.lastLiquidityUsd = usd
                assertEquals(expected, SecondScorer.score(ts).components.getValue("liq"))
            }
        listOf(1.0 to 3, 500.0 to 8, 5_000.0 to 12, 50_000.0 to 15)
            .forEach { (usd, expected) ->
                withVolume(ts, usd)
                assertEquals(expected, SecondScorer.score(ts).components.getValue("vol"))
            }
    }

    @Test fun balancedDefaultAndStalePressureAreNotObservedBalancedMarkets() {
        val ts = token()
        assertEquals(50.0, ts.lastBuyPressurePct, 0.0)
        assertEquals(0, SecondScorer.score(ts).components.getValue("buy"))
        ts.buyPressureObservedAtMs8084 = System.currentTimeMillis()
        assertEquals(15, SecondScorer.score(ts).components.getValue("buy"))
        ts.buyPressureObservedAtMs8084 = System.currentTimeMillis() - 180_000L
        assertEquals(0, SecondScorer.score(ts).components.getValue("buy"))
        ts.buyPressureObservedAtMs8084 = System.currentTimeMillis()
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 101.0).forEach {
            ts.lastBuyPressurePct = it
            assertEquals(0, SecondScorer.score(ts).components.getValue("buy"))
        }
        listOf(0.0 to 0, 30.0 to 8, 50.0 to 15, 60.0 to 15, 70.0 to 10, 100.0 to 3)
            .forEach { (pct, expected) ->
                ts.lastBuyPressurePct = pct
                assertEquals(expected, SecondScorer.score(ts).components.getValue("buy"))
            }
    }

    @Test fun observedHolderCountsAndAllMaximumObservedComponentsKeepHundredPointScale() {
        val ts = token()
        listOf(-1 to 0, 0 to 0, 1 to 4, 49 to 4, 50 to 10, 199 to 10, 200 to 16, 999 to 16, 1_000 to 20)
            .forEach { (holders, expected) ->
                ts.peakHolderCount = holders
                assertEquals(expected, SecondScorer.score(ts).components.getValue("holders"))
            }
        val mint = "8527SecondScorerKnownOld".padEnd(44, 'B')
        AdmissionBirth8144.noteCreateFrame8144(mint, System.currentTimeMillis() - 3L * 60L * 60_000L)
        val observed = TokenState(mint = mint, symbol = "OBSERVED")
        observed.lastLiquidityUsd = 100_000.0
        observed.peakHolderCount = 1_000
        observed.lastBuyPressurePct = 50.0
        observed.buyPressureObservedAtMs8084 = System.currentTimeMillis()
        observed.safety = SafetyReport(rugcheckScore = 100)
        withVolume(observed, 50_000.0)
        assertEquals(100, SecondScorer.score(observed).score)
    }
}
