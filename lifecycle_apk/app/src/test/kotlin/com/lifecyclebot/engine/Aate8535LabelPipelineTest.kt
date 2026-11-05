package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.engine.truth.DowntimeRecovery8430
import com.lifecyclebot.engine.truth.OffWatchSchedule8535
import com.lifecyclebot.engine.truth.OffWatchSchedule8535.Cause
import com.lifecyclebot.engine.truth.OffWatchSchedule8535.Due
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8535 — audit P0-01 (the label pricer's schedule: window-scaled retries, soonest-closing first, every miss carries
 * its provider cause, the Jupiter rung covers every miss) and P0-02 (downtime backfill receipts).
 */
class Aate8535LabelPipelineTest {
    @After fun cleanup() { DowntimeRecovery8430.resetForTest8430() }

    // ── P0-01 ──

    @Test fun the_retry_gap_follows_the_mints_own_window() {
        val now = 1_000_000L
        assertEquals("a 1-minute label with 60 s of grace left is re-asked every 20 s", 20_000L, OffWatchSchedule8535.retryGapMs(now + 60_000L, now))
        assertEquals("the ceiling is the old 30 s", OffWatchSchedule8535.BASE_RETRY_MS, OffWatchSchedule8535.retryGapMs(now + 10 * 60_000L, now))
        assertEquals("never under 5 s", OffWatchSchedule8535.MIN_RETRY_MS, OffWatchSchedule8535.retryGapMs(now + 9_000L, now))
        assertEquals(OffWatchSchedule8535.BASE_RETRY_MS, OffWatchSchedule8535.retryGapMs(null, now))
        assertEquals(OffWatchSchedule8535.BASE_RETRY_MS, OffWatchSchedule8535.retryGapMs(now - 1L, now))
    }

    @Test fun the_ready_list_is_soonest_closing_first_and_defers_only_inside_the_mints_own_gap() {
        val now = 1_000_000L
        val due = listOf(Due("slow", now + 600_000L), Due("fast", now + 60_000L), Due("mid", now + 150_000L), Due("fast", now + 60_000L))
        val (ready, deferred) = OffWatchSchedule8535.ready(due, { null }, now)
        assertEquals(listOf("fast", "mid", "slow"), ready); assertEquals(0, deferred)
        // asked 25 s ago: the 1-minute label (gap 20 s) is ready again, the 10-minute one (gap 30 s) is not
        val (ready2, deferred2) = OffWatchSchedule8535.ready(due, { now - 25_000L }, now)
        assertEquals(listOf("fast"), ready2); assertEquals(2, deferred2)
    }

    @Test fun provider_causes_are_distinct_and_a_priced_mint_never_loses_it() {
        assertEquals(Cause.PRICED, OffWatchSchedule8535.dexCause(refused = false, answered = true, listed = true))
        assertEquals(Cause.RATE_LIMIT, OffWatchSchedule8535.dexCause(refused = true, answered = false, listed = false))
        assertEquals(Cause.UNAVAILABLE, OffWatchSchedule8535.dexCause(refused = false, answered = false, listed = false))
        assertEquals(Cause.NOT_LISTED, OffWatchSchedule8535.dexCause(refused = false, answered = true, listed = false))
        assertEquals(Cause.PRICED, OffWatchSchedule8535.merge(Cause.NOT_LISTED, Cause.PRICED))
        assertEquals(Cause.PRICED, OffWatchSchedule8535.merge(Cause.PRICED, Cause.UNAVAILABLE))
        assertEquals("an answered not-listed stands over an earlier outage", Cause.NOT_LISTED, OffWatchSchedule8535.merge(Cause.UNAVAILABLE, Cause.NOT_LISTED))
        assertEquals(Cause.NOT_LISTED, OffWatchSchedule8535.merge(Cause.NOT_LISTED, Cause.RATE_LIMIT))
        assertEquals(Cause.RATE_LIMIT, OffWatchSchedule8535.merge(null, Cause.RATE_LIMIT))
        val frl = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        assertTrue(frl.contains(".put(\"cause8535\", cause8535)"))
        assertTrue("the Jupiter rung covers every miss in chunks", frl.contains("missedJup8405.chunked(50)"))
        assertFalse(frl.contains("batch.filter { it !in got }.take(50)"))
        assertTrue(frl.contains("labelCoverage8535[h1m="))
    }

