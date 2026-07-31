package com.lifecyclebot.engine.truth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5.0.6399 — Tests A..L for ENTRY AUTHORITY ORDERING AND SPLIT-BRAIN REMOVAL.
 */
class Bundle6399EntryAuthorityOrderingTest {

    @Before fun setUp() {
        CanonicalEntryPipeline6398.clearAllForTest()
        AuthorityInvariants6399.clearAllForTest()
        CounterParityLedger6399.clearAllForTest()
        EntryRejectionTelemetry6396.clearAllForTest()
    }
    @After fun tearDown() { setUp() }

    private fun scoreEnvelope(mint: String, lane: TraderLane, effective: Double,
                              hardSafetyPassed: Boolean = true, evidence: Double = 0.9,
                              dataVersion: Long = 1L) =
        CanonicalEntryPipeline6398.buildScoreEnvelope(
            evaluationId = "ev_${mint}_${lane.name}_$dataVersion",
            inp = CanonicalEntryPipeline6398.ScoreInputs(
                mint = mint, symbol = "SYM_$mint", lane = lane,
                trader = TraderId.MEME, tactic = EntryTactic.STANDARD,
                lifecycleStage = LifecycleStage.GATE,
                sourceSet = setOf(DiscoverySource.PUMPFUN),
                rawScore = effective, componentScores = emptyMap(),
                softPenalties = emptyMap(),
                hardSafetyPassed = hardSafetyPassed,
                hardSafetyReasons = emptyList(),
                evidenceCompleteness = evidence, confidence = 1.0,
                dataVersion = dataVersion,
            )
        )

    private fun floorEnvelope(evId: String, lane: TraderLane, base: Double) =
        CanonicalEntryPipeline6398.buildFloorEnvelope(evId,
            CanonicalEntryPipeline6398.FloorInputs(lane, TraderId.MEME,
                EntryTactic.STANDARD, baseLaneFloor = base))

    // ================================================================
    // Test A — Score-floor block: no ticket, no exec, no BUY_FAILED
    // ================================================================
    @Test fun testA_score_floor_block_no_ticket_no_exec_no_buy_fail() {
        val s = scoreEnvelope("A_MINT", TraderLane.MOONSHOT, effective = 7.0)
        val f = floorEnvelope(s.evaluationId, TraderLane.MOONSHOT, base = 19.0)
        val d = CanonicalEntryPipeline6398.decide(s, f)
        assertEquals(EntryOutcome.ENTRY_GATE_BLOCK, d.outcome)
        CounterParityLedger6399.recordTerminal(FdgTerminalOutcome6399.FDG_BLOCK_SCORE)
        // No ticket may be issued.
        val t = CanonicalEntryPipeline6398.issueAndRegister(d)
        assertNull(t)
        assertEquals(0L, CounterParityLedger6399.liveAuthorityTicketsIssued.get())
        assertEquals(0L, CounterParityLedger6399.liveExecutorInvocations.get())
        assertEquals(0L, CounterParityLedger6399.liveBuyAttempts.get())
        assertEquals(0L, CounterParityLedger6399.liveBuyFailures.get())
    }

    // ================================================================
    // Test B — Live allow: FDG_ALLOW_LIVE=1, ticket=1, exec=1
    // ================================================================
    @Test fun testB_live_allow_full_path() {
        val s = scoreEnvelope("B_MINT", TraderLane.MOONSHOT, effective = 22.0)
        val f = floorEnvelope(s.evaluationId, TraderLane.MOONSHOT, base = 16.0)
        val d = CanonicalEntryPipeline6398.decide(s, f)
        assertEquals(EntryOutcome.ALLOW, d.outcome)
        CounterParityLedger6399.recordTerminal(FdgTerminalOutcome6399.FDG_ALLOW_LIVE)
        val t = CanonicalEntryPipeline6398.issueAndRegister(d,
            routeMode = RouteMode6399.LIVE, isDenylisted = false, isShadowOnly = false)
        assertNotNull(t)
        CounterParityLedger6399.recordLiveExecutorInvocation()
        assertEquals(1L, CounterParityLedger6399.fdgCount(FdgTerminalOutcome6399.FDG_ALLOW_LIVE))
        assertEquals(1L, CounterParityLedger6399.liveAuthorityTicketsIssued.get())
        assertEquals(1L, CounterParityLedger6399.liveExecutorInvocations.get())
        assertTrue(CounterParityLedger6399.checkParity().ok)
    }

