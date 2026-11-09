package com.lifecyclebot.engine

import com.lifecyclebot.data.CandidateDecision
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate8527NativeCandidateValuesTest {
    private val p = NativeCandidateValues8527
    private val base = CandidateDecision.blocked("GENERIC_WATCH").copy(
        entryScore = 99.0, aiConfidence = 98.0, edgeConfidence = 77.0)

    @Test fun everyDeskKeepsNativeScoreAndConfidenceInsteadOfGenericMaximaOrFloors() {
        for (lane in CoreNativeDispatch8527.nativeLanes8527) {
            val own = p.apply8527(base, 41.0, 12.0)
            assertEquals(lane, 41.0, own.entryScore, 0.0)
            assertEquals(lane, 12.0, own.aiConfidence, 0.0)
            assertEquals(77.0, own.edgeConfidence, 0.0) // separate EdgeOptimizer evidence is not overwritten
            assertEquals(base.meta, own.meta)
            assertEquals("GENERIC_WATCH", own.blockReason)
            assertFalse(own.shouldTrade) // value propagation never grants BUY
        }
        assertEquals(0.0, p.apply8527(base, Double.NaN, Double.POSITIVE_INFINITY).entryScore, 0.0)
        assertEquals(0.0, p.apply8527(base, Double.NaN, Double.POSITIVE_INFINITY).aiConfidence, 0.0)
        assertEquals(100.0, p.apply8527(base, 150.0, -3.0).entryScore, 0.0)
        assertEquals(0.0, p.apply8527(base, 150.0, -3.0).aiConfidence, 0.0)
    }

    @Test fun coreEnsembleAveragesScoreAndConfidenceIndependentlyNotElectionPriority() {
        fun voter(lane: String, score: Int, confidence: Int) = SpecialistBrainBridge7542.Opinion(
            lane, true, score, confidence, 0.01, "READY", "BREAKOUT", "", "",
            ownershipConviction7948 = 91.0, selectionPriority8522 = 100.0)
        assertEquals(40 to 65, p.coreEnsemble8527(listOf(voter("MOONSHOT", 20, 90), voter("QUALITY", 60, 40)), 1, 2))
        assertEquals(31 to 17, p.coreEnsemble8527(emptyList(), 31, 17))
    }

    @Test fun coreEnsembleNormalisesMoonshot150ScaleBeforeRankingOrAveraging() {
        val moonshot = SpecialistBrainBridge7542.Opinion("MOONSHOT", true, 100, 69, 0.01,
            "READY", "MOONSHOT_RUNNER", "", "", rawScore8535 = 105, scaleMax8535 = 150)
        val shitcoin = SpecialistBrainBridge7542.Opinion("SHITCOIN", true, 82, 79, 0.01,
            "READY", "SHITCOIN_SETUP", "", "", rawScore8535 = 82, scaleMax8535 = 100)
        assertEquals(70.0, p.coreScorePct8651(moonshot), 0.01)
        assertEquals(70.0, p.coreRank8651(moonshot), 0.01)
        assertEquals(82.0, p.coreRank8651(shitcoin), 0.01)
        assertEquals(76 to 74, p.coreEnsemble8527(listOf(shitcoin, moonshot), 0, 0))
        val bridge = File("src/main/kotlin/com/lifecyclebot/engine/SpecialistBrainBridge7542.kt").readText()
        assertTrue(bridge.contains(".sortedByDescending { NativeCandidateValues8527.coreRank8651(it) }"))
        assertTrue(bridge.contains(".copy(firedSetup8535 = fired8651, signalAtMs8535 = now)"))
        assertTrue(bridge.contains("NativeLaneAge8521.review8521(triggered8651, ageEvidence8521)"))
    }

    @Test fun coreFallbackRequiresAnObservedBuyShareAndNamedNativeSetup() {
        assertFalse(p.coreFallback8651(0, 75, 75, null, 3_000.0, false))
        assertFalse(p.coreFallback8651(0, 75, 75, Double.NaN, 3_000.0, false))
        assertFalse(p.coreFallback8651(0, 75, 75, 49.9, 3_000.0, false))
        assertFalse(p.coreFallback8651(0, 75, 75, 50.0, 3_000.0, true))
        assertFalse(p.coreFallback8651(0, 75, 75, 50.0, 0.0, false))
        assertFalse(p.coreFallback8651(1, 75, 75, 55.0, 3_000.0, false))
        assertTrue(p.coreFallback8651(0, 75, 75, 55.0, 3_000.0, false))
        val bridge = File("src/main/kotlin/com/lifecyclebot/engine/SpecialistBrainBridge7542.kt").readText()
        assertTrue(bridge.contains("NativeCandidateValues8527.coreFallback8651"))
        assertTrue(bridge.contains("CORE_NO_NAMED_SETUP_8535"))
    }

    @Test fun activeCallsitesPassEachAvailableNativeMeasurementAndKeepExactFdgValues() {
        val b = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val pairs = listOf(
            "treasurySignal6022.entryScore.toDouble(), treasurySignal6022.confidence.toDouble()",
            "blueChipSignal6022.entryScore.toDouble(), blueChipSignal6022.confidence.toDouble()",
            "moonshotScore.score.toDouble(), moonshotScore.confidence",
            "shitCoinSignal.entryScore.toDouble(), shitCoinSignal.confidence.toDouble()",
            "qualitySignal6022.qualityScore.toDouble(), qualitySignal6022.qualityScore.toDouble()",
            "manipSignal.manipScore.toDouble(), manipSignal.manipScore.toDouble()",
            "expressSignal.confidence.toDouble(), expressSignal.confidence.toDouble()",
            "assessment.confidence.toDouble(), assessment.confidence.toDouble()",
            "dipSignal.confidence.toDouble(), dipSignal.confidence.toDouble()")
        pairs.forEach { assertTrue(it, b.contains("laneScoredBase7389(decision, " + it + ")")) }
        assertTrue(b.contains("laneScoredBase7389(base, native.score.toDouble(), native.confidence.toDouble())"))
        assertFalse(b.contains("aiConfidence = laneBase.aiConfidence.coerceAtLeast"))
        assertFalse(b.contains("entryScore = maxOf(base.entryScore"))
        for (fdg in listOf("qualityFdg", "blueChipFdg", "moonshotFdgDecision", "shitCoinFdg", "manipFdg", "expressFdg", "dipFdg", "treasuryFdg")) {
            assertTrue(fdg, b.contains("score = " + fdg + "?.effectiveEntryScore7687"))
            assertTrue(fdg, b.contains("confidence = " + fdg + "?.confidence"))
            assertTrue(fdg, b.contains("preResolvedSizeSol = " + fdg + "?.sizeSol"))
        }
        assertTrue(b.contains("fdgDecision7835 = sniperFdg8527, tokenState7835 = ts"))
        assertTrue(b.contains("score = sniperFdg8527.effectiveEntryScore7687"))
        assertTrue(b.contains("preResolvedSizeSol = sniperFdg8527.sizeSol"))
        val native = File("src/main/kotlin/com/lifecyclebot/engine/SpecialistBrainBridge7542.kt").readText()
        assertTrue(native.contains("NativeCandidateValues8527.coreEnsemble8527(s, v3, v3c)"))
        assertTrue(native.contains("coreOk, coreScore, coreConfidence"))
    }

    @Test fun cashgenIdentityAndSizeRemainBoundAcrossMemoryFdgAuthorizerExecutorAndLearning() {
        val b = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val cash = b.substringAfter("val compounderLane7614 = when").substringBefore("// END Treasury Mode")
        for (method in listOf("isDangerZone", "recommendedSizeMult", "shouldReject", "calibrationSizeMult", "bucketMean", "bucketSamples"))
            assertTrue(method, cash.contains(method + "(compounderLane7614, _trsScore)"))
        assertTrue(cash.contains("), compounderLane7614, confidenceFloor ="))
        assertTrue(cash.contains("ModeSpecificGates.fromTradingMode(compounderLane7614)"))
        assertTrue(cash.contains("specialistLane = compounderLane7614"))
        assertTrue(cash.contains("requestedBook = executionBookForLane6494(compounderLane7614)"))
        assertTrue(cash.contains("paperLayerTag = compounderLane7614"))
        assertTrue(cash.contains("ownerLane = compounderLane7614"))
        assertTrue(cash.contains("requireNotNull(authResult.executionIntent7835).resolvedSize"))
        assertFalse(b.contains("v3WillExecuteCore"))
    }
}
