package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.SignalTape8828
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8994 — a base broken on buying, read on the launch's own 10-second print bars: a signal beside the owner's others,
 * graded by the same replay and armed only by its own proven record.
 */
class Aate8994BaseBreakoutSignalTest {
    private val wide = SignalTape8828.BASE_WIDE_8994
    private val tight = SignalTape8828.BASE_TIGHT_8994

    /** A flat base in bars 1..[last], then bar [last]+1 at [breakPx] with [buySol] bought and [sellSol] sold; the print that closes it. */
    private fun run(last: Int, breakPx: Double, buySol: Double, sellSol: Double, baseHigh: Double = 1.02e-7): List<String> {
        val t = SignalTape8828()
        t.onPrint(1_000, 1.00e-7, true, 0.2, -1, true)
        for (k in 1..last) {
            t.onPrint(k * 10_000L + 1_000, 1.00e-7, true, 0.2, -1, true)
            t.onPrint(k * 10_000L + 5_000, baseHigh, false, 0.2, -1, true)
        }
        val bk = (last + 1) * 10_000L
        t.onPrint(bk + 1_000, breakPx, true, buySol, -1, true)
        t.onPrint(bk + 5_000, breakPx * 0.995, false, sellSol, -1, true)
        return t.onPrint(bk + 11_000, breakPx, true, 0.1, -1, true)   // the next bar's print closes the breakout bar
    }

    @Test fun aBaseBrokenOnBuyingTurnsTheSignalOn() {
        val early = run(last = 6, breakPx = 1.10e-7, buySol = 1.0, sellSol = 0.1)      // breakout bar 7: 70 s
        assertTrue(early.toString(), wide in early)
        assertFalse("the tight setting waits for 90 s", tight in early)
        val later = run(last = 9, breakPx = 1.10e-7, buySol = 1.0, sellSol = 0.1)      // breakout bar 10: 100 s
        assertTrue(later.toString(), wide in later && tight in later)
    }

    @Test fun noBaseNoBuyingNoBreakNoSignal() {
        assertFalse("sold into: buys are 9 % of the bar", wide in run(9, 1.10e-7, buySol = 0.1, sellSol = 1.0))
        assertFalse("not over the base", wide in run(9, 1.03e-7, buySol = 1.0, sellSol = 0.1))
        assertFalse("a 50 % range is not a base", wide in run(9, 1.60e-7, buySol = 1.0, sellSol = 0.1, baseHigh = 1.50e-7))
        val wideOnly = run(9, 1.36e-7, buySol = 1.0, sellSol = 0.1, baseHigh = 1.30e-7)   // a 30 % base: wide yes, tight no
        assertTrue(wide in wideOnly); assertFalse(tight in wideOnly)
        assertFalse("thin buying: under 1.5x the base's bar volume", tight in run(9, 1.10e-7, buySol = 0.5, sellSol = 0.1))
        assertTrue("the wide setting needs only the base's own volume", wide in run(9, 1.10e-7, buySol = 0.5, sellSol = 0.1))
        assertFalse("too young: bar 4", wide in run(3, 1.10e-7, buySol = 1.0, sellSol = 0.1))
    }

    @Test fun wired() {
        assertTrue(SignalTape8828.IDS.containsAll(listOf(wide, tight)))
        assertFalse("a bar signal needs no wallet", wide in SignalTape8828.WALLET_IDS || tight in SignalTape8828.WALLET_IDS)
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/SignalTape8828.kt").readText()
        assertTrue(src.contains("BASE_WIDE_8994 -> baseBreakout8994(minIdx = 6L, minBars = 3, rangeMax = 0.35, overMin = 0.02, volX = 1.0, shareMin = 0.55)"))
        assertTrue(src.contains("BASE_TIGHT_8994 -> baseBreakout8994(minIdx = 9L, minBars = 4, rangeMax = 0.25, overMin = 0.03, volX = 1.5, shareMin = 0.65)"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 179. The base breakout on the launch tape (8994)"))
    }
}