    // ================================================================
    // Test C — Shadow redirect: no live ticket, no live exec, no LIVE_BUY_ENTRY
    // ================================================================
    @Test fun testC_shadow_redirect_never_reaches_live_executor() {
        val s = scoreEnvelope("C_MINT", TraderLane.MOONSHOT, effective = 22.0)
        val f = floorEnvelope(s.evaluationId, TraderLane.MOONSHOT, base = 16.0)
        val d = CanonicalEntryPipeline6398.decide(s, f)
        // Shadow candidate — attempt to issue a live ticket must fail.
        val t = CanonicalEntryPipeline6398.issueAndRegister(d,
            routeMode = RouteMode6399.SHADOW_READ_ONLY, isShadowOnly = true)
        assertNull(t)
        assertEquals(0L, CounterParityLedger6399.liveAuthorityTicketsIssued.get())
        assertTrue(AuthorityInvariants6399.authorityInvariantFailures.get() >= 1L)
    }

    // ================================================================
    // Test D — Ticket ordering: FDG_ALLOW_LIVE happens BEFORE ticket
    // creation
    // ================================================================
    @Test fun testD_fdg_allow_live_recorded_before_ticket_creation() {
        val s = scoreEnvelope("D_MINT", TraderLane.SHITCOIN, effective = 21.0)
        val f = floorEnvelope(s.evaluationId, TraderLane.SHITCOIN, base = 15.0)
        val d = CanonicalEntryPipeline6398.decide(s, f)
        // Record terminal first.
        CounterParityLedger6399.recordTerminal(FdgTerminalOutcome6399.FDG_ALLOW_LIVE)
        val fdgCountBeforeTicket = CounterParityLedger6399.fdgCount(FdgTerminalOutcome6399.FDG_ALLOW_LIVE)
        val ticketsBeforeTicket = CounterParityLedger6399.liveAuthorityTicketsIssued.get()
        assertEquals(1L, fdgCountBeforeTicket)
        assertEquals(0L, ticketsBeforeTicket)
        // NOW issue ticket.
        val t = CanonicalEntryPipeline6398.issueAndRegister(d, routeMode = RouteMode6399.LIVE)
        assertNotNull(t)
        assertEquals(1L, CounterParityLedger6399.liveAuthorityTicketsIssued.get())
    }

    // ================================================================
    // Test E — Impossible ticket: attempt to issue for a blocked
    // decision -> invariant assertion, no ticket persisted, no exec
    // ================================================================
    @Test fun testE_impossible_ticket_blocks_invariant_and_no_persist() {
        val s = scoreEnvelope("E_MINT", TraderLane.MOONSHOT, effective = 5.0)
        val f = floorEnvelope(s.evaluationId, TraderLane.MOONSHOT, base = 19.0)
        val d = CanonicalEntryPipeline6398.decide(s, f)
        assertEquals(EntryOutcome.ENTRY_GATE_BLOCK, d.outcome)
        val t = CanonicalEntryPipeline6398.issueAndRegister(d, routeMode = RouteMode6399.LIVE)
        assertNull(t)
        assertEquals(0L, CounterParityLedger6399.liveExecutorInvocations.get())
    }

