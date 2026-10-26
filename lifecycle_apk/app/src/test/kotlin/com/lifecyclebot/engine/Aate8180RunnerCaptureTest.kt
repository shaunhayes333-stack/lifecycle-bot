package com.lifecyclebot.engine

import com.lifecyclebot.engine.ElectionRescueContract8144.ClaimOutcome8144
import com.lifecyclebot.engine.SpecialistElection8136.Contender8136
import com.lifecyclebot.engine.SpecialistElection8136.NativeView8136
import com.lifecyclebot.engine.SpecialistElection8136.Terminal8136
import com.lifecyclebot.engine.truth.AdmissionEdge8180
import com.lifecyclebot.engine.truth.AdmissionHandoff8144
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CellProofLadder7731
import com.lifecyclebot.engine.truth.LabelBasis8180
import com.lifecyclebot.engine.truth.StageCarry8180
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8180 — stop missing runners (owner, 5.0.8175: 11 runners, 10 missed). Behaviour tests for each root cause:
 * desk identities never open a meme hand-off; a hand-off names the lane whose floors the coin meets (else waits);
 * MOONSHOT reads $1M-$5M coins; a primary's judgement names the qualified lane; an idle rescue slot is released;
 * a VALID own record carries a lane-wide stage loss; an admitted specific edge reaches a routable size; a vanished
 * mark on a different basis is not booked; data deferrals request their inputs; a cell veto answers to the audit.
 */
class Aate8180RunnerCaptureTest {
    private val e = SpecialistElection8136
    private val tk = HandoffReceiverTasks8153
    private val t0 = 5_000_000_000L
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private fun view(n: Double, eligible: Boolean = true) = NativeView8136(eligible, n, "OK")
    private fun c(lane: String, n: Double) = Contender8136(lane, n)

    @Before fun setUp() { e.resetForTests8136(); tk.resetForTests8153(); AdmissionEdge8180.resetForTests8180() }
    @After fun tearDown() { e.resetForTests8136(); tk.resetForTests8153(); AdmissionEdge8180.resetForTests8180() }

    // ── 1. DEFERRED_NO_TOKEN_STATE: crypto-desk identities ──

    @Test fun aDeskIdentityIsNotAMemeMintAndMapsToItsMemeKey() {
        val r = HandoffRoute8180
        assertTrue(r.deskIdentity8180("solana|So11111111111111111111111111111111111111112"))
        assertTrue(r.deskIdentity8180("static:BTC"))
        assertTrue(r.deskIdentity8180("cg:bitcoin"))
        assertTrue(r.deskIdentity8180("base|0xabc"))
        assertFalse(r.deskIdentity8180("7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"))
        assertEquals("7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU", r.memeKey8180("solana|7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"))
        assertEquals("base|0xabc", r.memeKey8180("base|0xabc"))
    }

    @Test fun aDeskIdentityOpensNoReceiverTaskAndNoHandoff() {
        val desk = "solana|DeskMint8180xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
        assertNull(tk.openTask8153(desk, "MOONSHOT", "PROJECT_SNIPER", "TOO_OLD_8144", 10L, t0, t0))
        assertNull(tk.taskOf8153(desk, t0))
        AdmissionHandoff8144.emitHandoff8144(desk, "PROJECT_SNIPER", "MOONSHOT", "TOO_OLD_8144 600s > 180s", t0)
        assertNull(AdmissionHandoff8144.handoffOf8144(desk, t0))
        // A real meme mint still opens its task.
        val meme = "MemeMint8180yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy"
        assertEquals("MOONSHOT", tk.openTask8153(meme, "MOONSHOT", "PROJECT_SNIPER", "TOO_OLD_8144", 10L, t0, t0)?.lane)
        // The receiver's token lookup is the meme map, by the meme key too.
        assertTrue(src("engine/SpecialistOwnership7951.kt").contains("val ts = HandoffRoute8180.tokenFor8180(mint)  // V5.0.8180"))
        assertTrue(src("engine/RunnerCapture8180.kt").contains("tokens[mint] ?: tokens[bare]"))
        assertTrue(src("engine/RunnerCapture8180.kt").contains("!deskIdentity8180(bare)"))
    }

    // ── 2. arrivals refused on the named lane's own floors ──

