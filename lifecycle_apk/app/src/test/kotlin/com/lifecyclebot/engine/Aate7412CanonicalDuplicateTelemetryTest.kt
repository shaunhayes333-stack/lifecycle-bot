package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate7412CanonicalDuplicateTelemetryTest {
    @Test fun duplicate_store_receipts_keep_exact_counter_but_coalesce_labels() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalEconomicEvent6635.kt").readText()
        assertTrue(s.contains("duplicateStoreCommits7412.incrementAndGet()"))
        assertTrue(s.contains("DUPLICATE_EMIT_INTERVAL_MS_7412"))
        assertTrue(s.contains("duplicateStoreCommits="))
        assertTrue(s.contains("dupStore="))
    }
}