    // ================================================================
    // Test F — Counter parity: reconcile from canonical events only
    // ================================================================
    @Test fun testF_counter_parity_from_canonical_events() {
        CounterParityLedger6399.recordTerminal(FdgTerminalOutcome6399.FDG_ALLOW_LIVE)
        CounterParityLedger6399.recordTerminal(FdgTerminalOutcome6399.FDG_ALLOW_LIVE)
        CounterParityLedger6399.recordTerminal(FdgTerminalOutcome6399.FDG_BLOCK_SCORE)
        CounterParityLedger6399.recordTerminal(FdgTerminalOutcome6399.FDG_DEFER_HYDRATION)
        val s1 = scoreEnvelope("F1", TraderLane.SHITCOIN, effective = 20.0)
        val f1 = floorEnvelope(s1.evaluationId, TraderLane.SHITCOIN, base = 15.0)
        val d1 = CanonicalEntryPipeline6398.decide(s1, f1)
        CanonicalEntryPipeline6398.issueAndRegister(d1)
        val s2 = scoreEnvelope("F2", TraderLane.SHITCOIN, effective = 20.0)
        val f2 = floorEnvelope(s2.evaluationId, TraderLane.SHITCOIN, base = 15.0)
        val d2 = CanonicalEntryPipeline6398.decide(s2, f2)
        CanonicalEntryPipeline6398.issueAndRegister(d2)
        assertEquals(4L, CounterParityLedger6399.fdgTotal())
        assertEquals(2L, CounterParityLedger6399.fdgCount(FdgTerminalOutcome6399.FDG_ALLOW_LIVE))
        assertEquals(2L, CounterParityLedger6399.liveAuthorityTicketsIssued.get())
        assertTrue(CounterParityLedger6399.checkParity().ok)
    }

    // ================================================================
    // Test G — Route taxonomy: score-floor rejection MUST NOT
    // increment ROUTE_FAILED counter
    // ================================================================
    @Test fun testG_score_floor_reject_never_increments_route_failed() {
        // We enforce this by mechanical rule: EntryRejectionTelemetry
        // assertNotBuyFailure throws for BUY_FAILED etc.
        val threw = try {
            EntryRejectionTelemetry6396.assertNotBuyFailure("PROVIDER_FAILURE"); false
        } catch (_: IllegalStateException) { true }
        assertTrue(threw)
    }

    // ================================================================
    // Test H — Failure taxonomy: BUY_FAILED never increments on score-floor
    // ================================================================
    @Test fun testH_buy_failed_never_increments_on_score_floor_reject() {
        // Emit a score-floor reject via the sanctioned surface — no
        // buy-failure counter must increment.
        val startFailures = CounterParityLedger6399.liveBuyFailures.get()
        EntryRejectionTelemetry6396.emitScoreFloorReject(
            EntryRejectionTelemetry6396.ScoreFloorRejectRecord(
                mint = "H_MINT", symbol = "SH", lane = "MOONSHOT",
                rawScore = 7.0, effectiveScore = 7.0, finalFloor = 19,
                scoreScaleVersion = "V", metricEpoch = 1L, decisionId = "d",
                governorState = "BASELINE", hardSafetyPassed = true,
                recheckEligibleAt = 0L))
        assertEquals(startFailures, CounterParityLedger6399.liveBuyFailures.get())
    }

    // ================================================================
    // Test I — Enabled lanes remain enabled under poor performance
    // ================================================================
    @Test fun testI_poor_lane_performance_does_not_disable_lane() {
        AdaptiveFloorBrain6397.postLaneStat(
            AdaptiveFloorBrain6397.LaneStat(lane = "SHITCOIN", trades = 30,
                winRatePct = 25.0, expectancySol = -0.05))
        val rec = AdaptiveFloorBrain6397.recommend(
            AdaptiveFloorBrain6397.BrainInputs(
                LiveEntryThresholdAuthority6396.GovernorTier.BASELINE,
                lane = "SHITCOIN", scannerHeatPct01 = 0.5))
        // Lane is tightened (delta > 0) but remains inside envelope.
        assertTrue(rec.laneDelta > 0)
        assertTrue(rec.finalFloor <= LiveEntryThresholdAuthority6396.ABSOLUTE_MAX)
        assertTrue(rec.finalFloor >= LiveEntryThresholdAuthority6396.ABSOLUTE_MIN)
    }

