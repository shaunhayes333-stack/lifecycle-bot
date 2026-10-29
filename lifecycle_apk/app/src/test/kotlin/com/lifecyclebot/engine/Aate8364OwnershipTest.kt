package com.lifecyclebot.engine

import com.lifecyclebot.engine.SpecialistElection8136.Contender8136
import com.lifecyclebot.engine.SpecialistElection8136.NativeView8136
import com.lifecyclebot.engine.SpecialistElection8136.Terminal8136
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8364 — the specialist / trader ownership chain: one candidate followed from QUALIFIED to its FDG verdict, owner,
 * intent and ticket (every qualified candidate ends by name, or is counted OWNER_LOST_NO_TERMINAL_8364_<stage>), the
 * common scale neutral to a lane's sample count, a second rescue after a non-judgement rescue refusal, an FDG allow over
 * an unevaluated claim, a late yield after NO_ELIGIBLE_RECEIVER, and the 8214 native slots for natively eligible lanes.
 */
class Aate8364OwnershipTest {
    private val e = SpecialistElection8136
    private val chain = OwnershipChain8364
    private val fdg = SpecialistElection8136.TRIGGER_FDG
    private val cell = SpecialistElection8136.TRIGGER_CELL_PROVEN
    private val t0 = 1_000_000_000L
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Before fun setUp() { e.resetForTests8136(); chain.resetForTests8364(); SpecialistOwnership7951.resetForTests7951() }
    @After fun tearDown() { e.resetForTests8136(); chain.resetForTests8364(); SpecialistOwnership7951.resetForTests7951() }

    private fun view(n: Double, eligible: Boolean = true, reason: String = "OK") = NativeView8136(eligible, n, reason)
    private fun c(lane: String, n: Double, proven: Boolean = false) = Contender8136(lane, n, proven)

    // ── candidate identity ──

    @Test fun bothEventIdShapesResolveToTheSameCandidate() {
        assertEquals(OwnershipChain8364.CandidateId8364("MintA", 7L), chain.parseCandidate8364("MintA:7"))
        assertEquals(OwnershipChain8364.CandidateId8364("MintA", 7L), chain.parseCandidate8364("3:PAPER:MintA:BUY:MOONSHOT:7:42"))
        assertEquals(OwnershipChain8364.CandidateId8364("MintA", 7L), chain.parseCandidate8364("3:LIVE:MintA:BUY:QUALITY:7:1"))
        assertNull(chain.parseCandidate8364("LIVE:MintA:pos"))
        assertNull(chain.parseCandidate8364("MintA:notAVersion"))
        assertNull(chain.parseCandidate8364("MintA:0"))
        assertNull(chain.parseCandidate8364(""))
    }

    @Test fun closeKeyNamesEveryEndAndLostOnlyWithoutOne() {
        val q = OwnershipChain8364.Step8364.QUALIFIED
        val b = OwnershipChain8364.Step8364.FDG_BLOCK
        val a = OwnershipChain8364.Step8364.FDG_ALLOW
        val i = OwnershipChain8364.Step8364.INTENT
        assertEquals("T:FDG_X", chain.closeKey8364(true, listOf(q, b), "FDG_X", ""))
        assertEquals("T:FDG_BLOCK", chain.closeKey8364(true, listOf(q, b), "", ""))
        assertEquals("T:NOT_EVALUATED_NOT_PRIMARY_MOONSHOT", chain.closeKey8364(true, listOf(q), "", "NOT_EVALUATED_NOT_PRIMARY_MOONSHOT"))
        assertEquals("LOST:QUALIFIED", chain.closeKey8364(true, listOf(q), "", ""))
        assertEquals("LOST:INTENT", chain.closeKey8364(true, listOf(q, a, i), "", ""))
        assertEquals("T:EMPTY", chain.closeKey8364(false, emptyList(), "", ""))
        assertEquals("FDG_LIVE_PROVEN_SETUP_ONLY_8059", "FDG_" + chain.reasonKey8364("LIVE_PROVEN_SETUP_ONLY_8059: setup unproven"))
    }

