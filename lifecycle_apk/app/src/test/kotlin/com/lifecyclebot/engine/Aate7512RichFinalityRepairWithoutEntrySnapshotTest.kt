package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7512RichFinalityRepairWithoutEntrySnapshotTest {
    private fun src() =
        File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedLearningReconciler7423.kt").readText()

    @Test fun rich_finality_is_checked_before_entry_snapshot_requirement() {
        val s = src()
        val fn = s.substringAfter("fun repairDurableBusPublishFailures7459").substringBefore("fun statusLine")
        assertTrue(fn.indexOf("durableEventForPosition7459") < fn.indexOf("if (entry == null && rich == null)"))
    }

    @Test fun typed_sell_only_path_still_requires_entry_snapshot() {
        val fn = src().substringAfter("fun repairDurableBusPublishFailures7459").substringBefore("fun statusLine")
        assertTrue(fn.contains("if (entry == null && rich == null)"))
        assertTrue(fn.contains("FINALIZED_BUS_REPAIR_ENTRY_SNAPSHOT_MISSING_7459"))
    }

    @Test fun optional_strategy_fields_are_not_fabricated_without_snapshot() {
        val fn = src().substringAfter("fun repairDurableBusPublishFailures7459").substringBefore("fun statusLine")
        assertTrue(fn.contains("val entryScore = entry?.entryScore?.coerceIn(0, 100) ?: 0"))
        assertTrue(fn.contains("entryTradeType = entry?.entryTradeType.orEmpty()"))
        assertTrue(fn.contains("entrySource = entry?.entrySource.orEmpty()"))
        assertTrue(fn.contains("marketRegime = entry?.entryMarketRegime.orEmpty()"))
    }
}