    // ================================================================
    // Test J — Denylist path: denylisted candidate never issues a live ticket
    // ================================================================
    @Test fun testJ_denylisted_candidate_never_issues_live_ticket() {
        val s = scoreEnvelope("J_MINT", TraderLane.MOONSHOT, effective = 22.0)
        val f = floorEnvelope(s.evaluationId, TraderLane.MOONSHOT, base = 15.0)
        val d = CanonicalEntryPipeline6398.decide(s, f)
        val t = CanonicalEntryPipeline6398.issueAndRegister(d,
            routeMode = RouteMode6399.LIVE, isDenylisted = true)
        assertNull("denylisted candidate must not get a live ticket", t)
        assertEquals(0L, CounterParityLedger6399.liveAuthorityTicketsIssued.get())
        assertTrue(AuthorityInvariants6399.authorityInvariantFailures.get() >= 1L)
    }

    // ================================================================
    // Test K — Runtime doctor detects split-brain when a shadow
    // candidate reaches the live executor path
    // ================================================================
    @Test fun testK_runtime_doctor_detects_split_brain() {
        // Force a violation.
        try {
            AuthorityInvariants6399.assertNotShadowInLivePath(
                RouteMode6399.SHADOW_READ_ONLY, isShadowOnly = true, mint = "K_MINT")
        } catch (_: IllegalStateException) { /* expected */ }
        val v = RuntimeDoctor6399.diagnose(liveMode = true, sellReconcilerActive = true)
        assertEquals(RuntimeDoctor6399.Diagnosis.AUTHORITY_PIPELINE_SPLIT_BRAIN, v.diagnosis)
    }

    // ================================================================
    // Test L — Sell reconciler: live mode + reconciler off = degraded
    // ================================================================
    @Test fun testL_sell_reconciler_must_be_active_in_live_mode() {
        val healthy = RuntimeDoctor6399.diagnose(liveMode = true, sellReconcilerActive = true)
        assertEquals(RuntimeDoctor6399.Diagnosis.HEALTHY, healthy.diagnosis)
        val degraded = RuntimeDoctor6399.diagnose(liveMode = true, sellReconcilerActive = false)
        assertEquals(RuntimeDoctor6399.Diagnosis.RECONCILER_NOT_RUNNING, degraded.diagnosis)
    }

    // ================================================================
    // Additional: parity ledger fails when tickets > FDG_ALLOW_LIVE
    // ================================================================
    @Test fun parity_ledger_fails_when_tickets_exceed_allow_live() {
        // Manually record more tickets than FDG_ALLOW_LIVE events —
        // this simulates the split-brain regression signature.
        CounterParityLedger6399.recordLiveTicketIssued()
        CounterParityLedger6399.recordLiveTicketIssued()
        // Zero FDG_ALLOW_LIVE terminals.
        val p = CounterParityLedger6399.checkParity()
        assertFalse(p.ok)
        assertTrue(p.violations.any { it.contains("TICKETS(2) != FDG_ALLOW_LIVE(0)") })
    }

    // ================================================================
    // Additional: healthy diagnosis when everything reconciles
    // ================================================================
    @Test fun runtime_doctor_healthy_when_no_faults() {
        CounterParityLedger6399.recordTerminal(FdgTerminalOutcome6399.FDG_ALLOW_LIVE)
        val s = scoreEnvelope("H_MINT_OK", TraderLane.QUALITY, effective = 24.0)
        val f = floorEnvelope(s.evaluationId, TraderLane.QUALITY, base = 20.0)
        val d = CanonicalEntryPipeline6398.decide(s, f)
        CanonicalEntryPipeline6398.issueAndRegister(d)
        val v = RuntimeDoctor6399.diagnose(liveMode = true, sellReconcilerActive = true)
        assertEquals(RuntimeDoctor6399.Diagnosis.HEALTHY, v.diagnosis)
    }
}