    @Test fun aHandoffNamesTheRunnerLaneWhoseFloorsTheCoinMeets() {
        val r = HandoffRoute8180
        // Unknown readings never refuse (the lanes defer on them); a real reading must reach the floor.
        assertTrue(r.meets8180(0.0, 2_000.0))
        assertTrue(r.meets8180(Double.NaN, 2_000.0))
        assertFalse(r.meets8180(1_200.0, 2_000.0))
        assertTrue(r.meets8180(2_500.0, 2_000.0))
        // MOONSHOT's liquidity floor not met, SHITCOIN fits: the hand-off goes to SHITCOIN and the reason follows.
        val toShit = r.route8180("MOONSHOT", "PHASE_HANDOFF_8136_EXPANDING_TO_MOONSHOT") { it == "SHITCOIN" }
        assertFalse(toShit.withhold)
        assertEquals("SHITCOIN", toShit.named)
        assertEquals("PHASE_HANDOFF_8136_EXPANDING_TO_SHITCOIN", toShit.reason)
        // Named lane fits: unchanged.
        val keep = r.route8180("MOONSHOT", "TOO_OLD_8144") { true }
        assertEquals("MOONSHOT" to "TOO_OLD_8144", keep.named to keep.reason)
        // No runner lane fits yet: withheld (no hand-off that would settle and hold the mint for ten minutes).
        assertTrue(r.route8180("SHITCOIN", "PHASE_HANDOFF_8136_BIRTH_TO_SHITCOIN_X") { false }.withhold)
        // Any other named lane (EXPRESS at migration) is not re-routed.
        val express = r.route8180("EXPRESS", "PHASE_HANDOFF_8136_MIGRATION_TO_EXPRESS") { false }
        assertFalse(express.withhold)
        assertEquals("EXPRESS", express.named)
        // The runtime route reads each lane's own floors, never lowered.
        val code = src("engine/RunnerCapture8180.kt")
        assertTrue(code.contains("MoonshotTraderAI.minLiquidityUsdNow8180()"))
        assertTrue(code.contains("LaneAgeLiquidityScope8140.effectiveLiquidityFloor8140("))
        assertTrue(src("engine/truth/AdmissionHandoff8144.kt").contains("if (route8180.withhold) { count8144(\"WITHHELD_NO_LANE_FITS_8180\"); return }"))
    }

    @Test fun moonshotReadsACoinUpToItsOwnFiveMillionCeiling() {
        assertTrue("MOONSHOT" in SpecialistBrainBridge7542.nativeScope8168(2_000_000.0, emptySet()))
        assertFalse("MOONSHOT" in SpecialistBrainBridge7542.nativeScope8168(9_000_000.0, emptySet()))
        // The named lane of an open hand-off joins the native scope.
        assertTrue(src("engine/SpecialistBrainBridge7542.kt").contains("} catch (_: Throwable) { emptySet<String>() }) + handoffScope8180(ts.mint)"))
    }

    // ── 3. QUALIFIED_LANE_STOPPED: the primary's judgement names the qualified lane ──

    @Test fun aGradedNonBindingPrimaryRefusalNamesTheQualifiedLane() {
        val y = NativeYield8180
        assertTrue(y.namesReceiver8180("NO_CONTINUATION: 1.0% < 4.0%", gradeable = true, fit = false, binding = false, data = false))
        assertTrue(y.namesReceiver8180("MCAP_TOO_HIGH: x", gradeable = false, fit = true, binding = false, data = false))
        assertFalse(y.namesReceiver8180("RUG_DETECTED", gradeable = true, fit = false, binding = true, data = false))
        assertFalse(y.namesReceiver8180("LANE_LIQ_UNHYDRATED_DEFER_8140_EXPRESS", gradeable = true, fit = false, binding = false, data = true))
        assertFalse(y.namesReceiver8180("COOLDOWN", gradeable = false, fit = false, binding = false, data = false))
        assertFalse(y.namesReceiver8180("", gradeable = true, fit = false, binding = false, data = false))
        // MANIPULATED's shape refusal is a lane-fit refusal (handed on like a cap / age band).
        assertTrue(SpecialistOwnership7951.laneFitRefusal8132("NO_KNOWN_MANIPULATION_EVIDENCE_7425"))
        assertFalse(SpecialistOwnership7951.laneFitRefusal8132("RUG_DETECTED"))
        assertTrue(src("engine/SpecialistOwnership7951.kt").contains("if (NativeYield8180.namesReceiver8180(op.reason, op.gradeable, fit8180, refusalIsJudgement8136(op.reason, fdg = false), data8180)) yieldReceiver8136(mint, p, snap.opinions)"))
    }

    // ── 4. SHADOW_NOT_PRIMARY: an idle rescue slot is released ──

