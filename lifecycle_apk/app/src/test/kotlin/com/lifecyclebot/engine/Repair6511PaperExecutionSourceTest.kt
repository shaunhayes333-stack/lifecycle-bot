package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.OrderSizeResolver6441
import com.lifecyclebot.engine.truth.PaperAccountLedger6430
import com.lifecyclebot.engine.truth.TraderSizingBridge6444
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Repair6511PaperExecutionSourceTest {
    @Test
    fun sub_floor_adaptive_buys_promoted_once_to_min_6600() = synchronized(PaperAccountLedger6430) {
        // V5.0.6600 — restore canonical executable-minimum semantics.
        // Operator directive Feb 2026: sub-min requests with hard caps
        // that can fund minExec are promoted exactly once, so the
        // specialist can execute at trade #1 (was: silently collapsed to
        // BELOW_MIN_EXECUTABLE, blocking the learning loop entirely).
        PaperAccountLedger6430.resetForTest()
        // V5.0.7277 — the paper floor is now fee-aware (FeeAwareSizeFloor7277,
        // above the historical 0.05), so fund enough to cover it plus the fee reserve.
        PaperAccountLedger6430.initialize(1.0)
        OrderSizeResolver6441.updatePaperExecutableMinimumSol(
            PaperPreTicketSizeFloor6511.boundedMinimum(0.005),
        )
        val resolved = TraderSizingBridge6444.resolveForLane(
            laneName = "QUALITY",
            requestedSol = 0.02419,
            walletSol = PaperAccountLedger6430.cashSol(),
            paperMode = true,
            overrideLaneRiskCapSol = 1.0,
            mintForSeal = "6511-test-mint",
        )
        // V5.0.6833 §EDGE_CAPACITY_REDISTRIBUTION — QUALITY carries a
        //   RuntimeTune6833.laneCapacityMultiplier(...) of 1.125 which is
        //   applied inside the adaptive chain. The exact-request assertion
        //   is preserved by tracking the multiplier, not by hard-coding
        //   the post-multiplier value, so the test remains stable if the
        //   multiplier is retuned upstream.
        val expectedRequested6833 = 0.02419 *
            com.lifecyclebot.engine.truth.RuntimeTune6833.laneCapacityMultiplier("QUALITY")
        assertEquals(expectedRequested6833, resolved.requestedSol, 1e-9)
        assertTrue(resolved.executable)
        assertEquals("OK_MIN_PROMOTED_6600", resolved.reason)
    }

    @Test
    fun paper_ticket_and_commit_remain_downstream_of_canonical_resolution() {
        val executor = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val minimumBlock = executor.substring(
            executor.indexOf("private fun minConfiguredPaperTradeSol"),
            executor.indexOf("private fun clampPaperTradeSol"),
        )
        assertFalse(minimumBlock.contains("c.smallBuySol"))
        assertFalse(minimumBlock.contains("paperSimulatedBalance * 0.001"))
        assertTrue(minimumBlock.contains("PaperPreTicketSizeFloor6511.boundedMinimum"))
        // V5.0.7850 / V5.0.7852 — paperBuy no longer re-resolves size; it consumes
        // the sealed ticket's resolvedSize, still before the ticket check and commit.
        val consumed = executor.indexOf("val effectiveBuySol6451 = ticket6513?.resolvedSize ?: run {")
        val sealed = executor.indexOf("val canonicalBuyIntentSol6490 = requireNotNull(sealedIntent7835).resolvedSize", consumed)
        val ticket = executor.indexOf("ExecutableOpenGate.canOpenExecutablePosition", sealed)
        val commit = executor.indexOf("V5.0.6485 — ATOMIC PAPER BUY COMMIT", ticket)
        assertTrue(consumed >= 0 && consumed < sealed && sealed < ticket && ticket < commit)
        assertFalse(executor.contains("floorPromotionRequested6511"))
        assertFalse(executor.contains("PAPER_BUY_SIZE_FLOOR_PROMOTED_6511"))
    }
}