    @Test fun aQualifiedCandidateIsFollowedToItsFdgRefusalAndEveryOtherEndIsNamed() {
        // Refused at the FDG: the same candidate carries QUALIFIED -> FDG_BLOCK with the reason.
        chain.onDeskStage8364("MOONSHOT", "QUALIFIED", "MintR:5", t0)
        assertEquals(5L, chain.qualifiedVersion8364("MOONSHOT", "MintR"))
        chain.onFdgVerdict8364("MOONSHOT", "MintR", 5L, false, "TIMING_RECORD_NEGATIVE_8094", t0 + 1)
        // Allowed, owned, ticketed — the attempt id (rolled version 6) joins the qualified record of version 5.
        chain.onDeskStage8364("QUALITY", "QUALIFIED", "MintT:5", t0)
        chain.onFdgVerdict8364("QUALITY", "MintT", 5L, true, null, t0 + 1)
        chain.onDeskStage8364("QUALITY", "BUY_INTENT", "3:PAPER:MintT:BUY:QUALITY:6:9", t0 + 2)
        chain.onDeskStage8364("QUALITY", "OWNER_SELECTED", "3:PAPER:MintT:BUY:QUALITY:6:9", t0 + 2)
        chain.onDeskStage8364("QUALITY", "TICKET", "3:PAPER:MintT:BUY:QUALITY:6:9", t0 + 3)
        // Not let evaluate (another primary), then nothing: ends NOT_EVALUATED by name.
        chain.onDeskStage8364("SHITCOIN", "QUALIFIED", "MintN:5", t0)
        chain.onNotEvaluated8364("SHITCOIN", "MintN", "NOT_PRIMARY_MOONSHOT", t0 + 1)
        // Its own brain refused after qualifying.
        chain.onDeskStage8364("EXPRESS", "QUALIFIED", "MintE:5", t0)
        chain.onNativeLost8364("EXPRESS", "MintE", t0 + 1)
        // Qualified and then silence: lost with no terminal.
        chain.onDeskStage8364("BLUECHIP", "QUALIFIED", "MintL:5", t0)
        chain.flushForTests8364()
        val moon = chain.reportLine8364("MOONSHOT")!!
        assertTrue(moon, moon.contains("qualified=1 fdg=0/1"))
        assertTrue(moon, moon.contains("FDG_TIMING_RECORD_NEGATIVE_8094=1"))
        val q = chain.reportLine8364("QUALITY")!!
        assertTrue(q, q.contains("qualified=1 fdg=1/0 owner=1 intent=1 ticket=1"))
        assertTrue(q, q.contains("TICKET=1"))
        assertTrue(chain.reportLine8364("SHITCOIN")!!.contains("NOT_EVALUATED_NOT_PRIMARY_MOONSHOT=1"))
        assertTrue(chain.reportLine8364("EXPRESS")!!.contains("NATIVE_REFUSED_AFTER_QUALIFY=1"))
        assertTrue(chain.reportLine8364("BLUECHIP")!!.contains("lostNoTerminal{QUALIFIED=1}"))
        assertEquals(mapOf("QUALIFIED" to 1L), chain.lostNoTerminal8364())
        assertNull(chain.reportLine8364("CYCLIC"))
    }

    @Test fun aLaterEvaluationReplacesTheSoftNotEvaluatedEnd() {
        chain.onDeskStage8364("SHITCOIN", "QUALIFIED", "MintS:5", t0)
        chain.onNotEvaluated8364("SHITCOIN", "MintS", "NOT_PRIMARY_MOONSHOT", t0 + 1)
        chain.onFdgVerdict8364("SHITCOIN", "MintS", 5L, false, "NET_EDGE_BELOW_2X_COST_8030", t0 + 2)
        chain.onNotEvaluated8364("SHITCOIN", "MintS", "NOT_PRIMARY_MOONSHOT", t0 + 3)  // a verdict is never overwritten
        chain.flushForTests8364()
        val line = chain.reportLine8364("SHITCOIN")!!
        assertTrue(line, line.contains("FDG_NET_EDGE_BELOW_2X_COST_8030=1"))
        assertFalse(line, line.contains("NOT_EVALUATED"))
    }

