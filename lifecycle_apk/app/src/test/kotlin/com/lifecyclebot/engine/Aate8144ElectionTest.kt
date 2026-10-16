package com.lifecyclebot.engine

import com.lifecyclebot.engine.ElectionRescueContract8144.ClaimOutcome8144
import com.lifecyclebot.engine.ElectionRescueFunnel8144.Stage8144
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

/**
 * V5.0.8144 — the election contract end to end: an arbitration that throws never executes; CORE / TREASURY yields reach
 * their named receiver; a refused primary has its one rescue evaluated; a proven secondary takes the slot before the
 * lottery; concurrent claims elect one owner; a clock roll keeps the rescue bound to its lineage; the funnel reconciles.
 */
class Aate8144ElectionTest {
    private val e = SpecialistElection8136
    private val f = ElectionRescueFunnel8144
    private val fdg = SpecialistElection8136.TRIGGER_FDG
    private val native = SpecialistElection8136.TRIGGER_NATIVE
    private val cell = SpecialistElection8136.TRIGGER_CELL_PROVEN
    private val t0 = 3_000_000_000L
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Before fun setUp() { e.resetForTests8136() }
    @After fun tearDown() { e.resetForTests8136() }

    private fun view(n: Double, eligible: Boolean = true, reason: String = "OK") = NativeView8136(eligible, n, reason)
    private fun c(lane: String, n: Double) = Contender8136(lane, n)

    private fun assertBalanced(vararg lanes: String) {
        for (l in lanes) {
            val m = e.laneCounts8136(l)
            val terminals = Terminal8136.values().map { m.getValue(it.name) }.sum()
            assertEquals("lane $l: $m", m.getValue("QUALIFIED"), m.getValue("OPEN") + terminals)
        }
    }

    /** claimed + adopted >= scheduled; claimed + adopted >= evaluated >= fdgAllowed >= executed >= confirmed. */
    private fun assertFunnelReconciles(vararg lanes: String) {
        for (l in lanes) {
            val n = f.counts8144(l)
            val origin = n.getValue("CLAIMED") + n.getValue("ADOPTED")
            assertTrue("$l $n", origin >= n.getValue("SCHEDULED"))
            assertTrue("$l $n", origin >= n.getValue("EVALUATED"))
            assertTrue("$l $n", n.getValue("EVALUATED") >= n.getValue("FDG_ALLOWED"))
            assertTrue("$l $n", n.getValue("FDG_ALLOWED") >= n.getValue("EXECUTED"))
            assertTrue("$l $n", n.getValue("EXECUTED") >= n.getValue("CONFIRMED"))
        }
    }

    // ── 1. an exception in arbitration never executes ──

    @Test fun anExceptionInRescueArbitrationIsANamedDeferralNeverAnExecutableClaim() {
        val thrown = ElectionRescueContract8144.guardedClaim8144 { throw IllegalStateException("arbitration blew up") }
        assertEquals(ClaimOutcome8144.DEFERRED_ARBITRATION_ERROR, thrown)
        assertFalse(thrown.executable)
        assertEquals(ClaimOutcome8144.GRANTED, ElectionRescueContract8144.guardedClaim8144 { ClaimOutcome8144.GRANTED })
        for (o in ClaimOutcome8144.values()) assertEquals(o.name, o == ClaimOutcome8144.GRANTED || o == ClaimOutcome8144.HELD, o.executable)
        // The failure ends the lane's proposal DEFERRED by name, and it holds no slot.
        val m = "mint-error8144"
        e.registerNative8136(m, 100L, t0, mapOf("CASHGEN" to view(55.0), "MOONSHOT" to view(80.0)), t0)
        e.cyclePrimary8136(m, 100L, "CASHGEN", false, null, t0)
        e.noteArbitrationError8144(m, "MOONSHOT", t0 + 1)
        assertEquals(Terminal8136.DEFERRED_DATA to "DEFERRED_RESCUE_ARBITRATION_ERROR", e.terminalOf8136(m, "MOONSHOT"))
        assertFalse(e.holdsRescue8136(m, "MOONSHOT"))
        assertFalse(e.executableRescue8144(m, "MOONSHOT"))
        assertTrue(e.statusLine8136(), e.statusLine8136().contains("arbitrationErrors=1"))
        // The ownership claim runs inside the guard; the old `catch { true }` fallback is gone from the file.
        val o = src("engine/SpecialistOwnership7951.kt")
        assertTrue(o.contains("val outcome = ElectionRescueContract8144.guardedClaim8144 {"))
        assertTrue(o.contains("return outcome.executable"))
        assertFalse(o.contains("{ true }"))
        assertBalanced("CASHGEN", "MOONSHOT")
    }

