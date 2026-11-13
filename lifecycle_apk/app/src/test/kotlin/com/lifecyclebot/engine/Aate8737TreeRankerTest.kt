package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.TreeRanker8737
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** V5.0.8737 — one learned filter: boosted trees on the bot's own labels, earning authority on their own record. */
class Aate8737TreeRankerTest {
    @Test fun theTreesFindAnInteractionNoSingleFeatureShows() {
        // pays only when feature 0 is high AND feature 2 is low (a deep pool on a young coin); each alone pays nothing
        val r = Random(7)
        val xs = List(6_000) { DoubleArray(17) { j -> if (j == 5) Double.NaN else r.nextDouble() } }
        val ys = xs.map { x -> (if (x[0] > 0.6 && x[2] < 0.3) 25.0 else -8.0) + r.nextDouble() * 6 - 3 }
        val m = TreeRanker8737.train8737(xs, ys, List(xs.size) { 1.0 }, 0L, trees = 120)
        val good = DoubleArray(17) { 0.5 }.also { it[0] = 0.9; it[2] = 0.1; it[5] = Double.NaN }
        val bad = DoubleArray(17) { 0.5 }.also { it[0] = 0.9; it[2] = 0.9; it[5] = Double.NaN }
        assertTrue("${m.predict(good)}", m.predict(good) > 15.0)
        assertTrue("${m.predict(bad)}", m.predict(bad) < -3.0)
    }

    @Test fun aCoinWindowWeighsOneAcrossLanes() {
        val line = { lane: String, net: Double -> """{"t":1000,"mint":"AbcPUMPpump","lane":"$lane","liq":20000,"mcap":40000,"age":3,"cost":2,"net":$net}""" }
        val rows = TreeRanker8737.rowsOf8737(sequenceOf(line("CORE", 10.0), line("EXPRESS", 12.0), line("LPSTATE8725_PENDING", 50.0),
            """{"t":1000,"mint":"Other","lane":"CORE","net":null}"""))
        assertEquals(2, rows.size)
        assertEquals(1.0, rows.sumOf { it.third }, 1e-9)
        assertEquals(1.0, rows[0].first[15], 0.0)   // isPump
    }

    @Test fun theEdgeModelUsesItUnderItsOwnRecord() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/EdgeModel8537.kt").readText()
        assertTrue(src.contains("tree != null -> tree to TreeRanker8737.KEY_8737"))   // V5.0.8746: the mind answers first once warm
        assertTrue(src.contains("val (p, key) = predict8737(raw, x, ts.mint, ts"))
        assertTrue(src.contains("Verdict(p, p.isFinite() && o != null && authority(o.selN, o.selMean, o.selSe, o.allMean), key, trained,"))
    }
}
