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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * V5.0.8136 — one causal specialist election contract per candidate: every qualified proposal ends in exactly one
 * terminal, one executable primary, at most one rescue elected on each lane's own scale, judgement refusals never rescued.
 */
class Aate8136SpecialistElectionTest {
    private val e = SpecialistElection8136
    private val fdg = SpecialistElection8136.TRIGGER_FDG
    private val native = SpecialistElection8136.TRIGGER_NATIVE
    private val preFdg = SpecialistElection8136.TRIGGER_PRE_FDG
    private val cell = SpecialistElection8136.TRIGGER_CELL_PROVEN
    private val t0 = 1_000_000_000L
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Before fun setUp() { e.resetForTests8136() }
    @After fun tearDown() { e.resetForTests8136() }

    private fun view(n: Double, eligible: Boolean = true, reason: String = "OK") = NativeView8136(eligible, n, reason)
    private fun c(lane: String, n: Double, proven: Boolean = false) = Contender8136(lane, n, proven)

    /** Every qualified proposal is still open or sits in exactly one terminal. */
    private fun assertBalanced(vararg lanes: String) {
        for (l in lanes) {
            val m = e.laneCounts8136(l)
            val terminals = Terminal8136.values().map { m.getValue(it.name) }.sum()
            assertEquals("lane $l: $m", m.getValue("QUALIFIED"), m.getValue("OPEN") + terminals)
            assertTrue("lane $l: $m", m.getValue("DISCOVERED") >= m.getValue("QUALIFIED"))
        }
    }

    @Test fun aRefusedPrimaryHandsTheCoinToTheOneBestRescue() {
        val m = "mint-rescue"
        e.registerNative8136(m, 10L, t0, mapOf("CASHGEN" to view(55.0), "MOONSHOT" to view(80.0), "SHITCOIN" to view(65.0),
            "QUALITY" to view(0.0, eligible = false, reason = "MCAP too low")), t0)
        assertEquals("CASHGEN", e.cyclePrimary8136(m, 10L, "CASHGEN", false, null, t0))
        e.onFdgVerdict8136(m, "CASHGEN", 10L, false, "PLAYBOOK_NO_TRIGGER_7907_CASHGEN", false, true, 55.0, t0 + 10)
        assertEquals(Terminal8136.FDG_REFUSED, e.terminalOf8136(m, "CASHGEN")?.first)
        val cs = listOf(c("CASHGEN", 55.0), c("MOONSHOT", 80.0), c("SHITCOIN", 65.0))
        // The weaker READY lane asks first: outranked. The best one takes the slot; nobody else ever does.
        assertFalse(e.claimRescue8136(m, "SHITCOIN", "CASHGEN", fdg, "PLAYBOOK_NO_TRIGGER_7907_CASHGEN", false, cs, null, t0 + 20))
        assertTrue(e.claimRescue8136(m, "MOONSHOT", "CASHGEN", fdg, "PLAYBOOK_NO_TRIGGER_7907_CASHGEN", false, cs, null, t0 + 30))
        assertEquals("MOONSHOT", e.rescueOf8136(m))
        assertTrue(e.holdsRescue8136(m, "MOONSHOT"))
        assertTrue(e.claimRescue8136(m, "MOONSHOT", "CASHGEN", fdg, "x", false, cs, null, t0 + 35))      // idempotent for the holder
        assertFalse(e.claimRescue8136(m, "SHITCOIN", "CASHGEN", fdg, "x", false, cs, null, t0 + 36))
        e.noteNotEvaluated8136(m, "SHITCOIN", "CASHGEN", true, 65.0, t0 + 40)
        assertEquals(Terminal8136.CONTRIBUTOR_SHADOW to "RESCUE_SLOT_HELD_BY_MOONSHOT", e.terminalOf8136(m, "SHITCOIN"))
        e.onFdgVerdict8136(m, "MOONSHOT", 10L, true, null, false, true, 80.0, t0 + 50)
        assertEquals(Terminal8136.RESCUE_EVALUATED to "FDG_ALLOW", e.terminalOf8136(m, "MOONSHOT"))
        // A natively refused lane was assessed (discovered) but never a qualified proposal.
        assertNull(e.terminalOf8136(m, "QUALITY"))
        assertEquals(1L, e.laneCounts8136("QUALITY").getValue("DISCOVERED"))
        assertEquals(0L, e.laneCounts8136("QUALITY").getValue("QUALIFIED"))
        assertBalanced("CASHGEN", "MOONSHOT", "SHITCOIN", "QUALITY")
    }

