package com.lifecyclebot.engine

import com.lifecyclebot.engine.market.GroqTokenScout7830
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7864SizeDownNotHaltTest {

    @Test fun computedRiskLimitsAreSizeDownNotLatchedHalts() {
        assertTrue(KillSwitch.computedLimitLatch7864("MAX_DRAWDOWN: Drawdown 40% exceeded limit 25%"))
        assertTrue(KillSwitch.computedLimitLatch7864("MAX_DAILY_LOSS: Daily loss 16%"))
        assertTrue(KillSwitch.computedLimitLatch7864("MAX_CONSECUTIVE_LOSSES: 5 consecutive"))
        assertFalse(KillSwitch.computedLimitLatch7864("MANUAL: operator stop"))
        assertFalse(KillSwitch.computedLimitLatch7864(""))
    }

    @Test fun killSwitchNoLongerTriggersOnComputedLimits() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/KillSwitch.kt").readText()
        assertFalse(src.contains("triggerKill(context, \"MAX_DRAWDOWN\""))
        assertFalse(src.contains("triggerKill(context, \"MAX_DAILY_LOSS\""))
        assertFalse(src.contains("triggerKill(context, \"MAX_CONSECUTIVE_LOSSES\""))
        assertFalse(src.contains("DRAWDOWN_NEAR_LIMIT"))
        assertTrue(src.contains("SIZE_DOWN_NOT_HALT_7864"))
    }

    @Test fun groqScoutFallsBackToBrowserSearchModel() {
        val ids = listOf("llama-3.3-70b-versatile", "openai/gpt-oss-20b", "openai/gpt-oss-120b")
        assertNull(GroqTokenScout7830.selectCompoundModel7832(ids))
        assertEquals("openai/gpt-oss-120b", GroqTokenScout7830.selectBrowserSearchModel7864(ids))
        assertEquals("openai/gpt-oss-20b", GroqTokenScout7830.selectBrowserSearchModel7864(listOf("openai/gpt-oss-20b")))
        assertNull(GroqTokenScout7830.selectBrowserSearchModel7864(listOf("llama-3.1-8b-instant")))
        assertTrue(GroqTokenScout7830.usesBrowserSearchTool7864("openai/gpt-oss-120b"))
        assertFalse(GroqTokenScout7830.usesBrowserSearchTool7864("groq/compound"))
    }
}
