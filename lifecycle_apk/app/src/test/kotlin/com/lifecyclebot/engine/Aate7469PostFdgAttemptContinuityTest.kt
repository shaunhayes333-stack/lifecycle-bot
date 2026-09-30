package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7469PostFdgAttemptContinuityTest {
    private fun bot() = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()

    @Test fun express_reuses_sealed_attempt_before_fresh_fallback() {
        val s = bot()
        val block = s.substringAfter("val expressAttemptId7389 =")
            .substringBefore("val authResult = TradeAuthorizer.authorize(")
        assertTrue(block.contains("sealedSpecialistAttempt7468("))
        assertTrue(block.contains("nextAttemptId(ts.mint, \"EXPRESS\")"))
    }

    @Test fun shitcoin_reuses_sealed_attempt_before_fresh_fallback() {
        val s = bot()
        val block = s.substringAfter("val shitCoinAttemptId7389 =")
            .substringBefore("val authResult = TradeAuthorizer.authorize(")
        assertTrue(block.contains("sealedSpecialistAttempt7468("))
        assertTrue(block.contains("nextAttemptId(ts.mint, \"SHITCOIN\")"))
    }

    @Test fun project_sniper_standalone_attempt_remains_independent() {
        val s = bot()
        assertTrue(s.contains("val sniperAttemptId6842 = try {"))
        assertTrue(s.contains("ExecutableOpenGate.nextAttemptId(ts.mint, \"PROJECT_SNIPER\")"))
    }
}