    @Test fun anIdleRescueHolderNoLongerShadowsAQualifiedLane() {
        assertFalse(RescueIdle8180.idle8180(consumed = false, claimedAtMs = t0, nowMs = t0 + RescueIdle8180.RESCUE_IDLE_MS_8180))
        assertTrue(RescueIdle8180.idle8180(consumed = false, claimedAtMs = t0, nowMs = t0 + RescueIdle8180.RESCUE_IDLE_MS_8180 + 1))
        assertFalse(RescueIdle8180.idle8180(consumed = true, claimedAtMs = t0, nowMs = t0 + 999_999L))
        assertFalse(RescueIdle8180.idle8180(consumed = false, claimedAtMs = 0L, nowMs = t0))
        val m = "mint-idle8180"
        val fdg = SpecialistElection8136.TRIGGER_FDG
        // MOONSHOT (the runner lane) is not yet qualified when QUALITY takes the slot.
        e.registerNative8136(m, 900L, t0, mapOf("CASHGEN" to view(55.0), "QUALITY" to view(70.0)), t0)
        e.cyclePrimary8136(m, 900L, "CASHGEN", false, null, t0)
        e.onFdgVerdict8136(m, "CASHGEN", 900L, false, "PLAYBOOK_NO_TRIGGER_7907_CASHGEN", false, true, 55.0, t0 + 1)
        // QUALITY takes the slot and never reaches its FDG.
        assertEquals(ClaimOutcome8144.GRANTED, e.claimRescueOutcome8144(m, "QUALITY", "CASHGEN", fdg, "x", false,
            listOf(c("QUALITY", 70.0)), null, t0 + 2))
        // Inside the idle window the holder keeps it.
        assertEquals(ClaimOutcome8144.DENIED_HELD_BY_OTHER, e.claimRescueOutcome8144(m, "MOONSHOT", "CASHGEN", fdg, "x", false,
            listOf(c("MOONSHOT", 80.0)), null, t0 + 10_000L))
        // Past it, the qualified runner lane takes the slot and evaluates.
        val late = t0 + 2 + RescueIdle8180.RESCUE_IDLE_MS_8180 + 1_000L
        assertEquals(ClaimOutcome8144.GRANTED, e.claimRescueOutcome8144(m, "MOONSHOT", "CASHGEN", fdg, "x", false,
            listOf(c("MOONSHOT", 80.0)), null, late))
        assertEquals("MOONSHOT", e.rescueOf8136(m))
        assertEquals(Terminal8136.CONTRIBUTOR_SHADOW to "RESCUE_IDLE_RELEASED_8180", e.terminalOf8136(m, "QUALITY"))
        assertTrue(e.statusLine8136().contains("idleReleased8180=1"))
        // superseded by V5.0.8364 — a consumed rescue is never idle-released, but one that REFUSED on its own strategy
        // (non-judgement) opens ONE second rescue; after that the slot stays shut.
        e.onFdgVerdict8136(m, "MOONSHOT", 900L, false, "FDG_SCORE_FLOOR", false, true, 80.0, late + 1)
        val later = late + RescueIdle8180.RESCUE_IDLE_MS_8180 + 1_000L
        assertEquals(ClaimOutcome8144.GRANTED, e.claimRescueOutcome8144(m, "QUALITY", "CASHGEN", fdg, "x", false,
            listOf(c("QUALITY", 70.0)), null, later))
        e.onFdgVerdict8136(m, "QUALITY", 900L, false, "FDG_SCORE_FLOOR", false, true, 70.0, later + 1)
        assertEquals(ClaimOutcome8144.DENIED_HELD_BY_OTHER, e.claimRescueOutcome8144(m, "EXPRESS", "CASHGEN", fdg, "x", false,
            listOf(c("EXPRESS", 90.0)), null, later + 2))
    }

    @Test fun anIdleHolderNoLongerBlocksTheNamedHandoffReceiver() {
        val m = "mint-idle-recv8180"
        val native = SpecialistElection8136.TRIGGER_NATIVE
        e.registerNative8136(m, 910L, t0, mapOf("SHITCOIN" to view(60.0), "EXPRESS" to view(70.0), "MOONSHOT" to view(55.0)), t0)
        e.cyclePrimary8136(m, 910L, "SHITCOIN", false, null, t0)
        e.notePrimaryRefusal8136(m, "SHITCOIN", "NATIVE:MCAP_TOO_HIGH", false, t0 + 1)
        assertEquals(ClaimOutcome8144.GRANTED, e.claimRescueOutcome8144(m, "EXPRESS", "SHITCOIN", native, "MCAP_TOO_HIGH", false,
            listOf(c("EXPRESS", 70.0), c("MOONSHOT", 55.0)), null, t0 + 2))
        assertEquals(ClaimOutcome8144.DENIED_HELD_BY_OTHER, e.claimHandoffReceiver8153(m, "MOONSHOT", "SHITCOIN", null, t0 + 3))
        assertEquals(ClaimOutcome8144.GRANTED,
            e.claimHandoffReceiver8153(m, "MOONSHOT", "SHITCOIN", null, t0 + 3 + RescueIdle8180.RESCUE_IDLE_MS_8180))
        assertEquals("MOONSHOT", e.rescueOf8136(m))
    }

