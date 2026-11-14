package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.BaseFamily8754
import com.lifecyclebot.engine.truth.BaseFamily8754.Mode
import com.lifecyclebot.engine.truth.BaseFamily8754.Trigger
import com.lifecyclebot.engine.truth.LaunchReplay8744
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8754 — the base family: entries on the consolidation after the first pump and dump, graded with the launch replay's
 * honesty, and the trigger the sniper runs live is the same code the replay graded.
 */
class Aate8754BaseFamilyTest {
    private val solUsd = 100.0   // 2e-7 SOL/token x 1e9 x $100 = a $20k peak
    // birth at 1e-7, a $20k peak at 10 s, half off by 20 s, then a flat base (1.0..1.1e-7) printed every 10 s to 200 s, all buys
    private val times = listOf(0L, 10_000L) + (2..20).map { it * 10_000L }
    private val prices = listOf(1.0e-7, 2.0e-7) + (2..20).map { if (it % 2 == 0) 1.0e-7 else 1.1e-7 }

    private fun feed(tr: Trigger, live: Boolean, extra: List<Pair<Long, Double>> = emptyList()): Pair<Long, Double>? {
        val st = BaseFamily8754.State(tr)
        for ((t, p) in times.zip(prices) + extra) st.onPrint(t, p, true, live, solUsd)?.let { return t to it }
        return null
    }

    @Test fun aBaseFiresOnceTheWindowHasLeftThePeakBehind() {
        val tr = Trigger(10_000.0, 0.4, 120_000L, 1.3, 3, Mode.IN_BASE)
        val (at, stop) = feed(tr, live = true)!!
        assertEquals(140_000L, at)                // at 130 s the 120 s window still holds the 10 s peak; at 140 s it does not
        assertEquals(1.0e-7 * 0.85, stop, 1e-15)  // the stop sits 15 % under the base's low
        assertNull(feed(tr, live = false))        // a backfilled print never fires
        assertNull(feed(tr.copy(minPeakCapUsd = 40_000.0), live = true))   // the peak never reached $40k
        assertNull(feed(tr.copy(maxRange = 1.05), live = true))            // a 10 % range is not a 5 % base
    }

    @Test fun aBreakoutWaitsForThePrintOverTheBase() {
        val tr = Trigger(10_000.0, 0.4, 120_000L, 1.3, 3, Mode.BREAKOUT)
        assertNull(feed(tr, live = true))   // the base formed but never broke
        val (at, stop) = feed(tr, live = true, extra = listOf(210_000L to 1.2e-7))!!
        assertEquals(210_000L, at)
        assertEquals(1.0e-7 * 0.95, stop, 1e-15)
        // a base that breaks down first resets: the later pop is not a breakout of that base
        assertNull(feed(tr, live = true, extra = listOf(205_000L to 0.8e-7, 210_000L to 1.2e-7)))
    }

    @Test fun theReplayGradesTheFamilyThroughTheSharedExit() {
        val n = times.size + 2
        val t = (times + listOf(260_000L, 3_000_000L)).toLongArray()
        val p = (prices + listOf(4.0e-7, 4.0e-7)).toDoubleArray()
        val x = LaunchReplay8744.clean8748(t, p, BooleanArray(n) { true }, null)
        val out = BaseFamily8754.simulate8754(x, solUsd, 0.0)
        val tr = Trigger(10_000.0, 0.4, 120_000L, 1.3, 3, Mode.IN_BASE)
        val r = BaseFamily8754.Rule(tr, LaunchReplay8744.Ladder.HALF_TRAIL, 10_000L)
        // fired at 140 s (1.0e-7); +10 s the curve stood at 150 s (1.1e-7); half sold at 2x on the 4e-7 print, the rest held
        val e = 1.1e-7
        assertEquals((0.5 * (4.0e-7 / e - 1.0) + 0.5 * (4.0e-7 / e - 1.0)) * 100.0, out[r]!!, 1e-6)
        assertEquals(96 * 9, BaseFamily8754.GRID.size)
        assertTrue(out.keys.all { it in BaseFamily8754.GRID })
    }

    @Test fun onlyABroadRecordAtTheBotsLatencyIsProven() {
        val r = BaseFamily8754.Rule(Trigger(10_000.0, 0.4, 120_000L, 1.3, 3, Mode.IN_BASE), LaunchReplay8744.Ladder.DOCTRINE, 10_000L)
        val broad = BaseFamily8754.grade8754(r, List(120) { if (it % 3 == 0) 40.0 else -5.0 })!!
        assertEquals(broad, BaseFamily8754.provenOf8754(listOf(broad), 10_000L))
        assertNull(BaseFamily8754.provenOf8754(listOf(broad), 20_000L))
        val carried = BaseFamily8754.grade8754(r, List(117) { -10.0 } + listOf(5_000.0, 3_000.0, 2_000.0))!!
        assertNull(BaseFamily8754.provenOf8754(listOf(carried), 10_000L))
        assertNull(BaseFamily8754.provenOf8754(listOf(broad.copy(rule = r.copy(ladder = LaunchReplay8744.Ladder.MOONBAG))), 10_000L))
    }

    @Test fun theSniperRunsTheGradedTriggerLive() {
        val sn = File("src/main/kotlin/com/lifecyclebot/engine/truth/LaunchSniper8748.kt").readText()
        assertTrue(sn.contains("s.base = BaseFamily8754.State(tr)"))
        assertTrue(sn.contains("bs.onPrint(eventMs - s.bornMs, priceSol, isBuy, live, solUsd)"))
        val rp = File("src/main/kotlin/com/lifecyclebot/engine/truth/LaunchReplay8744.kt").readText()
        assertTrue(rp.contains("BaseFamily8754.evaluate8754(set, solUsd, cost, need)"))
        assertNotNull(rp.indexOf("internal fun exitNet8754(").takeIf { it >= 0 })
    }
}
