package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.MissingMarkExitVeto6835
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8008RugReactionTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun aRunnerHoldNeverHoldsACatastrophe() {
        assertFalse(RunnerGrab7967.deferrable7967("RAPID_CATASTROPHE_STOP", -20.0))
        assertFalse(RunnerGrab7967.deferrable7967("TICK_CATASTROPHIC_CONFIRMED_-30PCT", -30.0))
        assertTrue(RunnerGrab7967.deferrable7967("TICK_HARD_FLOOR_-20PCT", -20.0))   // the shakeout hold stays
    }

    @Test fun aCatastropheThatFiredFreshIsNotVetoedOnItsRetries() {
        MissingMarkExitVeto6835.clearForTest()
        try {
            val now = System.currentTimeMillis()
            assertTrue(MissingMarkExitVeto6835.evaluate("rug8008", 0.5, now, "RAPID_CATASTROPHE_STOP").allow)
            val retry = MissingMarkExitVeto6835.evaluate("rug8008", 0.5, now - 5 * 60_000L, "RAPID_CATASTROPHE_STOP")
            assertTrue(retry.allow)
            assertEquals("CATASTROPHE_RETRY_AFTER_FRESH_FIRE_8008", retry.reason6835)
            // a stale catastrophe that never fired fresh is still held (phantom-mark protection)
            assertFalse(MissingMarkExitVeto6835.evaluate("phantom8008", 0.5, now - 5 * 60_000L, "RAPID_CATASTROPHE_STOP").allow)
        } finally { MissingMarkExitVeto6835.clearForTest() }
    }

    @Test fun pushedFallsWakeTheStopSweepAndStopsSellOffTheSweep() {
        val b = src("engine/BotService.kt")
        assertTrue(b.contains("kotlinx.coroutines.withTimeoutOrNull(CHECK_INTERVAL_MS) { rapidWake8008.receive() }"))
        assertTrue(b.contains("try { wakeRapidOnFall8008(ts, px) } catch (_: Throwable) {}"))
        assertTrue(b.contains("requestSellOffLoop7288(ts, \"RAPID_CATASTROPHE_STOP\", wallet, effectiveBalance)"))
        assertTrue(b.contains("requestSellOffLoop7288(ts, \"RAPID_HARD_FLOOR_STOP\", wallet, effectiveBalance)"))
        assertTrue(b.contains("requestSellOffLoop7288(ts, \"DEEP_CATASTROPHE_NET\", wallet, effectiveBalance)"))
        assertTrue(b.contains("\"DEV_DUMP_PUSH_\${pctInt}PCT_8008\""))
        assertTrue(com.lifecyclebot.engine.sell.ProtectiveExitClass7807.isEmergency("DEV_DUMP_PUSH_40PCT_8008"))
        assertTrue(src("network/HeliusWebSocket.kt").contains("val pool = if (isHeld8008(mint)) heldCallbacks8008 else callbacks7863"))
    }
}
