package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7869SpecialistExecTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun pendingAttemptBindsByMintAndIsConsumedOnce() {
        LivePendingAttempt7868.bind("MINT_A", "attempt-1", "MOONSHOT", nowMs = 1_000L)
        val b = LivePendingAttempt7868.take("MINT_A", nowMs = 2_000L)
        assertEquals("attempt-1", b?.attemptId)
        assertEquals("MOONSHOT", b?.lane)
        assertNull(LivePendingAttempt7868.take("MINT_A", nowMs = 3_000L))
        LivePendingAttempt7868.bind("MINT_B", "attempt-2", "CASHGEN", nowMs = 0L)
        assertNull("expired binding is not used", LivePendingAttempt7868.take("MINT_B", nowMs = 31L * 60_000L))
    }

    @Test fun liveMarkReadyAndRefusalsUseTheSealedGateIntent() {
        val exec = src("engine/Executor.kt")
        val mark = exec.substringAfter("private fun stampLiveEntryMark7790").substringBefore("private fun ")
        assertTrue(mark.contains("liveIntentFor7868(ts.mint)"))
        val term = exec.substringAfter("private fun terminalizeCanonicalLiveFailure7790").substringBefore("    /**")
        assertTrue(term.contains("liveIntentFor7868(ts.mint)"))
        val lookup = exec.substringAfter("private fun liveIntentFor7868").substringBefore("private fun buyPhase")
        assertTrue(lookup.contains("ExecutableOpenGate.activeExecutionIntent6519(\"LIVE\", mint, cv)"))
    }

    @Test fun pendingProofBindsTheReservationAndWalletPromotionStampsExec() {
        assertTrue(src("engine/Executor.kt").contains("pendingProofBind7868(sealedIntent7835)"))
        val rec = src("engine/LiveCanonicalRecovery6686.kt")
        assertTrue(rec.contains("LivePendingAttempt7868.take(mint)"))
        assertTrue(rec.contains("ToolkitSignalSheet.recordEntryExecOpen7809(b7868.lane, b7868.attemptId, b7868.attemptId)"))
        assertFalse(rec.contains("recordEntryExecOpen7809(\"STANDARD\""))
    }

    @Test fun terminalStageIdsAreNormalisedToPositionEvents() {
        assertEquals("LIVE:mint:abc", ToolkitSignalSheet.normalizeTerminalEventId7868("FINALIZED", "LIVE:mint:abc"))
        assertEquals("attempt-x", ToolkitSignalSheet.normalizeTerminalEventId7868("TICKET", "attempt-x"))
        // Unknown position id stays as is (no fabrication).
        assertEquals("LIVE_PROTECT_7807:zzz", ToolkitSignalSheet.normalizeTerminalEventId7868("FINALIZED", "LIVE_PROTECT_7807:zzz"))
    }

    @Test fun otherCandidatesReservationsReduceFreeCash() {
        val now = 100_000L
        val all = mapOf("A" to (0.0427 to now - 1_000L), "B" to (0.0427 to now - 200_000L))
        assertEquals(0.0427, WalletCapacitySeal7868.reservedExcluding(all, "C", now), 1e-12)
        assertEquals(0.0, WalletCapacitySeal7868.reservedExcluding(all, "A", now), 1e-12)
        assertTrue(src("engine/FinalDecisionGate.kt").contains("WalletCapacitySeal7868.freeCashFor(ts.mint, WalletManager.cachedSolBalance())"))
    }
}
