package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.6692/6697 regression locks for the 5.0.6691 forensic repair plus 6697 asset scoping. */
class Aate6692RuntimeRepairTest {
    @Test
    fun `nonterminal release retains immutable ticket and adaptive retry authority`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val release = src.substringAfter("fun releaseAttemptNonTerminal6514")
            .substringBefore("fun terminalizeAttempt6514")
        assertFalse(release.contains("executionTickets.remove(attemptId)"))
        assertTrue(release.contains("PAPER_TICKET_AUTHORITY_RETAINED_6692"))
        assertTrue(src.contains("effectiveRetryPendingTtlMs6692"))
        assertTrue(src.contains("EXEC_RESTORED_SPECIALIST_VIA_TRUNK_6692"))
        assertTrue(src.contains("isSourceBucketLane(requestedLane)"))
    }

    @Test
    fun `static 24 slot meme choke is gone`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/SlotHealthGate.kt").readText()
        assertFalse(src.contains("MEME_TURNOVER_ABSOLUTE_CAP_6689 = 24"))
        assertTrue(src.contains("memeTurnoverAbsoluteCap6689(): Int = Int.MAX_VALUE"))
        assertTrue(src.contains("MEME_EXIT_PRIORITY_ADVISORY_6692"))
    }

    @Test
    fun `paper cross asset history repair excludes native solana and precedes replay`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperTransaction6486.kt").readText()
        assertTrue(src.contains("it.assetClass != AssetClass.SOLANA_TOKEN"))
        val reconcile = src.substringAfter("fun reconcileJournalAuthority6663")
            .substringBefore("private fun awaitJournalBoundary6669")
        assertTrue(reconcile.indexOf("repairCryptoHistory6659()") in 1 until reconcile.indexOf("JournalEconomicReplay6619.replay()"))
        assertTrue(src.contains("PAPER_CANONICAL_HISTORY_REPROJECTED_6692"))
    }

    @Test
    fun `stale quote synthetic backstop cannot manufacture paper profit`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperTransaction6486.kt").readText()
        val close = src.substringAfter("fun close(positionId: String")
            .substringBefore("private fun recordCloseProjection6659")
        assertTrue(close.contains("STALE_QUOTE_EMERGENCY_25PCT_BACKSTOP"))
        assertTrue(close.contains("FRESH_EXIT_QUOTE_REQUIRED_6737"))
        assertFalse(close.contains("minOf(grossProceedsSol, boundedGross6692)"))
        assertTrue("Unknown economics must return before the ledger mutation",
            close.indexOf("FRESH_EXIT_QUOTE_REQUIRED_6737") < close.indexOf("CanonicalPaperTerminalBridge6469.finalizeSell"))
    }

    @Test
    fun `paper ledger journal divergence is telemetry only and per event purity remains hard`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/EconomicPurityGate6504.kt").readText()
        assertTrue(src.contains("JournalEconomicReplay6619.latestLedgerDivergenceSol()"))
        assertTrue(src.contains("ECONOMIC_PURITY_ACCOUNT_REPLAY_DIVERGENCE_TELEMETRY_6712"))
        assertFalse(src.contains("ECONOMIC_PURITY_GLOBAL_PAPER_DIVERGENCE_6692"))

        val gate = src.substringAfter("fun shouldExcludeFromAnalytics")
            .substringBefore("fun size()")
        assertTrue(gate.contains("val excluded = local || invariantBroken || historical"))
        assertFalse(gate.contains("|| unreconciledPaperAccount"))
    }
}
