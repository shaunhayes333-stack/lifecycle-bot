package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * V5.0.7809 — SIZE -> TICKET -> EXEC continuity on ONE causal record, and
 * position stages bound to their real predecessor. Real objects, no mocks.
 */
class Aate7809FunnelTicketExecTest {
    private val mint = "So11111111111111111111111111111111111111112"
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Before fun reset() {
        ExecutableOpenGate.resetForTests()
        SpecialistCausalFunnel6625.resetForTest()
    }

    private fun sealed(lane: String, version: Long): ExecutableOpenGate.ExecutionIntent {
        val now = System.currentTimeMillis()
        val intent = ExecutableOpenGate.ExecutionIntent(
            attemptId = ExecutableOpenGate.canonicalExecutionKey(mint, mode = "PAPER", lane = lane, candidateVersion = version),
            candidateId = "$mint:$version", candidateVersion = version,
            mint = mint, mode = "PAPER", canonicalLane = lane,
            fdgVerdict = "BUY", fdgAllowed = true, authorityVersion = version,
            resolvedSize = 0.1, createdAt = now, symbol = "TEST",
            effectiveEntryScore7256 = 72,
            finalDecision6613 = ExecutableOpenGate.CanonicalFinalDecision6613.BUY,
            decisionAuthorityId6613 = "FDG:$version",
            fdgDecisionId6613 = "PAPER:$mint:$version:$lane",
            fdgEvidence6613 = "fdgCan=true;hardNo=0",
            executableMarkSource6613 = "TEST_MARK_7809",
            executableMarkTimestampMs6613 = now,
            executableMarkPriceUsd6613 = 1.0,
        )
        assertNotNull(ExecutableOpenGate.registerCanonicalIntent6554(intent))
        return intent
    }

    @Test fun ticketJoinsTheSizedRecordWhenAnotherLaneSealedANewerVersion() {
        val v = System.nanoTime()
        val shitcoin = sealed("SHITCOIN", v)
        sealed("QUALITY", v + 1)
        ToolkitSignalSheet.recordDeskStage("SHITCOIN", "SIZED_EXECUTABLE", shitcoin.attemptId)
        // A TICKET callback whose key carries a drifted version and is not
        // itself a ticket. Before 7809 the cross-lane any-version fallback
        // returned QUALITY's newer intent, the lane filter rejected it, and the
        // TICKET opened its own record (raw=1, validated=0: TICKET_CHOKED).
        val drifted = ExecutableOpenGate.canonicalExecutionKey(mint, mode = "PAPER", lane = "SHITCOIN", candidateVersion = v + 7)
        ToolkitSignalSheet.recordDeskStage("SHITCOIN", "TICKET", drifted)
        val snap = SpecialistCausalFunnel6625.laneSnapshot6647("SHITCOIN")
        assertEquals(1, snap.rawCounts7086[SpecialistCausalFunnel6625.Stage.TICKET] ?: 0)
        assertEquals(1, snap.counts[SpecialistCausalFunnel6625.Stage.TICKET] ?: 0)
        assertNull(snap.phantomMissing6883["NO_MARK"])
    }

    @Test fun manipulatedExecLandsOnItsOwnTicketNotTheLaneBlindIntent() {
        val v = System.nanoTime()
        val manip = sealed("MANIPULATED", v)
        ToolkitSignalSheet.recordDeskStage("MANIPULATED", "SIZED_EXECUTABLE", manip.attemptId)
        ToolkitSignalSheet.recordDeskStage("MANIPULATED", "TICKET", manip.attemptId)
        val other = sealed("SHITCOIN", v + 1)
        // Executor resolved SHITCOIN's intent (lane-blind lookup) for a
        // MANIPULATED fill through the shared shitCoinBuy transport.
        ToolkitSignalSheet.recordEntryExecOpen7809("SHITCOIN", manip.attemptId, other.attemptId)
        val snap = SpecialistCausalFunnel6625.laneSnapshot6647("MANIPULATED")
        assertEquals(1, snap.counts[SpecialistCausalFunnel6625.Stage.EXEC] ?: 0)
        assertEquals(1, snap.counts[SpecialistCausalFunnel6625.Stage.OPEN] ?: 0)
        assertNull(SpecialistCausalFunnel6625.stageCounts6625("SHITCOIN")[SpecialistCausalFunnel6625.Stage.EXEC])
    }