    @Test fun theCoordinatorsReElectionIsTheRescueAndAThirdLaneNeverTakesTheVersion() {
        val m = "mint-reelect"
        e.registerNative8136(m, 20L, t0, mapOf("CASHGEN" to view(55.0), "MOONSHOT" to view(80.0), "SHITCOIN" to view(65.0)), t0)
        assertEquals("CASHGEN", e.cyclePrimary8136(m, 20L, "CASHGEN", false, null, t0))
        e.onFdgVerdict8136(m, "CASHGEN", 20L, false, "C3_PROVEN_NEGATIVE_EDGE", false, true, 55.0, t0 + 5)
        assertEquals("MOONSHOT", e.cyclePrimary8136(m, 20L, "MOONSHOT", false, null, t0 + 100))
        assertEquals("MOONSHOT", e.rescueOf8136(m))
        assertEquals("CASHGEN", e.primaryOf8136(m))                                                  // one executable primary
        e.onFdgVerdict8136(m, "MOONSHOT", 20L, false, "PLAYBOOK_NO_TRIGGER_7907_MOONSHOT", false, true, 80.0, t0 + 110)
        assertEquals(Terminal8136.RESCUE_EVALUATED, e.terminalOf8136(m, "MOONSHOT")?.first)
        // Primary and rescue both refused: the version is concluded; SHITCOIN does not become a second rescue.
        assertEquals("MOONSHOT", e.cyclePrimary8136(m, 20L, "SHITCOIN", false, null, t0 + 200))
        assertEquals(Terminal8136.CONTRIBUTOR_SHADOW to "RESCUE_SLOT_HELD_BY_MOONSHOT", e.terminalOf8136(m, "SHITCOIN"))
        // A sealed / forced owner is authoritative and immutable.
        assertEquals("SHITCOIN", e.cyclePrimary8136(m, 20L, "SHITCOIN", true, null, t0 + 300))
        assertEquals("SHITCOIN", e.primaryOf8136(m))
        assertBalanced("CASHGEN", "MOONSHOT", "SHITCOIN")
    }

