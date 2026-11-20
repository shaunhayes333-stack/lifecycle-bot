package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CryptoSpotPaperBacking8788
import com.lifecyclebot.engine.truth.ManualRead8806
import com.lifecyclebot.engine.truth.ManualRead8806.Bar
import com.lifecyclebot.engine.truth.ManualRead8806.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8806 — owner: "add my manual read please" and "the crypto trader still isn't buying anything at all despite its own
 * tactics showing decent returns in paper". His read (pullback, base breakout, ignition, staircase) runs on every witnessed
 * print with a hand trader's scale-out ladder, graded forward in paper and armed only on the half it was not judged on; the
 * crypto paper backing now answers CRYPTO_ALT — the lane every coin the crypto trader finds is in — by its own record.
 */
class Aate8806ManualReadTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun aQuietTapeThatIgnitesIsReadAndAFlatOneIsNot() {
        fun bar(i: Int, buys: Int, sells: Int) = Bar(i * 10_000L, 1.0, 1.0, 1.0, 1.0, buys, sells)
        val quiet = List(20) { bar(it, 0, 1) }
        assertTrue(ManualRead8806.detect(quiet).isEmpty())
        val ignites = List(14) { bar(it, 0, 1) } + List(6) { bar(14 + it, 5, 1) }       // 36 trades a minute after 6, buyers 83 %
        assertEquals(setOf(Pattern.IGNITION), ManualRead8806.detect(ignites))
        assertTrue("too few bars is no read", ManualRead8806.detect(ignites.takeLast(8)).isEmpty())
    }

    @Test fun theLadderCutsScalesOutAndTrails() {
        assertEquals(-24.5, ManualRead8806.Ladder(1.0, 0L).step(10_000L, 0.79, 0.79, 0.79)!!, 1e-9)          // the stop, filled at the price seen (8816)
        assertEquals(-23.5, ManualRead8806.Ladder(1.0, 0L).step(10_000L, 1.0, 0.79, 0.85)!!, 1e-9)           // a wick through it closes back above: the level
        assertEquals(1.5, ManualRead8806.Ladder(1.0, 0L).step(600_000L, 1.05, 1.05, 1.05)!!, 1e-9)           // ten flat minutes
        val l = ManualRead8806.Ladder(1.0, 0L)
        assertNull(l.step(10_000L, 1.5, 1.5, 1.5))                       // a third off at +50 %
        assertNull(l.step(20_000L, 2.5, 2.5, 2.5))                       // a third off at +150 %
        assertNull(l.step(30_000L, 2.0, 2.0, 2.0))                       // above the 35 % trail (1.625)
        assertEquals(50.0 / 3.0 + 50.0 + 20.0 - 3.5, l.step(40_000L, 1.6, 1.6, 1.6)!!, 1e-6)   // 16.67 + 50 + a third of +60 (seen under the 1.625 trail), less cost
        val giveBack = ManualRead8806.Ladder(1.0, 0L)
        assertNull(giveBack.step(10_000L, 1.5, 1.5, 1.5))
        // superseded by V5.0.8816 — the floor is the line, not a guaranteed fill: a print seen under it fills where it was seen
        assertEquals(50.0 / 3.0 + (2.0 / 3.0) * -10.0 - 3.5, giveBack.step(20_000L, 0.9, 0.9, 0.9)!!, 1e-6)
    }

    @Test fun aPatternIsArmedOnlyOnTheHalfItWasNotJudgedOn() {
        val paying = List(60) { if (it % 2 == 0) 5.0 else 15.0 }
        assertTrue(ManualRead8806.armed(paying))
        assertFalse("too few closed fires", ManualRead8806.armed(paying.take(40)))
        assertFalse("the later half lost", ManualRead8806.armed(paying.take(30) + List(30) { if (it % 2 == 0) -25.0 else 10.0 }))
        assertFalse("the earlier half lost", ManualRead8806.armed(List(30) { -5.0 } + paying.take(30)))
        val (n, mean, se) = ManualRead8806.record(listOf(5.0, 15.0))
        assertEquals(2, n); assertEquals(10.0, mean, 1e-12); assertEquals(5.0, se, 1e-9)
    }

    @Test fun theReadIsWiredToTheTapeAdmissionAndReport() {
        assertTrue(src("engine/truth/TradeTape8100.kt").contains("ManualRead8806.onPrint8806(mint, eventMs, priceSol, isBuy, recvMs)"))
        assertTrue(src("engine/truth/CandidateAdmission8136.kt").contains("?: ManualRead8806.evidence8806(ts.mint, nowMs)"))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("ManualRead8806.statusLine8806()"))
        assertTrue(ManualRead8806.statusLine8806().contains("SHADOW"))
    }

    @Test fun theCryptoAltLaneIsBackedByItsOwnPaperRecord() {
        assertEquals(setOf("CRYPTO_SPOT", "CRYPTO_ALT"), CryptoSpotPaperBacking8788.BACKED_LANES_8806)
        val b = src("engine/truth/CryptoSpotPaperBacking8788.kt")
        // 8870 names capacity separately; disabled/wrong-lane still cannot return backing readiness.
        assertTrue(b.contains("if (!enabled8788 || l !in BACKED_LANES_8806) return Availability8870.DISABLED_OR_WRONG_LANE"))
        // Superseded in part by V5.0.8886: the readiness also reads the candidate's trend cell (default none = the lane alone).
        assertTrue(b.contains("return availability8870(lane, nowMs, cell) == Availability8870.READY"))
        assertFalse(CryptoSpotPaperBacking8788.backs8788("CORE", System.currentTimeMillis()))
        assertTrue(b.contains(".firstOrNull { it.strategy.equals(lane, ignoreCase = true) }"))
    }
}
