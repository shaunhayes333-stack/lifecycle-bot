package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8552 — owner (8550 PAPER log): "there are still 0 paper meme trades being made". The lanes qualified and the FDG
 * approved, then 191 approvals died SIZE_NOT_EXECUTABLE_7835 (MOONSHOT 113, QUALITY 75, CASHGEN 51, EXPRESS 24 ...) with
 * 5.99 SOL of paper cash free. The FDG handed the order resolver a lane cap equal to its own multiplier-shrunk size, so every
 * meme request under the paper floor (~0.05 SOL) was refused LANE_CAP_BELOW_MIN_EXECUTABLE_6490 (resolver skip=292), and a
 * promoted one was killed by `final > riskSized`. LIVE does not hit this: its risk authority promotes a sub-minimum to the
 * route minimum. PAPER now does the same inside the market caps (depth share, curve share), and every zero size is named.
 */
class Aate8552PaperMemeSizeTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun paperSealsUpToWhatTheMarketCarriesLiveKeepsTheRiskSize() {
        // a 0.012 SOL meme request, the pool share carries 0.4 SOL, no curve: paper may seal up to 0.4 (the floor fits)
        assertEquals(0.4, TicketExec8304.sealCeiling8552(true, 0.012, 0.4, Double.MAX_VALUE), 1e-12)
        // a curve coin: the curve share binds
        assertEquals(0.08, TicketExec8304.sealCeiling8552(true, 0.012, 0.4, 0.08), 1e-12)
        // the market cannot carry more than the request: the request is the ceiling (nothing invented)
        assertEquals(0.012, TicketExec8304.sealCeiling8552(true, 0.012, 0.01, Double.MAX_VALUE), 1e-12)
        // LIVE: the risk authority's size, unchanged
        assertEquals(0.012, TicketExec8304.sealCeiling8552(false, 0.012, 0.4, Double.MAX_VALUE), 1e-12)
        assertTrue(TicketExec8304.sizeZeroLine8552().startsWith("fdgSizeZero8552{"))
        TicketExec8304.fdgSizeZero8304("MOONSHOT", "REQUESTED_ZERO")
        assertTrue(TicketExec8304.sizeZeroLine8552().contains(":REQUESTED_ZERO=1"))
    }

    @Test fun theFdgUsesTheCeilingAndNamesEveryZero() {
        val f = src("engine/FinalDecisionGate.kt").substringAfter("private fun resolveExecutableSize7835(").substringBefore("fun logBlockedTrade(")
        // superseded (8559): the ceiling also takes the sealed minimum — a sub-floor request is promoted to the floor, no further
        assertTrue(f.contains("val ceiling8552 = TicketExec8304.sealCeiling8552(config.paperMode, riskSized, depthCap, curveCap, sealedMinimum8269)"))
        assertTrue(f.contains("laneRiskCapSol = minOf(configuredCap, ceiling8552), laneMinExecutableSol = sealedMinimum8269,"))
        assertFalse("superseded: `laneRiskCapSol = minOf(configuredCap, riskSized)` refused every sub-floor paper meme request",
            f.contains("laneRiskCapSol = minOf(configuredCap, riskSized)"))
        assertTrue(f.contains("return TicketExec8304.fdgSizeZero8304(lane, \"REQUESTED_ZERO\")"))
        assertTrue(f.contains("return TicketExec8304.fdgSizeZero8304(lane, \"ABOVE_SEAL_CEILING\")"))
        assertTrue(f.contains("return TicketExec8304.fdgSizeZero8304(lane, \"BELOW_SEALED_MINIMUM\")"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("TicketExec8304.sizeZeroLine8552()"))
    }
}
