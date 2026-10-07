package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7846EndToEndAuthorityTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test
    fun live_entry_history_is_mode_pure() {
        val s = src("engine/truth/ExecutableEntryAuthority6450.kt")
        val streak = s.substringAfter("fun consecutiveLossesFor6488").substringBefore("fun defensiveActiveFor6488")
        assertFalse(streak.contains("PaperSeededPrior6991"))
        assertFalse(streak.contains("cohortKey(\"PAPER\""))
        val pays = s.substringAfter("private fun lanePaysEv7334").substringBefore("// ─────────────────────────────────────────────────────────────────────")
        assertTrue(pays.contains("if (live)"))
        assertTrue(pays.contains("liveSnap?.evPct"))
    }

    @Test
    fun sealed_intent_cannot_be_redecided_by_entry_authority() {
        val s = src("engine/ExecutableOpenGate.kt")
        assertTrue(s.contains("private fun sealedEntryAuthority7846("))
        assertTrue(s.contains("sealedEntryAuthority7846(immutableAuthority6513, ticketAuthority6564)"))
        assertTrue(s.contains("sealed_execution_intent_authority_7846"))
    }

    @Test
    fun permit_consumes_existing_ticket_and_exact_live_size() {
        val s = src("engine/FinalExecutionPermit.kt")
        assertTrue(s.contains("if (!sizeFinalityTicketPresent6491)"))
        assertFalse(s.contains("if (!finalityPrechecked || !sizeFinalityTicketPresent6491)"))
        assertTrue(s.contains("SealedExecutionSize7835.refusal("))
        assertTrue(s.contains("LIVE_PERMIT_EXACT_SEALED_SIZE_7846"))
    }
}
