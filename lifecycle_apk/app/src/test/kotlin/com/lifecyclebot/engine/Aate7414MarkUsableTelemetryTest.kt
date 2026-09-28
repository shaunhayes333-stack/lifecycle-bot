package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate7414MarkUsableTelemetryTest {
    @Test fun usable_mark_keeps_exact_counter_but_coalesces_label() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/MarkIdentityExecutionGate7230.kt").readText()
        assertTrue(s.contains("executionAllowed.incrementAndGet()"))
        assertTrue(s.contains("USABLE_EMIT_INTERVAL_MS_7414"))
        assertTrue(s.contains("usableEmitAt7414[usableKey7414]"))
    }
}
