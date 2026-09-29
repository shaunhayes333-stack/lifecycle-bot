package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7493FinalizedReconcileRevisionCacheTest {
    @Test fun unique_bus_revision_changes_only_after_nonduplicate_publish() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalFinalizedTradeBus6464.kt").readText()
        val fn = s.substringAfter("fun publish(env: Envelope)").substringBefore("fun deliverToConsumers")
        val duplicateReturn = fn.indexOf("return false")
        val revision = fn.indexOf("canonicalRevision7493.incrementAndGet()")
        assertTrue(duplicateReturn >= 0 && revision > duplicateReturn)
        assertTrue(s.contains("fun canonicalRevision7493(): Long"))
    }

    @Test fun reconcile_snapshot_key_reads_all_three_authorities() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedLearningReconciler7423.kt").readText()
        assertTrue(s.contains("CanonicalPositionAuthority6441.mutationCount7387()"))
        assertTrue(s.contains("EconomicEventSchema6464.version()"))
        assertTrue(s.contains("CanonicalFinalizedTradeBus6464.canonicalRevision7493()"))
        assertTrue(s.contains("FINALIZED_RECONCILE_SNAPSHOT_REUSED_7493"))
    }

    @Test fun durable_repair_path_is_not_cached_away() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedLearningReconciler7423.kt").readText()
        val repair = s.substringAfter("fun repairDurableBusPublishFailures7459")
        assertTrue(repair.contains("CanonicalFinalizedTradeBus6464.publish(env)"))
        assertTrue(repair.contains("CanonicalFinalizedTradeBus6464.deliverToConsumers"))
    }
}
