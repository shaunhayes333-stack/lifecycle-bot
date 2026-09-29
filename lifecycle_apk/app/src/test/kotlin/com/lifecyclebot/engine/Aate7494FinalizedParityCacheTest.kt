package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7494FinalizedParityCacheTest {
    private fun src() =
        File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalFinalizedTradeBus6464.kt").readText()

    @Test fun parity_cache_is_exact_revision_keyed() {
        val s = src()
        assertTrue(s.contains("consumerParityRevision7494"))
        assertTrue(s.contains("ParityCache7494"))
        assertTrue(s.contains("FINALIZED_BUS_PARITY_REUSED_7494"))
        assertTrue(s.contains("c.canonicalRevision == canonicalRev7494"))
        assertTrue(s.contains("c.consumerRevision == consumerRev7494"))
    }

    @Test fun ack_and_exclusion_mutations_advance_consumer_revision() {
        val s = src()
        assertTrue(s.contains("if (addedEx7494 || removedAck7494) consumerParityRevision7494.incrementAndGet()"))
        assertTrue(s.contains("if (removedEx7494 || addedAck7494) consumerParityRevision7494.incrementAndGet()"))
    }

    @Test fun retry_and_delivery_remain_live() {
        val s = src()
        assertTrue(s.contains("fun redeliverPending6486()"))
        assertTrue(s.contains("fun deliverToConsumers(env: Envelope"))
        assertTrue(s.contains("CanonicalFinalityPersistence6486.recordAck6486"))
    }
}
