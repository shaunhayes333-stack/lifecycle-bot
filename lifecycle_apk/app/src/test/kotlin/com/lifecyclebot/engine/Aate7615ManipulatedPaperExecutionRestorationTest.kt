package com.lifecyclebot.engine
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7615ManipulatedPaperExecutionRestorationTest {
    private fun bot()=File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()

    @Test fun paperCanUseManipulatedPathButLiveConstantRemainsClosed() {
        val s=bot()
        assertTrue(s.contains("MANIPULATED_LIVE_BUYER_7395 = false"))
        assertTrue(s.contains("RuntimeModeAuthority.isPaper() || MANIPULATED_LIVE_BUYER_7395"))
        assertTrue(s.contains("manipSignal.shouldEnter && manipulatedBuyerEnabled7609()"))
        assertFalse(s.contains("manipSignal.shouldEnter && MANIPULATED_IS_A_BUYER_7395"))
    }

    @Test fun manipulatedStillUsesItsOwnCanonicalBookAndLane() {
        val s=bot()
        val b=s.substringAfter("☠️ THE MANIPULATED").substringBefore("END ManipulatedTraderAI evaluation")
        assertTrue(b.contains("specialistLane = \"MANIPULATED\""))
        assertTrue(b.contains("TradeAuthorizer.ExecutionBook.MANIPULATED"))
        assertTrue(b.contains("executionLane = \"MANIPULATED\""))
        assertTrue(b.contains("ts.position.tradingMode = \"MANIPULATED\""))
    }
}