    // ── 2. CORE and TREASURY yields reach the receiver ──

    @Test fun aCoreYieldReachesItsNamedReceiverEvenWhenAStrongerReadyLaneAsksFirst() {
        val m = "mint-core8144"
        e.registerNative8136(m, 200L, t0, mapOf("CORE" to view(70.0, eligible = false, reason = "CORE_YIELD_CLEAR_SPECIALIST_OWNER"),
            "MOONSHOT" to view(70.0)), t0)
        assertEquals("CORE", e.cyclePrimary8136(m, 200L, "CORE", false, null, t0))
        e.notePrimaryRefusal8136(m, "CORE", "NATIVE:CORE_YIELD_CLEAR_SPECIALIST_OWNER", false, t0 + 1)
        assertEquals("MOONSHOT", e.noteYield8136(m, "CORE", "MOONSHOT", t0 + 1))
        // The first name stands for the candidate: a refreshed snapshot ranking QUALITY first does not strand MOONSHOT.
        assertEquals("MOONSHOT", e.noteYield8136(m, "CORE", "QUALITY", t0 + 2))
        assertEquals("QUALITY", e.noteYield8136(m, "TREASURY", "QUALITY", t0 + 2))
        val cs = listOf(c("MOONSHOT", 70.0), c("QUALITY", 95.0))
        assertEquals(ClaimOutcome8144.DENIED_OUTRANKED,
            e.claimRescueOutcome8144(m, "QUALITY", "CORE", native, "CORE_YIELD_CLEAR_SPECIALIST_OWNER", false, cs, null, t0 + 3))
        assertTrue(e.isNamedReceiver8144(m, "MOONSHOT"))
        assertTrue(e.electionQualified8144(m, "MOONSHOT"))          // desk-qualified by the election itself
        assertFalse(e.electionQualified8144(m, "QUALITY"))
        assertEquals(ClaimOutcome8144.GRANTED,
            e.claimRescueOutcome8144(m, "MOONSHOT", "CORE", native, "CORE_YIELD_CLEAR_SPECIALIST_OWNER", false, cs, null, t0 + 4))
        e.noteScheduled8144(m, "MOONSHOT", t0 + 5)
        e.onFdgVerdict8136(m, "MOONSHOT", 200L, false, "PLAYBOOK_NO_TRIGGER_7907_MOONSHOT", false, true, 70.0, t0 + 6)
        assertEquals(Terminal8136.RESCUE_EVALUATED, e.terminalOf8136(m, "MOONSHOT")?.first)
        val line = e.statusLine8136()
        assertTrue(line, line.contains("yield(named=1 reached=1"))
        assertTrue(line, line.contains("namedWins=1"))
        assertFalse(e.isNamedReceiver8144(m, "MOONSHOT"))            // settled
        e.noteNotEvaluated8136(m, "QUALITY", "CORE", true, 95.0, t0 + 7)
        assertEquals(Terminal8136.CONTRIBUTOR_SHADOW to "RESCUE_SLOT_HELD_BY_MOONSHOT", e.terminalOf8136(m, "QUALITY"))
        val n = f.counts8144("MOONSHOT")
        assertEquals(1L, n.getValue("CLAIMED"))
        assertEquals(1L, n.getValue("SCHEDULED"))
        assertEquals(1L, n.getValue("EVALUATED"))
        assertEquals(0L, n.getValue("FDG_ALLOWED"))
        assertBalanced("CORE", "MOONSHOT", "QUALITY")
        assertFunnelReconciles("MOONSHOT", "QUALITY")
    }