    @Test fun nonOwnerSkipsSplitByQualification() {
        assertEquals("NON_OWNER_UNQUALIFIED_8364", chain.nonOwnerSkipKey8364("NON_OWNER_NOT_RESCUE", false))
        assertEquals("NON_OWNER_QUALIFIED_NO_SLOT_8364", chain.nonOwnerSkipKey8364("NON_OWNER_NOT_RESCUE", true))
        assertEquals("LANE_QUARANTINE_6002", chain.nonOwnerSkipKey8364("LANE_QUARANTINE_6002", true))
        assertEquals("PRIMARY_GATED_UNNAMED", chain.skipKey8364("PRIMARY_GATED_UNNAMED", "m", "MOONSHOT"))
    }

    // ── owner-why: refusals only ──

    @Test fun ownerWhyShowsRefusalsAndNativeVerdictsMoveToTheirOwnLine() {
        repeat(4) { SpecialistOwnership7951.notePreIntent7951("QUALITY", "NATIVE_READY") }
        repeat(3) { SpecialistOwnership7951.notePreIntent7951("QUALITY", "NATIVE_MCAP_TOO_LOW") }
        SpecialistOwnership7951.notePreIntent7951("QUALITY", "READY_HANDED_OFF_FROM_MOONSHOT_7960")
        SpecialistOwnership7951.notePreIntent7951("QUALITY", "RESCUE_ELECTED_8136_FDG")
        repeat(2) { SpecialistOwnership7951.notePreIntent7951("QUALITY", "FDG_LIVE_PROVEN_SETUP_ONLY_8059") }
        assertEquals("QUALITY ownerWhy8330[FDG_LIVE_PROVEN_SETUP_ONLY_8059=2] total=2", SpecialistOwnership7951.ownerWhyLine8330("QUALITY"))
        assertEquals("QUALITY nativeWhy8364[ready=4 refused{MCAP_TOO_LOW=3}] (native verdicts, not owner refusals)",
            SpecialistOwnership7951.nativeWhyLine8364("QUALITY"))
        assertFalse(SpecialistOwnership7951.ownerRefusalKey8364("NATIVE_READY"))
        assertTrue(SpecialistOwnership7951.ownerRefusalKey8364("READY_NOT_EVALUATED_PRIMARY_MOONSHOT"))
        assertTrue(SpecialistOwnership7951.ownerRefusalKey8364("LANE_NO_CONTINUATION"))
        assertNull(SpecialistOwnership7951.nativeWhyLine8364("BLUECHIP"))
    }

    // ── common scale ──

    @Test fun aYoungLaneIsNotOutRankedByAMatureLanesMedian() {
        assertEquals(75.0, SpecialistOwnership7951.immatureScale8364(33.0, listOf(33.0)), 1e-9)
        assertEquals(75.0, SpecialistOwnership7951.immatureScale8364(33.0, emptyList()), 1e-9)
        assertEquals(50.0, SpecialistOwnership7951.immatureScale8364(30.0, listOf(30.0, 50.0)), 1e-9)
        assertEquals(100.0, SpecialistOwnership7951.immatureScale8364(50.0, listOf(30.0, 50.0)), 1e-9)
        // Mature median and young lone value read the same.
        val mature = (0 until 21).map { 30.0 + it }
        assertEquals(SpecialistOwnership7951.commonScale7951(40.0, mature), SpecialistOwnership7951.commonScale7951(90.0, listOf(90.0)), 1e-9)
    }

    @Test fun oneHotMintNoLongerFloodsTheLanesPopulation() {
        val o = SpecialistBrainBridge7542.Opinion("MOONSHOT", true, 40, 40, 0.0, "t", "NONE", "e", "x")
        repeat(20) { SpecialistOwnership7951.onCommonScale7951("hotMint8364", mapOf("MOONSHOT" to o)) }
        val b = SpecialistBrainBridge7542.Opinion("MOONSHOT", true, 60, 60, 0.0, "t", "NONE", "e", "x")
        val out = SpecialistOwnership7951.onCommonScale7951("otherMint8364", mapOf("MOONSHOT" to b))
        // Two distinct mints (40, 60): the stronger one tops the lane's own range (it was ranked against 20 copies of one coin).
        assertEquals(100.0, out.getValue("MOONSHOT").ownershipConviction7948!!, 1e-9)
    }

