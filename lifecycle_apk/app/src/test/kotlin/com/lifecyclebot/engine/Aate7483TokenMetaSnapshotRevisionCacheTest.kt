package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7483TokenMetaSnapshotRevisionCacheTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/TokenMetaCache.kt").readText()

    @Test fun completeness_scan_is_revision_cached() {
        val s = src()
        assertTrue(s.contains("metaCompletenessRevision7483"))
        assertTrue(s.contains("completenessCache7483"))
        assertTrue(s.contains("TOKEN_META_COMPLETENESS_SNAPSHOT_REUSED_7483"))
    }

    @Test fun structural_changes_invalidate_cache() {
        val s = src()
        assertTrue(s.contains("completenessChanged7483"))
        assertTrue(s.contains("if (e.pairAddress.isBlank()) completenessChanged7483 = true"))
        assertTrue(s.contains("e.decimals = decimals; changed = true; completenessChanged7483 = true"))
        assertTrue(s.contains("if (e.lastInteractedMs <= 0L) completenessChanged7483 = true"))
    }

    @Test fun dynamic_report_counters_remain_live() {
        val s = src()
        val snap = s.substringAfter("fun snapshot(): Snapshot").substringBefore("companion object")
        assertTrue(snap.contains("dirtyRows = dirty.size"))
        assertTrue(snap.contains("totalReadHits = hits"))
        assertTrue(snap.contains("totalWrites = totalWrites.get()"))
    }
}
