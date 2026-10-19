package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.PairFlow8169
import com.lifecyclebot.engine.truth.TradeShapeLearner7883
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Protects the all-specialist data and native/admission contract in 8240. */
class Aate8240PipelineContinuityTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun missingShapeAsksIndependentHeliusSourceWithoutInventingBars() {
        val o = src("engine/DataOrchestrator.kt")
        assertTrue(o.contains("if (repairShape8168) try {"))
        assertTrue(o.contains("HeliusSwapCandles7819.request7819(ts)"))
        val h = src("engine/truth/HeliusSwapCandles7819.kt")
        assertTrue(h.contains("if (!claimBudget(nowMs)"))
        assertTrue(h.contains("val bars = binPrints7819("))
    }

    @Test fun cachedDexFlowCarriesItsOriginalObservedTime() {
        val d = src("network/DexscreenerApi.kt")
        assertTrue(d.contains("restoreCachedPairFlow8240(chainId, tokenAddress, cached)"))
        assertTrue(d.contains("PairFlow8169.notePair8169(mint, pair, cached.timestamp)"))
        val mint = "AbcdEFGHabcdefghABCDEFGHabcdefghABCDE1234"
        PairFlow8169.clearForTest8169()
        val row = PairFlow8169.Flow8169(10, 2, 30, 10, 7.0, 15.0, 25_000.0, 100_000L)
        PairFlow8169.note8169(mint, row)
        PairFlow8169.note8169(mint, row)
        assertEquals(100_000L, PairFlow8169.peek8169(mint)?.atMs)
        assertEquals("1/1", PairFlow8169.line8169())
        PairFlow8169.clearForTest8169()
    }

    @Test fun exactNegativeStillBindsWhenBroaderShapePopulationDoesNot() {
        assertEquals("EXACT_SETUP", TradeShapeLearner7883.bindingScope8239(true, true, true))
        assertNull(TradeShapeLearner7883.bindingScope8239(true, false, true))
        assertEquals("LANE_FEATURE", TradeShapeLearner7883.bindingScope8239(true, false, false))
        val l = src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(l.contains("shape?.endsWith(\"_LANE_FEATURE_8239\") == true"))
        assertTrue(l.contains("if (shape != null && !broadShapeSuperseded8240)"))
    }

    @Test fun accountingGraceCannotManufactureZeroReconciliation() {
        val s = src("engine/truth/ExecutionSpineAcceptance6647.kt")
        assertTrue(s.contains("ACCOUNTING_SETTLE_GRACE_MS_8240 = 15_000L"))
        assertTrue(s.contains("duration < ExecutionSpineAcceptance6647.MIN_WINDOW_MS + ACCOUNTING_SETTLE_GRACE_MS_8240"))
        assertTrue(s.contains("if (forensic?.reconciled == true && value != null) value else Double.NaN"))
    }
}
