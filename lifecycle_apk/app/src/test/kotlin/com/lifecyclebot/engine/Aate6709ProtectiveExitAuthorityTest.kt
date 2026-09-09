package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.6709: canonical SL sign/magnitude authority must remain coherent. */
class Aate6709ProtectiveExitAuthorityTest {

    @Test
    fun `auto mode passes signed negative threshold to fluid learner and returns magnitude`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/AutoModeEngine.kt").readText()
        val block = src.substringAfter("private fun fluidStop(modeDefaultStop: Double)")
            .substringBefore("private fun fluidTrailing")
        assertTrue(block.contains("getFluidStopLoss(-magnitude6709)"))
        assertTrue(block.contains("kotlin.math.abs(signed6709)"))
        assertFalse(block.contains("getFluidStopLoss(modeDefaultStop)"))
    }

    @Test
    fun `executor canonical stop price cannot be disabled by signed learned stop`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val risk = src.substringAfter("fun riskCheck(ts: TokenState")
            .substringBefore("PROTECTIVE_EXIT_DELIVERY_ERROR_6600")
        assertTrue(risk.contains("effStopPctRaw6709"))
        assertTrue(risk.contains("val effStopPct = kotlin.math.abs(effStopPctRaw6709)"))
        assertTrue(risk.contains("PROTECTIVE_EXIT_STOP_SIGN_NORMALIZED_6709"))
        assertTrue(risk.contains("effStopPct.isFinite() && effStopPct > 0.0"))
    }
}