    @Test fun aTreasuryYieldReachesItsReceiverAcrossTheLotteryAndAClockRoll() {
        val m = "mint-treasury8144"
        val floor = view(48.0, eligible = false, reason = "TREASURY_ROLE_FLOOR_8031")
        e.registerNative8136(m, 300L, t0, mapOf("TREASURY" to floor, "MOONSHOT" to view(60.0), "EXPRESS" to view(40.0)), t0)
        assertEquals("TREASURY", e.cyclePrimary8136(m, 300L, "TREASURY", false, null, t0))
        // Before anything refused, the hash pick may not take the slot from a stronger lane with native evidence.
        assertNull(e.boundedRescue8141(m, "EXPRESS", "TREASURY", "EXPRESS", t0 + 1))
        assertEquals("", e.rescueOf8136(m))
        e.notePrimaryRefusal8136(m, "TREASURY", "NATIVE:TREASURY_ROLE_FLOOR_8031", false, t0 + 2)
        assertEquals("MOONSHOT", e.noteYield8136(m, "TREASURY", "MOONSHOT", t0 + 2))
        // The 30 s bucket rolls: the unconcluded refusal + yield keep the record (the clock is not a new candidate).
        e.registerNative8136(m, 301L, t0 + 30_000L, mapOf("TREASURY" to floor, "MOONSHOT" to view(61.0), "EXPRESS" to view(40.0)), t0 + 30_000L)
        assertEquals("TREASURY", e.primaryOf8136(m))
        assertTrue(e.isNamedReceiver8144(m, "MOONSHOT"))
        assertNull(e.boundedRescue8141(m, "EXPRESS", "TREASURY", "EXPRESS", t0 + 30_001L))    // named receiver: lottery barred
        assertEquals(ClaimOutcome8144.GRANTED, e.claimRescueOutcome8144(m, "MOONSHOT", "TREASURY", native, "TREASURY_ROLE_FLOOR_8031",
            false, listOf(c("MOONSHOT", 61.0), c("EXPRESS", 40.0)), null, t0 + 30_002L))
        assertTrue(e.executableRescue8144(m, "MOONSHOT"))            // the coordinator recognises the same rescue
        e.noteScheduled8144(m, "MOONSHOT", t0 + 30_003L)
        // The receiver's FDG verdict was computed under the previous bucket: it lands on its lineage, not as stale.
        e.onFdgVerdict8136(m, "MOONSHOT", 300L, true, null, false, true, 61.0, t0 + 31_000L)
        assertEquals(Terminal8136.RESCUE_EVALUATED to "FDG_ALLOW", e.terminalOf8136(m, "MOONSHOT"))
        e.noteExecutionStage8144("MOONSHOT", "TICKET", "7:LIVE:$m:BUY:MOONSHOT:301:9", t0 + 32_000L)
        e.noteExecutionStage8144("MOONSHOT", "EXEC", "7:LIVE:$m:BUY:MOONSHOT:301:9", t0 + 40_000L)
        e.noteExecutionStage8144("SHITCOIN", "EXEC", "7:LIVE:$m:BUY:SHITCOIN:301:10", t0 + 40_000L)   // no rescue: not counted
        e.noteExecutionStage8144("MOONSHOT", "SELL_CONFIRMED", "7:LIVE:$m:BUY:MOONSHOT:301:9", t0 + 40_000L)
        assertEquals("c1/a0>s1>e1>f1>x1>k1", f.compact8144("MOONSHOT"))
        assertEquals(0L, f.counts8144("SHITCOIN").values.sum())
        assertEquals(0L, f.unboundCount8144())
        val line = e.statusLine8136()
        assertTrue(line, line.contains("yield(named=1 reached=1"))
        assertTrue(line, line.contains("lotteryBarred=2"))
        assertTrue(line, line.contains("lineageCarried=1"))
        assertTrue(line, line.contains("stale=0"))
        assertEquals(0L to 2L, e.lotteryCounts8141())
        assertBalanced("TREASURY", "MOONSHOT", "EXPRESS")
        assertFunnelReconciles("MOONSHOT", "EXPRESS", "SHITCOIN")
    }

