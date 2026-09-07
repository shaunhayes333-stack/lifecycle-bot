package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate6684AdaptiveClosedLoopTest {
    private fun src(path: String) = File("src/main/kotlin/$path").readText()

    @Test fun `adaptive runtime durably starts lab sentience and ssi`() {
        val app = src("com/lifecyclebot/AATEApp.kt")
        val rt = src("com/lifecyclebot/engine/AdaptiveIntelligenceRuntime6684.kt")
        assertTrue(app.contains("AdaptiveIntelligenceRuntime6684.start(applicationContext)"))
        assertTrue(rt.contains("LlmLabEngine.start(app)"))
        assertTrue(rt.contains("LlmLabEngine.tick { buildUniverse() }"))
        assertTrue(rt.contains("SentienceOrchestrator.start(app)"))
        assertTrue(rt.contains("SsiPilotCouncil.start()"))
        assertTrue(rt.contains("SentienceHooks.maybeAutoTune(app)"))
    }

    @Test fun `failed lane only reenters through exact lab proof`() {
        val reproof = src("com/lifecyclebot/engine/AdaptiveLaneReproof6684.kt")
        val pause = src("com/lifecyclebot/engine/LaneAutoPauseGuard.kt")
        val shadow = src("com/lifecyclebot/engine/LaneShadowProofLoop.kt")
        val ssi = src("com/lifecyclebot/engine/SsiPilotCouncil.kt")
        assertTrue(reproof.contains("proofPasses(s: LabStrategy)"))
        assertTrue(reproof.contains("LaneAutoPauseGuard.manualResume("))
        assertTrue(reproof.contains("activationEpochMs"))
        assertTrue(pause.contains("AdaptiveLaneReproof6684.onLaneFailed(lane, reason)"))
        assertTrue(pause.contains("t.ts < promotionEpoch6684"))
        assertFalse(pause.contains("LANE_AUTO_RECOVERED_6305"))
        assertFalse(shadow.contains("resumeBlacklist"))
        assertFalse(ssi.contains("SSI_PILOT_LANE_RESUMED_6090"))
        assertTrue(ssi.contains("SSI_PILOT_REPROOF_REQUEST_6684"))
    }

    @Test fun `targeted lab strategy is lane local and actuated`() {
        val feed = src("com/lifecyclebot/engine/lab/LabPromotedFeed.kt")
        val open = src("com/lifecyclebot/engine/ExecutableOpenGate.kt")
        val size = src("com/lifecyclebot/engine/truth/OrderSizeResolver6441.kt")
        val hold = src("com/lifecyclebot/engine/HoldingLogicLayer.kt")
        val bucket = src("com/lifecyclebot/engine/BucketExecutionState.kt")
        assertTrue(feed.contains("AdaptiveLaneReproof6684.isTargetedStrategy"))
        assertTrue(open.contains("EXEC_OPEN_LAB_STRATEGY_WAIT_6684"))
        assertTrue(size.contains("AdaptiveLaneReproof6684.sizeMultiplierForLane"))
        assertTrue(size.contains("SsiPilotCouncil.sizeMultiplierForLane"))
        assertTrue(hold.contains("AdaptiveLaneReproof6684.exitStrategy(mode)"))
        assertTrue(bucket.contains("AdaptiveLaneReproof6684.activeStrategy"))
    }

    @Test fun `all meme specialist roles participate in reproof ring`() {
        val reproof = src("com/lifecyclebot/engine/AdaptiveLaneReproof6684.kt")
        listOf(
            "QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "EXPRESS", "CORE",
            "MOONSHOT", "PROJECT_SNIPER", "PRESALE_SNIPE", "DIP_HUNTER",
            "MANIPULATED", "TREASURY", "CASHGEN",
        ).forEach { assertTrue("$it missing from reproof ring", reproof.contains("\"$it\"")) }
    }
}