    @Test fun coreYieldReachesTheNamedSpecialistOrEndsWithAPreciseRefusal() {
        assertTrue(SpecialistOwnership7951.laneFitRefusal8132("CORE_YIELD_CLEAR_SPECIALIST_OWNER"))
        val m = "mint-core"
        e.registerNative8136(m, 30L, t0, mapOf("CORE" to view(70.0, eligible = false, reason = "CORE_YIELD_CLEAR_SPECIALIST_OWNER"),
            "MOONSHOT" to view(84.0), "CASHGEN" to view(61.0), "CYCLIC" to view(95.0)), t0)
        assertEquals("CORE", e.cyclePrimary8136(m, 30L, "CORE", false, null, t0))
        val cs = listOf(c("MOONSHOT", 84.0), c("CASHGEN", 61.0), c("CYCLIC", 95.0))
        val named = e.electRescue8136(cs + c("CORE", 70.0), "CORE")
        assertEquals("MOONSHOT", named)                                                             // CYCLIC / CORE are never the receiver
        e.notePrimaryRefusal8136(m, "CORE", "NATIVE:CORE_YIELD_CLEAR_SPECIALIST_OWNER", false, t0 + 1)
        e.noteYield8136(m, "CORE", named, t0 + 1)
        assertFalse(e.claimRescue8136(m, "CASHGEN", "CORE", native, "CORE_YIELD_CLEAR_SPECIALIST_OWNER", false, cs, null, t0 + 2))
        assertTrue(e.claimRescue8136(m, "MOONSHOT", "CORE", native, "CORE_YIELD_CLEAR_SPECIALIST_OWNER", false, cs, null, t0 + 3))
        e.onFdgVerdict8136(m, "MOONSHOT", 30L, false, "PLAYBOOK_NO_TRIGGER_7907_MOONSHOT", false, true, 84.0, t0 + 4)
        assertTrue(e.statusLine8136(), e.statusLine8136().contains("yield(named=1 reached=1"))
        // The named receiver refused by its own record: a precise refusal, not silence.
        val m2 = "mint-core-refused"
        e.registerNative8136(m2, 31L, t0, mapOf("CORE" to view(70.0, eligible = false, reason = "CORE_YIELD_CLEAR_SPECIALIST_OWNER"),
            "MOONSHOT" to view(84.0)), t0)
        e.cyclePrimary8136(m2, 31L, "CORE", false, null, t0)
        e.noteYield8136(m2, "CORE", "MOONSHOT", t0 + 1)
        e.markIneligible8136(m2, "MOONSHOT", "RECEIVER_RECORD_UNSOUND_8094", t0 + 2)
        assertEquals(Terminal8136.INELIGIBLE to "RECEIVER_RECORD_UNSOUND_8094", e.terminalOf8136(m2, "MOONSHOT"))
        assertTrue(e.statusLine8136(), e.statusLine8136().contains("RECEIVER_RECORD_UNSOUND_8094=1"))
        assertFalse(e.mayOwn8136(m2, "MOONSHOT"))
        // Nobody eligible to receive: named as such.
        val m3 = "mint-core-none"
        e.noteYield8136(m3, "CORE", null, t0)
        assertTrue(e.statusLine8136().contains("NO_ELIGIBLE_RECEIVER=1"))
        assertBalanced("MOONSHOT", "CASHGEN", "CYCLIC", "CORE")
    }

    @Test fun aTreasuryRoleFloorRefusalHandsTheRunnerThesisToTheRunnerNotTheRawScoreScalper() {
        assertTrue(SpecialistOwnership7951.laneFitRefusal8132("TREASURY_ROLE_FLOOR_8031"))
        assertFalse(SpecialistOwnership7951.refusalIsJudgement8136("TREASURY_ROLE_FLOOR_8031", fdg = false))
        val cash = e.normalised8136(76.0, 72.0, null)          // raw 76 on CASHGEN's 72 bar
        val moon = e.normalised8136(55.0, 30.0, null)          // raw 55 on MOONSHOT's 30 bar
        assertTrue("moon=$moon cash=$cash", moon > cash)
        val m = "mint-treasury"
        e.registerNative8136(m, 40L, t0, mapOf("TREASURY" to view(48.0, eligible = false, reason = "TREASURY_ROLE_FLOOR_8031"),
            "CASHGEN" to view(cash), "MOONSHOT" to view(moon)), t0)
        assertEquals("TREASURY", e.cyclePrimary8136(m, 40L, "TREASURY", false, null, t0))
        e.notePrimaryRefusal8136(m, "TREASURY", "NATIVE:TREASURY_ROLE_FLOOR_8031", false, t0 + 1)
        val cs = listOf(c("CASHGEN", cash), c("MOONSHOT", moon))
        assertEquals("MOONSHOT", e.electRescue8136(cs, "TREASURY"))
        assertFalse(e.claimRescue8136(m, "CASHGEN", "TREASURY", native, "TREASURY_ROLE_FLOOR_8031", false, cs, null, t0 + 2))
        assertTrue(e.claimRescue8136(m, "MOONSHOT", "TREASURY", native, "TREASURY_ROLE_FLOOR_8031", false, cs, null, t0 + 3))
        assertBalanced("TREASURY", "CASHGEN", "MOONSHOT")
    }