    @Test fun moonshotExecKeepsTicketVersionWhenANewerSameLaneIntentExists() {
        val v = System.nanoTime()
        val first = sealed("MOONSHOT", v)
        ToolkitSignalSheet.recordDeskStage("MOONSHOT", "SIZED_EXECUTABLE", first.attemptId)
        ToolkitSignalSheet.recordDeskStage("MOONSHOT", "TICKET", first.attemptId)
        ExecutableOpenGate.terminalizeAttempt6514(first.attemptId, mint, "MOONSHOT")
        val reSealed = sealed("MOONSHOT", v + 1)
        ToolkitSignalSheet.recordEntryExecOpen7809("MOONSHOT", first.attemptId, reSealed.attemptId)
        val snap = SpecialistCausalFunnel6625.laneSnapshot6647("MOONSHOT")
        assertEquals(1, snap.counts[SpecialistCausalFunnel6625.Stage.EXEC] ?: 0)
        assertNull(snap.outcomes["ORPHAN_EXEC_NO_TICKET_7537"])
    }

    @Test fun noExecutorOwnTicketKeepsThePreviousFallback() {
        val v = System.nanoTime()
        val sealedIntent = sealed("QUALITY", v)
        ToolkitSignalSheet.recordDeskStage("QUALITY", "SIZED_EXECUTABLE", sealedIntent.attemptId)
        ToolkitSignalSheet.recordDeskStage("QUALITY", "TICKET", sealedIntent.attemptId)
        ToolkitSignalSheet.recordEntryExecOpen7809("QUALITY", "", sealedIntent.attemptId)
        assertEquals(1, SpecialistCausalFunnel6625.laneSnapshot6647("QUALITY").counts[SpecialistCausalFunnel6625.Stage.EXEC] ?: 0)
    }

    @Test fun exitTriggerCountsOnlyOnTheOpenRecord() {
        val v = System.nanoTime()
        sealed("CORE", v)
        // A record exists for the mint/lane but was never opened: EXIT must
        // not be counted there (it was, via latestCandidateVersion6647).
        ToolkitSignalSheet.recordDeskStage("CORE", "EXIT_TRIGGER", "PAPER:$mint:h$v:STOP_LOSS")
        assertNull(SpecialistCausalFunnel6625.stageCounts6625("CORE")[SpecialistCausalFunnel6625.Stage.EXIT])

        val opened = sealed("QUALITY", v + 3)
        ToolkitSignalSheet.recordDeskStage("QUALITY", "SIZED_EXECUTABLE", opened.attemptId)
        ToolkitSignalSheet.recordDeskStage("QUALITY", "TICKET", opened.attemptId)
        ToolkitSignalSheet.recordEntryExecOpen7809("QUALITY", opened.attemptId, null)
        ToolkitSignalSheet.recordDeskStage("QUALITY", "EXIT_TRIGGER", "PAPER:$mint:h$v:TAKE_PROFIT")
        assertEquals(1, SpecialistCausalFunnel6625.stageCounts6625("QUALITY")[SpecialistCausalFunnel6625.Stage.EXIT] ?: 0)
    }

    @Test fun sourceContracts() {
        val ex = src("engine/Executor.kt")
        assertTrue(ex.contains("recordEntryExecOpen7809(entryLane6450, executionAttemptId6514, sealedIntent6613?.attemptId)"))
        assertTrue(ex.contains("recordEntryExecOpen7809(liveEntryLane6568, recoveredLiveAttemptId, sealedLiveIntent6613?.attemptId ?: pidLive6486)"))
        val sheet = src("engine/ToolkitSignalSheet.kt")
        // Advisory sizes are still withheld; only a sealed/ticketed attempt counts.
        assertTrue(sheet.contains("stage == \"SIZED_EXECUTABLE\" && sealedIntent7471 == null && boundLineage7807 == null"))
        assertTrue(sheet.contains("activeExecutionIntentForLane7809(resolvedMode7471, mint, canonicalLane7809)"))
        val funnel = src("engine/truth/MemeExecutionFunnelReceivers6625.kt")
        val affinity = funnel.substringAfter("fun ensureAffinityLineage7464(").substringBefore("fun stamp6625(")
        assertTrue(affinity.contains(".map { CanonicalLaneIdentity6506.canonical(it) }"))
        // Recovery still fills only DISCOVER/QUALIFY.
        assertFalse(affinity.contains("rec.stages[Stage.MARK]"))
        assertFalse(affinity.contains("rec.stages[Stage.SIZE]"))
    }
}
