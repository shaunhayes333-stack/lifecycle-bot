package com.lifecyclebot.engine
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7614CashgenCanonicalExecutionIdentityTest {
    private fun bot()=File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()

    @Test fun cashgenClaimStaysCashgen() {
        val s=bot()
        val b=s.substringAfter("val huntClaim7297").substringBefore("val roleFitPrimary6614")
        assertFalse(b.contains("if (it == \"CASHGEN\") \"TREASURY\""))
    }

    @Test fun compounderIdentityFlowsThroughCanonicalStages() {
        val s=bot()
        val b=s.substringAfter("val compounderLane7614").substringBefore("// BLUE CHIP TRADER")
        assertTrue(b.contains("cyclePrimaryLane.equals(\"CASHGEN\", true)"))
        // V5.0.7835 — FDG runs under the compounder lane and its decision is sealed by
        // TradeAuthorizer (SpecialistPreauthSeal7834), not by a BotService-side recordFdg.
        assertTrue(b.contains("specialistLane = compounderLane7614,"))
        assertTrue(b.contains("fdgDecision7835 = treasuryFdg, tokenState7835 = ts,"))
        assertTrue(b.contains("requestedBook = executionBookForLane6494(compounderLane7614)"))
        assertTrue(b.contains("layer = compounderLane7614"))
        assertTrue(b.contains("ts.position.tradingMode = compounderLane7614"))
        assertFalse(b.contains("alias=TREASURY_CASHGEN_SHARED_EXEC no_fdg=true"))
    }
}
