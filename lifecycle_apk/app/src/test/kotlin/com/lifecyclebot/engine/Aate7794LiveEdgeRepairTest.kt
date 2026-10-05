package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7794LiveEdgeRepairTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/" + path).readText()

    @Test fun heliusSubscriptionsAreBoundedAndEvictOldest() {
        val s = src("network/HeliusWebSocket.kt")
        assertTrue(s.contains("MAX_TOKEN_SUBSCRIPTIONS_7794 = 128"))
        assertTrue(s.contains("HELIUS_WS_TOKEN_SUB_EVICTED_7794"))
        assertTrue(s.contains("logsUnsubscribe"))
        assertTrue(s.contains("tokenSnapshot = synchronized(subscriptions)"))
    }

    @Test fun liveMatureNegativeScoreBandsAreNoLongerBypassed() {
        val s = src("engine/ScoreExpectancyTracker.kt")
        val block = s.substringAfter("fun shouldReject").substringBefore("fun calibrationSizeMult")
        assertTrue(block.contains("LIVE_EXPECTANCY_REJECT_HONOURED_7794"))
        assertFalse(block.contains("LIVE_EXPECTANCY_REJECT_BYPASSED"))
        assertTrue(block.contains("mean < REJECT_MEAN_PNL_PCT"))
    }

    @Test fun projectSniperOwnsOnlyFlowProvenPreIgnition() {
        val s = src("v3/scoring/ProjectSniperAI.kt")
        assertTrue(s.contains("phase != com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.PRE_IGNITION"))
        assertTrue(s.contains("distinctBuyers60s < 3"))
        assertTrue(s.contains("!launch7449.accelerationRising"))
        assertTrue(s.contains("launch7449.buySharePct < 60.0"))
        assertTrue(s.contains("SNIPER_PREIGNITION_FLOW_NOT_PROVEN_7794"))
    }
}
