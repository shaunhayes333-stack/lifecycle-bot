package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CrossAssetCortex7931
import com.lifecyclebot.engine.truth.AssetClass
import com.lifecyclebot.engine.truth.CanonicalAssetEntryCandidate6551
import com.lifecyclebot.engine.truth.CanonicalEntryAuthority6540
import com.lifecyclebot.engine.truth.FlowRead8546
import com.lifecyclebot.engine.truth.MemeStrategies8546
import com.lifecyclebot.engine.truth.TradePlan7739
import com.lifecyclebot.engine.truth.TradeTape8100
import com.lifecyclebot.engine.market.BuyerFlow8666
import com.lifecyclebot.perps.CryptoLaneDesk7391
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 8848: positive historical cells bought falling charts; 164 crypto FDG submissions, zero intents. */
class Aate8849EntryStageAndCryptoFdgTest {
    private val tier = TradePlan7739.Tier(30.0, 25.0, 25.0)
    private val now = 5_250_000L
    private fun bars(vararg prices: Double) = prices.mapIndexed { i, p ->
        TradePlan7739.Bar(5_100_000L + i * 15_000L, p, p, p, p)
    }
    private fun miss(why: String) = TradePlan7739.Read(null, why)

    @Test fun measuredPositivePastCannotBuyACurrentDumpOrUnretracedSpike() {
        val rising = bars(1.0, 1.25, 1.46)
        assertEquals("UNRETRACED_IMPULSE", TradePlan7739.currentStageWait8849(
            miss("NO_PULLBACK_YET"), rising, tier, 3, now))
        val confirmed = bars(1.0, 1.3, 1.5, 1.28, 1.23, 1.17)
        for (danger in listOf("STILL_FALLING", "NOT_TURNING_UP", "NO_RECLAIM", "PULLBACK_TOO_DEEP",
                "CHASING_THE_HIGH", "EXTENDED_PAST_BASE")) {
            assertEquals(danger, TradePlan7739.currentStageWait8849(miss(danger), confirmed, tier, 3, now))
        }
        assertEquals("SELL_DOMINANT_TAPE", TradePlan7739.currentStageWait8849(
            miss("SELL_DOMINANT_TAPE"), emptyList(), tier, 3, now))
    }

    @Test fun insufficientEvidenceIsNotInventedAsAFallingStage() {
        assertNull(TradePlan7739.currentStageWait8849(miss("STILL_FALLING"), bars(1.0), tier, 3, now))
        assertNull(TradePlan7739.currentStageWait8849(miss("NO_PULLBACK_YET"),
            bars(1.0, 1.03, 1.07), tier, 3, now))
        assertNull(TradePlan7739.currentStageWait8849(miss("TOO_FEW_BARS"), emptyList(), tier, 3, now))
        assertNull(TradePlan7739.currentStageWait8849(
            TradePlan7739.Read(TradePlan7739.Setup.PULLBACK_RECLAIM, "PULLBACK_RECLAIM", 8.0, 18.0, 32.0),
            bars(1.0, 1.3, 1.5, 1.25, 1.28, 1.35), tier, 3, now))
        assertNull("an old failed pullback cannot veto a new decision", TradePlan7739.currentStageWait8849(
            miss("STILL_FALLING"), bars(1.5, 1.3, 1.2), tier, 3, now + 300_000L))
    }

    @Test fun theChartBuyAndHistoricalEvidenceShortcutsBothFollowCurrentStage() {
        val source = File("src/main/kotlin/com/lifecyclebot/engine/truth/TradePlan7739.kt").readText()
        val stage = source.indexOf("val currentStage8849 = currentStageWait8849")
        val chart = source.indexOf("if (LiveEdgeGate7877.chartAdmits7950", stage)
        val evidence = source.indexOf("if (evidenceAdmit8418 && read8418.setup", stage)
        val specific = source.indexOf("if (specific8171?.admitted == true && optionalChartForAdmit", stage)
        assertTrue("stage must bind before both alternate approvals", stage > 0 && chart > stage && evidence > chart && specific > evidence)
        assertTrue(source.contains("CandidateAdmission8136.noteVerdict8136(\"PLAN\", \"WAIT\""))
    }