    @Test fun aGatedReceiverEndsWithThePreciseGateNotSilence() {
        val m = "mint-sniper8144"
        e.registerNative8136(m, 250L, t0, mapOf("CORE" to view(70.0, eligible = false, reason = "CORE_YIELD_CLEAR_SPECIALIST_OWNER"),
            "PROJECT_SNIPER" to view(80.0)), t0)
        e.cyclePrimary8136(m, 250L, "CORE", false, null, t0)
        e.notePrimaryRefusal8136(m, "CORE", "NATIVE:CORE_YIELD_CLEAR_SPECIALIST_OWNER", false, t0 + 1)
        e.noteYield8136(m, "CORE", "PROJECT_SNIPER", t0 + 1)
        e.noteGateReason8144(m, "PROJECT_SNIPER", "SNIPER_NOT_DESIGNATED_POOL_6599:NO_DESK_HYPOTHESIS")
        e.noteNotEvaluated8136(m, "PROJECT_SNIPER", "CORE", true, 80.0, t0 + 2)
        val t = e.terminalOf8136(m, "PROJECT_SNIPER")
        assertEquals(Terminal8136.INELIGIBLE, t?.first)
        assertTrue(t!!.second, t.second.startsWith("RECEIVER_GATED_SNIPER_NOT_DESIGNATED_POOL_6599"))
        assertTrue(e.statusLine8136(), e.statusLine8136().contains("RECEIVER_GATED_SNIPER_NOT_DESIGNATED_POOL_6599=1"))
        // Any other gated lane is a shadow that names its gate.
        val m2 = "mint-quarantine8144"
        e.noteGateReason8144(m2, "EXPRESS", "LANE_QUARANTINE_6002")
        e.noteNotEvaluated8136(m2, "EXPRESS", "SHITCOIN", true, 60.0, t0)
        assertEquals(Terminal8136.CONTRIBUTOR_SHADOW to "GATED_LANE_QUARANTINE_6002", e.terminalOf8136(m2, "EXPRESS"))
        assertBalanced("PROJECT_SNIPER", "EXPRESS")
    }

    // ── 3. a primary refusal has its rescue evaluated ──

    @Test fun aRefusedPrimaryHasItsOneRescueEvaluatedAndTheCoordinatorAgrees() {
        val m = "mint-refused8144"
        e.registerNative8136(m, 400L, t0, mapOf("CASHGEN" to view(55.0), "BLUECHIP" to view(72.0), "QUALITY" to view(66.0)), t0)
        e.cyclePrimary8136(m, 400L, "CASHGEN", false, null, t0)
        assertFalse(e.executableRescue8144(m, "BLUECHIP"))
        e.onFdgVerdict8136(m, "CASHGEN", 400L, false, "PLAYBOOK_NO_TRIGGER_7907_CASHGEN", false, true, 55.0, t0 + 1)
        val cs = listOf(c("BLUECHIP", 72.0), c("QUALITY", 66.0))
        assertTrue(e.claimRescue8136(m, "BLUECHIP", "CASHGEN", fdg, "PLAYBOOK_NO_TRIGGER_7907_CASHGEN", false, cs, null, t0 + 2))
        assertTrue(e.executableRescue8144(m, "BLUECHIP"))
        assertFalse(e.executableRescue8144(m, "QUALITY"))
        assertFalse(e.executableRescue8144(m, "CASHGEN"))
        e.noteScheduled8144(m, "BLUECHIP", t0 + 3)
        e.noteScheduled8144(m, "QUALITY", t0 + 3)                   // not the rescue: nothing to schedule
        e.onFdgVerdict8136(m, "BLUECHIP", 400L, true, null, false, true, 72.0, t0 + 4)
        assertEquals(Terminal8136.RESCUE_EVALUATED to "FDG_ALLOW", e.terminalOf8136(m, "BLUECHIP"))
        assertEquals("c1/a0>s1>e1>f1>x0>k0", f.compact8144("BLUECHIP"))
        assertEquals(0L, f.counts8144("QUALITY").values.sum())
        // A judgement refusal is never rescued and never makes an executable rescue.
        val m2 = "mint-judgement8144"
        e.registerNative8136(m2, 410L, t0, mapOf("CASHGEN" to view(55.0), "BLUECHIP" to view(72.0)), t0)
        e.cyclePrimary8136(m2, 410L, "CASHGEN", false, null, t0)
        e.onFdgVerdict8136(m2, "CASHGEN", 410L, false, "HARD_BLOCK_RUGCHECK_FAILED", true, true, 55.0, t0 + 1)
        assertEquals(ClaimOutcome8144.DENIED_JUDGEMENT, e.claimRescueOutcome8144(m2, "BLUECHIP", "CASHGEN", fdg,
            "HARD_BLOCK_RUGCHECK_FAILED", true, listOf(c("BLUECHIP", 72.0)), null, t0 + 2))
        assertFalse(e.executableRescue8144(m2, "BLUECHIP"))
        assertBalanced("CASHGEN", "BLUECHIP", "QUALITY")
        assertFunnelReconciles("BLUECHIP", "QUALITY")
        val coord = src("engine/LaneExecutionCoordinator.kt")
        assertTrue(coord.contains("if (sealedFdgOwner6679 == null && electedRescueOwner8144(mint, laneUpper)) {"))
        assertTrue(coord.contains("SpecialistElection8136.executableRescue8144(mint, laneUpper) && laneCanOwnExecution6910(laneUpper)"))
        assertTrue(coord.contains("if (prior8144 != null && !prior8144.sealed && prior8144.primaryLane != laneUpper) {"))
    }

