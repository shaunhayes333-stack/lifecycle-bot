package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.MemePaperTrader8544
import com.lifecyclebot.engine.truth.PaperExitLatency8223
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8699 — a paper stop waits the latency live's stops are measured at. 8689: `norm=16000ms(n=0,prior) emrg=745ms(n=3)`;
 * every paper exit waited the unmeasured 16 s, and paper stops filled at -23 .. -37 % against live's -5 .. -17 %.
 */
class Aate8699PaperStopLatencyTest {
    @Test fun anUnmeasuredNormalClassBorrowsTheMeasuredSpineNotAnOldPrior() {
        assertEquals(745L, PaperExitLatency8223.normalLatency8699(normalN = 0, normalSumMs = 0, emergencyN = 3, emergencyMeasuredMs = 745L))
        assertEquals("its own measurement wins", 2_000L, PaperExitLatency8223.normalLatency8699(4, 8_000L, 3, 745L))
        assertEquals("nothing measured: the labelled prior", 16_000L, PaperExitLatency8223.normalLatency8699(0, 0, 2, 6_000L))
    }

    @Test fun stopsAreTheProtectiveClass() {
        assertTrue(MemePaperTrader8544.stopExit8699("STOP"))
        assertTrue(MemePaperTrader8544.stopExit8699("TRAIL_STOP"))
        assertFalse(MemePaperTrader8544.stopExit8699("TAKE_PROFIT_T1"))
        assertFalse(MemePaperTrader8544.stopExit8699("CLOCK"))
        assertFalse(MemePaperTrader8544.stopExit8699(null))
        val p = File("src/main/kotlin/com/lifecyclebot/engine/truth/MemePaperTrader8544.kt").readText()
        assertTrue(p.contains("val latency = if (stopExit8699(pending)) stopLatency8699 else normalLatency8699"))
    }
}
