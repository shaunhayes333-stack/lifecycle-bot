package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7851TradeAuthorizerSealTest {
    @Test fun promotion_cannot_reject_after_exact_seal() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/TradeAuthorizer.kt").readText()
        val afterSeal = s.substringAfter("SpecialistPreauthSeal7834.ensure")
        val promotion = afterSeal.substringAfter("// V5.0.7851 — the promotion gate")
            .substringBefore("// PASS: authorize execution")
        assertTrue(promotion.contains("TRADE_AUTH_PROMOTION_POST_SEAL_ADVISORY_7851"))
        assertFalse(promotion.contains("return AuthorizationResult("))
        assertFalse(promotion.contains("return rejectAuth4424("))
    }

    @Test fun hard_post_seal_execution_constraints_remain() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/TradeAuthorizer.kt").readText()
        val afterSeal = s.substringAfter("SpecialistPreauthSeal7834.ensure")
        assertTrue(afterSeal.contains("BannedTokens.isBanned"))
        assertTrue(afterSeal.contains("LiveSafetyCircuitBreaker.isTripped()"))
        assertTrue(afterSeal.contains("rugcheckScore <= 0"))
        assertTrue(afterSeal.contains("ALREADY_OPEN_CROSS_BOOK"))
    }
}
