package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * V5.0.7821 — Moonshot fluid identity + exact executable attempt continuity.
 */
class Aate7821MoonshotFluidIdentityAndAttemptTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun moonshot_election_uses_same_fluid_floor_as_native_scorer() {
        val s = src("engine/LaneEntryContract6342.kt")
        val fn = s.substringAfter("fun isLaneIdentityEligible7252(")
            .substringBefore("/** MINT_ROUTE placeholder")
        assertTrue(fn.contains("MoonshotTraderAI.minMarketCapUsdFluid7719()"))
        assertTrue(fn.contains("MoonshotTraderAI.MAX_MARKET_CAP_USD"))
        assertFalse(fn.contains("ts.lastMcap < com.lifecyclebot.v3.scoring.MoonshotTraderAI.MIN_MARKET_CAP_USD"))
    }

    @Test fun executable_stages_join_real_sealed_attempt_not_synthetic_alias() {
        val s = src("engine/ToolkitSignalSheet.kt")
        val region = s.substringAfter("val expectedIntentId6647 = when {")
            .substringBefore("val resolvedMode6858")
        assertTrue(region.contains("sealedIntent7471?.attemptId"))
        assertTrue(region.contains("boundLineage7807 != null && attemptId.isNotBlank()"))
        assertTrue(region.contains("canonicalAttempt6647 && attemptId.isNotBlank()"))
        assertTrue(region.contains("else -> \"\$mint:\$candidateVersion6647:\$lane\""))
    }
}