    /**
     * The audit's acceptance fixture: a 1,000-mint burst against providers that refuse (429) and fail. Every eligible
     * target ends PRICED or one explanatory UNAVAILABLE / NOT_LISTED / RATE_LIMIT by its deadline, nothing twice, and the
     * soonest-closing windows are asked first.
     */
    @Test fun a_thousand_mint_burst_under_provider_delay_ends_every_target_priced_or_explained_once() {
        val t0 = 1_000_000L
        val batch = 300
        val windows = longArrayOf(60_000L, 150_000L, 600_000L)   // the 1 m / 5 m / 1 h graces
        val due = (0 until 1000).map { i -> Due("m$i", t0 + windows[i % 3]) }
        val listed = (0 until 1000).filter { it % 10 != 0 }.map { "m$it" }.toHashSet()     // 10 % nowhere listed
        val attemptAt = HashMap<String, Long>()
        val cause = HashMap<String, Cause>()
        val terminal = LinkedHashMap<String, Cause>()
        var firstAsked: List<String>? = null
        var now = t0
        var tick = 0
        while (now < t0 + 700_000L) {
            val open = due.filter { it.mint !in terminal && now <= it.windowEndMs }
            // windows that closed unpriced terminalise exactly once, with their last cause
            for (d in due) if (d.mint !in terminal && now > d.windowEndMs) terminal[d.mint] = cause[d.mint] ?: Cause.NO_SOURCE
            val (ready, _) = OffWatchSchedule8535.ready(open, { attemptAt[it] }, now)
            val asked = ready.take(batch)
            if (firstAsked == null && asked.isNotEmpty()) firstAsked = asked
            for (chunk in asked.chunked(30)) {
                val refused = (tick + chunk.hashCode()) % 4 == 0          // our limiter / a 429 on a quarter of the calls
                val failed = !refused && (tick + chunk.size) % 7 == 0     // a provider failure on some
                for (m in chunk) {
                    attemptAt[m] = now
                    val next = OffWatchSchedule8535.dexCause(refused, answered = !refused && !failed, listed = !refused && !failed && m in listed)
                    cause[m] = OffWatchSchedule8535.merge(cause[m], next)
                    if (cause[m] == Cause.PRICED) terminal[m] = Cause.PRICED
                }
            }
            now += 10_000L; tick++
        }
        for (d in due) if (d.mint !in terminal) terminal[d.mint] = cause[d.mint] ?: Cause.NO_SOURCE
        assertEquals("every target ended exactly once", 1000, terminal.size)
        assertTrue("the soonest-closing windows were asked first", firstAsked!!.all { due.first { d -> d.mint == it }.windowEndMs == t0 + 60_000L })
        val priced = terminal.values.count { it == Cause.PRICED }
        assertTrue("listed coins are priced despite refusals and failures: $priced", priced >= 850)
        assertTrue("unlisted coins end NOT_LISTED, not a silent loss", terminal.filterKeys { it !in listed }.values.all { it == Cause.NOT_LISTED || it == Cause.RATE_LIMIT || it == Cause.UNAVAILABLE })
        assertEquals("no target is left without a cause", 0, terminal.values.count { it == Cause.NO_SOURCE })
    }

    // ── P0-02 ──

    private fun candle(ts: Long, px: Double) = Candle(ts = ts, priceUsd = px, marketCap = 0.0, volumeH1 = 0.0, volume24h = 0.0)

    @Test fun every_queued_backfill_ends_with_one_named_receipt_and_recovery_is_source_timed() {
        DowntimeRecovery8430.resetForTest8430()
        val boot = 10_000_000L
        val target = boot - 300_000L
        // a window that closed before boot queues (WAIT); its receipt is RECOVERED once the covering candle lands
        val first = DowntimeRecovery8430.candleMark8430("m1", target, target, target + 150_000L, boot, boot + 1_000L)
        assertTrue(first.waiting)
        DowntimeRecovery8430.seedForTest8430("m1", listOf(candle(target - 60_000L, 1.0), candle(target - 60_000L + 60_000L, 1.1)))
        val read = DowntimeRecovery8430.candleMark8430("m1", target, target, target + 150_000L, boot, boot + 2_000L)
        assertNotNull(read.mark); assertEquals("RECOVERED", DowntimeRecovery8430.receipt8535("m1"))
        assertEquals("the mark carries the candle's own close time, not the fetch time", target + 60_000L, read.mark!!.observedAtMs)
        // no candle covering the window: a named receipt, not a negative return
        DowntimeRecovery8430.seedForTest8430("m2", listOf(candle(target - 3_600_000L, 0.5)))
        assertNull(DowntimeRecovery8430.candleMark8430("m2", target, target, target + 150_000L, boot, boot + 2_000L).mark)
        assertEquals("NO_CANDLE_IN_WINDOW", DowntimeRecovery8430.receipt8535("m2"))
        // a window that did not close before boot is not this path's business
        assertFalse(DowntimeRecovery8430.downtime8430(boot + 1L, boot))
        assertFalse(DowntimeRecovery8430.candleMark8430("m3", boot + 60_000L, boot + 60_000L, boot + 120_000L, boot, boot + 2_000L).waiting)
        assertNull(DowntimeRecovery8430.receipt8535("m3"))
        // past the boot window: the queue is closed by name
        DowntimeRecovery8430.candleMark8430("m4", target, target, target + 150_000L, boot, boot + DowntimeRecovery8430.RECOVERY_WINDOW_MS_8430 + 1L)
        assertEquals("BOOT_WINDOW_CLOSED", DowntimeRecovery8430.receipt8535("m4"))
        // the labeler writes the receipt on the terminal row of a downtime cohort
        val frl = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        assertTrue(frl.contains("DowntimeRecovery8430.receipt8535(o.mint) ?: \"NO_RECEIPT\""))
        assertTrue(DowntimeRecovery8430.statusLine8430().contains("gaveUpBy8535{"))
    }

    @Test fun the_boot_capacity_names_the_overflow() {
        DowntimeRecovery8430.resetForTest8430()
        val boot = 20_000_000L
        val target = boot - 300_000L
        repeat(DowntimeRecovery8430.MAX_MINTS_PER_BOOT_8430) { i ->
            assertTrue(DowntimeRecovery8430.candleMark8430("cap$i", target, target, target + 150_000L, boot, boot + 1_000L).waiting)
        }
        val over = DowntimeRecovery8430.candleMark8430("capOver", target, target, target + 150_000L, boot, boot + 1_000L)
        assertFalse(over.waiting); assertNull(over.mark)
        assertEquals("BOOT_CAPACITY", DowntimeRecovery8430.receipt8535("capOver"))
        assertEquals(1L, DowntimeRecovery8430.counts8438()[3])
    }
}
