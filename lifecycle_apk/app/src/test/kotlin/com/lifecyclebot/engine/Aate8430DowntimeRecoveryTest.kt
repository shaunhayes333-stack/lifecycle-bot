package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.engine.truth.DowntimeRecovery8430
import com.lifecyclebot.engine.truth.LabelEvidence8386
import com.lifecyclebot.engine.truth.LabelMark8386
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** V5.0.8430 — the restart cohort's horizon marks come from the keyless 1-minute candle, bounded, never fabricated. */
class Aate8430DowntimeRecoveryTest {
    @Before fun reset() { DowntimeRecovery8430.resetForTest8430() }
    @After fun back() { DowntimeRecovery8430.resetForTest8430() }

    private val boot = 1_800_000_000_000L
    private fun candle(tsMs: Long, px: Double) = Candle(ts = tsMs, priceUsd = px, marketCap = 0.0, volumeH1 = 0.0, volume24h = 0.0)

    @Test fun onlyAWindowThatClosedBeforeBootIsADowntimeWindow() {
        assertTrue(DowntimeRecovery8430.downtime8430(boot - 1L, boot))
        assertFalse(DowntimeRecovery8430.downtime8430(boot, boot))
        assertFalse(DowntimeRecovery8430.downtime8430(boot + 60_000L, boot))
        // a live window is never touched: no queue, no wait
        val r = DowntimeRecovery8430.candleMark8430("M", boot + 300_000L, boot + 150_000L, boot + 450_000L, boot, boot + 460_000L)
        assertFalse(r.waiting); assertNull(r.mark)
        assertTrue(DowntimeRecovery8430.statusLine8430().contains("queued=0"))
    }

    @Test fun theCandleCoveringTheHorizonIsPickedAndProviderSecondsAreNormalised() {
        val at = boot - 3_600_000L                       // decided an hour before boot
        val target = at + 300_000L                       // the 5-minute horizon
        val cs = listOf(candle(target - 180_000L, 1.0), candle(target - 60_000L, 2.0), candle(target, 3.0), candle(target + 60_000L, 4.0), candle(target + 400_000L, 9.0))
        val pick = DowntimeRecovery8430.pick8430(cs, target, at + 150_000L, at + 450_000L)
        assertNotNull(pick); assertEquals(3.0, pick!!.priceUsd, 0.0)
        // seconds from a provider read the same
        val secs = cs.map { it.copy(ts = it.ts / 1000L) }
        assertEquals(3.0, DowntimeRecovery8430.pick8430(secs, target, at + 150_000L, at + 450_000L)!!.priceUsd, 0.0)
        // nothing inside the window: nothing (a candle after the deadline is not the horizon)
        assertNull(DowntimeRecovery8430.pick8430(listOf(candle(target + 400_000L, 9.0)), target, at + 150_000L, at + 450_000L))
        assertNull(DowntimeRecovery8430.pick8430(listOf(candle(target, Double.NaN)), target, at + 150_000L, at + 450_000L))
    }

    @Test fun anObservationWaitsForItsQueuedFetchThenReadsTheCandleOrClosesAsBefore() {
        val at = boot - 3_600_000L; val target = at + 300_000L; val from = at + 150_000L; val deadline = at + 450_000L
        val now = boot + 30_000L
        // first ask inside the boot window: queued, the observation waits
        val w = DowntimeRecovery8430.candleMark8430("MintA", target, from, deadline, boot, now)
        assertTrue(w.waiting); assertNull(w.mark)
        assertTrue(DowntimeRecovery8430.statusLine8430().contains("queued=1"))
        // the fetch lands: the mark is the candle's price at the candle's time, a provider source, no independent basis
        DowntimeRecovery8430.seedForTest8430("MintA", listOf(candle(target, 0.0025)))
        val r = DowntimeRecovery8430.candleMark8430("MintA", target, from, deadline, boot, now + 20_000L)
        assertFalse(r.waiting)
        val m = r.mark!!
        assertEquals(0.0025, m.usd, 0.0); assertEquals(target, m.observedAtMs); assertEquals(DowntimeRecovery8430.SOURCE_8430, m.source)
        assertFalse(m.independentBasis)
        assertTrue(m.known())
        // against a known entry basis it is an ordinary (not extreme) move: eligible for the labeler's recovery filter
        val entry = LabelMark8386("MintA", "", 0.0020, at, at, "DEXSCREENER")
        assertTrue(LabelEvidence8386.eligible(entry, m))
        // a 20x candle against an unwitnessed basis is NOT booked (unwitnessed extreme), exactly as any other mark
        assertFalse(LabelEvidence8386.eligible(entry, LabelMark8386("MintA", "ohlcv", 0.040, target, now, DowntimeRecovery8430.SOURCE_8430)))
        // a mint with no candle closes as before
        DowntimeRecovery8430.seedForTest8430("MintB", emptyList())
        val none = DowntimeRecovery8430.candleMark8430("MintB", target, from, deadline, boot, now)
        assertFalse(none.waiting); assertNull(none.mark)
        // past the boot window nothing new is queued: it closes as before (and is counted once)
        val late = DowntimeRecovery8430.candleMark8430("MintC", target, from, deadline, boot, boot + DowntimeRecovery8430.RECOVERY_WINDOW_MS_8430 + 1L)
        assertFalse(late.waiting); assertNull(late.mark)
        assertTrue(DowntimeRecovery8430.statusLine8430().contains("gaveUp=1"))
        DowntimeRecovery8430.candleMark8430("MintC", target, from, deadline, boot, boot + DowntimeRecovery8430.RECOVERY_WINDOW_MS_8430 + 2L)
        assertTrue(DowntimeRecovery8430.statusLine8430().contains("gaveUp=1"))
    }

    @Test fun wiring() {
        val l = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        assertTrue(l.contains("private val bootMs8430 = System.currentTimeMillis()"))
        assertTrue(l.contains("else DowntimeRecovery8430.candleMark8430(o.mint, o.atMs + horizon, o.atMs + horizon / 2, deadline, bootMs8430, nowMs)"))
        assertTrue(l.contains("if (downtime8430.waiting) return false"))
        assertTrue("the candle goes through the SAME recovery filter as every other mark", l.contains("val recovered=listOfNotNull(o.lastAcceptedMark8395,incoming,downtime8430.mark).filter {"))
        assertTrue(l.contains("try { DowntimeRecovery8430.drain8430(nowMs) } catch (_: Throwable) {}"))
        assertEquals(150, DowntimeRecovery8430.MAX_MINTS_PER_BOOT_8430)
        assertEquals(10_000L, DowntimeRecovery8430.FETCH_GAP_MS_8430)
    }
}
