package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7497FinalizedCanonicalProjectionCacheTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalFinalizedTradeBus6464.kt").readText()

    @Test fun position_ids_and_earliest_time_share_unique_revision_projection() {
        val s = src()
        assertTrue(s.contains("data class CanonicalProjectionCache7497"))
        assertTrue(s.contains("val revision7497 = canonicalRevision7493.get()"))
        assertTrue(s.contains("fun canonicalPositionIds7018(): Set<String> = canonicalProjection7497().positionIds"))
        assertTrue(s.contains("fun earliestCanonicalAtMs7433(): Long? = canonicalProjection7497().earliestAtMs"))
    }

    @Test fun racing_publish_prevents_projection_cache() {
        assertTrue(src().contains("if (canonicalRevision7493.get() == revision7497) canonicalProjectionCache7497.set(built7497)"))
    }
}
