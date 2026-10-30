package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CostLedger7962
import com.lifecyclebot.engine.truth.NetEdge8030
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8463 — owner: "we need to fix the lane specific round trip costs. that's excessive and unnecessary". A coin is priced
 * on its own itemised trip (NetEdge8030.costPct8030: route, venue, ticket, depth, realised slippage); the lane's mark-based
 * medians (8457: MOONSHOT 8.78 %, SHITCOIN 7.69 % against 3.56 / 3.88 % itemised — market drift on rug-drain and stop sells)
 * no longer reach a decision: the economics bar is the owner's 1.5x, and a profit exit's expected slippage is the wallet's
 * realised sell fills against their quotes.
 */
class Aate8463LaneCostTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun theEntryBarIsTheOwners1point5xOnTheCoinsOwnTrip() {
        assertEquals(NetEdge8030.EDGE_MULT, CostLedger7962.margin8391("MOONSHOT", 0.033, 3.56), 0.0)
        assertEquals(NetEdge8030.EDGE_MULT, CostLedger7962.margin8391("SHITCOIN", 0.5, 3.88), 0.0)
        // the pure reserve rule is kept for the report; it no longer sets a bar
        assertTrue(CostLedger7962.adaptiveMargin8391(List(30) { 12.0 }, 3.0) > NetEdge8030.EDGE_MULT)
        val ne = src("engine/truth/NetEdge8030.kt").substringAfter("fun costPct8030(").substringBefore("@Volatile private var lastBreakdown8169")
        assertTrue("the mark-based lane median never lifts a coin's price", src("engine/RoundTrip8418.kt")
            .contains("fun markLift8418(markPct: Double?, receiptMeasured: Boolean): Double? = null"))
        assertTrue(ne.contains("val model = if (components8151.isFinite()) components8151 + slip8418 + impact8418"))
    }

    @Test fun aProfitExitIsDiscountedByRealisedSellSlippageNotTheLanesMarkGap() {
        val pe = src("engine/ProfitExitTruth8163.kt").substringAfter("private fun expectedSlip8189(").substringBefore("fun preflight(")
        assertTrue(pe.contains("com.lifecyclebot.engine.RealisedSlip8418.sellReceipt8463()"))
        assertFalse(pe.contains("sellSidePct8189"))
        assertTrue(src("engine/RoundTrip8418.kt").contains("median8418(sells.toList(), 1)?.let { it to sells.size }"))
        // fewer than 5 measured sells: the 1.0 % default; measured: the receipt median, clamped to [0.5 %, cap]
        assertEquals(100, ProfitExitTruth8163.expectedSlippageBps8189(0.3, 2, 500))
        assertEquals(50, ProfitExitTruth8163.expectedSlippageBps8189(0.1, 7, 500))
    }

    @Test fun theReportNamesTheMarkGapForWhatItIs() {
        val c = src("engine/Costs8369.kt")
        assertTrue(c.contains("\"modelVsMarkGap8463{\${mvm.ifBlank { \"-\" }}}] \""))
        assertFalse(c.contains("modelVsMeasured{"))
    }
}
