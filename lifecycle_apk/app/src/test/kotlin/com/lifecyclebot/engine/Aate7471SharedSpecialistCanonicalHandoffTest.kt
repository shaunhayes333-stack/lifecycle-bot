package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7471SharedSpecialistCanonicalHandoffTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun shared_specialist_causal_identity_prefers_sealed_intent() {
        val s = src("engine/ToolkitSignalSheet.kt")
        val fn = s.substringAfter("V5.0.7471 — downstream specialist stages")
            .substringBefore("// P3 — pending intent backlog drainage.")
        assertTrue(fn.contains("ExecutableOpenGate.activeExecutionIntent6519"))
        assertTrue(fn.contains("sealedIntent7471?.candidateVersion ?: parsedCandidateVersion7471"))
        assertTrue(fn.contains("CanonicalLaneIdentity6506.canonical(intent.canonicalLane)"))
        assertTrue(fn.contains("SPECIALIST_CAUSAL_SEALED_VERSION_REBOUND_7471"))
    }

    @Test fun token_map_proof_can_materialise_shared_executable_mark() {
        val tm = src("engine/TokenMapAuthority.kt")
        val marks = src("engine/truth/CanonicalPriceMark6522.kt")
        assertTrue(tm.contains("fun cachedExecutableForEntry7471"))
        assertTrue(tm.contains("PUMPFUN_BONDING_CURVE_EXECUTABLE"))
        assertTrue(tm.contains("DEX_ROUTABLE"))
        assertTrue(marks.contains("TokenMapAuthority.cachedExecutableForEntry7471(mint)"))
        assertTrue(marks.contains("refreshFromExecutableTokenMap6614("))
        assertTrue(marks.contains("ENTRY_MARK_TOKEN_MAP_MATERIALIZED_7471"))
    }

    @Test fun live_still_requires_executable_mark() {
        val marks = src("engine/truth/CanonicalPriceMark6522.kt")
        val resolver = marks.substringAfter("fun resolveEntryMarkForMode7465(").substringBefore("/** V5.0.6614")
        assertTrue(resolver.indexOf("cachedExecutableForEntry7471") < resolver.indexOf("if (paperMode)"))
        assertTrue(resolver.contains("LIVE_NO_EXECUTABLE_MARK"))
    }
}
