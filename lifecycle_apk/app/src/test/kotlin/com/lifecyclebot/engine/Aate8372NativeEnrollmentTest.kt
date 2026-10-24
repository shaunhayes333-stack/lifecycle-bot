package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate8372NativeEnrollmentTest {
    @Test fun cryptoNativeObservationsKeepEverySpecialistsExactCryptoLane() {
        assertTrue(NativeDecisionEnrollment8372.isCryptoDesk("CRYPTO_ALT_DESK_7391"))
        assertFalse(NativeDecisionEnrollment8372.isCryptoDesk("DEXSCREENER"))
        for (lane in listOf("QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "EXPRESS", "CORE", "MOONSHOT",
            "PROJECT_SNIPER", "DIP_HUNTER", "MANIPULATED", "TREASURY", "CASHGEN")) {
            assertEquals("CRYPTO_$lane", NativeDecisionEnrollment8372.cryptoLearningLane(lane))
            assertEquals("CRYPTO_$lane", NativeDecisionEnrollment8372.cryptoLearningLane("CRYPTO_$lane"))
        }
        assertNull(NativeDecisionEnrollment8372.cryptoLearningLane("UNKNOWN"))
        assertEquals("ethereum|0xabc", NativeDecisionEnrollment8372.learningIdentity("ethereum|0xabc", "PEPE", null))
        assertEquals("bsc|0xabc", NativeDecisionEnrollment8372.learningIdentity("bsc|0xabc", "PEPE", null))
        assertEquals("mint", NativeDecisionEnrollment8372.learningIdentity("solana|mint", "ABC", null))
    }

    @Test fun onlyExactFreshPriceObservationsCanDateNativeCryptoPredictions() {
        assertEquals(900L, NativeDecisionEnrollment8372.observedPriceAt(2.0, 2.0, 900L, 1000L))
        assertEquals(0L, NativeDecisionEnrollment8372.observedPriceAt(2.0, 2.0, 0L, 1000L))
        assertEquals(0L, NativeDecisionEnrollment8372.observedPriceAt(2.0, 3.0, 900L, 1000L))
        assertEquals(0L, NativeDecisionEnrollment8372.observedPriceAt(2.0, 2.0, 1001L, 1000L))
        assertEquals(0L, NativeDecisionEnrollment8372.observedPriceAt(2.0, 2.0, 1L, 121_000L))
        assertEquals(0L, NativeDecisionEnrollment8372.observedPriceAt(Double.NaN, Double.NaN, 900L, 1000L))
    }

    @Test fun cryptoContextDoesNotEnterSolanaShadowAndMetadataDoesNotForgePriceTime() {
        val shadow = File("src/main/kotlin/com/lifecyclebot/engine/truth/ExploreShadow8347.kt").readText()
            .substringAfter("fun onOpinions8347").substringBefore("fun onCortexAssessment8368")
        assertTrue(shadow.indexOf("isCryptoDesk(ts.lastPriceSource)") < shadow.indexOf("ShadowMark8360.observed8360"))
        val registry = File("src/main/kotlin/com/lifecyclebot/perps/DynamicAltTokenRegistry.kt").readText()
        assertTrue(registry.contains("val priceObservedAtMs8372: Long = 0L"))
        assertTrue(registry.contains("priceObservedAtMs8372 = o.optLong(\"priceObservedAtMs8372\", 0L)"))
        assertTrue(registry.contains("fun learningMark8372"))
        val native = File("src/main/kotlin/com/lifecyclebot/engine/DecisionReasons8031.kt").readText()
        assertTrue(native.contains("CrossAssetCortex7931.observeNativeLong8372"))
    }
    @Test fun yesAndNoFromEverySpecialistAreBothLearningObservations() {
        val lanes = listOf("QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "EXPRESS", "CORE", "MOONSHOT",
            "PROJECT_SNIPER", "DIP_HUNTER", "MANIPULATED", "TREASURY", "CASHGEN")
        for (lane in lanes) {
            val yes = NativeDecisionEnrollment8372.observation(lane, true, true, 71, "qualified")!!
            val no = NativeDecisionEnrollment8372.observation(lane, false, true, 24, "MCAP too low: 123")!!
            assertEquals(lane, yes.lane)
            assertEquals(lane, no.lane)
            assertTrue(yes.admitted)
            assertFalse(no.admitted)
            assertEquals("NATIVE_READY", yes.reason)
            assertEquals("NATIVE_MCAP_TOO_LOW", no.reason)
            assertEquals(71, yes.score)
            assertEquals(24, no.score)
        }
    }

    @Test fun deferredMissingDataAndErrorsAreNotTurnedIntoLosingPredictions() {
        assertNull(NativeDecisionEnrollment8372.observation("EXPRESS", false, false, 0, "HYDRATING"))
        assertNull(NativeDecisionEnrollment8372.observation("EXPRESS", true, false, 80, "BRAIN_ERROR"))
        assertNull(NativeDecisionEnrollment8372.observation("", true, true, 80, "qualified"))
    }

    @Test fun noOwnerOrFdgOutcomeIsNeededToOfferThePrediction() {
        val observed = NativeDecisionEnrollment8372.observation("express", true, true, 75, "qualified")!!
        assertEquals("EXPRESS", observed.lane)
        // The API intentionally has no ownerSelected, liveIntent, FDG or execution-state argument.
        assertTrue(observed.admitted)
    }

    @Test fun nativeYesIsARecordedPredictionNotAFillBasis() {
        val ready = NativeDecisionEnrollment8372.observation("EXPRESS", true, true, 80, "qualified")!!
        assertTrue(ready.admitted)
        assertFalse(NativeDecisionEnrollment8372.executionAdmission(ready.admitted, ready.reason))
        assertFalse(NativeDecisionEnrollment8372.executionAdmission(false, "FDG_REFUSED"))
        assertTrue(NativeDecisionEnrollment8372.executionAdmission(true, null))
        assertTrue(NativeDecisionEnrollment8372.executionAdmission(true, "FDG_ALLOWED"))
    }

    @Test fun realAdmissionBindsSeparatelyWithoutRewritingAnEarlierNativeYesOrNo() {
        for (prior in listOf("NATIVE_READY", "NATIVE_MCAP_TOO_LOW")) {
            assertTrue(NativeDecisionEnrollment8372.bindExecutionWithoutReplacingObservation(true, null, prior))
            assertTrue(NativeDecisionEnrollment8372.bindExecutionWithoutReplacingObservation(true, "FDG_ALLOW", prior))
            assertFalse(NativeDecisionEnrollment8372.bindExecutionWithoutReplacingObservation(true, "NATIVE_READY", prior))
            assertFalse(NativeDecisionEnrollment8372.bindExecutionWithoutReplacingObservation(false, "FDG_REFUSE", prior))
        }
        assertFalse(NativeDecisionEnrollment8372.bindExecutionWithoutReplacingObservation(true, null, "FDG_REFUSE"))
        assertFalse(NativeDecisionEnrollment8372.bindExecutionWithoutReplacingObservation(true, null, null))
    }

    @Test fun completeNativeSnapshotIsPublishedBeforeLearningOrShadowReadsIt() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/SpecialistBrainBridge7542.kt").readText()
        val publish = src.indexOf("cache[ts.mint] = Cached(snapshot8372)")
        val enroll = src.indexOf("DecisionReasons8031.nativeVerdicts8031(ts, snapshot8372.opinions.values)")
        val shadow = src.indexOf("ExploreShadow8347.onOpinions8347(ts, snapshot8372.opinions, now)")
        assertTrue("A complete decision is cached before observers read it", publish >= 0 && publish < enroll && publish < shadow)
        assertTrue(src.contains("return snapshot8372.also"))
    }
}
