package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464
import com.lifecyclebot.engine.truth.CausalFeedbackAuthority6715
import com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** V5.0.7807 — causal lineage / ticket continuity. */
class Aate7807CausalLineageTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Before fun reset() {
        CausalFeedbackAuthority6715.resetForTest6715()
    }

    private fun terminal(pid: String, mint: String, lane: String, mode: String) =
        CanonicalFinalizedTradeBus6464.Envelope(
            tradeId = "t-$pid", atMs = System.currentTimeMillis(), realizedPnlSol = 0.0,
            realizedReturnPct = 1.0, mint = mint, lane = lane, positionId = pid,
            mode = mode, entryScore = 50, scoreBand = "S41-60", learningEligible = false,
        )

    @Test fun openAfterTerminalDroppedReservationRecoversStampedBand() {
        val lane = "QUALITY"; val mode = "PAPER"
        CausalFeedbackAuthority6715.stampDecision("a0", "mint-0", mode, lane, 50)
        assertTrue(CausalFeedbackAuthority6715.admit("a0", "mint-0", mode, lane, 50).allowed)
        CausalFeedbackAuthority6715.onPositionOpened("p0", mode, "mint-0", lane)
        CausalFeedbackAuthority6715.stampDecision("a1", "mint-1", mode, lane, 50)
        assertTrue(CausalFeedbackAuthority6715.admit("a1", "mint-1", mode, lane, 50).allowed)
        // A same-scope close invalidates a1's reservation while its buy is in flight.
        CausalFeedbackAuthority6715.onTerminal(terminal("p0", "mint-0", lane, mode))
        CausalFeedbackAuthority6715.onPositionOpened("p1", mode, "mint-1", lane)
        assertEquals(
            listOf("LANE|PAPER|QUALITY", "BAND|PAPER|QUALITY|S41-60"),
            CausalFeedbackAuthority6715.positionScopesForTest7807("p1"),
        )
        // Evidence is consumed by one OPEN; it is never reused.
        CausalFeedbackAuthority6715.onPositionOpened("p1b", mode, "mint-1", lane)
        assertEquals("BAND|PAPER|QUALITY|UNKNOWN", CausalFeedbackAuthority6715.positionScopesForTest7807("p1b")?.last())
    }

    @Test fun liveDecisionStampWithoutReservationIsValidEvidence() {
        CausalFeedbackAuthority6715.stampDecision("a4", "mint-4", "LIVE", "MOONSHOT", 70)
        CausalFeedbackAuthority6715.onPositionOpened("p4", "live", "mint-4", "MOONSHOT")
        assertEquals("BAND|LIVE|MOONSHOT|S61+", CausalFeedbackAuthority6715.positionScopesForTest7807("p4")?.last())
    }

    @Test fun evidenceNeverCrossesModeMintOrLane() {
        CausalFeedbackAuthority6715.stampDecision("a5", "mint-5", "LIVE", "CORE", 50)
        CausalFeedbackAuthority6715.onPositionOpened("p5", "PAPER", "mint-5", "CORE")
        CausalFeedbackAuthority6715.onPositionOpened("p6", "LIVE", "mint-6", "CORE")
        CausalFeedbackAuthority6715.onPositionOpened("p7", "LIVE", "mint-5", "TREASURY")
        assertEquals("BAND|PAPER|CORE|UNKNOWN", CausalFeedbackAuthority6715.positionScopesForTest7807("p5")?.last())
        assertEquals("BAND|LIVE|CORE|UNKNOWN", CausalFeedbackAuthority6715.positionScopesForTest7807("p6")?.last())
        assertEquals("BAND|LIVE|TREASURY|UNKNOWN", CausalFeedbackAuthority6715.positionScopesForTest7807("p7")?.last())
    }

    @Test fun normalReservationPathStillWinsAndEmitsNoRecovery() {
        CausalFeedbackAuthority6715.stampDecision("a8", "mint-8", "PAPER", "CASHGEN", 20)
        assertTrue(CausalFeedbackAuthority6715.admit("a8", "mint-8", "PAPER", "CASHGEN", 20).allowed)
        CausalFeedbackAuthority6715.onPositionOpened("p8", "PAPER", "mint-8", "CASHGEN")
        assertEquals("BAND|PAPER|CASHGEN|S11-25", CausalFeedbackAuthority6715.positionScopesForTest7807("p8")?.last())
        val s = src("engine/truth/CausalFeedbackAuthority6715.kt")
        assertTrue(s.contains("CAUSAL_LINEAGE_RECOVERED_FROM_TICKET_7807"))
        assertTrue(s.contains("CAUSAL_OPEN_WITHOUT_RESERVATION_6715"))
    }

    @Test fun aliasLaneStagesLandInTheCanonicalLaneFunnel() {
        SpecialistCausalFunnel6625.resetForTest()
        val mint = "So11111111111111111111111111111111111111112"
        val id = "1:PAPER:$mint:BUY:DIP_HUNTER:${System.nanoTime()}:1"
        ToolkitSignalSheet.recordDeskStage("DIP", "BUY_INTENT", id)
        assertEquals(1, SpecialistCausalFunnel6625.stageCounts6625("DIP_HUNTER")[SpecialistCausalFunnel6625.Stage.INTENT] ?: 0)
        assertTrue(SpecialistCausalFunnel6625.stageCounts6625("DIP").isEmpty())
    }

    @Test fun execAfterIntentRevocationJoinsTheTicketRecordOnItsOwningLane() {
        ExecutableOpenGate.resetForTests()
        SpecialistCausalFunnel6625.resetForTest()
        val mint = "So11111111111111111111111111111111111111112"
        val version = System.nanoTime()
        val lane = "QUALITY"
        val intent = ExecutableOpenGate.ExecutionIntent(
            attemptId = ExecutableOpenGate.canonicalExecutionKey(mint, mode = "PAPER", lane = lane, candidateVersion = version),
            candidateId = "$mint:$version", candidateVersion = version,
            mint = mint, mode = "PAPER", canonicalLane = lane,
            fdgVerdict = "BUY", fdgAllowed = true, authorityVersion = version,
            resolvedSize = 0.1, createdAt = System.currentTimeMillis(), symbol = "TEST",
            effectiveEntryScore7256 = 72,
            finalDecision6613 = ExecutableOpenGate.CanonicalFinalDecision6613.BUY,
            decisionAuthorityId6613 = "FDG:$version",
            fdgDecisionId6613 = "PAPER:$mint:$version:$lane",
            fdgEvidence6613 = "fdgCan=true;hardNo=0",
        )
        assertNotNull(ExecutableOpenGate.registerCanonicalIntent6554(intent))
        ToolkitSignalSheet.recordDeskStage(lane, "TICKET", intent.attemptId)
        ExecutableOpenGate.terminalizeAttempt6514(intent.attemptId, mint, lane)
        // Executor's fallback lane once the sealed intent is gone.
        ToolkitSignalSheet.recordDeskStage("STANDARD", "EXEC", intent.attemptId)
        val snap = SpecialistCausalFunnel6625.laneSnapshot6647(lane)
        assertEquals(1, snap.rawCounts7086[SpecialistCausalFunnel6625.Stage.TICKET] ?: 0)
        assertEquals(1, snap.rawCounts7086[SpecialistCausalFunnel6625.Stage.EXEC] ?: 0)
        assertNull(snap.outcomes["ORPHAN_EXEC_NO_TICKET_7537"])
        assertTrue(SpecialistCausalFunnel6625.stageCounts6625("STANDARD").isEmpty())
    }

    @Test fun ticketMirrorsOnlyFactsSealedIntoTheTicket() {
        val g = src("engine/ExecutableOpenGate.kt")
        val helper = g.substringAfter("private fun mirrorTicketPredecessors7807(").substringBefore("private fun mirrorExistingTicket7807(")
        assertTrue(helper.contains("ticket.executableMarkTimestampMs6613 > 0L"))
        assertTrue(helper.contains("if (sealedMark7807) ToolkitSignalSheet.recordDeskStage(lane, \"MARK_READY\", ticket.attemptId)"))
        assertTrue(helper.contains("ticket.resolvedSize.isFinite() && ticket.resolvedSize > 0.0"))
        assertFalse(helper.contains("MARK_REJECT"))
        // Sealing alone must not reach the ticket mirror (7687 contract).
        val seal = g.substringAfter("fun registerCanonicalIntent6554(").substringBefore("private fun ")
        assertFalse(seal.contains("mirrorTicketPredecessors7807"))
        assertFalse(seal.contains("mirrorExistingTicket7807"))
        // Gate-allow stamps the already-registered ticket instead of skipping it.
        assertTrue(g.contains("executionTickets[execKey]?.let { existing7807 -> mirrorExistingTicket7807(existing7807) }"))
        // FDG-time ticket uses the intent's canonical lane, never the raw request.
        assertFalse(g.contains("ToolkitSignalSheet.recordDeskStage(lane, \"TICKET\", intent.attemptId)"))
    }

    @Test fun recordDeskStageFoldsLanesThroughTheOneAuthority() {
        val s = src("engine/ToolkitSignalSheet.kt")
        val fn = s.substringAfter("fun recordDeskStage(").substringBefore("if (l.isBlank()) return")
        assertTrue(fn.contains("CanonicalLaneIdentity6506.canonical("))
        assertTrue(fn.contains("ticketLineageLane7807("))
    }
}