    // ── 4. a proven secondary ──

    @Test fun aProvenSecondaryTakesTheSlotBeforeTheLotteryAndIsEvaluated() {
        val l = ElectionRescueContract8144
        assertTrue(l.lotteryMayFill8144("EXPRESS", null, null))           // no evidence anywhere: the lottery keeps its job
        assertTrue(l.lotteryMayFill8144("EXPRESS", null, "EXPRESS"))
        assertFalse(l.lotteryMayFill8144("EXPRESS", null, "MOONSHOT"))
        assertFalse(l.lotteryMayFill8144("EXPRESS", "MOONSHOT", "EXPRESS"))
        assertTrue(l.lotteryMayFill8144("MOONSHOT", "MOONSHOT", "EXPRESS"))
        val pool = listOf(c("QUALITY", 95.0), c("MOONSHOT", 70.0))
        assertEquals("MOONSHOT", l.poolWinner8144(pool, "CORE", "MOONSHOT"))
        assertEquals("QUALITY", l.poolWinner8144(pool, "CORE", null))
        assertEquals("QUALITY", l.poolWinner8144(pool, "CORE", "BLUECHIP"))   // not in the pool: no proposal to rescue with
        assertEquals("QUALITY", l.poolWinner8144(pool + c("CYCLIC", 40.0), "CORE", "CYCLIC"))
        val m = "mint-proven8144"
        e.registerNative8136(m, 500L, t0, mapOf("SHITCOIN" to view(70.0), "MOONSHOT" to view(72.0), "EXPRESS" to view(60.0)), t0)
        e.cyclePrimary8136(m, 500L, "SHITCOIN", false, null, t0)
        assertNull(e.boundedRescue8141(m, "EXPRESS", "SHITCOIN", "EXPRESS", t0 + 1))
        val cs = listOf(c("SHITCOIN", 70.0), c("MOONSHOT", 72.0), c("EXPRESS", 60.0))
        assertTrue(e.claimRescue8136(m, "MOONSHOT", "SHITCOIN", cell, "", false, cs, null, t0 + 2))
        assertEquals("MOONSHOT", e.boundedRescue8141(m, "EXPRESS", "SHITCOIN", "EXPRESS", t0 + 3))
        assertFalse(e.executableRescue8144(m, "MOONSHOT"))           // the primary has not refused: normal arbitration
        e.onFdgVerdict8136(m, "MOONSHOT", 500L, true, null, false, true, 72.0, t0 + 4)
        assertEquals(Terminal8136.RESCUE_EVALUATED to "FDG_ALLOW", e.terminalOf8136(m, "MOONSHOT"))
        assertEquals("c1/a0>s0>e1>f1>x0>k0", f.compact8144("MOONSHOT"))
        assertEquals(0L to 2L, e.lotteryCounts8141())
        assertTrue(e.statusLine8136().contains("lotteryBarred=1"))
        assertBalanced("SHITCOIN", "MOONSHOT", "EXPRESS")
    }

    // ── 5. a race elects one owner ──

