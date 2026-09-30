package com.lifecyclebot.engine.truth

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate7527MarkSuppressionFreshnessTest {
    @Test fun newer_strict_mark_may_clear_stale_suppression_only() {
        MarkIdentityExecutionGate7230.clearForTest()
        val mint = "MarkFresh7527${System.nanoTime()}"
        MarkIdentityExecutionGate7230.suppressMint7243(mint, "IDENTITY_BROKEN")
        val at = MarkIdentityExecutionGate7230.suppressionAtMs7527(mint)
        assertTrue(at > 0L)
        assertFalse(MarkIdentityExecutionGate7230.clearWithNewerExecutableMark7527(mint, at, "same_time"))
        assertTrue(MarkIdentityExecutionGate7230.isExecutionSuppressed7243(mint))
        assertTrue(MarkIdentityExecutionGate7230.clearWithNewerExecutableMark7527(mint, at + 1L, "new_strict_mark"))
        assertFalse(MarkIdentityExecutionGate7230.isExecutionSuppressed7243(mint))
    }

    @Test fun canonical_entry_resolver_wires_strict_mark_to_timestamped_clear() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPriceMark6522.kt").readText()
        assertTrue(s.contains("clearWithNewerExecutableMark7527"))
        assertTrue(s.contains("markTimestampMs = strict.timestampMs"))
        assertTrue(s.contains("markTimestampMs = admitted.timestampMs"))
    }
}