    // ── 6. STAGE_PROVEN_LOSING: the coin's own VALID evidence carries a lane-wide stage loss ──

    private fun stageCandidate(setups: List<CandidateAdmission8136.Evidence8136>, cell: CandidateAdmission8136.Evidence8136? = null,
                               generic: Double = 70.0) = CandidateAdmission8136.Candidate8136(
        mint = "m8180", lane = "SHITCOIN", selectedSetup = setups.firstOrNull()?.id,
        stage = "FRESH_LAUNCH", priceAgeMs = 2_000L, expectedGrossPct = 120.0, costPct = 9.0,
        setups = setups, cell = cell,
        stageRecord = CandidateAdmission8136.Evidence8136("STAGE", "FRESH_LAUNCH", 80, -12.0, 3.0),
        genericScore = generic, genericFloor = 50.0,
    )

    @Test fun aValidOwnRecordCarriesAStageLossButIsNotAnEdge() {
        val valid = CandidateAdmission8136.Evidence8136(CandidateAdmission8136.KIND_SETUP, "VOLUME_IGNITION", 18, 25.0, 8.0)
        assertTrue(StageCarry8180.valid8180(valid, 9.0))
        val d = CandidateAdmission8136.decide8136(stageCandidate(listOf(valid)))
        assertEquals(d.compact, CandidateAdmission8136.Terminal8136.ADMIT, d.terminal)
        assertEquals("GENERIC_PRIOR_CLEARS_8136", d.reason)            // carried, then the generic prior decides
        assertTrue(d.trace.any { it.contains("carried by setup") })
        // The generic prior below its floor still refuses: a carry is not an admission.
        assertEquals("GENERIC_SCORE_FLOOR_8136", CandidateAdmission8136.decide8136(stageCandidate(listOf(valid), generic = 30.0)).reason)
        // The coin's own cell at the VALID margin carries too.
        val cell = CandidateAdmission8136.Evidence8136(CandidateAdmission8136.KIND_CELL, "PUMP_PORTAL_WS|SHITCOIN|MC_LT10K", 20, 10.0, 3.0)
        val thin = CandidateAdmission8136.Evidence8136(CandidateAdmission8136.KIND_SETUP, "THIN", 14, 20.0, 15.0)
        val byCell = CandidateAdmission8136.decide8136(stageCandidate(listOf(thin), cell))
        assertTrue(byCell.compact, byCell.trace.any { it.contains("carried by cell") })
        // Thin evidence (mean - 1.5 SE <= 0) does not carry: the 8173 refusal stands.
        assertFalse(StageCarry8180.valid8180(thin, 9.0))
        assertEquals("STAGE_PROVEN_LOSING_8136", CandidateAdmission8136.decide8136(stageCandidate(listOf(thin))).reason)
        // A valid setup losing at the coin's timing band does not carry.
        val losingBand = stageCandidate(listOf(valid)).copy(setupTimings = mapOf("VOLUME_IGNITION" to
            CandidateAdmission8136.Evidence8136(CandidateAdmission8136.KIND_TIMING, "VOLUME_IGNITION@LATE", 20, -9.0, 2.0)))
        assertNull(StageCarry8180.carry8180(losingBand))
    }

    // ── 7. FDG ALLOW then no execution: the admission carries its edge to the sizers ──

