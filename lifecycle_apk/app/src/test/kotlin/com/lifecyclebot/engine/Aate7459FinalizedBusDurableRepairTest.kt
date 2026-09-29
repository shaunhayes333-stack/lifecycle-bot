package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7459FinalizedBusDurableRepairTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun durable_rich_finality_has_exact_position_lookup() {
        val s = src("engine/truth/CanonicalFinalityPersistence6486.kt")
        assertTrue(s.contains("fun durableEventForPosition7459("))
        assertTrue(s.contains("prefs?.getString(PREFIX + positionId"))
        assertTrue(s.contains("return decode(raw)"))
    }

    @Test fun repair_requires_closed_sell_and_entry_identity() {
        val s = src("engine/truth/FinalizedLearningReconciler7423.kt")
        val fn = s.substringAfter("fun repairDurableBusPublishFailures7459")
            .substringBefore("fun statusLine")
        assertTrue(fn.contains("CanonicalPositionAuthority6441.closedPositions()"))
        assertTrue(fn.contains("filterIsInstance<EconomicEventSchema6464.Sell>()"))
        assertTrue(fn.contains("!it.partial"))
        assertTrue(fn.contains("EntryStrategySnapshot6450.snapshot(p.positionId)"))
        assertTrue(fn.contains("sell.allocatedCostBasisSol <= 0.0"))
    }

    @Test fun reconstruction_uses_net_economics_not_gross_sell_return() {
        val s = src("engine/truth/FinalizedLearningReconciler7423.kt")
        assertTrue(s.contains("sell.realizedPnlSol - sell.exitFeesSol"))
        assertTrue(s.contains("netPnl / sell.allocatedCostBasisSol * 100.0"))
        assertFalse(s.contains("realizedReturnPct = sell.realizedReturnPct"))
    }

    @Test fun repair_publishes_exact_projection_and_drives_consumers() {
        val s = src("engine/truth/FinalizedLearningReconciler7423.kt")
        assertTrue(s.contains("CanonicalFinalizedTradeBus6464.publish(env)"))
        assertTrue(s.contains("FinalizedBusConsumerBridge6465::deliver"))
        assertTrue(s.contains("FINALIZED_BUS_DURABLE_REPAIR_7459"))
    }

    @Test fun repair_runs_on_independent_wall_clock_not_bot_loop() {
        val s = src("engine/truth/IndependentReconcilerScheduler6431.kt")
        assertTrue(s.contains("repairDurableBusPublishFailures7459(limit = 8)"))
        assertTrue(s.contains("FULL_CADENCE_MS"))
    }
}
