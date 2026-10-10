package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.DiscoveryRate7984
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7984DiscoveryTest {

    @Test fun watchedMintsHeldToFiveMinutes() {
        assertEquals(30_000L, intakeDedupTtl7984(false, 30_000L))
        assertEquals(300_000L, intakeDedupTtl7984(true, 30_000L))
        val bot = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bot.contains("(nowMs - prevAt) < dedupTtl7984(mint, prevAt, nowMs)"))
    }

    @Test fun newVersusRepeatIsCounted() {
        assertEquals(25, DiscoveryRate7984.newSharePct7984(1, 3))
        assertEquals(0, DiscoveryRate7984.newSharePct7984(0, 0))
        val t = 1_800_000_000_000L
        DiscoveryRate7984.note7984("discA7984", true, t)
        DiscoveryRate7984.note7984("discA7984", false, t + 1_000L)
        DiscoveryRate7984.note7984("discB7984", true, t + 2_000L)
        val line = DiscoveryRate7984.line7984(t + 3_000L)
        assertTrue(line, line.contains("last10m new=2 repeat=1"))
    }
}
