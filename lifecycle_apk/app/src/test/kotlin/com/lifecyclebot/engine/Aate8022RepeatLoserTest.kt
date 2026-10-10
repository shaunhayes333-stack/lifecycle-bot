package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.LiveEdgeGate7877
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8022RepeatLoserTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    private fun cell(n: Int, lost: Int, mean: Double) =
        ForwardReturnLabeler7731.CellStat("X", n, mean, 0.2, 0.05, 2.0, lost)

    @Test fun aMostlyUnpricedCellRefusesInsteadOfAdmitting() {
        // CRYPTO_ALT 8018: n97 +14.6% with 69 lost (58% resolved)
        val v = LiveEdgeGate7877.judge(cell(97, 69, 14.6), laneProven = false)
        assertFalse(v.allow)
        assertTrue(v.why.startsWith("CELL_MOSTLY_UNPRICED_8022"))
        assertFalse(LiveEdgeGate7877.judgeRunner(cell(97, 69, 14.6), emptyList(), false).allow)
        assertNotNull(LiveEdgeGate7877.mostlyUnpriced8022(cell(97, 69, 14.6)))
        assertNull(LiveEdgeGate7877.mostlyUnpriced8022(cell(97, 10, 14.6)))       // 91% resolved: a measurement
        assertTrue(LiveEdgeGate7877.judge(cell(97, 10, 14.6), false).allow)
    }

    @Test fun aCoinThatLostTwiceTodayIsNotBoughtAgain() {
        val now = 10L * 24 * 3_600_000L
        assertEquals(2, RebuyLockout8019.MAX_LOSSES_8022)
        assertEquals(24L * 3_600_000L, RebuyLockout8019.LOSS_MEMORY_MS_8022)
        assertTrue(RebuyLockout8019.lossLocked8022(listOf(now - 60_000L, now - 3_600_000L), now, ticket = false))
        assertFalse(RebuyLockout8019.lossLocked8022(listOf(now - 60_000L), now, false))
        assertFalse(RebuyLockout8019.lossLocked8022(listOf(now - 60_000L, now - 25L * 3_600_000L), now, false))
        assertFalse(RebuyLockout8019.lossLocked8022(listOf(now - 60_000L, now - 120_000L), now, ticket = true))
        RebuyLockout8019.onClose8019("LOSER8022", now - 30 * 60_000L, -12.0)
        RebuyLockout8019.onClose8019("LOSER8022", now - 20 * 60_000L, -5.0)
        assertEquals("REPEAT_LOSER_8022", RebuyLockout8019.refusal8019("LOSER8022", now))
        RebuyLockout8019.onClose8019("WINNER8022", now - 30 * 60_000L, 40.0)
        RebuyLockout8019.onClose8019("WINNER8022", now - 20 * 60_000L, 12.0)
        assertNull(RebuyLockout8019.refusal8019("WINNER8022", now))
        assertTrue(src("perps/CryptoAltTrader.kt").contains(".filter { !DynamicAltTokenRegistry.pumpMint8019(it.mint) || it.isStatic }  // V5.0.8022"))
    }
}
