package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.CloseLease
import com.lifecyclebot.engine.sell.StopSlip8327
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class Aate9062StopClockTest {
    @After fun cleanup() = StopSlip8327.resetForTest8327()

    private fun trigger(mint: String, at: Long) = StopSlip8327.noteTrigger8327(
        mint, "RAPID_CATASTROPHE_STOP", .8, 1.0, at, "CURVE_FRAME", at)

    @Test fun rejectedUnsignedStopCannotPolluteARearmedStopsClock() {
        val mint = "clock9062-${UUID.randomUUID()}"
        trigger(mint, 1_000)
        val old = StopSlip8327.triggerFor9062(mint)!!
        val lease = CloseLease.acquire(mint, "test", "RAPID_CATASTROPHE_STOP")!!
        try {
            assertFalse(CloseLease.invalidateSignal8521(mint, "RAPID_CATASTROPHE_STOP", true, lease) {
                fail("a signed/owned sell must retain its clock")
            })
            assertSame(old, StopSlip8327.triggerFor9062(mint))
            assertTrue(CloseLease.invalidateSignal8521(mint, "RAPID_CATASTROPHE_STOP", false, lease) {
                assertTrue(StopSlip8327.invalidateUnbroadcast9062(mint, old))
            })
            trigger(mint, 20_000)
            val next = StopSlip8327.triggerFor9062(mint)!!
            assertEquals(20_000L, next.atMs)
            assertFalse(StopSlip8327.invalidateUnbroadcast9062(mint, old))
            assertSame(next, StopSlip8327.triggerFor9062(mint))
            assertEquals(2_000L, StopSlip8327.slip8327(next.pnlPct, -25.0, next.atMs, 22_000L).second)
        } finally { CloseLease.release(mint, "TEST_END") }
    }

    @Test fun realRetriesKeepTheirFirstTriggerAndCleanupIsWiredToTheLease() {
        val mint = "retry9062-${UUID.randomUUID()}"
        trigger(mint, 1_000); trigger(mint, 5_000)
        assertEquals(1_000L, StopSlip8327.triggerFor9062(mint)!!.atMs)
        val source = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
            .substringAfter("private fun invalidateExitSignal8521(").substringBefore("data class PartialSellReceipt6566")
        assertTrue(source.contains("val rejectedStop9062 = com.lifecyclebot.engine.sell.StopSlip8327.triggerFor9062(ts.mint)"))
        assertTrue(source.substringAfter("CloseLease.invalidateSignal8521").contains("StopSlip8327.invalidateUnbroadcast9062(ts.mint, rejectedStop9062)"))
    }
}
