package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.LabelClock8442
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8442 — the 8439 run priced 1254 of 9657 due label marks: the labeler (and the off-watch price batch it asks) ran
 * only at the bot loop's pace (24 s a cycle). Labels now tick on their own 5 s clock, the batch is wider, and open shadow
 * tickets' mints ride the same batch so their outcomes stop closing LOST_MARK.
 */
class Aate8442LabelClockTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun labelsTickOnTheirOwnClockWhileTheLoopLives() {
        assertTrue(LabelClock8442.shouldTick8442(1_000_000L, 1_000_000L - 30_000L))
        assertFalse("bot stopped: the clock idles", LabelClock8442.shouldTick8442(1_000_000L, 1_000_000L - LabelClock8442.LOOP_STALE_MS_8442 - 1L))
        assertFalse("never beat", LabelClock8442.shouldTick8442(1_000_000L, 0L))
        assertEquals(5_000L, LabelClock8442.PERIOD_MS_8442)
        val bs = src("engine/BotService.kt")
        val d = bs.indexOf("private fun dispatchLearningTicks8020(")
        assertTrue(bs.indexOf("LabelClock8442.noteLoop8442(nowShadow7307)", d) in d until bs.indexOf("learningTickInFlight8020.compareAndSet(false, true)", d))
        assertTrue(LabelClock8442.statusLine8442().startsWith("labelClock8442[period=5s"))
    }

    @Test fun twoDriversNeverTickTheLabelerAtOnce() {
        val l = src("engine/truth/ForwardReturnLabeler7731.kt")
        val tick = l.substring(l.indexOf("fun tick(priceFor0:"), l.indexOf("private fun tickOnce8442("))
        assertTrue(tick.contains("if (!ticking8442.compareAndSet(false, true)) {"))
        assertTrue(tick.contains("try { tickOnce8442(priceFor0, nowMs) } finally { ticking8442.set(false) }"))
    }

    @Test fun openShadowTicketsArePricedByTheLabelBatch() {
        val now = 10_000_000L
        val wanted = mapOf("A" to now + 60_000L, "B" to now + 60_000L, "C" to now - 1L, "D" to now + 60_000L, "E" to now + 60_000L)
        val marks = mapOf("A" to (1.0 to now - 5_000L), "B" to (1.0 to now - 40_000L), "D" to (1.0 to now - 400_000L))
        // A fresh off-watch, C no longer wanted, E priced by its token state; D unpriced longest first, then B
        assertEquals(listOf("D", "B"), ForwardReturnLabeler7731.shadowDue8442(wanted, marks, now, 22_500L) { it == "E" })
        assertTrue(src("engine/truth/ExploreShadow8347.kt").contains("ForwardReturnLabeler7731.noteShadowWanted8442(mint, untilMs)"))
        val l = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(l.contains("+ freshUnpriced7737 + shadow8442, nowMs)"))
    }
}