    @Test fun incompatibleNativeScalesAreComparedOnEachLanesOwnBar() {
        assertEquals(50.0, e.normalised8136(30.0, 30.0, null), 1e-9)
        assertEquals(100.0, e.normalised8136(100.0, 60.0, null), 1e-9)
        assertEquals(25.0, e.normalised8136(30.0, 60.0, null), 1e-9)
        assertEquals(73.0, e.normalised8136(12.0, 50.0, 73.0), 1e-9)          // the calibrated conviction wins
        assertEquals(42.0, e.normalised8136(42.0, Double.NaN, null), 1e-9)    // no bar known: the raw value
        assertEquals(0.0, e.normalised8136(Double.NaN, 50.0, null), 1e-9)
        // Raw: CASHGEN 76 > SHITCOIN 40. On their own bars (72 vs 15) SHITCOIN is the stronger thesis.
        val pool = listOf(c("CASHGEN", e.normalised8136(76.0, 72.0, null)), c("SHITCOIN", e.normalised8136(40.0, 15.0, null)))
        assertEquals("SHITCOIN", e.electRescue8136(pool, "MOONSHOT"))
        assertEquals("BLUECHIP", e.electRescue8136(listOf(c("QUALITY", 60.0), c("BLUECHIP", 60.0)), "CORE"))   // deterministic tie
        assertEquals(60.0 + SpecialistElection8136.PROVEN_BONUS_8136, e.rescueRank8136(c("QUALITY", 60.0, proven = true)), 1e-9)
        assertEquals(setOf("CORE", "CYCLIC"), SpecialistElection8136.RESCUE_EXCLUDED_8136)
        assertNull(e.electRescue8136(listOf(c("CORE", 90.0), c("CYCLIC", 90.0), c("MOONSHOT", 90.0)), "MOONSHOT"))
    }

    @Test fun aStrongSecondarySpecialistWithAProvenCellTakesTheOneSlot() {
        val m = "mint-secondary"
        e.registerNative8136(m, 50L, t0, mapOf("SHITCOIN" to view(70.0), "MOONSHOT" to view(72.0), "EXPRESS" to view(78.0)), t0)
        assertEquals("SHITCOIN", e.cyclePrimary8136(m, 50L, "SHITCOIN", false, null, t0))
        val cs = listOf(c("SHITCOIN", 70.0), c("MOONSHOT", 72.0), c("EXPRESS", 78.0))
        // The primary has not refused: only a proven cell may enter, and only one.
        assertTrue(e.claimRescue8136(m, "MOONSHOT", "SHITCOIN", cell, "", false, cs, null, t0 + 1))
        assertFalse(e.claimRescue8136(m, "EXPRESS", "SHITCOIN", cell, "", false, cs, null, t0 + 2))
        assertEquals("MOONSHOT", e.rescueOf8136(m))
        // The proven bonus lets a strong proven secondary beat a slightly higher unproven one, not a much higher one.
        assertEquals("MOONSHOT", e.electRescue8136(listOf(c("MOONSHOT", 72.0, proven = true), c("EXPRESS", 78.0)), "SHITCOIN"))
        assertEquals("EXPRESS", e.electRescue8136(listOf(c("MOONSHOT", 60.0, proven = true), c("EXPRESS", 78.0)), "SHITCOIN"))
        // A non-primary qualified lane that reaches the FDG once the slot is held is counted, never a silent second rescue.
        e.onFdgVerdict8136(m, "EXPRESS", 50L, false, "PLAYBOOK_NO_TRIGGER_7907_EXPRESS", false, true, 78.0, t0 + 3)
        assertTrue(e.statusLine8136().contains("overflow=1"))
        assertBalanced("SHITCOIN", "MOONSHOT", "EXPRESS")
    }

