package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8583 §HORIZON_PRICER — 8575: labelCoverage h1m=612/912, h5m=16/443, h60m=0/1,826. Every open label window is priced
 * on its own thread at the window's time, not by whichever source happened to hold a mark.
 */
class Aate8583HorizonPricerTest {
    private val s = 1_000L; private val m = 60_000L

    @Test fun the_open_windows_are_exactly_the_reads_a_label_books_from() {
        val f = ForwardReturnLabeler7731::horizonWindowOpen8583
        assertTrue(f(10 * s, false, false, false, false))          // re-base: the buyable entry read
        assertFalse(f(10 * s, true, false, false, false))          // already re-based, before the 1-minute read
        assertTrue(f(90 * s, true, false, false, false))           // 1-minute window (1..2 min)
        assertFalse(f(90 * s, true, true, false, false))           // booked
        assertTrue(f(6 * m, true, true, false, false))             // 5-minute window (5..7.5 min)
        assertFalse(f(20 * m, true, true, true, false))            // between windows: nothing to price
        assertTrue(f(65 * m, true, true, true, false))             // the hour window (60..70 min) — the read that was 0/1,826
        assertFalse(f(71 * m, true, true, true, false))            // closed
        assertFalse(f(65 * m, true, true, true, true))             // booked
    }

    @Test fun one_read_per_mint_nearest_deadline_first_and_never_twice_inside_the_gap() {
        val now = 10_000_000L
        val open = listOf("B" to 300L, "A" to 100L, "A" to 200L, "C" to 50L)
        val last = mapOf("C" to now - 1_000L)
        assertEquals(listOf("A", "B"), ForwardReturnLabeler7731.hpDueMints8583(open, { last[it] }, now))
        assertEquals(listOf("C", "A", "B"), ForwardReturnLabeler7731.hpDueMints8583(open, { null }, now))
        assertEquals(listOf("C"), ForwardReturnLabeler7731.hpDueMints8583(open, { null }, now, cap = 1))
        assertTrue(ForwardReturnLabeler7731.HP_READ_GAP_MS_8583 < 60_000L)   // several tries inside even a 1-minute window
        assertTrue(ForwardReturnLabeler7731.HP_TICK_MS_8583 <= 5_000L && ForwardReturnLabeler7731.HP_MAX_MINTS_8583 in 60..120)
    }

    @Test fun an_imminent_1m_label_beats_an_older_60m_label_and_a_15m_runner_target_is_not_invisible() {
        val f = ForwardReturnLabeler7731::horizonDeadline8650
        val now = 5_000_000L
        val hour = f(now - 61 * m, now, true, true, true, false, 60 * m)
        val oneMinute = f(now - 90 * s, now, true, false, false, false, 15 * m)
        assertTrue(hour != null && oneMinute != null && oneMinute < hour)
        assertEquals(listOf("YOUNG", "OLD"), ForwardReturnLabeler7731.hpDueMints8583(
            listOf("OLD" to hour!!, "YOUNG" to oneMinute!!), { null }, now, cap = 2))
        assertTrue("a 15-minute runner needs its own priced target", f(now - 16 * m, now, true, true, true, false, 15 * m) != null)
        assertEquals("the same coin was not due for its old 60-minute slot yet", null,
            f(now - 16 * m, now, true, true, true, false, 60 * m))
    }

    @Test fun the_pricer_runs_from_the_tick_on_its_own_thread_and_publishes_inside_the_window() {
        val lab = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        assertTrue(lab.contains("startHorizonPricer8583()   // V5.0.8583"))
        val pass = lab.substringAfter("private fun horizonPass8583(").substringBefore("fun horizonPricerLine8583()")
        for (rung in listOf("ParallelMarkFanout7088.curvePrices7392(onCurve)", "batchPriceFetchTyped8087(chunk, LABEL_SLOT_WAIT_MS_8474",
                "jupiterBatchPrices7737(chunk)", "OnChainDepth8038.chainPriceUsd8540(m, CHAIN_MARK_MAX_AGE_MS_8540)",
                "noteOffWatchResponse(prices.filterValues { it.isFinite() && it > 0.0 }, System.currentTimeMillis())"))
            assertTrue(rung, pass.contains(rung))
        assertTrue(lab.contains("Thread({") && lab.contains("\"label-horizon-8583\""))
        assertTrue(lab.contains("\${horizonPricerLine8583()} \${labelMarksLine8418()}"))
    }
}