    // ── election ──

    @Test fun secondRescueRuleIsNarrow() {
        assertTrue(e.secondRescueOpen8364(held = true, consumed = true, rescueRefusedNonJudgement = true, primaryJudgement = false, secondUsed = false))
        assertFalse(e.secondRescueOpen8364(true, false, true, false, false))   // claimed, not yet evaluated
        assertFalse(e.secondRescueOpen8364(true, true, false, false, false))   // allowed / judgement refusal
        assertFalse(e.secondRescueOpen8364(true, true, true, true, false))     // primary judgement (safety / token wall)
        assertFalse(e.secondRescueOpen8364(true, true, true, false, true))     // already used
        assertFalse(e.secondRescueOpen8364(false, true, true, false, false))
    }

    @Test fun aJudgementRefusalByTheRescueNeverOpensASecond() {
        val m = "mint-judgement8364"
        e.registerNative8136(m, 30L, t0, mapOf("CASHGEN" to view(55.0), "MOONSHOT" to view(80.0), "SHITCOIN" to view(65.0)), t0)
        e.cyclePrimary8136(m, 30L, "CASHGEN", false, null, t0)
        e.onFdgVerdict8136(m, "CASHGEN", 30L, false, "PLAYBOOK_NO_TRIGGER_7907_CASHGEN", false, true, 55.0, t0 + 1)
        assertEquals("MOONSHOT", e.cyclePrimary8136(m, 30L, "MOONSHOT", false, null, t0 + 2))
        e.onFdgVerdict8136(m, "MOONSHOT", 30L, false, "LIQUIDITY_COLLAPSE", true, true, 80.0, t0 + 3)
        assertEquals("MOONSHOT", e.cyclePrimary8136(m, 30L, "SHITCOIN", false, null, t0 + 4))
        assertTrue(e.statusLine8136().contains("secondRescue=0"))
    }

    @Test fun anFdgAllowTakesTheSlotFromAnUnevaluatedClaim() {
        val m = "mint-allow8364"
        e.registerNative8136(m, 40L, t0, mapOf("SHITCOIN" to view(70.0), "MOONSHOT" to view(72.0), "EXPRESS" to view(78.0)), t0)
        e.cyclePrimary8136(m, 40L, "SHITCOIN", false, null, t0)
        assertTrue(e.claimRescue8136(m, "MOONSHOT", "SHITCOIN", cell, "", false,
            listOf(c("SHITCOIN", 70.0), c("MOONSHOT", 72.0), c("EXPRESS", 78.0)), null, t0 + 1))
        e.onFdgVerdict8136(m, "EXPRESS", 40L, true, null, false, true, 78.0, t0 + 2)
        assertEquals("EXPRESS", e.rescueOf8136(m))
        assertEquals(Terminal8136.CONTRIBUTOR_SHADOW to "RESCUE_PREEMPTED_BY_FDG_ALLOW_8364_EXPRESS", e.terminalOf8136(m, "MOONSHOT"))
        assertEquals(Terminal8136.RESCUE_EVALUATED to "ADOPTED_FDG_ALLOW_OVER_UNEVALUATED_CLAIM_8364", e.terminalOf8136(m, "EXPRESS"))
        val line = e.statusLine8136()
        assertTrue(line, line.contains("allowPreempted=1"))
        assertTrue(line, line.contains("overflow=0"))
    }

