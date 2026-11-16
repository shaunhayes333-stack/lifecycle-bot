package com.lifecyclebot.engine

import com.lifecyclebot.engine.lab.StrategyLab8755
import com.lifecyclebot.engine.truth.LaunchReplay8744
import com.lifecyclebot.engine.truth.LaunchReplay8744.Ladder
import com.lifecyclebot.engine.truth.LaunchReplay8744.Rule
import com.lifecyclebot.engine.truth.LaunchSniper8748
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8824 — the $10k crossers that keep going: live buys since birth are a launch-rule axis, graded and traded alike. */
class Aate8824TenKCrossersTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val solUsd = 100.0

    /** 60 live buys one second apart; the curve cap ($) is price x 1e9 x 100: $5,000 at the first print, $9,940 at print 19, $10,200 at print 20. */
    private fun path(n: Int = 60): LaunchReplay8744.Path8748 {
        val t = LongArray(n) { 1_000L * it }
        val p = DoubleArray(n) { 5.0e-8 + it * 2.6e-9 }
        return LaunchReplay8744.Path8748(t, p, BooleanArray(n) { true }, BooleanArray(n) { true }, 0)
    }

    @Test fun theCrossWaitsForTheBuysSinceBirth() {
        val x = path()
        assertEquals("window only: the first print at $10k", 20, LaunchReplay8744.trigger8748(x, 10_000.0, 1, solUsd))
        assertEquals("45 buys since birth: the 45th print", 44, LaunchReplay8744.trigger8748(x, 10_000.0, 1, solUsd, 45))
        assertEquals("80 never printed: no entry", -1, LaunchReplay8744.trigger8748(x, 10_000.0, 1, solUsd, 80))
        // a backfilled buy (not received live) never counts toward the total
        val late = LaunchReplay8744.Path8748(x.t, x.p, x.buy, BooleanArray(x.t.size) { it >= 10 }, 0)
        assertEquals(54, LaunchReplay8744.trigger8748(late, 10_000.0, 1, solUsd, 45))
        // the simulation enters on the same trigger
        val r = Rule(10_000.0, 1, 3.0, 35.0, Ladder.DOCTRINE, 2_000L, 80)
        assertEquals(null, LaunchReplay8744.simulateRule8755(x, r, solUsd, 0.0))
        assertTrue(LaunchReplay8744.simulateRule8755(x, r.copy(totalBuys = 45), solUsd, 0.0) != null)
    }

    @Test fun theGridGradesTheFamilyBesideTheWindowRules() {
        val fam = LaunchReplay8744.TOTAL_FAMILY_8824
        assertEquals(360, fam.size)
        assertEquals("every rule is its own id", fam.size, fam.map { it.id }.toSet().size)
        assertTrue(LaunchReplay8744.GRID.containsAll(fam))
        assertTrue(fam.all { it.totalBuys in setOf(25, 45, 80) && it.delayMs >= 10_000L && it.capUsd >= 10_000.0 })
        assertTrue("the live ladder can be proven", fam.any { it.ladder == Ladder.DOCTRINE })
        assertEquals("a window-only rule keeps its id", "cap\$10k buys>=3 tp3x stop35% HALF_TRAIL +2s", Rule(10_000.0, 3, 3.0, 35.0).id)
        assertTrue(Rule(10_000.0, 1, 2.0, 35.0, Ladder.DOCTRINE, 10_000L, 45).id.contains("buys>=1 total>=45 "))
        val line = LaunchReplay8744.totalLine8824(emptyList())
        assertTrue(line.startsWith("cumulative8824{best=- vsWindowOnly=-"))
    }

    @Test fun theSniperFiresOnTheSameTrigger() {
        val r = Rule(10_000.0, 1, 2.0, 35.0, Ladder.DOCTRINE, 10_000L, 45)
        assertFalse(LaunchSniper8748.triggers8748(r, 12_000.0, 4, 44))
        assertTrue(LaunchSniper8748.triggers8748(r, 12_000.0, 4, 45))
        assertFalse(LaunchSniper8748.triggers8748(r, 9_000.0, 4, 200))
        assertTrue("a window-only rule ignores the total", LaunchSniper8748.triggers8748(r.copy(totalBuys = 0), 12_000.0, 1))
        val sn = src("engine/truth/LaunchSniper8748.kt")
        assertTrue(sn.contains("if (isBuy) { s.buys.addLast(eventMs); s.totalBuys++ }"))
        assertTrue(sn.contains("triggers8748(result.rule, cap, s.buys.size, s.totalBuys)"))
    }

    @Test fun theLabStoresAndProposesTheTotal() {
        val spec = StrategyLab8755.specOf8755(JSONObject("""{"family":"LAUNCH","capUsd":10000,"minBuys":1,"totalBuys":45,"ladder":"DOCTRINE","delayMs":10000}"""))!!
        assertEquals(45, spec.launch!!.totalBuys)
        assertEquals(spec, StrategyLab8755.specOf8755(JSONObject(StrategyLab8755.specJson8755(spec))))
        assertEquals(500, StrategyLab8755.specOf8755(JSONObject("""{"family":"LAUNCH","totalBuys":99999}"""))!!.launch!!.totalBuys)
        assertEquals(0, StrategyLab8755.specOf8755(JSONObject("""{"family":"LAUNCH"}"""))!!.launch!!.totalBuys)
        assertTrue(src("engine/lab/LlmLabEngine.kt").contains("\"totalBuys\":0..500"))
    }

    @Test fun anArmedFireIsNotChargedTheBandGapTwice() {
        assertFalse(LaunchSniper8748.armedWindow8824("", 1L))
        assertFalse(LaunchSniper8748.armedWindow8824("NoFireMint11111111111111111111111111111pump", 1L))
        val ne = src("engine/truth/NetEdge8030.kt").substringAfter("fun preTradeCostPct8329(").substringBefore("fun entryRefusal8030(")
        assertTrue(ne.contains("val gap = if (gapExempt8824(mint, System.currentTimeMillis())) 0.0"))
        assertTrue(src("engine/truth/NetEdge8030.kt").contains("replayFireExempt8824="))
        assertTrue(src("engine/truth/LaunchReplay8744.kt").contains("\${totalLine8824(last)} "))
    }
}
