package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaunchReplay8744
import com.lifecyclebot.engine.truth.LaunchReplay8744.Ladder
import com.lifecyclebot.engine.truth.LaunchReplay8744.Rule
import com.lifecyclebot.engine.truth.SignalTruth8825
import com.lifecyclebot.engine.truth.SignalTruth8825.Way
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8825 — which way each signal points, measured; and the $10k crossers in front of the decision loop. */
class Aate8825SignalTruthTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun ranksShareTiesAndTheCorrelationReadsDirection() {
        assertArrayEquals(doubleArrayOf(1.0, 2.5, 2.5, 4.0), SignalTruth8825.ranks8825(doubleArrayOf(0.0, 5.0, 5.0, 9.0)), 1e-12)
        val x = DoubleArray(50) { it.toDouble() }
        assertEquals(1.0, SignalTruth8825.spearman8825(x, DoubleArray(50) { it * 3.0 + 1 }), 1e-12)
        assertEquals(-1.0, SignalTruth8825.spearman8825(x, DoubleArray(50) { -it.toDouble() }), 1e-12)
        assertTrue("no spread is no reading", SignalTruth8825.spearman8825(x, DoubleArray(50) { 2.0 }).isNaN())
    }

    @Test fun aDirectionNeedsBothHalvesPastTheirNoise() {
        val x = DoubleArray(200) { (it % 20).toDouble() }
        assertEquals(Way.HIGHER_WORSE, SignalTruth8825.way8825(x, DoubleArray(200) { -x[it] }).third)
        assertEquals(Way.HIGHER_BETTER, SignalTruth8825.way8825(x, DoubleArray(200) { x[it] }).third)
        // the older half says better, the newer half says worse: no direction
        assertEquals(Way.NOISE, SignalTruth8825.way8825(x, DoubleArray(200) { if (it < 100) x[it] else -x[it] }).third)
        assertEquals(Way.THIN, SignalTruth8825.way8825(x.copyOf(99), DoubleArray(99)).third)
    }

    @Test fun oneCoinCountsOncePerAgeBandAndTheLabHearsTheAgreedDirections() {
        SignalTruth8825.resetForTest8825()
        assertEquals("", SignalTruth8825.promptLine8825())
        assertEquals(0, SignalTruth8825.band8825(0.4)); assertEquals(1, SignalTruth8825.band8825(3.0))
        assertEquals(4, SignalTruth8825.band8825(5_000.0)); assertEquals(-1, SignalTruth8825.band8825(Double.NaN))
        for (i in 0 until 200) {
            val burst = (i % 20).toDouble()
            SignalTruth8825.note8825("Mint$i", 0.4, -2.0 * burst - 1.0) { k -> if (k == "buyTx15s") burst else Double.NaN }
        }
        SignalTruth8825.note8825("Mint7", 0.4, 500.0) { 99.0 }   // the same coin again in the same band: not counted
        val r = SignalTruth8825.reads8825().first { it.signal == "buyTx15s" && it.band == "<1m" }
        assertEquals(200, r.n); assertEquals(Way.HIGHER_WORSE, r.way)
        assertTrue(SignalTruth8825.promptLine8825().contains("HIGHER IS WORSE: buyTx15s@<1m -1.00/-1.00 n200"))
        assertTrue(SignalTruth8825.statusLine8825().contains("repeatsSkipped=1"))
        SignalTruth8825.resetForTest8825()
    }

    @Test fun theCrossersAreWatchedOnTheBestGradedCumulativeTrigger() {
        fun res(r: Rule, n: Int, ex: Double) = LaunchReplay8744.Result(r, n, ex, 1.0, 0.5, 0.1, 0.0, 0.0, ex, 1.0)
        val window = res(Rule(10_000.0, 3, 3.0, 35.0, Ladder.DOCTRINE, 10_000L), 500, 9.0)
        val cumA = res(Rule(10_000.0, 1, 2.0, 35.0, Ladder.DOCTRINE, 10_000L, 45), 120, 2.0)
        val cumB = res(Rule(15_000.0, 1, 2.0, 35.0, Ladder.SCALE_OUT, 20_000L, 80), 60, 4.0)
        val thin = res(Rule(10_000.0, 8, 2.0, 20.0, Ladder.DOCTRINE, 10_000L, 25), 49, 50.0)
        assertEquals(cumB, LaunchReplay8744.attentionOf8825(listOf(window, cumA, cumB, thin)))
        assertNull("window-only and thin rules never name the watch", LaunchReplay8744.attentionOf8825(listOf(window, thin)))
        val sn = src("engine/truth/LaunchSniper8748.kt")
        assertTrue(sn.contains("triggers8748(attention, cap, s.buys.size, s.totalBuys)) { s.watched = true; watch = true }"))
        assertTrue(sn.contains("addToWatchlist(mint, mint.take(6), \"LAUNCH_SNIPER_WATCH_8825\""))
        assertTrue(src("engine/truth/LaunchReplay8744.kt").contains("attention = attentionOf8825(res)"))
    }

    @Test fun everyLabelAndTheBootReplayFeedItAndItIsReported() {
        val em = src("engine/truth/EdgeModel8537.kt")
        assertTrue(em.contains("SignalTruth8825.note8825(s.mint, s.raw.ageMin, netPct) { signal8825(s.raw, it) }"))
        assertTrue(em.contains("SignalTruth8825.note8825(o.optString(\"mint\"), r.ageMin, d(\"net\")) { k -> d(k) }"))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("SignalTruth8825.statusLine8825()"))
        assertTrue(src("engine/lab/LlmLabEngine.kt").contains("\$directions8825"))
    }
}