    @Test fun cryptoDeskForecastUsesItsActualLearningLaneWithoutChangingExecutionOwner() {
        fun candidate(desk: String, assetClass: AssetClass = AssetClass.CRYPTO_ALT) = CanonicalAssetEntryCandidate6551(
            assetId = "a-real-mint", symbol = "SOL", assetClass = assetClass, mode = "PAPER",
            direction = "LONG", requestedVenue = "JUPITER_ROUTABLE", adapter = "PAPER_EXECUTOR",
            source = "CRYPTO_UNIVERSE", specialist = "CRYPTO", score = 73.0, confidence = 0.68,
            evidence = mapOf("deskLane7391" to desk), requestedSizeSol = 0.03,
            price = 0.12, liquidityUsd = 50_000.0, candidateVersion = 1L,
        )
        assertEquals("CRYPTO_MOONSHOT", CrossAssetCortex7931.forecastLane8849(candidate("MOONSHOT")))
        assertEquals("CRYPTO_QUALITY", CrossAssetCortex7931.forecastLane8849(candidate("quality")))
        assertEquals("CRYPTO_ALT", CrossAssetCortex7931.forecastLane8849(candidate("no-desk")))
        assertEquals("CRYPTO", candidate("MOONSHOT").specialist)
        assertEquals("CRYPTO", CrossAssetCortex7931.forecastLane8849(candidate("MOONSHOT", AssetClass.STOCK)))
    }

    @Test fun onlyTheDesksActualSetupIsPassedAsPhase() {
        assertEquals("PULLBACK_RECLAIM", CryptoLaneDesk7391.observedSetup8849(
            listOf("DynScan Quality", "LANE7391=QUALITY desk=QUALITY setup=PULLBACK_RECLAIM move=UP")))
        assertNull(CryptoLaneDesk7391.observedSetup8849(listOf("CryptoBrain BUY")))
        assertNull(CryptoLaneDesk7391.observedSetup8849(listOf("LANE7391=CORE desk=CORE setup=not/a/setup")))
        val trader = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        assertTrue(trader.contains("\"edgePhase\" to CryptoLaneDesk7391.observedSetup8849(signal.reasons).orEmpty()"))
        assertTrue(trader.contains("confidence = (candidate.confidence.coerceIn(0, 100) / 100.0)"))
    }

    @Test fun exactCryptoFdGRefusalsWillBeVisibleInTheNextPipelineDump() {
        assertEquals("LIVE_NON_POSITIVE_EXPECTANCY_7828", CanonicalEntryAuthority6540.canonicalBlockReasonKey8849(
            "LIVE_NON_POSITIVE_EXPECTANCY_7828"))
        assertEquals("CRYPTO_LIVE_REFUSED_WALLET_BELOW_ROUTABLE_7708", CanonicalEntryAuthority6540.canonicalBlockReasonKey8849(
            "CRYPTO_LIVE_REFUSED_WALLET_BELOW_ROUTABLE_7708:tradeable=0.002"))
        val report = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalEntryAuthority6540.kt").readText()
        assertTrue(report.contains("CrossAsset canonical block reasons 8849"))
    }

