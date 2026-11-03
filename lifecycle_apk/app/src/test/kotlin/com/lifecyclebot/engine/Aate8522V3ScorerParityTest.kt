package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.v3.bridge.V3Adapter
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate8522V3ScorerParityTest {
    @Test fun candidateCarriesTheExactV3StrategyLaneKey() {
        val ts = TokenState("11111111111111111111111111111112")
        com.lifecyclebot.engine.truth.AdmissionBirth8144.noteChainMintInit8179(ts.mint, System.currentTimeMillis() - 60_000L)
        ts.position.tradingMode = "BLUE_CHIP"
        val snap = V3Adapter.toCandidate(ts)
        assertEquals("BLUECHIP", snap.extraString("lane"))
        assertEquals("BLUECHIP", snap.extraString("tradingMode"))
    }

    @Test fun defaultV3TrunkDoesNotBorrowAnotherSpecialistsHistory() {
        val ts = TokenState("11111111111111111111111111111113")
        com.lifecyclebot.engine.truth.AdmissionBirth8144.noteChainMintInit8179(ts.mint, System.currentTimeMillis() - 60_000L)
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
