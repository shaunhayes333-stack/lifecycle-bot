package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7522ImmutableExecutionAuthorityTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/" + path).readText()

    @Test fun immutable_ticket_size_wins_over_mutable_mint_seal() {
        val s = src("engine/ExecutableOpenGate.kt")
        val block = s.substringAfter("V5.0.7522 — the immutable ExecutionIntent owns")
            .substringBefore("if (effectiveResolvedSize6497 < 0.0)")
        assertTrue(block.contains("immutableTicket?.resolvedSize"))
        assertTrue(block.contains("immutableTicket != null && immutableResolvedSize7522 != null"))
        assertFalse(block.contains("immutableTicket!!"))
        assertTrue(block.contains("MINT_SEAL_IGNORED_IMMUTABLE_INTENT_7522"))
        val ticketIdx = block.indexOf("immutableResolvedSize7522 != null")
        val legacyIdx = block.indexOf(".authoritativeSize(mint")
        assertTrue(ticketIdx >= 0 && legacyIdx > ticketIdx)
    }

    @Test fun restored_inventory_does_not_fabricate_fresh_causal_open() {
        val s = src("engine/truth/CanonicalPositionAuthority6441.kt")
        val fn = s.substringAfter("private fun lockEntryMetricsAtOpen6636(")
            .substringBefore("// ─── Position mutation gate")
        assertTrue(fn.contains("freshExecution6715"))
        assertTrue(fn.contains("CAUSAL_OPEN_RESTORE_SUPPRESSED_7522"))
        assertTrue(s.contains("lockEntryMetricsAtOpen6636(p, freshExecution6715 = false)"))
        assertTrue(s.contains("lockEntryMetricsAtOpen6636(recovered, freshExecution6715 = false)"))
        assertTrue(s.contains("lockEntryMetricsAtOpen6636(promoted)"))
    }
}
