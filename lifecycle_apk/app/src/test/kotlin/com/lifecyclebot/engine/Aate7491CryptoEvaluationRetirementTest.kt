package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7491CryptoEvaluationRetirementTest {
    private fun src() =
        File("src/main/kotlin/com/lifecyclebot/perps/DynamicAltTokenRegistry.kt").readText()

    @Test fun stale_identity_eviction_retires_subordinate_evaluation_state() {
        val s = src()
        val helper = s.substringAfter("private fun releaseEvaluationIdentity7491")
            .substringBefore("private val lastDiscoveryCycle")
        assertTrue(helper.contains("evaluationInflight6615.remove(identity)"))
        assertTrue(helper.contains("evaluationInflightStartedAt6692.remove(identity)"))
        assertTrue(helper.contains("evaluationCompleted6615.remove(identity)"))
        assertTrue(helper.contains("evaluationTerminalKeys6615.removeIf"))
        assertTrue(helper.contains("evaluationProgressKeys6615.removeIf"))
        assertTrue(helper.contains("evaluationProgressStamp6580.keys.removeIf"))
    }

    @Test fun cleanup_runs_only_on_actual_registry_drop() {
        val s = src()
        val eviction = s.substringAfter("registry.entries.removeIf")
            .substringBefore("if (freshEvicted6547 > 0)")
        assertTrue(eviction.contains("if (drop)"))
        assertTrue(eviction.contains("releaseEvaluationIdentity7491(tok.canonicalIdentity6544)"))
        assertTrue(eviction.contains("if (held7245)"))
        assertTrue(eviction.contains("return@removeIf false"))
    }
}