    @Test fun theSameCandidateVersionIsCountedOnce() {
        val m = "mint-dup"
        repeat(3) { e.registerNative8136(m, 60L, t0, mapOf("MOONSHOT" to view(70.0)), t0 + it) }
        repeat(3) { assertEquals("MOONSHOT", e.cyclePrimary8136(m, 60L, "MOONSHOT", false, null, t0 + 10 + it)) }
        repeat(3) { e.onFdgVerdict8136(m, "MOONSHOT", 60L, true, null, false, true, 70.0, t0 + 20 + it) }
        e.onFdgVerdict8136(m, "MOONSHOT", 60L, false, "LATE_LOWER_VERDICT", false, true, 70.0, t0 + 30)
        val n = e.laneCounts8136("MOONSHOT")
        assertEquals(1L, n.getValue("DISCOVERED"))
        assertEquals(1L, n.getValue("QUALIFIED"))
        assertEquals(1L, n.getValue("ELECTED_EVALUATED"))
        assertEquals(0L, n.getValue("FDG_REFUSED"))
        assertEquals(Terminal8136.ELECTED_EVALUATED to "FDG_ALLOW", e.terminalOf8136(m, "MOONSHOT"))
        assertBalanced("MOONSHOT")
    }

    @Test fun newerEvidenceSupersedesAndStaleEvidenceIsIgnored() {
        val m = "mint-newer"
        e.registerNative8136(m, 70L, t0, mapOf("MOONSHOT" to view(70.0), "SHITCOIN" to view(60.0)), t0)
        e.cyclePrimary8136(m, 70L, "MOONSHOT", false, null, t0)
        e.registerNative8136(m, 71L, t0 + 5, mapOf("MOONSHOT" to view(75.0), "SHITCOIN" to view(60.0)), t0 + 5)
        // Version 70 closed: its silent primary is named, the untouched proposal superseded.
        val moon = e.laneCounts8136("MOONSHOT")
        assertEquals(2L, moon.getValue("QUALIFIED"))
        assertEquals(1L, moon.getValue("INELIGIBLE"))
        assertEquals(1L, e.laneCounts8136("SHITCOIN").getValue("SUPERSEDED"))
        assertNull(e.terminalOf8136(m, "MOONSHOT")?.first)                    // v71's proposal is open
        e.onFdgVerdict8136(m, "MOONSHOT", 70L, true, null, false, true, 70.0, t0 + 6)
        assertTrue(e.statusLine8136().contains("stale=1"))
        assertNull(e.terminalOf8136(m, "MOONSHOT")?.first)
        // Inside one version: a native refusal ends the proposal; newer eligible evidence reopens it; older evidence is ignored.
        e.registerNative8136(m, 71L, t0 + 10, mapOf("SHITCOIN" to view(0.0, eligible = false, reason = "WEAK_MOMENTUM")), t0 + 10)
        assertEquals(Terminal8136.INELIGIBLE, e.terminalOf8136(m, "SHITCOIN")?.first)
        assertFalse(e.mayOwn8136(m, "SHITCOIN"))
        e.registerNative8136(m, 71L, t0 + 20, mapOf("SHITCOIN" to view(66.0)), t0 + 20)
        assertNull(e.terminalOf8136(m, "SHITCOIN")?.first)
        e.registerNative8136(m, 71L, t0 + 15, mapOf("SHITCOIN" to view(0.0, eligible = false, reason = "OLDER")), t0 + 21)
        assertNull(e.terminalOf8136(m, "SHITCOIN")?.first)
        assertTrue(e.mayOwn8136(m, "SHITCOIN"))
        assertBalanced("MOONSHOT", "SHITCOIN")
    }

