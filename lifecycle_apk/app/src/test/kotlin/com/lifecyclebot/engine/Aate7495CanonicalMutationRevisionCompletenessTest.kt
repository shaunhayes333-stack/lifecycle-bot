package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7495CanonicalMutationRevisionCompletenessTest {
    private fun src() =
        File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt").readText()

    @Test fun abort_and_quarantine_bump_revision() {
        val s = src()
        val abort = s.substringAfter("fun abortEntry6485").substringBefore("fun quarantine")
        assertTrue(abort.contains("muts.incrementAndGet()"))
        val quarantine = s.substringAfter("fun quarantine(positionId").substringBefore("private fun isDuplicate")
        assertTrue(quarantine.contains("muts.incrementAndGet()"))
    }

    @Test fun replay_rebuild_bumps_revision_after_reconstruction() {
        val s = src()
        val fn = s.substringAfter("fun rebuildPaperFromEvents6486").substringBefore("fun pendingEntryPositions6461")
        val bump = fn.lastIndexOf("muts.incrementAndGet()")
        val ret = fn.lastIndexOf("return positions.values.count")
        assertTrue(bump >= 0 && ret > bump)
        assertTrue(fn.contains("CANONICAL_REVISION_BUMP_REBUILD_7495"))
    }

    @Test fun maintenance_lifecycle_mutations_bump_revision() {
        val s = src()
        val cancel = s.substringAfter("fun cancelStalePendingEntries6461").substringBefore("data class LifecycleClassification")
        assertTrue(cancel.contains("CANONICAL_REVISION_BUMP_PENDING_CANCEL_7495"))
        val purge = s.substringAfter("fun purgeZeroQtyLifecycleOpens6752").substringBefore("internal fun resetForTest")
        assertTrue(purge.contains("CANONICAL_REVISION_BUMP_ZERO_QTY_PURGE_7495"))
        assertTrue(purge.contains("muts.incrementAndGet()"))
    }
}
