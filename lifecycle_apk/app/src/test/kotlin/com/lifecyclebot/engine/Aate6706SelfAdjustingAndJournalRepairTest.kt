package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-lock regression coverage for the 5.0.6705 failures. */
class Aate6706SelfAdjustingAndJournalRepairTest {

    @Test
    fun canonical_finality_owns_self_adjusting_lane_feedback() {
        val bridge = File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedBusConsumerBridge6465.kt").readText()
        val authority = File("src/main/kotlin/com/lifecyclebot/engine/learning/AdaptiveWinRateAuthority6706.kt").readText()
        val sizing = File("src/main/kotlin/com/lifecyclebot/engine/truth/OrderSizeResolver6441.kt").readText()

        assertTrue(bridge.contains("AdaptiveWinRateAuthority6706.recordCanonicalOutcome("))
        assertTrue(authority.contains("const val TARGET_WR = 0.50"))
        assertTrue(authority.contains("BELOW_TARGET_RETRAINING_HOLD"))
        assertTrue(authority.contains("LearningPersistence.save("))
        assertTrue(authority.contains("LanePolicy.recordOutcome("))
        assertTrue(authority.contains("RetrainingDecay.noteOutcome("))
        assertTrue(sizing.contains("AdaptiveWinRateAuthority6706.entryDecision(laneName)"))
        assertTrue(sizing.contains("ORDER_SIZE_ADAPTIVE_WR_HELD_6706"))
        assertTrue(sizing.contains("if (wr6706.probe) nudgedRisk"))
        assertFalse(
            "6706 WR reprobe must not be re-floored to the old 0.35 minimum",
            sizing.contains("wr6706.sizeMultiplier).coerceIn(0.35")
        )
    }

    @Test
    fun accounting_repair_uses_exact_typed_terminal_receipts() {
        val repair = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalJournalTerminalRepair6706.kt").readText()
        val reconcile = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForensicReconciliation6635.kt").readText()

        assertTrue(repair.contains("filterIsInstance<EconomicEventSchema6464.Sell>()"))
        assertTrue(repair.contains("val eventId = sell.idempotencyKey"))
        assertTrue(repair.contains("canonicalConsumedRaw = sell.soldQty"))
        assertTrue(repair.contains("soldCostBasisSol = sell.allocatedCostBasisSol"))
        assertTrue(repair.contains("grossProceedsSol = sell.grossProceedsSol"))
        assertTrue(repair.contains("pnlSol = sell.realizedPnlSol"))
        assertTrue(reconcile.contains("CanonicalJournalTerminalRepair6706.repairMissingTerminalLegs()"))
        assertTrue(reconcile.contains("quantityDeltaRaw6647 == java.math.BigInteger.ZERO"))
        assertTrue(reconcile.contains("PaperAccountLedger6430.reconcileFromJournal6663("))
    }
}
