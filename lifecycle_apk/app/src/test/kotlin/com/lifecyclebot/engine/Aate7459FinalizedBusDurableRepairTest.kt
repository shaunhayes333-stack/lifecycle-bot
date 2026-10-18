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
        // V5.0.7500 — full (non-partial) terminal SELLs come from the terminal index.
        assertTrue(fn.contains("EconomicEventSchema6464.fullTerminalSellsByPosition7500()"))
        assertTrue(fn.contains("EntryStrategySnapshot6450.snapshot(p.positionId)"))
        assertTrue(fn.contains("sell.allocatedCostBasisSol <= 0.0"))
    }

    @Test fun reconstruction_uses_net_economics_not_gross_sell_return() {
        val s = src("engine/truth/FinalizedLearningReconciler7423.kt")
        // V5.0.7835 — net economics come from the whole closed position (fees included).
        assertTrue(s.contains("WholePositionEconomics7835.from(p)"))
        assertTrue(s.contains("val netPnl = econ8214.first"))  // 8214: whole-position first inside repairEconomics8214
        assertTrue(s.contains("val netPct = econ8214.second") && s.contains("if (w.netSol.isFinite() && w.returnPct.isFinite()) return w.netSol to w.returnPct"))
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
        assertTrue(s.contains(".repairDurableBusPublishFailures7459("))
        assertTrue(s.contains("delay(FULL_CADENCE_MS)"))
        assertTrue(s.contains("FULL_CADENCE_MS"))
    }
}
