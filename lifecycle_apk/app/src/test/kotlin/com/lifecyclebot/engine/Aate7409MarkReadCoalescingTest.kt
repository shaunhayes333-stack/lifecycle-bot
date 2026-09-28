package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate7409MarkReadCoalescingTest {
    @Test fun mark_cache_read_labels_are_coalesced_but_exact_counters_remain() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/MarkIdentityRepairAuthority7236.kt").readText()
        assertTrue(s.contains("CACHE_READ_EMIT_INTERVAL_MS_7409"))
        assertTrue(s.contains("cacheHits.incrementAndGet()"))
        assertTrue(s.contains("cacheMissesStale.incrementAndGet()"))
        assertTrue(s.contains("cacheHitEmitAt7409[mint]"))
        assertTrue(s.contains("cacheStaleEmitAt7409[mint]"))
    }
}
