package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7432ForwardProgressRepairTest {

    @Test
    fun `locked venue provider IO is bounded away from exit scheduler`() {
        val src = File("src/main/kotlin/com/lifecyclebot/network/LockedVenueMarks7392.kt").readText()
        assertTrue(src.contains("PROVIDER_DEADLINE_MS_7432"))
        assertTrue(src.contains("SynchronousQueue<Runnable>()"))
        assertTrue(src.contains("boundedProvider7432(\"CURVE\")"))
        assertTrue(src.contains("boundedProvider7432(\"POOL\")"))
        assertTrue(src.contains("LOCKED_VENUE_PROVIDER_TIMEOUT_7432"))
    }

    @Test
    fun `crypto silent evaluation leases are released for retry not completed`() {
        val src = File("src/main/kotlin/com/lifecyclebot/perps/DynamicAltTokenRegistry.kt").readText()
        val fn = src.substringAfter("private fun reapSilentInflightLeases7432")
            .substringBefore("private val EVIDENCE_TTL_MS_6580")
        assertTrue(fn.contains("evaluationInflight6615.remove(identity, generation)"))
        assertTrue(fn.contains("evaluationInflightStartedAt6692.remove(identity, born)"))
        assertTrue(fn.contains("CRYPTO_EVAL_SILENT_LEASE_REAPED_7432"))
        assertFalse(fn.contains("evaluationCompleted6615[identity]"))
        assertTrue(src.contains("suspend fun runDiscoveryCycle"))
        assertTrue(src.contains("reapSilentInflightLeases7432(now)"))
    }
}
