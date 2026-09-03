package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Regression barriers for the operator failures captured on 5.0.6654. */
class Aate6655RegressionBarrierTest {

    private fun decision(executable: Boolean) = FinalDecisionGate.FinalDecision(
        shouldTrade = executable,
        mode = FinalDecisionGate.TradeMode.PAPER,
        approvalClass = if (executable) FinalDecisionGate.ApprovalClass.LIVE else FinalDecisionGate.ApprovalClass.BLOCKED,
        quality = "TEST",
        confidence = 80.0,
        edge = if (executable) FinalDecisionGate.EdgeVerdict.STRONG else FinalDecisionGate.EdgeVerdict.SKIP,
        blockReason = if (executable) null else "WAIT",
        blockLevel = if (executable) null else FinalDecisionGate.BlockLevel.EDGE,
        sizeSol = if (executable) 0.05 else 0.0,
        tags = emptyList(),
        mint = "mint",
        symbol = "TEST",
        approvalReason = "test",
        gateChecks = emptyList(),
    )

    @Test
    fun `executable FDG verdict is never reusable without its exact intent`() {
        FdgReEvalThrottle.clear()
        FdgReEvalThrottle.put("mint", 1L, "CORE", "evidence", 60, decision(true))
        assertEquals(0, FdgReEvalThrottle.size())
        assertNull(FdgReEvalThrottle.get("mint", 1L, "CORE", "evidence", 60))

        FdgReEvalThrottle.put("mint", 1L, "CORE", "evidence", 60, decision(false))
        assertFalse(FdgReEvalThrottle.get("mint", 1L, "CORE", "evidence", 60)!!.canExecute())
        FdgReEvalThrottle.clear()
    }

    @Test
    fun `meme trunk seals intent before authorizer and consumes same attempt`() {
        val bot = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val seal = bot.indexOf("val sealedIntent6655 = ExecutableOpenGate.recordFdgAndGetIntent6533")
        val auth = bot.indexOf("val authResult = TradeAuthorizer.authorize", seal)
        assertTrue(seal >= 0 && auth > seal)
        val authEnd = bot.indexOf("ErrorLogger.info", auth)
        assertTrue(bot.substring(auth, authEnd).contains("attemptId = sealedIntent6655.attemptId"))
    }

    @Test
    fun `held positions are outside discovery cap and UI shed cannot latch forever`() {
        val registry = File("src/main/kotlin/com/lifecyclebot/engine/GlobalTradeRegistry.kt").readText()
        val main = File("src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt").readText()
        val account = File("src/main/kotlin/com/lifecyclebot/engine/truth/UnifiedAccountSnapshot6635.kt").readText()
        assertTrue(registry.contains("fun discoveryWatchlistSize6655"))
        assertTrue(registry.contains("!incomingIsHeld6655 && discoverySize6655 >= MAX_WATCHLIST_SIZE"))
        assertTrue(registry.contains("heldRetained"))
        assertTrue(main.contains("newAnrHint6655"))
        assertTrue(main.contains("nowForRenderShed + 4_000L"))
        assertFalse(main.contains("anrHintsForRenderShed >= 100"))
        val readBody = account.substring(account.indexOf("fun read("), account.indexOf("fun lastSnapshot"))
        assertFalse(readBody.contains("ForensicReconciliation6635.reconcile6635()"))
    }
}