    @Test fun anAdmittedSpecificEdgeReachesTheRoutableMinimumInsideTheCap() {
        val a = AdmissionEdge8180
        val edge = CandidateAdmission8136.Decision8136(CandidateAdmission8136.Terminal8136.ADMIT, "SPECIFIC_EDGE_PROVEN_8136", true, "S", 10.0, emptyList(), "SETUP:S n40")
        val prior = CandidateAdmission8136.Decision8136(CandidateAdmission8136.Terminal8136.ADMIT, "GENERIC_PRIOR_CLEARS_8136", false, "S", 10.0, emptyList())
        a.note8180("LIVE", "mE", "SHITCOIN", 7L, edge, t0)
        assertTrue(a.admitted8180("LIVE", "mE", "SHITCOIN", 7L, t0 + a.TTL_MS_8180))
        assertFalse(a.admitted8180("LIVE", "mE", "SHITCOIN", 7L, t0 + a.TTL_MS_8180 + 1))
        a.note8180("LIVE", "mE", "SHITCOIN", 7L, prior, t0 + 1)                       // the same lane's later terminal clears it
        assertFalse(a.admitted8180("LIVE", "mE", "SHITCOIN", 7L, t0 + 2))
        // Promotion only for an admitted edge, only up to the routable minimum, only when the cap funds it.
        assertEquals(0.05, a.promote8180(true, 0.02, 0.05, 0.10), 1e-9)
        assertEquals(0.02, a.promote8180(false, 0.02, 0.05, 0.10), 1e-9)
        assertEquals(0.02, a.promote8180(true, 0.02, 0.05, 0.04), 1e-9)               // the cap does not fund the minimum
        assertEquals(0.08, a.promote8180(true, 0.08, 0.05, 0.10), 1e-9)               // already above the minimum
        assertEquals(0.0, a.promote8180(true, 0.0, 0.05, 0.10), 1e-9)                 // a zero request is not a request
        assertTrue(a.carriesEdge8180(edge))
        assertFalse(a.carriesEdge8180(prior))
        assertTrue(src("engine/truth/OrderSizeResolver6441.kt").contains("!(!paperMode && AdmissionEdge8180.admittedNow8198(paperMode, mint, laneName))"))
        assertTrue(src("v3/sizing/SmartSizerV3.kt").contains("AdmissionEdge8180.promote8180(admitted8180, cappedSize, liveFloorEff7697, minOf(safeShareCap7142, tradeable))"))
    }

    // ── 8. BAND MC_GT1M outliers: vanished marks on another basis ──

    @Test fun aVanishedMarkOnAnotherBasisIsNotBooked() {
        // 1,000x above a $2M entry: not a move (never corroborated above a $1M entry).
        assertFalse(LabelBasis8180.bookable8180(0.001, 1.0, 2_000_000.0, 2_000_000_000.0, 150.0))
        // An ordinary move books.
        assertTrue(LabelBasis8180.bookable8180(0.001, 0.0015, 2_000_000.0, 3_000_000.0, 150.0))
        // superseded by V5.0.8395: the price-only vanished path is gone; bounded recovery of an accepted mark keeps the same basis rule.
        assertTrue(src("engine/truth/ForwardReturnLabeler7731.kt").contains("LabelBasis8180.bookable8180(o.entryPrice, it.usd, o.entryMcap, nowMcap7769(o.mint),"))
    }

    // ── 9. DATA_INCOMPLETE: the deferral requests what it waits for ──

    @Test fun aDataDeferralRequestsItsMissingInputs() {
        val f = DataDeferFetch8180
        assertEquals(listOf("liq:UNKNOWN"), f.inputsFor8180(listOf("liquidity:UNKNOWN")))
        assertEquals(listOf("liq:REFRESH", "chg:UNOBSERVED"), f.inputsFor8180(listOf("price", "economics")))
        assertEquals(listOf("shape:HISTORY_SPARSE", "chg:UNOBSERVED", "bp:UNOBSERVED"), f.inputsFor8180(listOf("core:UNKNOWN")))
        assertEquals(listOf("shape:UNKNOWN", "tape:UNKNOWN"), f.inputsFor8180(listOf("shape:UNKNOWN", "tape:UNKNOWN", " ")))
        assertTrue(f.inputsFor8180(emptyList()).isEmpty())
        val gate = src("engine/truth/LiveEdgeGate7877.kt")
        val defer = gate.substringAfter("private fun dataDefer8136(").substringBefore("private const val DATA_DEFER_REASON_8136")
        assertTrue(defer.contains("DataDeferFetch8180.request8180(ts, missing, nowMs)"))
    }

    // ── 5. a judgement cell veto answers to the veto audit ──

    @Test fun aNegativeCellAnswersToTheVetoAudit() {
        assertEquals("CELL_PROOF_NEGATIVE_7731", CellProofLadder7731.CELL_PROOF_RULE_8180)
        assertTrue(src("engine/truth/CellProofLadder7731.kt").contains("Cortex7885.ownLaneVetoRefusesWinners8076(CELL_PROOF_RULE_8180, lane)"))
    }
}
