package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.truth.BasePocket8904
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8905 — (1) a coin the base pocket could buy stays on the hot watchlist for its first six hours; (2) the brief names
 * what the Cortex ledgers hold (seats, voters, lanes, regimes, empty seats), the largest store in the 21 Nov heap map.
 */
class Aate8905BaseWatchCensusTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val other = "7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmW2hr"

    @Test fun theBaseWatchKeepsOffPumpFunMidPoolsForSixHours() {
        assertTrue(BasePocket8904.watches8904(other, 45_000.0, 60_000L))
        assertFalse("after six hours", BasePocket8904.watches8904(other, 45_000.0, BasePocket8904.BASE_WATCH_MS_8904))
        assertFalse(BasePocket8904.watches8904("8KdKRqDWzDsrSrxo4mGA8e48KbG2JK9XgnVEFVXfpump", 45_000.0, 60_000L))
        assertFalse(BasePocket8904.watches8904(other, 4_000.0, 60_000L))   // V5.0.8921 — the floor is $5k
        assertFalse("pool unread", BasePocket8904.watches8904(other, Double.NaN, 60_000L))
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("if (entry.processCount >= STALE_PROCESS_COUNT_THRESHOLD && !baseWatch8904(mint, entry.addedAt)) {"))
        assertTrue(bot.contains("if (entry.processCount >= 1 && (nowMs - lastTouch) > STALE_AGE_MS && !baseWatch8904(mint, entry.addedAt)) {"))
    }

    @Test fun theCensusCountsSeatsVotersLanesRegimesAndEmptySeats() {
        val l = CortexLedger7885()
        l.seat("v1", "CORE", 5); l.seat("v1", "CORE@HOT", 5); l.seat("v2", "EXPRESS", 5)
        l.seat("v2", "EXPRESS", 5).bins[0].n = 3.0
        assertEquals("seats=3 voters=2 lanes=2 regimes=1 regimeSeats=1 empty=2 sidesMade8924=0/6", l.census8904(this))   // V5.0.8924 — and the sides made
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("cortexSeats8904[timing{"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 93. The base watch and the Cortex census (8905)"))
    }
}