    @Test fun earlyDiscoveryRequiresActualSameVenueBuyPressureEvenWithoutSlowerFlowRead() {
        val prints = listOf(
            TradeTape8100.Print8128(now - 100_000L, 1.0, 1.0, true),
            TradeTape8100.Print8128(now - 90_000L, 1.1, 0.2, false),
            TradeTape8100.Print8128(now - 70_000L, 1.2, 1.0, true),
            TradeTape8100.Print8128(now - 50_000L, 1.3, 1.0, true),
            TradeTape8100.Print8128(now - 25_000L, 1.2, 1.0, true),
            TradeTape8100.Print8128(now - 10_000L, 1.15, 0.1, false),
            TradeTape8100.Print8128(now - 3_000L, 1.18, 1.0, true),
        )
        assertTrue(MemeStrategies8546.independentEarlyFlow8849(prints, now))
        assertFalse("inferred marks cannot prove buys", MemeStrategies8546.independentEarlyFlow8849(
            prints.map { it.copy(priceOnly = true) }, now))
        assertFalse("sell SOL dominance must refuse", MemeStrategies8546.independentEarlyFlow8849(
            prints + TradeTape8100.Print8128(now - 1_000L, 1.17, 20.0, false), now))
        assertFalse("old signed trades are not a current setup", MemeStrategies8546.independentEarlyFlow8849(
            prints, now + 130_000L))
        val unknownSlowFlow = FlowRead8546.Flow8546(false, 0, 0, 0.0, 0.0, 0, 0, 0.0, 0.0, Double.NaN, false, 0)
        assertFalse(MemeStrategies8546.flowConfirms8546(MemeStrategies8546.Family8546.EARLY_FLOW_RECLAIM, unknownSlowFlow))
        val source = File("src/main/kotlin/com/lifecyclebot/engine/truth/MemeStrategies8546.kt").readText()
        assertTrue(source.contains("EARLY_FLOW_RECLAIM, earlyFlowReclaim8650(early, nowMs), earlyConfirmed8849"))
        assertFalse("no price-bearing tape should depend on 2/5-minute provider readiness",
            source.contains("if (flow.known) try { TradeTape8100.prints8128"))
    }

    @Test fun signedBuyerDiscoveryNamesWhyItMissedWithoutCallingUnknownBirthYoung() {
        val strong = BuyerFlow8666.View(now, 5, 3, 10.0, 78.0, 4.0, 5, 2, 1,
            10.0, 65.0, true)
        assertEquals("NO_SIGNED_PRINTS", MemeStrategies8546.signedDemandGate8849(null, 1.0))
        assertEquals("BIRTH_UNRESOLVED", MemeStrategies8546.signedDemandGate8849(strong, null))
        assertEquals("AGE_OUTSIDE_LAUNCH", MemeStrategies8546.signedDemandGate8849(strong, 45.0))
        assertEquals("NO_SUSTAINED_WALLET_DEMAND", MemeStrategies8546.signedDemandGate8849(
            strong.copy(sustainedDemand = false), 2.0))
        assertEquals("READY", MemeStrategies8546.signedDemandPulse8849(strong))
        assertEquals("NO_SUSTAINED_WALLET_DEMAND", MemeStrategies8546.signedDemandPulse8849(
            strong.copy(sustainedDemand = false)))
        assertEquals("READY", MemeStrategies8546.signedDemandGate8849(strong, 2.0))
        assertNotNull(MemeStrategies8546.signedDemandForming8845(strong, 2.0))
        assertNull(MemeStrategies8546.signedDemandForming8845(strong, null))
        val source = File("src/main/kotlin/com/lifecyclebot/engine/truth/MemeStrategies8546.kt").readText()
        assertTrue(source.contains("signedPulse8849{") && source.contains("signedAge8849{"))
    }

    @Test fun aThousandPercentRunnerIsNotCutAtTheLaunchPlansOrdinaryFullTarget() {
        val runner = TradePlan7739.Plan(TradePlan7739.Setup.LAUNCH_EARLY, -15.0, 50.0, 100.0, now)
        val first = TradePlan7739.exitFor(runner, 1_100.0, 1_140.0, 5 * 60_000L,
            trailBroken = false, costPct = 4.0, runner = true)
        assertEquals(TradePlan7739.ExitKind.HALF, first?.kind)
        runner.firstTargetTaken = true
        assertNull("the remaining runner is uncapped by the ordinary +100% target",
            TradePlan7739.exitFor(runner, 1_100.0, 1_140.0, 6 * 60_000L,
                trailBroken = false, costPct = 4.0, runner = true))
        assertEquals(TradePlan7739.ExitKind.FULL, TradePlan7739.exitFor(
            TradePlan7739.Plan(TradePlan7739.Setup.LAUNCH_EARLY, -15.0, 50.0, 100.0, now),
            110.0, 114.0, 6 * 60_000L, false, 4.0, runner = false)?.kind)
    }
}
