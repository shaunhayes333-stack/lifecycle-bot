package com.lifecyclebot.engine

import com.lifecyclebot.engine.chart.Bar7950
import com.lifecyclebot.engine.chart.ColourShape8807
import com.lifecyclebot.engine.truth.StopSlip8756
import com.lifecyclebot.xstocks.XsApi
import com.lifecyclebot.xstocks.XsDesk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8810 — 5.0.8809 on the device: the colour / shape table had scored no chart in 20 minutes (its downloads wait on the
 * GeckoTerminal slot the live feed owns), and paper stops set at -5 % still triggered at -26 % with per-trade marks. The
 * bot's own retired one-minute tapes now teach the table, and the slip line separates the first breach from the confirmed one.
 */
class Aate8810OwnChartsAndSlipTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun aRetiredTapeIsScoredOnceAsOneChart() {
        ColourShape8807.resetForTest8807()
        var p = 100.0
        val bars = ArrayList<Bar7950>()
        repeat(60) { i -> val d = if (i % 3 == 0) -1.0 else 1.5; bars += Bar7950(i * 60_000L, p, maxOf(p, p + d), minOf(p, p + d), p + d, 1.0); p += d }
        ColourShape8807.learnTape8810("TapeMint", bars.take(20))       // too short to have a future: not offered
        ColourShape8807.learnTape8810("TapeMint", bars)
        assertEquals(1, ColourShape8807.drainTapesForTest8810())
        val line = ColourShape8807.statusLine8807()
        assertTrue(line, line.contains("charts=1 ") && line.contains("ownTapes8810{offered=1 learned=1 dropped=0 duplicate8816=0}"))
        val reader = src("engine/chart/ChartReader7950.kt")
        assertTrue(reader.contains("ColourShape8807.learnTape8810(mint, synchronized(tape) { toBars(tape.bars) })"))
        assertTrue("quiet and capped tapes", reader.contains("if (tapes.remove(it.key, it.value)) retire8810(it.key, it.value)"))
        assertTrue("and tapes freed under memory pressure", reader.contains("freed++; retire8810(e.key, e.value)"))
        ColourShape8807.resetForTest8807()
    }

    @Test fun theSlipLineSeparatesTheFirstBreachFromTheConfirmedOne() {
        StopSlip8756.resetForTest8756()
        StopSlip8756.notePaper8756("express", -5.0, -26.0, -21.0, true, -12.0)
        StopSlip8756.notePaper8756("moonshot", -15.0, -18.0, -20.0, false)
        val line = StopSlip8756.statusLine8756()
        assertTrue(line, line.contains("EXPRESS:n1 level-5 first-12 trig-26 fill-21 print1"))
        assertTrue("an unknown first breach prints nothing", line.contains("MOONSHOT:n1 level-15 trig-18 fill-20"))
        val book = src("engine/truth/MemePaperTrader8544.kt")
        assertTrue(book.contains("else if (p.stopSeenAtMs8683 == 0L) { p.stopSeenAtMs8683 = p.lastAtMs; p.slipFirst8810 = g }"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 17. The bot's own charts teach the colour table"))
        StopSlip8756.resetForTest8756()
    }

    @Test fun anXStocksDayWhoseReadsWereRefusedIsAskedAgain() {
        assertTrue("unanswered reads before the deadline: ask again", XsDesk.historyRetry8810(3, 60L * 60_000L))
        assertFalse("every read answered: evaluate", XsDesk.historyRetry8810(0, 60L * 60_000L))
        assertFalse("six hours into the day: evaluate with what answered", XsDesk.historyRetry8810(3, 7L * 60L * 60_000L))
        assertTrue(XsApi.line().contains("localRefused8810="))
        val api = src("xstocks/XsApi.kt")
        assertTrue(api.contains("val local = r.header(com.lifecyclebot.network.HostCircuitInterceptor.SYNTHETIC_HEADER_6969) != null"))
        val desk = src("xstocks/XsDesk.kt")
        assertTrue(desk.contains("if (historyRetry8810(unanswered, now - today * DAY_MS)) { historyRetryAt8810 = now + HISTORY_RETRY_MS_8810; return }"))
        assertTrue(desk.indexOf("historyRetryAt8810 = now + HISTORY_RETRY_MS_8810; return }") < desk.indexOf("        lastEvalDay = today\n        historyPasses8810 = 0"))
    }
}