    @Test fun concurrentClaimsLotteryPicksAndANamedReceiverElectExactlyOneOwner() {
        val m = "mint-race8144"
        val lanes = listOf("MOONSHOT", "SHITCOIN", "EXPRESS", "QUALITY", "BLUECHIP", "DIP_HUNTER")
        val cs = lanes.mapIndexed { i, l -> c(l, 50.0 + i * 5.0) }
        e.registerNative8136(m, 600L, t0, cs.associate { it.lane to view(it.normalised) } + ("CASHGEN" to view(55.0)), t0)
        e.cyclePrimary8136(m, 600L, "CASHGEN", false, null, t0)
        e.onFdgVerdict8136(m, "CASHGEN", 600L, false, "PLAYBOOK_NO_TRIGGER_7907_CASHGEN", false, true, 55.0, t0 + 1)
        e.noteYield8136(m, "CASHGEN", "QUALITY", t0 + 2)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val claimed = ConcurrentHashMap.newKeySet<String>()
        val lottery = ConcurrentHashMap.newKeySet<String>()
        repeat(4) {
            for (lane in lanes) {
                pool.execute {
                    start.await()
                    if (e.claimRescue8136(m, lane, "CASHGEN", fdg, "PLAYBOOK_NO_TRIGGER_7907_CASHGEN", false, cs, null, t0 + 100)) claimed.add(lane)
                }
                pool.execute {
                    start.await()
                    e.boundedRescue8141(m, lane, "CASHGEN", lane, t0 + 100)?.let { lottery.add(it) }
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals(setOf("QUALITY"), claimed.toSet())
        assertTrue(lottery.toString(), lottery.all { it == "QUALITY" })
        assertEquals("QUALITY", e.rescueOf8136(m))
        assertEquals(listOf("QUALITY"), lanes.filter { e.executableRescue8144(m, it) })
        assertEquals(1L, f.counts8144("QUALITY").getValue("CLAIMED"))
        assertBalanced(*(lanes + "CASHGEN").toTypedArray())
        assertFunnelReconciles(*lanes.toTypedArray())
    }

    // ── 6. supersession during a rescue ──

    @Test fun aClockRollDuringTheRescueKeepsItBoundToItsLineageAndAConcludedOneCloses() {
        val l = ElectionRescueContract8144
        assertTrue(l.carriesLineage8144(true, false, false, 0L))
        assertTrue(l.carriesLineage8144(false, true, false, l.LINEAGE_MAX_MS_8144))
        assertFalse(l.carriesLineage8144(false, false, true, l.LINEAGE_MAX_MS_8144 + 1L))
        assertFalse(l.carriesLineage8144(false, false, false, 0L))
        assertFalse(l.carriesLineage8144(true, true, true, -1L))
        val m = "mint-roll8144"
        e.registerNative8136(m, 700L, t0, mapOf("CASHGEN" to view(55.0), "MOONSHOT" to view(80.0)), t0)
        e.cyclePrimary8136(m, 700L, "CASHGEN", false, null, t0)
        e.onFdgVerdict8136(m, "CASHGEN", 700L, false, "PLAYBOOK_NO_TRIGGER_7907_CASHGEN", false, true, 55.0, t0 + 1)
        assertTrue(e.claimRescue8136(m, "MOONSHOT", "CASHGEN", fdg, "PLAYBOOK_NO_TRIGGER_7907_CASHGEN", false, listOf(c("MOONSHOT", 80.0)), null, t0 + 2))
        e.registerNative8136(m, 701L, t0 + 30_000L, mapOf("CASHGEN" to view(55.0), "MOONSHOT" to view(80.0), "SHITCOIN" to view(90.0)), t0 + 30_000L)
        assertEquals("MOONSHOT", e.rescueOf8136(m))
        assertEquals("CASHGEN", e.primaryOf8136(m))
        assertNull(e.terminalOf8136(m, "MOONSHOT")?.first)
        // A stronger lane appearing in the new bucket does not take the version from the bound rescue.
        assertEquals(ClaimOutcome8144.DENIED_HELD_BY_OTHER, e.claimRescueOutcome8144(m, "SHITCOIN", "CASHGEN", fdg, "x", false,
            listOf(c("SHITCOIN", 90.0)), null, t0 + 30_001L))
        e.onFdgVerdict8136(m, "MOONSHOT", 700L, true, null, false, true, 80.0, t0 + 31_000L)
        assertEquals(Terminal8136.RESCUE_EVALUATED to "FDG_ALLOW", e.terminalOf8136(m, "MOONSHOT"))
        assertTrue(e.statusLine8136().contains("stale=0"))
        assertTrue(e.statusLine8136().contains("lineageCarried=1"))
        // Concluded: the next bucket closes the record, and an event of the old lineage is stale on the new one.
        e.registerNative8136(m, 702L, t0 + 60_000L, mapOf("SHITCOIN" to view(90.0)), t0 + 60_000L)
        assertEquals("", e.rescueOf8136(m))
        e.onFdgVerdict8136(m, "MOONSHOT", 701L, true, null, false, true, 80.0, t0 + 61_000L)
        assertTrue(e.statusLine8136().contains("stale=1"))
        // A lineage older than the hand-off window closes even mid-rescue: the rescue ends by name.
        val m2 = "mint-roll-old8144"
        e.registerNative8136(m2, 710L, t0, mapOf("CASHGEN" to view(55.0), "MOONSHOT" to view(80.0)), t0)
        e.cyclePrimary8136(m2, 710L, "CASHGEN", false, null, t0)
        e.onFdgVerdict8136(m2, "CASHGEN", 710L, false, "PLAYBOOK_NO_TRIGGER_7907_CASHGEN", false, true, 55.0, t0 + 1)
        assertTrue(e.claimRescue8136(m2, "MOONSHOT", "CASHGEN", fdg, "x", false, listOf(c("MOONSHOT", 80.0)), null, t0 + 2))
        e.registerNative8136(m2, 711L, t0 + l.LINEAGE_MAX_MS_8144 + 1L, mapOf("CASHGEN" to view(55.0), "MOONSHOT" to view(80.0)), t0 + l.LINEAGE_MAX_MS_8144 + 1L)
        assertEquals(1L, e.laneCounts8136("MOONSHOT").getValue("INELIGIBLE"))     // RESCUE_NO_FDG_VERDICT
        assertEquals("", e.rescueOf8136(m2))
        // The candidate's silence clock survives a bucket roll and a re-election (the 6 s rescue no longer stays shut).
        val m3 = "mint-silent8144"
        e.registerNative8136(m3, 720L, t0, mapOf("SHITCOIN" to view(60.0), "EXPRESS" to view(70.0)), t0)
        e.cyclePrimary8136(m3, 720L, "SHITCOIN", false, null, t0)
        e.registerNative8136(m3, 721L, t0 + 5_000L, mapOf("SHITCOIN" to view(60.0), "EXPRESS" to view(70.0)), t0 + 5_000L)
        e.cyclePrimary8136(m3, 721L, "SHITCOIN", false, null, t0 + 5_001L)
        assertTrue(e.primarySilent8136(m3, "SHITCOIN", t0 + SpecialistElection8136.PRIMARY_SILENT_MS_8136 + 1L))
        val m4 = "mint-flip8144"
        e.registerNative8136(m4, 730L, t0, mapOf("SHITCOIN" to view(60.0), "EXPRESS" to view(70.0)), t0)
        e.cyclePrimary8136(m4, 730L, "SHITCOIN", false, null, t0)
        assertEquals("EXPRESS", e.cyclePrimary8136(m4, 730L, "EXPRESS", false, null, t0 + 5_000L))
        assertTrue(e.primarySilent8136(m4, "EXPRESS", t0 + SpecialistElection8136.PRIMARY_SILENT_MS_8136 + 1L))
        assertBalanced("CASHGEN", "MOONSHOT", "SHITCOIN", "EXPRESS")
        assertFunnelReconciles("MOONSHOT", "SHITCOIN")
    }

    // ── 7. the counters reconcile ──

    @Test fun theRescueFunnelCountsEachStageOnceAndOnlyAfterItsPredecessor() {
        assertTrue(f.bind8144("mF", "MOONSHOT", 800L, adopted = false, nowMs = t0))
        assertFalse(f.bind8144("mF", "MOONSHOT", 801L, adopted = false, nowMs = t0 + 1))     // same lineage: no double count
        assertFalse(f.mark8144("mF", "MOONSHOT", Stage8144.CONFIRMED, 800L, t0 + 2))        // no EXECUTED yet
        assertFalse(f.mark8144("mF", "MOONSHOT", Stage8144.EXECUTED, 800L, t0 + 2))         // no FDG allow yet
        assertEquals(2L, f.unboundCount8144())
        assertTrue(f.mark8144("mF", "MOONSHOT", Stage8144.SCHEDULED, 800L, t0 + 3))
        assertTrue(f.mark8144("mF", "MOONSHOT", Stage8144.EVALUATED, 800L, t0 + 4))
        assertFalse(f.mark8144("mF", "MOONSHOT", Stage8144.EVALUATED, 800L, t0 + 5))        // once
        assertTrue(f.mark8144("mF", "MOONSHOT", Stage8144.FDG_ALLOWED, 800L, t0 + 6))
        assertTrue(f.mark8144("mF", "MOONSHOT", Stage8144.EXECUTED, 801L, t0 + 7))
        assertTrue(f.mark8144("mF", "MOONSHOT", Stage8144.CONFIRMED, 801L, t0 + 8))
        assertFalse(f.mark8144("mF", "QUALITY", Stage8144.SCHEDULED, 800L, t0 + 9))          // never bound
        assertFalse(f.mark8144("mF", "QUALITY", Stage8144.CONFIRMED, 800L, t0 + 9, countUnbound = false))
        assertFalse(f.mark8144("mF", "MOONSHOT", Stage8144.EVALUATED, 805L, t0 + 10))       // another lineage's version
        assertEquals(4L, f.unboundCount8144())
        assertEquals("c1/a0>s1>e1>f1>x1>k1", f.compact8144("MOONSHOT"))
        assertFalse(f.mark8144("mF", "MOONSHOT", Stage8144.CLAIMED, 800L, t0 + 11))          // origins only through bind
        // A binding expires with the record TTL; a later claim is a new rescue.
        assertTrue(f.bind8144("mG", "EXPRESS", 900L, adopted = false, nowMs = t0))
        assertFalse(f.mark8144("mG", "EXPRESS", Stage8144.SCHEDULED, 900L, t0 + 120_001L))
        assertTrue(f.bind8144("mG", "EXPRESS", 900L, adopted = false, nowMs = t0 + 120_001L))
        assertEquals(2L, f.counts8144("EXPRESS").getValue("CLAIMED"))
        // FDG adoption without a claim is counted as ADOPTED, never as CLAIMED.
        val m = "mint-adopt8144"
        e.registerNative8136(m, 820L, t0, mapOf("SHITCOIN" to view(60.0), "DIP_HUNTER" to view(65.0)), t0)
        e.cyclePrimary8136(m, 820L, "SHITCOIN", false, null, t0)
        e.onFdgVerdict8136(m, "DIP_HUNTER", 820L, true, null, false, true, 65.0, t0 + 1)
        assertEquals("c0/a1>s0>e1>f1>x0>k0", f.compact8144("DIP_HUNTER"))
        assertTrue(e.statusLine8136().contains("adopted=1"))
        assertFunnelReconciles("MOONSHOT", "QUALITY", "EXPRESS", "DIP_HUNTER")
        val line = LaneParticipation8029.statusLine()
        assertTrue(line, line.contains("rescueFunnel8144(c/a>s>e>f>x>k)"))
        assertTrue(line, line.contains("DIP_HUNTER=c0/a1>s0>e1>f1>x0>k0"))
    }

    @Test fun onlyACanonicalBuyAttemptIsParsedAndTheWiringIsInPlace() {
        val p = ElectionRescueContract8144.parseBuyAttempt8144("7:LIVE:So1aNaMint:BUY:BLUECHIP:42:3")
        assertEquals(ElectionRescueContract8144.BuyAttempt8144("So1aNaMint", "BLUECHIP", 42L), p)
        assertNull(ElectionRescueContract8144.parseBuyAttempt8144("7:LIVE:So1aNaMint:SELL:BLUECHIP:42:3"))
        assertNull(ElectionRescueContract8144.parseBuyAttempt8144("LIVE:So1aNaMint:BUY:BLUECHIP:42:3"))
        assertNull(ElectionRescueContract8144.parseBuyAttempt8144("7:LIVE:So1aNaMint:BUY:BLUECHIP:x:3"))
        assertNull(ElectionRescueContract8144.parseBuyAttempt8144("So1aNaMint:42"))
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("SpecialistElection8136.electionQualified8144(ts.mint, l)"))
        assertTrue(bot.contains("HandoffTaskRuntime8153.onNativeCandidate8153("))
        assertTrue(src("engine/HandoffReceiverTasks8153.kt").contains("SpecialistElection8136.noteScheduled8144(mint, l, nowMs)"))
        assertTrue(bot.contains("SpecialistElection8136.noteGateReason8144(ts.mint, lane, \"LANE_QUARANTINE_6002\")"))
        assertTrue(bot.contains("SpecialistElection8136.noteGateReason8144(ts.mint, l, \"SNIPER_NOT_DESIGNATED_POOL_6599:\""))
        assertTrue(src("engine/ToolkitSignalSheet.kt").contains("SpecialistElection8136.noteExecutionStage8144(l, st, eventId)"))
        val o = src("engine/SpecialistOwnership7951.kt")
        assertTrue(o.contains("SpecialistElection8136.isNamedReceiver8144(mint, l)"))
        assertTrue(o.contains("return SpecialistElection8136.noteYield8136(mint, lane7951(primary), receiver)"))
    }
}
