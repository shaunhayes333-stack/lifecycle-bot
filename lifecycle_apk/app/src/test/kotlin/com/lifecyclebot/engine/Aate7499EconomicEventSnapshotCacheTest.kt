package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7499EconomicEventSnapshotCacheTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/truth/EconomicEventSchema6464.kt").readText()

    @Test fun event_snapshot_is_exact_version_cached() {
        val s = src()
        assertTrue(s.contains("data class SnapshotCache7499"))
        assertTrue(s.contains("val version7499 = eventVersion.get()"))
        assertTrue(s.contains("c.version == version7499"))
        assertTrue(s.contains("ECONOMIC_EVENT_SNAPSHOT_REUSED_7499"))
    }

    @Test fun race_does_not_publish_stale_snapshot() {
        assertTrue(src().contains("if (eventVersion.get() == version7499)"))
    }

    @Test fun bounded_event_store_semantics_remain() {
        val s = src()
        assertTrue(s.contains("while (eventCount.get() > CAP)"))
        assertTrue(s.contains("foldEvictedIntoReplayCarry6489"))
        assertTrue(s.contains("eventVersion.incrementAndGet()"))
    }
}
