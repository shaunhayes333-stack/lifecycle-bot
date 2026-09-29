package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7477RevisionGatedMaintenanceTest {
    @Test fun parity_reuses_only_unchanged_authority_revisions() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/PositionRegistryParityAudit6464.kt").readText()
        assertTrue(s.contains("CanonicalPositionAuthority6441.mutationCount7387()"))
        assertTrue(s.contains("AuthoritySnapshotVersion6464.snapshotVersion()"))
        assertTrue(s.contains("POSITION_PARITY_UNCHANGED_REUSED_7477"))
        assertTrue(s.contains("rebuildFromCanonical6475()"))
    }

    @Test fun full_reconstruct_is_lazy_on_unchanged_rows_and_positions() {
        val r = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalReconciler6441.kt").readText()
        val m = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForensicRowMirror6442.kt").readText()
        assertTrue(r.contains("fullReconstructIfChanged7477"))
        assertTrue(r.contains("CanonicalPositionAuthority6441.mutationCount7387()"))
        assertTrue(r.contains("RECONCILER_FULL_UNCHANGED_REUSED_7477"))
        assertTrue(m.contains("fun revision7477(): Long = acceptedCount.get()"))
    }

    @Test fun scheduled_parity_does_not_blindly_rebuild_first() {
        val b = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val region = b.substringAfter("name = \"position_parity_audit_6464\"").substringBefore("// V5.0.6467 §P0 (item 9)")
        assertFalse(region.contains("rebuildFromCanonical6475()"))
        assertTrue(region.contains("PositionRegistryParityAudit6464.audit()"))
    }
}
