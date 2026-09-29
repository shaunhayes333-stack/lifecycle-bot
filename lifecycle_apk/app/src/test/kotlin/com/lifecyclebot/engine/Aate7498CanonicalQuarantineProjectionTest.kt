package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7498CanonicalQuarantineProjectionTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt").readText()

    @Test fun quarantine_and_pending_reads_use_position_view_cache() {
        val s = src()
        assertTrue(s.contains("val pending: List<Position>"))
        assertTrue(s.contains("val quarantinedIdsByMode: Map<String, Set<String>>"))
        assertTrue(s.contains("return views.quarantinedIdsByMode[key] ?: emptySet()"))
        assertTrue(s.contains("fun pendingEntryPositions6461(): List<Position> = positionViews7496().pending"))
    }

    @Test fun all_and_mode_specific_quarantine_indexes_are_built() {
        val s = src()
        assertTrue(s.contains("put(\"*\", quarantined7498.mapTo(HashSet())"))
        assertTrue(s.contains("quarantined7498.groupBy { it.mode.lowercase() }"))
    }
}