    @Test fun concurrentClaimsElectExactlyOneRescueAndKeepTheBooksBalanced() {
        val m = "mint-threads"
        val lanes = listOf("MOONSHOT", "SHITCOIN", "EXPRESS", "QUALITY", "BLUECHIP", "DIP_HUNTER", "MANIPULATED", "PROJECT_SNIPER")
        val cs = lanes.mapIndexed { i, l -> c(l, 50.0 + i * 3.0) }
        e.registerNative8136(m, 90L, t0, (cs.associate { it.lane to view(it.normalised) }) + ("CASHGEN" to view(55.0)), t0)
        e.cyclePrimary8136(m, 90L, "CASHGEN", false, null, t0)
        e.onFdgVerdict8136(m, "CASHGEN", 90L, false, "PLAYBOOK_NO_TRIGGER_7907_CASHGEN", false, true, 55.0, t0 + 1)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val granted = AtomicInteger()
        val winners = ConcurrentHashMap.newKeySet<String>()
        repeat(4) { round ->
            for (lane in lanes) pool.execute {
                start.await()
                if (e.claimRescue8136(m, lane, "CASHGEN", fdg, "PLAYBOOK_NO_TRIGGER_7907_CASHGEN", false, cs, null, t0 + 100)) {
                    granted.incrementAndGet(); winners.add(lane)
                }
                e.registerNative8136("mint-t-$lane", 90L + round, t0 + 100, mapOf(lane to view(60.0)), t0 + 100)
                e.noteNotEvaluated8136("mint-t-$lane", lane, "CORE", true, 60.0, t0 + 100)
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals(setOf("PROJECT_SNIPER"), winners.toSet())
        assertEquals(4, granted.get())                                       // the holder, idempotently, every round
        assertEquals("PROJECT_SNIPER", e.rescueOf8136(m))
        assertBalanced(*(lanes + "CASHGEN").toTypedArray())
    }

    @Test fun aJudgementRefusalIsNeverRescued() {
        val j = SpecialistOwnership7951
        assertTrue(j.refusalIsJudgement8136("HARD_BLOCK_RUGCHECK_FAILED", fdg = true))
        assertTrue(j.refusalIsJudgement8136("LIQUIDITY_BELOW_EXECUTION_FLOOR", fdg = true))
        assertTrue(j.refusalIsJudgement8136("SIZE_NOT_EXECUTABLE_7835", fdg = true))
        assertTrue(j.refusalIsJudgement8136("DEV_SELLING", fdg = false))
        assertFalse(j.refusalIsJudgement8136("PLAYBOOK_NO_TRIGGER_7907_SHITCOIN", fdg = true))
        assertFalse(j.refusalIsJudgement8136("C3_PROVEN_NEGATIVE_EDGE", fdg = true))
        assertFalse(j.refusalIsJudgement8136("LIQ_TOO_LOW: 52 < 309", fdg = false))     // a lane's own floor: 8094 READY hand-off kept
        assertFalse(j.refusalIsJudgement8136(null, fdg = true))
        // 8198 — one lane's strategy / policy / toxicity refusal is its own fit, not the token's verdict: the election stays rescuable.
        for (laneOwn in listOf("MEMORY_NEGATIVE_BLOCK", "SELECTION_QUALITY_FLOOR_BLOCKED_6830_MOONSHOT", "FDG_POLICY_BLOCK_7835",
            "LEARNED_TOXIC_LANE", "TOXIC_PATTERN_TACTIC_PIVOT_6482", "BRAIN_CONSENSUS_SOFT_BLOCK_DAMPED_7263", "FDG_LOCAL_BLOCK",
            "CANONICAL_EDGE_PARITY_BLOCK_7431", "LANE_EVAL_ERROR")) {
            assertFalse(laneOwn, j.refusalIsJudgement8136(laneOwn, fdg = true))
            assertFalse(laneOwn, j.refusalIsJudgement8136(laneOwn, fdg = false))
        }
        for (token in listOf("HARD_BLOCK_FREEZE_AUTHORITY", "BEHAVIOR_BLOCK_100PCT_LOSS", "HARD_BLOCK_TOP_HOLDER_", "BUNDLE_DETECTED", "LEARNING_QUARANTINE")) {
            assertTrue(token, j.refusalIsJudgement8136(token, fdg = false))
        }
        val m = "mint-judgement"
        e.registerNative8136(m, 100L, t0, mapOf("CASHGEN" to view(55.0), "MOONSHOT" to view(80.0)), t0)
        e.cyclePrimary8136(m, 100L, "CASHGEN", false, null, t0)
        e.onFdgVerdict8136(m, "CASHGEN", 100L, false, "HARD_BLOCK_RUGCHECK_FAILED", true, true, 55.0, t0 + 1)
        val cs = listOf(c("CASHGEN", 55.0), c("MOONSHOT", 80.0))
        assertFalse(e.claimRescue8136(m, "MOONSHOT", "CASHGEN", fdg, "HARD_BLOCK_RUGCHECK_FAILED", true, cs, null, t0 + 2))
        assertFalse(e.claimRescue8136(m, "MOONSHOT", "CASHGEN", cell, "", false, cs, null, t0 + 3))      // not even a proven cell
        val t = e.terminalOf8136(m, "MOONSHOT")
        assertEquals(Terminal8136.INELIGIBLE, t?.first)
        assertTrue(t!!.second, t.second.startsWith("PRIMARY_JUDGEMENT:FDG:HARD_BLOCK_RUGCHECK_FAILED"))
        // The coordinator's re-election does not rescue it either.
        assertEquals("CASHGEN", e.cyclePrimary8136(m, 100L, "MOONSHOT", false, null, t0 + 10))
        assertEquals("", e.rescueOf8136(m))
        assertBalanced("CASHGEN", "MOONSHOT")
    }

    @Test fun aGatedOrSilentPrimaryOpensTheRescueAndAGatedRescueReleasesIt() {
        val m = "mint-gated"
        e.registerNative8136(m, 110L, t0, mapOf("QUALITY" to view(60.0), "BLUECHIP" to view(70.0), "MOONSHOT" to view(65.0)), t0)
        e.cyclePrimary8136(m, 110L, "QUALITY", false, null, t0)
        assertFalse(e.primaryGatedPreFdg8136(m, "QUALITY"))
        e.noteNotEvaluated8136(m, "QUALITY", "QUALITY", true, 60.0, t0 + 1)
        assertTrue(e.primaryGatedPreFdg8136(m, "QUALITY"))
        assertEquals(Terminal8136.INELIGIBLE to "PRIMARY_GATED_PRE_FDG", e.terminalOf8136(m, "QUALITY"))
        assertFalse(e.mayOwn8136(m, "QUALITY"))
        val cs = listOf(c("QUALITY", 60.0), c("BLUECHIP", 70.0), c("MOONSHOT", 65.0))
        assertTrue(e.claimRescue8136(m, "BLUECHIP", "QUALITY", preFdg, "PRIMARY_GATED_PRE_FDG", false, cs, null, t0 + 2))
        e.noteNotEvaluated8136(m, "BLUECHIP", "QUALITY", true, 70.0, t0 + 3)
        assertEquals(Terminal8136.INELIGIBLE to "RESCUE_GATED_PRE_FDG", e.terminalOf8136(m, "BLUECHIP"))
        assertEquals("", e.rescueOf8136(m))
        assertTrue(e.claimRescue8136(m, "MOONSHOT", "QUALITY", preFdg, "PRIMARY_GATED_PRE_FDG", false, cs, null, t0 + 4))
        // A primary that sits silent (no FDG verdict, no refusal) past the window counts as refused too.
        val m2 = "mint-silent"
        e.registerNative8136(m2, 120L, t0, mapOf("SHITCOIN" to view(60.0), "EXPRESS" to view(70.0)), t0)
        e.cyclePrimary8136(m2, 120L, "SHITCOIN", false, null, t0)
        assertFalse(e.primarySilent8136(m2, "SHITCOIN", t0 + 1_000L))
        assertTrue(e.primarySilent8136(m2, "SHITCOIN", t0 + SpecialistElection8136.PRIMARY_SILENT_MS_8136 + 1L))
        e.onFdgVerdict8136(m2, "SHITCOIN", 120L, true, null, false, true, 60.0, t0 + 2_000L)
        assertFalse(e.primarySilent8136(m2, "SHITCOIN", t0 + SpecialistElection8136.PRIMARY_SILENT_MS_8136 + 1L))
        // A winner that never asks loses its offer, and the next best lane is evaluated.
        val m3 = "mint-offer"
        e.registerNative8136(m3, 130L, t0, mapOf("CASHGEN" to view(55.0), "MOONSHOT" to view(90.0), "SHITCOIN" to view(60.0)), t0)
        e.cyclePrimary8136(m3, 130L, "CASHGEN", false, null, t0)
        e.onFdgVerdict8136(m3, "CASHGEN", 130L, false, "EDGE_VETO_ACTIVE", false, true, 55.0, t0 + 1)
        val cs3 = listOf(c("MOONSHOT", 90.0), c("SHITCOIN", 60.0))
        val offer = SpecialistElection8136.OFFER_MS_8136
        assertFalse(e.claimRescue8136(m3, "SHITCOIN", "CASHGEN", fdg, "EDGE_VETO_ACTIVE", false, cs3, null, t0 + 10))
        assertFalse(e.claimRescue8136(m3, "SHITCOIN", "CASHGEN", fdg, "EDGE_VETO_ACTIVE", false, cs3, null, t0 + 10 + offer))
        assertTrue(e.claimRescue8136(m3, "SHITCOIN", "CASHGEN", fdg, "EDGE_VETO_ACTIVE", false, cs3, null, t0 + 11 + offer))
        assertEquals(Terminal8136.INELIGIBLE to "RESCUE_OFFER_UNCLAIMED", e.terminalOf8136(m3, "MOONSHOT"))
        assertBalanced("QUALITY", "BLUECHIP", "MOONSHOT", "SHITCOIN", "EXPRESS", "CASHGEN")
    }

    @Test fun theReportShowsPerLaneElectionCounts() {
        e.registerNative8136("mint-report", 140L, t0, mapOf("MOONSHOT" to view(70.0)), t0)
        assertEquals("D1>Q1>E0/F0/R0/S0/X0/DD0/I0 open1", e.laneCompact8136("MOONSHOT"))
        assertTrue(e.statusLine8136().contains("MOONSHOT=D1>Q1>E0/F0/R0/S0/X0/DD0/I0 open1"))
        val line = LaneParticipation8029.statusLine()
        assertTrue(line.contains("shadowLabels="))
        assertTrue(line.contains("election8136 records="))
        assertTrue(src("engine/ToolkitSignalSheet.kt").contains("election8136=\${election8136(lane)}"))
    }

    @Test fun theWiringRunsEveryHandOffThroughTheOneContract() {
        val o = src("engine/SpecialistOwnership7951.kt")
        // V5.0.8221 — a lost rescue claim is terminal-named (RESCUE_OVERFLOW_7951) before it returns false.
        assertTrue(o.contains("if (ready && !rescueClaim8136(l, mint, primaryLane, trigger8136, nowMs)) {"))
        assertTrue(o.contains("SpecialistElection8136.markIneligible8136(mint, l, \"RESCUE_OVERFLOW_7951\", nowMs)"))
        assertTrue(o.contains("(ok || audit) && rescueClaim8136(l, mint, primaryLane, SpecialistElection8136.TRIGGER_CELL_PROVEN"))
        assertTrue(o.contains("noteNotEvaluated8136(l, mint, primaryLane, ready)"))
        assertTrue(o.contains("noteFdgVerdict8136(mint, lane, candidateVersion, shouldTrade, blockReason, nowMs)"))
        assertTrue(o.contains("yielded && yieldReceiver8136(mint, p, ops!!) == lane7951(lane)"))
        assertFalse(o.contains(".maxByOrNull { maxOf(it.score, it.confidence) }?.lane == lane"))   // the raw-score receiver pick is gone
        val c = src("engine/LaneExecutionCoordinator.kt")
        assertTrue(c.contains("= cycleOwnerUnderElection8136(ts.mint, readyOwnerForCycleCore8136(ts, proposed, eligibleLanes, operatorForced), operatorForced)"))
        assertTrue(c.contains(".filter { SpecialistElection8136.mayOwn8136(mint, it) }"))
        assertTrue(src("engine/SpecialistBrainBridge7542.kt").contains("registerElection8136(it)"))
    }
}
