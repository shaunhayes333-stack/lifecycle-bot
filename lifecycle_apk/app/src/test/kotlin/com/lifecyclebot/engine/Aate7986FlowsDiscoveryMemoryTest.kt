package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CapitalDrawdown7948
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7986FlowsDiscoveryMemoryTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun tradeLagIsNotAnExternalFlow() {
        assertEquals(false, CapitalDrawdown7948.confirmFlow7986(5L, 6L, 0L, 1_000L))   // a journal write explains it
        assertNull(CapitalDrawdown7948.confirmFlow7986(5L, 5L, 0L, 10_000L))           // not stood long enough
        assertEquals(true, CapitalDrawdown7948.confirmFlow7986(5L, 5L, 0L, 45_000L))    // owner moved SOL
    }

    @Test fun newMintsAreCountedAtAccept() {
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("markAccepted7986(mint, prevAt, nowMs)"))
        assertTrue(bot.contains("if (prevAt == null) try { com.lifecyclebot.engine.truth.DiscoveryRate7984.note7984(mint, true, nowMs) }"))
    }

    @Test fun chartTapesBounded() {
        val cr = src("engine/chart/ChartReader7950.kt")
        assertTrue(cr.contains("MAX_MINTS = 1_000"))
        assertTrue(cr.contains("tapes.entries.removeIf { now - it.value.lastMs > 15L * 60_000L }"))
    }
}
