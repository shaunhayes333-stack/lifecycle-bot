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
        assertTrue(fn.indexOf("durableEventForPosition7459") > 0)
        assertTrue(fn.indexOf("durableEventForPosition7459") < fn.indexOf("val terminalOnly7521 = entry == null && rich == null"))
    }

    @Test fun optional_strategy_fields_are_not_fabricated_without_snapshot() {
        val fn = src().substringAfter("fun repairDurableBusPublishFailures7459").substringBefore("fun statusLine")
        assertTrue(fn.contains("val entryScore = if (restoredEntry7526) 0 else entry?.entryScore?.coerceIn(0, 100) ?: 0"))
        assertTrue(fn.contains("entryTradeType = if (restoredEntry7526) \"\" else entry?.entryTradeType.orEmpty()"))
        assertTrue(fn.contains("entrySource = if (restoredEntry7526) \"RESTORED_NONTRAINABLE_7526\" else entry?.entrySource.orEmpty()"))
        assertTrue(fn.contains("marketRegime = if (restoredEntry7526) \"\" else entry?.entryMarketRegime.orEmpty()"))
    }
}
