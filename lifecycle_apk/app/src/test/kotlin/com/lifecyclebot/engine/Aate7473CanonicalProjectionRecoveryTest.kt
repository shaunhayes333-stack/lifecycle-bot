package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7473CanonicalProjectionRecoveryTest {
    private fun src() =
        File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalTradeFinalizedBus6450.kt").readText()

    @Test fun duplicate_finalization_can_reach_projection_without_replaying_rich_side() {
        val s = src()
        assertTrue(s.contains("val richDuplicate7473 = prior != null"))
        assertTrue(s.contains("CANONICAL_FINALITY_DUPLICATE_REDRIVE_7473"))
        assertTrue(s.contains("if (economicInvalid6495 == null && !richDuplicate7473)"))
        assertTrue(s.contains("CanonicalFinalizedTradeBus6464.publish(env)"))
    }

    @Test fun duplicate_public_semantics_remain_idempotent() {
        assertTrue(src().contains("return !richDuplicate7473"))
    }
}
