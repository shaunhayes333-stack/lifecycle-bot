package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexInversion8310
import com.lifecyclebot.engine.truth.SelectionDoors8310
import com.lifecyclebot.engine.truth.SelectionDoors8310.TicketDoor8310
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8310SelectionTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    // ── D1: the runner-ticket door reads its own verified live record ──

    @Test fun ticketDoorStaysOpenWhileExploringOrPaying() {
        val now = 100L * 3_600_000L
        assertEquals(TicketDoor8310.OPEN, SelectionDoors8310.ticketDoor8310(emptyList(), 0L, now))
        assertEquals(TicketDoor8310.OPEN, SelectionDoors8310.ticketDoor8310(listOf(-0.027, -0.01), 0L, now))
        // one runner pays for the losers: open
        assertEquals(TicketDoor8310.OPEN, SelectionDoors8310.ticketDoor8310(listOf(-0.012, -0.012, 0.15, -0.027), 0L, now))
    }

    @Test fun losingTicketRecordSuspendsTheDoorWithAProbePerWindow() {
        val now = 100L * 3_600_000L
        val losing = listOf(-0.0267, -0.012, -0.011)
        assertEquals(TicketDoor8310.PROBE, SelectionDoors8310.ticketDoor8310(losing, 0L, now))
        assertEquals(TicketDoor8310.SUSPENDED, SelectionDoors8310.ticketDoor8310(losing, now - 3_600_000L, now))
        assertEquals(TicketDoor8310.PROBE, SelectionDoors8310.ticketDoor8310(losing, now - SelectionDoors8310.TICKET_PROBE_MS_8310, now))
        // only the last window counts: old losers age out behind newer wins
        val recovered = List(SelectionDoors8310.TICKET_WINDOW_8310) { 0.01 } + losing
        assertEquals(TicketDoor8310.OPEN, SelectionDoors8310.ticketDoor8310(recovered, now, now))
    }

    // ── D2 / D3: real-record checks at the admit ──

    @Test fun aSetupWhoseLiveClosesLoseIsRefusedFromTwoCloses() {
        assertTrue(SelectionDoors8310.setupLiveLosing8310(2.0, -12.0))
        assertFalse(SelectionDoors8310.setupLiveLosing8310(1.0, -85.7))
        assertFalse(SelectionDoors8310.setupLiveLosing8310(3.0, 4.0))
        assertFalse(SelectionDoors8310.setupLiveLosing8310(Double.NaN, -5.0))
    }

    @Test fun thePayingPondIsTailDependentButABroadWinnerIsNot() {
        // 8302 best60: PUMP_PORTAL_WS|SHITCOIN|MC_10K_100K|AGE_LT15M n704 +231.7% wr43% run33%: non-runners <= +7.5%.
        assertTrue(SelectionDoors8310.tailOnly8310(231.7, 0.43, 0.33))
        // a cell where most coins win is not a lottery
        assertFalse(SelectionDoors8310.tailOnly8310(25.0, 0.70, 0.10))
        assertFalse(SelectionDoors8310.tailOnly8310(-5.0, 0.30, 0.05))
    }

    // ── Cortex: a REFUSE column that beats STRONG stays in shadow ──

    @Test fun refuseBeatsStrongNeedsReadsOnBothSides() {
        assertTrue(CortexInversion8310.refuseBeatsStrong8310(11.0, 90.7, 6.0, 12.0))
        assertFalse(CortexInversion8310.refuseBeatsStrong8310(9.0, 90.7, 6.0, 12.0))
        assertFalse(CortexInversion8310.refuseBeatsStrong8310(11.0, 90.7, 4.0, 12.0))
        assertFalse(CortexInversion8310.refuseBeatsStrong8310(40.0, -14.5, 10.0, -10.9 + 20.0))
        assertFalse(CortexInversion8310.refuseBeatsStrong8310(40.0, Double.NaN, 10.0, 1.0))
    }

    @Test fun shadowWhyNamesInvertedLaneAndSetup() {
        assertEquals("INVERTED", CortexInversion8310.shadowWhy8310(true, 0.0, 0.0, 0.0, 0.0, 0.0, Double.NaN, 0.0, Double.NaN))
        assertEquals("LANE", CortexInversion8310.shadowWhy8310(false, 93.0, -8.6, 20.0, -24.0, 0.0, Double.NaN, 0.0, Double.NaN))
        // SHITCOIN|REFUSE|LAUNCH_LADDER_PROVEN n11 +90.7% vs the lane's STRONG (setup STRONG too thin -> lane STRONG)
        assertEquals("SETUP", CortexInversion8310.shadowWhy8310(false, 243.0, -12.0, 40.0, 3.0, 11.0, 90.7, 2.0, 200.0))
        // the setup's own STRONG cell, when thick enough, is the comparison
        assertNull(CortexInversion8310.shadowWhy8310(false, 243.0, -12.0, 40.0, 3.0, 11.0, 90.7, 8.0, 120.0))
        // refuse below strong everywhere: the refusal binds
        assertNull(CortexInversion8310.shadowWhy8310(false, 243.0, -12.0, 40.0, 3.0, 30.0, -20.0, 8.0, 5.0))
    }

    @Test fun onlyTheRefuseColumnPowersAreShadowed() {
        assertTrue(CortexInversion8310.refuseColumnRule8310("C3_PROVEN_NEGATIVE_EDGE"))
        assertTrue(CortexInversion8310.refuseColumnRule8310("C3_RELATIVE_REFUSE_8019"))
        assertFalse(CortexInversion8310.refuseColumnRule8310("C1_HARD_SAFETY"))
        assertFalse(CortexInversion8310.refuseColumnRule8310("C6_SETUP_DEAD_8082"))
        assertFalse(CortexInversion8310.refuseColumnRule8310("C4_WAIT_FOR_DIP"))
        assertFalse(CortexInversion8310.refuseColumnRule8310(null))
    }

    // ── Safety proof: a deferred live buy gets a current proof, not the cached one ──

    @Test fun cachedReportServesOnlyCallersThatAcceptItsAge() {
        val now = 1_000_000L
        assertTrue(SafetyProofPriority8310.cacheUsable8310(now - 500_000L, Long.MAX_VALUE, now))
        assertTrue(SafetyProofPriority8310.cacheUsable8310(now - 60_000L, SafetyProofPriority8310.LIVE_PROOF_MAX_AGE_MS_8310, now))
        assertFalse(SafetyProofPriority8310.cacheUsable8310(now - 300_000L, SafetyProofPriority8310.LIVE_PROOF_MAX_AGE_MS_8310, now))
        assertFalse(SafetyProofPriority8310.cacheUsable8310(0L, SafetyProofPriority8310.LIVE_PROOF_MAX_AGE_MS_8310, now))
        // the window is inside the buy door's own freshness (PreTradeHardGate SAFETY_FRESH_MS = 120 s)
        assertTrue(SafetyProofPriority8310.LIVE_PROOF_MAX_AGE_MS_8310 < 120_000L)
    }

    @Test fun freshProofIsForcedForExplicitRefreshesAndStaleLiveProspectsOnly() {
        assertTrue(SafetyProofPriority8310.needsFresh8310(true, true, false, 10_000L, 0L))
        assertTrue(SafetyProofPriority8310.needsFresh8310(true, false, true, 200_000L, Long.MAX_VALUE))
        assertFalse(SafetyProofPriority8310.needsFresh8310(true, false, true, 60_000L, Long.MAX_VALUE))
        assertFalse(SafetyProofPriority8310.needsFresh8310(true, false, true, 200_000L, 5_000L))
        assertFalse(SafetyProofPriority8310.needsFresh8310(true, false, false, 600_000L, Long.MAX_VALUE))
        assertFalse(SafetyProofPriority8310.needsFresh8310(false, true, true, 600_000L, Long.MAX_VALUE))
    }

    // ── wiring pins ──

    @Test fun wiringPins() {
        val cortex = src("engine/cortex/Cortex7885.kt")
        assertTrue(cortex.contains("CortexInversion8310.refuseColumnRule8310(rule0) && shadowInvertedRefusal8310(ts, a)) null else rule0"))
        assertTrue(cortex.contains("SUSPENDED_REFUSE_BEATS_STRONG_SIZE_UP_8310"))
        assertTrue(cortex.contains("refuseShadow8310(a.lane, null) == null) to"))

        val checker = src("engine/TokenSafetyChecker.kt")
        assertTrue(checker.contains("maxCacheAgeMs: Long = Long.MAX_VALUE"))
        assertTrue(checker.contains("SafetyProofPriority8310.cacheUsable8310(it.checkedAt, maxCacheAgeMs"))
        assertTrue(checker.contains("if (SafetyProofPriority8310.heliusFirst8310(mint)) com.lifecyclebot.engine.truth.OnChainMintAuthorityProof7248.resolve(mint, liveGate8094 = true)"))
        assertFalse(SafetyProofPriority8310.heliusFirst8310(""))
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("SafetyProofPriority8310.forceFresh8310(canonicalMint, explicitSafetyRefresh, ts.safety.checkedAt)"))
        assertTrue(bot.contains("maxCacheAgeMs = SafetyProofPriority8310.maxCacheAge8310(forceFresh8310)"))
        assertTrue(bot.contains("if (needsFirstCheck || explicitSafetyRefresh || forceFresh8310) {"))
        val pre = src("engine/PreTradeHardGate.kt")
        assertTrue(pre.contains("SafetyProofPriority8310.noteDeferred8310(ts.mint, reason)"))
        // the buy door's own proof checks are unchanged
        assertTrue(pre.contains("SAFETY_FRESH_MS = 120_000L"))
        assertTrue(pre.contains("FREEZE_AUTHORITY_UNVERIFIED_7238"))
        assertTrue(src("engine/HandoffThroughput8190.kt").contains("SafetyProofPriority8310.targets8310(nowMs)"))

        val ticket = src("engine/RunnerTicket8073.kt")
        assertTrue(ticket.contains("SelectionDoors8310.ticketMayIssue8310(nowMs)"))
        assertTrue(ticket.contains("SelectionDoors8310.noteTicketClose8310(e.positionId, e.mode, e.economicFinality"))
        val gate = src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(gate.contains("SelectionDoors8310.admitRefusal8310(ts, l8059, setup8083, nowMs)"))
        val chase = gate.substringAfter("internal fun chaseRefusal8397(", "")
            .substringBefore("private fun developingRunGrab8117(", "")
        // superseded (8480): label-record chase stand-downs removed (they read survivors with labels lost: LOSS_BOUGHT_TOP=13)
        assertFalse(chase.contains("labelStandsDownChase8310"))
        assertTrue(chase.contains("return \"CHASING_SPIKE_8065\""))
        // the admit refusal sits after the proven-only door and before the chase rule
        assertTrue(gate.indexOf("SelectionDoors8310.admitRefusal8310") > gate.indexOf("provenOnlyAdmitted8059.incrementAndGet()"))
        assertTrue(gate.indexOf("SelectionDoors8310.admitRefusal8310") in 0 until gate.indexOf("if (snap8065 != null) chaseRefusal8397("))
    }
}
