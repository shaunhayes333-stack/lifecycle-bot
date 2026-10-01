package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7677ExpressOwnershipContractTest {
    @Test fun expressReusesSealedSameLaneAttemptBeforeFallback() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val block = s.substringAfter("val expressAttemptId7389 =")
            .substringBefore("val authResult = TradeAuthorizer.authorize(")
        assertTrue(block.contains("sealedSpecialistAttempt7468("))
        assertTrue(block.contains("nextAttemptId(ts.mint, \"EXPRESS\")"))
    }

    @Test fun expressRemainsDistinctNativeSpecialist() {
        val b = File("src/main/kotlin/com/lifecyclebot/engine/SpecialistBrainBridge7542.kt").readText()
        assertTrue(b.contains("out[\"EXPRESS\"]"))
        assertTrue(b.contains("ShitCoinExpress.evaluate"))
        assertTrue(b.contains("express_native_"))
        assertTrue(b.contains("out[\"PROJECT_SNIPER\"]"))
    }

    @Test fun sourceItemsClosedButRuntimeAcceptanceStaysOpen() {
        val a = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertFalse(a.contains("- [ ] Find why EXPRESS can reach FDG accounting"))
        assertFalse(a.contains("- [ ] Require same-lane owner/intent proof"))
        assertTrue(a.contains("- [ ] Runtime acceptance: EXPRESS ownerSelected/buyIntent become non-zero"))
    }
}
