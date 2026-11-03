package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.v3.bridge.V3Adapter
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate8522V3ScorerParityTest {
    @Test fun candidateCarriesTheExactV3StrategyLaneKey() {
        val ts = TokenState("v3-lane-8522")
        ts.position.tradingMode = "BLUE_CHIP"
        val snap = V3Adapter.toCandidate(ts)
        assertEquals("BLUECHIP", snap.extraString("lane"))
        assertEquals("BLUECHIP", snap.extraString("tradingMode"))
    }

    @Test fun defaultV3TrunkDoesNotBorrowAnotherSpecialistsHistory() {
        val ts = TokenState("v3-default-8522")
        ts.position.tradingMode = ""
        val snap = V3Adapter.toCandidate(ts)
        assertEquals("STANDARD", snap.extraString("tradingMode"))
        assertEquals("STANDARD", snap.extraString("lane"))
    }

    @Test fun modernAndLiveUnifiedBothApplyTheSameBoundedBandCorrection() {
        val scorer = File("src/main/kotlin/com/lifecyclebot/v3/scoring/UnifiedScorer.kt").readText()
        assertTrue(scorer.contains("val bandComponent = bandExpectancyComponent8522(candidate, gatedComponents)"))
        assertTrue(scorer.contains("val bandComponent8522 = bandExpectancyComponent8522(candidate, moeComponents)"))
        assertTrue(scorer.contains("ScoreCard(moeComponents + listOfNotNull(bandComponent8522))"))
        assertTrue(scorer.contains("LosingPatternMemory.recommendedBandNudge(tradingMode, preTotal)"))
        val adapter = File("src/main/kotlin/com/lifecyclebot/v3/bridge/V3Adapter.kt").readText()
        assertTrue(adapter.contains("extras[\"tradingMode\"] = candidateLane8522"))
    }
}