    @Test fun aYieldWithNoReceiverNamesTheLaneThatBecameEligibleLater() {
        val m = "mint-yield8364"
        assertNull(e.noteYield8136(m, "CORE", null, t0))
        assertTrue(e.statusLine8136().contains("NO_ELIGIBLE_RECEIVER=1"))
        assertEquals("MOONSHOT", e.noteYield8136(m, "CORE", "MOONSHOT", t0 + 1))
        assertTrue(e.isNamedReceiver8144(m, "MOONSHOT"))
        assertEquals("MOONSHOT", e.noteYield8136(m, "CORE", "QUALITY", t0 + 2))    // the first late name stands
        assertTrue(e.statusLine8136().contains("lateYieldNamed=1"))
    }

    @Test fun theNativeSlotCapStillHoldsWithoutNativeEvidence() {
        val m = "Nat8364" + System.nanoTime()
        assertTrue(e.nativeEvalSlot8214(m, "MOONSHOT", "QUALITY", 1L, true))
        assertTrue(e.nativeEvalSlot8214(m, "SHITCOIN", "QUALITY", 1L, true))
        assertTrue(e.nativeEvalSlot8214(m, "EXPRESS", "QUALITY", 1L, true))
        // superseded by V5.0.8429 — the cap is six; the seventh asker meets it. Was: the fourth refused at a cap of three.
        assertTrue(e.nativeEvalSlot8214(m, "DIP_HUNTER", "QUALITY", 1L, true))
        assertTrue(e.nativeEvalSlot8214(m, "MANIPULATED", "QUALITY", 1L, true))
        assertTrue(e.nativeEvalSlot8214(m, "BLUECHIP", "QUALITY", 1L, true))
        // No cached native snapshot: unknown eligibility never evicts a holder.
        assertFalse(e.nativeEvalSlot8214(m, "PROJECT_SNIPER", "QUALITY", 1L, true))
        assertTrue(e.statusLine8136().contains("nativeSlotReassigned=0"))
    }

    // ── source pins at the hooks ──

    @Test fun hooksAreWired() {
        val sheet = src("engine/ToolkitSignalSheet.kt")
        assertTrue(sheet.contains("OwnershipChain8364.onDeskStage8364(l, st, eventId)"))
        assertTrue(sheet.contains("OwnershipChain8364.onNativeLost8364(lane, ts.mint)"))
        assertTrue(sheet.contains("OwnershipChain8364.reportLine8364(lane)?.let { appendLine(it) }"))
        assertTrue(sheet.contains("SpecialistOwnership7951.nativeWhyLine8364(lane)?.let { appendLine(it) }"))
        val own = src("engine/SpecialistOwnership7951.kt")
        assertTrue(own.contains("OwnershipChain8364.onFdgVerdict8364(lane, mint, candidateVersion, shouldTrade, blockReason, nowMs)"))
        assertTrue(own.contains("stampFdgBlockContinuity8364(l, mint)"))
        assertTrue(own.contains("ToolkitSignalSheet.recordDeskStage(lane, \"FDG_BLOCK\", \"\$mint:\$v\")"))
        assertTrue(own.contains("OwnershipChain8364.onNotEvaluated8364(l, mint,"))
        assertTrue(own.contains("observe7951(lane7951(lane), raw, mint)"))
        assertTrue(own.contains("if (population.size < MIN_SAMPLES_7951) return immatureScale8364(v, population)"))
        assertTrue(src("engine/LoadTelemetry8169.kt").contains("OwnershipChain8364.skipKey8364(laneSkipReason8169("))
        val el = src("engine/SpecialistElection8136.kt")
        assertTrue(el.contains("r.rescue.isNotEmpty() && !releaseIdle8180(r, o, nowMs) && !openSecondRescue8364(r) ->"))
        assertTrue(el.contains("l in set || (set.size < NATIVE_EVAL_CAP_8214 && set.add(l)) || reassignNativeSlot8364(set, mint, l)"))
        // OWNER_SELECTED stays after the coordinator election inside authorize (it follows an FDG allow).
        assertTrue(src("engine/TradeAuthorizer.kt").contains(
            "if (laneElection.allowed) try { ToolkitSignalSheet.recordDeskStage(requestedBook.name, \"OWNER_SELECTED\", causalAttempt6613) } catch (_: Throwable) {}"))
    }
}
