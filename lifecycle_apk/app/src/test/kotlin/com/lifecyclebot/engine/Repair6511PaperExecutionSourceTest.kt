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
    fun sub_floor_buys_promote_to_economic_minimum_005() = synchronized(PaperAccountLedger6430) {
        PaperAccountLedger6430.resetForTest()
        PaperAccountLedger6430.initialize(5.5099)
        OrderSizeResolver6441.updatePaperExecutableMinimumSol(
            PaperPreTicketSizeFloor6511.boundedMinimum(0.005),
        )
        val requested = PaperPreTicketSizeFloor6511.effectiveRequested(
            requestedSol = 0.02419,
            minimumSol = OrderSizeResolver6441.paperExecutableMinimumSol(),
            availableCashSol = PaperAccountLedger6430.cashSol(),
        )
        val resolved = TraderSizingBridge6444.resolveForLane(
            laneName = "QUALITY",
            requestedSol = requested,
            walletSol = PaperAccountLedger6430.cashSol(),
            paperMode = true,
            overrideLaneRiskCapSol = 1.0,
            mintForSeal = "6511-test-mint",
        )
        assertEquals(0.05, requested, 1e-9)
        assertTrue(resolved.finalSizeSol >= 0.05)
        assertTrue(resolved.finalSizeSol > 0.0)
        assertTrue(resolved.executable)
        assertFalse(resolved.reason.contains("BELOW_MIN_EXECUTABLE"))
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
        val promotion = executor.indexOf("val effectiveRequestedSol6511")
        val bridge = executor.indexOf("TraderSizingBridge6444.resolveForLane", promotion)
        val reject = executor.indexOf("PAPER_BUY_REJECTED_BEFORE_TICKET_SIZE_6490", bridge)
        val ticket = executor.indexOf("ExecutableOpenGate.canOpenExecutablePosition", reject)
        val commit = executor.indexOf("V5.0.6485 — ATOMIC PAPER BUY COMMIT", ticket)
        assertTrue(promotion >= 0 && promotion < bridge && bridge < reject && reject < ticket && ticket < commit)
        assertTrue(executor.contains("PAPER_SEALED_NOTIONAL_CONSUMED_6552") && executor.contains("sealedNotional6552"))
        assertTrue(executor.contains("PAPER_BUY_SIZE_FLOOR_PROMOTED_6511"))
    }
}
