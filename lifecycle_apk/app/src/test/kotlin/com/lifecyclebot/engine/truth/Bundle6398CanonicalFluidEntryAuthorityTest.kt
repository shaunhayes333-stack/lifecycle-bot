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
 * V5.0.6398 — Tests A..J for CANONICAL FLUID ENTRY AUTHORITY REPAIR.
 */
class Bundle6398CanonicalFluidEntryAuthorityTest {

    @Before fun setUp() {
        CanonicalEntryPipeline6398.clearAllForTest()
        EntryGateBlockCache6398.clearAllForTest()
        PairHydrationState6398.clearAllForTest()
        ScannerHeatPublisher6398.clearAllForTest()
        CognitiveAdvisoryBridge6398.clearAllForTest()
        LanePerformancePublisher6398.clearAllForTest()
        AdaptiveFloorBrain6397.clearAllForTest()
        CanonicalPerformanceFilter6395.clearAllForTest()
        EntryRejectionTelemetry6396.clearAllForTest()
    }
    @After fun tearDown() { setUp() }

    private fun scoreEnvelope(mint: String, lane: TraderLane, effective: Double,
                              hardSafetyPassed: Boolean = true, evidence: Double = 0.9,
                              dataVersion: Long = 1L): EntryScoreEnvelope6398 =
        CanonicalEntryPipeline6398.buildScoreEnvelope(
            evaluationId = "ev_${mint}_$dataVersion",
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

    private fun floorEnvelope(evId: String, lane: TraderLane, base: Double): DynamicFloorEnvelope6398 =
        CanonicalEntryPipeline6398.buildFloorEnvelope(evId,
            CanonicalEntryPipeline6398.FloorInputs(lane, TraderId.MEME,
                EntryTactic.STANDARD, baseLaneFloor = base))

    // ================================================================
    // Test A — CALLDOG replay: score 7 → 8, floor 19, one canonical
    // block, zero exec invocations
    // ================================================================
    @Test fun testA_CALLDOG_replay_one_canonical_block_zero_exec() {
        // First evaluation — score 7 vs floor 19 → ENTRY_GATE_BLOCK.
        val s1 = scoreEnvelope("CALLDOG_MINT", TraderLane.MOONSHOT, effective = 7.0)
        val f1 = floorEnvelope(s1.evaluationId, TraderLane.MOONSHOT, base = 19.0)
        val d1 = CanonicalEntryPipeline6398.decide(s1, f1)
        assertEquals(EntryOutcome.ENTRY_GATE_BLOCK, d1.outcome)
        assertTrue(EntryGateBlockCache6398.shouldEmitCanonicalBlock(d1, nowMs = 1_000L))
        // Second evaluation same cycle — score 8, same rounded fp → suppress.
        val s2 = scoreEnvelope("CALLDOG_MINT", TraderLane.MOONSHOT, effective = 8.0)
        val f2 = floorEnvelope(s2.evaluationId, TraderLane.MOONSHOT, base = 19.0)
        val d2 = CanonicalEntryPipeline6398.decide(s2, f2)
        assertFalse("second identical-fp block must be duplicate-suppressed",
            EntryGateBlockCache6398.shouldEmitCanonicalBlock(d2, nowMs = 3_000L))
        // No ticket should have been created for any gate-blocked decision.
        assertNull(CanonicalEntryPipeline6398.mintTicket(d1))
        assertNull(CanonicalEntryPipeline6398.mintTicket(d2))
        assertEquals(0L, CanonicalEntryPipeline6398.ticketsCreated.get())
    }

    // ================================================================
    // Test B — Pipeline ordering: every ALLOW carries the same
    // evaluationId across score, floor, decision, ticket
    // ================================================================
    @Test fun testB_pipeline_ordering_evaluation_id_carries_through() {
        val s = scoreEnvelope("MINT_B", TraderLane.SHITCOIN, effective = 18.0)
        val f = floorEnvelope(s.evaluationId, TraderLane.SHITCOIN, base = 15.0)
        val d = CanonicalEntryPipeline6398.decide(s, f)
        assertEquals(EntryOutcome.ALLOW, d.outcome)
        val t = CanonicalEntryPipeline6398.issueAndRegister(d)
        assertNotNull(t)
        assertEquals(s.evaluationId, t!!.evaluationId)
        assertEquals(f.evaluationId, d.evaluationId)
        assertEquals(s.evaluationId, d.evaluationId)
    }

    // ================================================================
    // Test C — Floor parity: displayed floor == FDG floor ==
    // ticket floor == executor validation floor
    // ================================================================
    @Test fun testC_floor_parity_all_stages_identical() {
        val s = scoreEnvelope("MINT_C", TraderLane.QUALITY, effective = 20.0)
        val f = floorEnvelope(s.evaluationId, TraderLane.QUALITY, base = 18.0)
        val d = CanonicalEntryPipeline6398.decide(s, f)
        val t = CanonicalEntryPipeline6398.issueAndRegister(d)!!
        assertEquals(f.effectiveFloor, d.floor.effectiveFloor, 1e-9)
        assertEquals(f.effectiveFloor, t.effectiveFloor, 1e-9)
        val v = CanonicalEntryPipeline6398.validateTicket(
            t, d, currentDataVersion = s.dataVersion)
        assertTrue(v.ok)
    }

    // ================================================================
    // Test D — Lane/trader independence: same evidence, different
    // lanes/traders → different score/floor envelopes
    // ================================================================
    @Test fun testD_lane_and_trader_independence() {
        val sMoon = scoreEnvelope("MINT_D", TraderLane.MOONSHOT, effective = 22.0)
        val sQual = scoreEnvelope("MINT_D", TraderLane.QUALITY, effective = 22.0)
        val fMoon = floorEnvelope(sMoon.evaluationId, TraderLane.MOONSHOT, base = 18.0)
        val fQual = floorEnvelope(sQual.evaluationId, TraderLane.QUALITY, base = 20.0)
        assertNotEquals(fMoon.effectiveFloor, fQual.effectiveFloor)
        assertNotEquals(sMoon.evaluationId, sQual.evaluationId)
    }

    // ================================================================
    // Test E — DexScreener degradation: source-native pair remains
    // usable; no NO_PAIR_NO_FALLBACK choke
    // ================================================================
    @Test fun testE_dexscreener_degraded_source_native_pair_still_usable() {
        val now = System.currentTimeMillis()
        val snap = PairHydrationState6398.resolve(
            mint = "MINT_E",
            dexscreenerPair = null,                     // degraded
            raydiumPoolFromScanner = "RAYDIUM_POOL_ABC",
            pumpFunBondingCurve = null,
            heliusPair = null,
            jupiterRouteOk = false,
            birdeyePair = null,
            watchlistLastKnownPair = null,
            providersAttempted = listOf("DEXSCREENER", "HELIUS", "BIRDEYE"),
            hydrationStartedAtMs = now, nowMs = now,
        )
        assertEquals(PairHydrationState6398.State.PAIR_SOURCE_NATIVE, snap.state)
        assertEquals("RAYDIUM_POOL_ABC", snap.pairAddress)
        assertEquals(0L, PairHydrationState6398.hardUnavailableEvents.get())
    }

    // ================================================================
    // Test F — Positive-EV lane relaxes appropriately
    // ================================================================
    @Test fun testF_positive_ev_lane_relaxes_without_disabling() {
        AdaptiveFloorBrain6397.postLaneStat(
            AdaptiveFloorBrain6397.LaneStat(lane = "MOONSHOT", trades = 30,
                winRatePct = 60.0, expectancySol = 0.03))
        val rec = AdaptiveFloorBrain6397.recommend(
            AdaptiveFloorBrain6397.BrainInputs(
                LiveEntryThresholdAuthority6396.GovernorTier.BASELINE,
                lane = "MOONSHOT", scannerHeatPct01 = 0.5))
        assertEquals(-2, rec.laneDelta)
        assertTrue(rec.finalFloor >= LiveEntryThresholdAuthority6396.ABSOLUTE_MIN)
    }

    // ================================================================
    // Test G — Bleeding lane tightens but is not disabled
    // ================================================================
    @Test fun testG_bleeding_lane_tightens_but_not_disabled() {
        AdaptiveFloorBrain6397.postLaneStat(
            AdaptiveFloorBrain6397.LaneStat(lane = "SHITCOIN", trades = 30,
                winRatePct = 32.0, expectancySol = -0.02))
        val rec = AdaptiveFloorBrain6397.recommend(
            AdaptiveFloorBrain6397.BrainInputs(
                LiveEntryThresholdAuthority6396.GovernorTier.BASELINE,
                lane = "SHITCOIN", scannerHeatPct01 = 0.5))
        assertEquals(+2, rec.laneDelta)
        // Floor stays inside the envelope — lane never disabled.
        assertTrue(rec.finalFloor <= LiveEntryThresholdAuthority6396.ABSOLUTE_MAX)
    }

    // ================================================================
    // Test H — Missing evidence returns HYDRATION_DEFERRED, not
    // BUY_FAILED and not a zero-score reject
    // ================================================================
    @Test fun testH_missing_evidence_returns_hydration_deferred() {
        val s = scoreEnvelope("MINT_H", TraderLane.MOONSHOT, effective = 18.0,
            evidence = 0.2)
        val f = floorEnvelope(s.evaluationId, TraderLane.MOONSHOT, base = 15.0)
        val d = CanonicalEntryPipeline6398.decide(s, f)
        assertEquals(EntryOutcome.HYDRATION_DEFERRED, d.outcome)
        assertNull(CanonicalEntryPipeline6398.mintTicket(d))
    }

    // ================================================================
    // Test I — Event taxonomy: score-floor rejection increments only
    // policy counters; buy-failure counters remain zero
    // ================================================================
    @Test fun testI_event_taxonomy_no_buy_failure_on_policy_reject() {
        val startPolicy = EntryRejectionTelemetry6396.entryPolicyReject.get()
        EntryRejectionTelemetry6396.emitScoreFloorReject(
            EntryRejectionTelemetry6396.ScoreFloorRejectRecord(
                mint = "MINT_I", symbol = "SI", lane = "MOONSHOT",
                rawScore = 7.0, effectiveScore = 7.0, finalFloor = 19,
                scoreScaleVersion = LiveEntryThresholdAuthority6396.SCORE_SCALE_VERSION,
                metricEpoch = 1L, decisionId = "d",
                governorState = "BASELINE", hardSafetyPassed = true,
                recheckEligibleAt = 0L,
            )
        )
        assertEquals(startPolicy + 1L, EntryRejectionTelemetry6396.entryPolicyReject.get())
        val threw = try {
            EntryRejectionTelemetry6396.assertNotBuyFailure("BUY_FAILED"); false
        } catch (_: IllegalStateException) { true }
        assertTrue("BUY_FAILED must be rejected as a valid counter", threw)
    }

    // ================================================================
    // Test J — Forensic export: canonical events carry provenance
    // (evaluationId, model versions, deltas). We assert the envelope
    // itself contains the required breakdown.
    // ================================================================
    @Test fun testJ_forensic_export_carries_full_provenance() {
        val s = scoreEnvelope("MINT_J", TraderLane.QUALITY, effective = 22.0)
        val f = CanonicalEntryPipeline6398.buildFloorEnvelope(s.evaluationId,
            CanonicalEntryPipeline6398.FloorInputs(
                lane = TraderLane.QUALITY, trader = TraderId.QUALITY,
                tactic = EntryTactic.STANDARD, baseLaneFloor = 18.0,
                governorDelta = 2.0, lanePerformanceDelta = -3.0,
                traderPerformanceDelta = -1.0, personalityDelta = 3.0,
                gateRelaxerDelta = 2.7))
        val d = CanonicalEntryPipeline6398.decide(s, f)
        val t = CanonicalEntryPipeline6398.issueAndRegister(d)!!
        // The following provenance fields MUST all be non-empty on the
        // exported envelopes.
        assertTrue(s.scoreModelVersion.isNotBlank())
        assertTrue(f.floorModelVersion.isNotBlank())
        assertEquals(s.dataVersion, t.dataVersion)
        assertEquals(s.effectiveScore, t.effectiveScore, 1e-9)
        assertEquals(f.effectiveFloor, t.effectiveFloor, 1e-9)
        assertNotNull(t.ticketId)
    }

    // ================================================================
    // wire-up: scanner heat publisher posts to the brain
    // ================================================================
    @Test fun scanner_heat_publisher_updates_brain_percentile() {
        val startPct = AdaptiveFloorBrain6397.scannerHeat()
        // Simulate 6 hydrated candidates in a burst.
        val now = System.currentTimeMillis()
        for (i in 0 until 6) ScannerHeatPublisher6398.onHydratedCandidate(now + i)
        val endPct = AdaptiveFloorBrain6397.scannerHeat()
        assertTrue("heat percentile must have risen", endPct > startPct)
        assertEquals(6L, ScannerHeatPublisher6398.hydratedTotal.get())
    }

    // ================================================================
    // wire-up: cognitive advisory bridge converts conviction to delta
    // ================================================================
    @Test fun cognitive_bridge_maps_conviction_to_advisory_delta() {
        CognitiveAdvisoryBridge6398.postSuperAgi(+1.0, "max tightening")
        CognitiveAdvisoryBridge6398.postSsi(-0.66, "moderate relax")
        CognitiveAdvisoryBridge6398.postLlm(0.0, "neutral")
        val advisories = AdaptiveFloorBrain6397.activeAdvisories()
        assertEquals(3, advisories.size)
        val superAgi = advisories.first { it.channel == AdaptiveFloorBrain6397.AdvisoryChannel.SUPER_AGI }
        assertEquals(+3, superAgi.signedDelta)
        val ssi = advisories.first { it.channel == AdaptiveFloorBrain6397.AdvisoryChannel.SSI }
        assertEquals(-2, ssi.signedDelta)
    }

    // ================================================================
    // wire-up: lane performance publisher only ingests clean rows
    // ================================================================
    @Test fun lane_performance_publisher_rejects_quarantined_rows() {
        // Row A is clean.
        LanePerformancePublisher6398.ingest("ROW_A", "MOONSHOT", wasWin = true, pnlSol = 0.05)
        // Row B was quarantined by 6395 filter → must be rejected here.
        CanonicalPerformanceFilter6395.quarantine("ROW_B",
            CanonicalPerformanceFilter6395.QuarantineReason.QTY_DECIMAL_SKEW)
        LanePerformancePublisher6398.ingest("ROW_B", "MOONSHOT", wasWin = true, pnlSol = 10.0)
        assertEquals(1L, LanePerformancePublisher6398.rowsIngested.get())
        assertEquals(1L, LanePerformancePublisher6398.rowsRejectedTainted.get())
        val stat = AdaptiveFloorBrain6397.laneStat("MOONSHOT")
        assertNotNull(stat); assertEquals(1, stat!!.trades)
    }

    // ================================================================
    // pipeline: legacy 55/56 remain mechanically unreachable
    // ================================================================
    @Test fun pipeline_floor_envelope_clamps_legacy_anchors() {
        val f = CanonicalEntryPipeline6398.buildFloorEnvelope("evX",
            CanonicalEntryPipeline6398.FloorInputs(
                lane = TraderLane.SHITCOIN, trader = TraderId.MEME,
                tactic = EntryTactic.STANDARD, baseLaneFloor = 55.0))
        assertEquals(LiveEntryThresholdAuthority6396.ABSOLUTE_MAX.toDouble(),
            f.effectiveFloor, 1e-9)
    }

    // ================================================================
    // pipeline: Bayesian shrinkage caps
    // ================================================================
    @Test fun pipeline_bayesian_shrinkage_caps() {
        assertEquals(2.0, CanonicalEntryPipeline6398.perfDeltaCap(3), 1e-9)
        assertEquals(5.0, CanonicalEntryPipeline6398.perfDeltaCap(12), 1e-9)
        assertEquals(8.0, CanonicalEntryPipeline6398.perfDeltaCap(50), 1e-9)
    }
}
