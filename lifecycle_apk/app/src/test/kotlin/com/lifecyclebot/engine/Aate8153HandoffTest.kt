package com.lifecyclebot.engine

import com.lifecyclebot.engine.ElectionRescueContract8144.ClaimOutcome8144
import com.lifecyclebot.engine.HandoffReceiverTasks8153.Block8153
import com.lifecyclebot.engine.HandoffReceiverTasks8153.Outcome8153
import com.lifecyclebot.engine.SpecialistElection8136.Contender8136
import com.lifecyclebot.engine.SpecialistElection8136.NativeView8136
import com.lifecyclebot.engine.SpecialistElection8136.Terminal8136
import com.lifecyclebot.engine.truth.AdmissionHandoff8144
import com.lifecyclebot.engine.truth.AdmissionHandoff8144.LaneRead8144
import com.lifecyclebot.engine.truth.AdmissionHandoff8144.State8144
import com.lifecyclebot.engine.truth.LaunchLifecycle8136
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * V5.0.8153 — a qualified specialist hand-off is a task the cycle consumes: the sniper's phase hand-off opens a
 * version-bound task, the receiver takes the candidate's one rescue slot and reaches the FDG (never
 * EXPIRED_NOT_EVALUATED); the named receiver beats an unconsumed lottery claim; a stale candidate is never prolonged;
 * one executable rescue under concurrency; a fit refusal hands on while safety stays binding; a receiver that used to
 * end SHADOW / RECEIVER_SLOT_HELD is evaluated once the slot frees. Pure functions and explicit clocks.
 */
class Aate8153HandoffTest {
    private val e = SpecialistElection8136
    private val tk = HandoffReceiverTasks8153
    private val a = AdmissionHandoff8144
    private val native = SpecialistElection8136.TRIGGER_NATIVE
    private val t0 = 4_000_000_000L
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private val fit: (String) -> Boolean = { SpecialistOwnership7951.laneFitRefusal8132(it) }

    @Before fun setUp() { e.resetForTests8136() }
    @After fun tearDown() { e.resetForTests8136() }

    private fun view(n: Double, eligible: Boolean = true, reason: String = "OK") = NativeView8136(eligible, n, reason)
    private fun c(lane: String, n: Double) = Contender8136(lane, n)
    private fun block(reason: String): Block8153 = tk.classify8153(reason, SpecialistOwnership7951.laneFitRefusal8132(reason),
        SpecialistOwnership7951.refusalIsJudgement8136(reason, fdg = false))

    // ── 1. sniper phase hand-off -> receiver task scheduled -> FDG verdict recorded ──

    @Test fun aSniperPhaseHandoffBecomesAScheduledTaskThatEndsWithTheReceiversFdgVerdict() {
        val m = "mint-phase8153"
        val reason = "PHASE_HANDOFF_8136_EXPANDING_TO_MOONSHOT"
        val h0 = a.emit8144(null, m, "PROJECT_SNIPER", "MOONSHOT", reason, t0)
        val reads = listOf(LaneRead8144("PROJECT_SNIPER", false, 0, reason), LaneRead8144("MOONSHOT", true, 62, "MOONSHOT_NATIVE_ALLOW"),
            LaneRead8144("SHITCOIN", true, 70, "OK"), LaneRead8144("CORE", true, 80, "CORE_ENSEMBLE"))
        val h1 = a.onNativeTransfer8153(h0, reads, t0, fit)
        assertEquals(State8144.RECEIVER_QUALIFIED, h1.state)
        assertEquals("MOONSHOT", h1.receiver)
        // The cycle primary is SHITCOIN, not the sniper: before 8153 nothing scheduled MOONSHOT at all.
        val views = mapOf("PROJECT_SNIPER" to view(0.0, eligible = false, reason = reason), "MOONSHOT" to view(62.0), "SHITCOIN" to view(70.0))
        e.registerNative8136(m, 100L, t0, views, t0)
        assertEquals("SHITCOIN", e.cyclePrimary8136(m, 100L, "SHITCOIN", false, null, t0))
        assertEquals("MOONSHOT", e.nameHandoffReceiver8153(m, "PROJECT_SNIPER", "MOONSHOT", t0))
        val task = tk.openTask8153(m, "MOONSHOT", "PROJECT_SNIPER", h1.reason, 100L, t0, t0, h1.emittedAtMs)!!
        assertTrue(task.pending)
        assertEquals("MOONSHOT", task.lane)
        assertTrue(tk.isDue8153(m, "MOONSHOT", t0 + 1_000L, 100L))
        assertFalse(tk.isDue8153(m, "SHITCOIN", t0 + 1_000L, 100L))
        assertTrue(e.electionQualified8144(m, "MOONSHOT", t0 + 1_000L))        // desk-qualified by its own task
        // The lane gate consumes the task: MOONSHOT takes the one rescue slot.
        assertEquals(ClaimOutcome8144.GRANTED, e.claimHandoffReceiver8153(m, "MOONSHOT", "SHITCOIN", null, t0 + 1_000L))
        assertTrue(e.holdsRescue8136(m, "MOONSHOT"))
        assertEquals(ClaimOutcome8144.HELD, e.claimHandoffReceiver8153(m, "MOONSHOT", "SHITCOIN", null, t0 + 1_500L))
        // One executable owner: the primary is still deciding, so the receiver is not yet an executable owner; once the
        // primary sat silent past the 6 s window it is (the coordinator lets it replace only an unsealed pre-seal election).
        assertFalse(e.executableRescue8144(m, "MOONSHOT", t0 + 1_000L))
        assertTrue(e.executableRescue8144(m, "MOONSHOT", t0 + 7_000L))
        // Fresh valid reads keep the task alive past the hand-off's 120 s emission clock (the legacy expiry would have ended it).
        for (at in listOf(40_000L, 80_000L)) {
            e.registerNative8136(m, 100L, t0 + at, views, t0 + at)
            tk.refreshTask8153(m, "MOONSHOT", 100L, true, t0 + at, t0 + at)
        }
        assertEquals(State8144.EXPIRED_NOT_EVALUATED, a.expire8144(h1, t0 + 120_500L).state)
        assertTrue(tk.isDue8153(m, "MOONSHOT", t0 + 121_000L, 100L))
        e.noteScheduled8144(m, "MOONSHOT", t0 + 120_500L)
        assertEquals(1, tk.taskOf8153(m, t0 + 120_500L)!!.schedules)
        // The receiver's FDG verdict completes task, election and ledger.
        tk.onFdgVerdict8153(m, "MOONSHOT", false, "PLAYBOOK_NO_TRIGGER_7907_MOONSHOT", t0 + 121_000L)
        e.onFdgVerdict8136(m, "MOONSHOT", 100L, false, "PLAYBOOK_NO_TRIGGER_7907_MOONSHOT", false, true, 62.0, t0 + 121_000L)
        val done = tk.taskOf8153(m, t0 + 500_000L)!!
        assertEquals(Outcome8153.EVALUATED, done.outcome)
        assertEquals("FDG_REFUSED:PLAYBOOK_NO_TRIGGER_7907_MOONSHOT", done.verdict)
        assertEquals(Terminal8136.RESCUE_EVALUATED, e.terminalOf8136(m, "MOONSHOT")?.first)
        val arrived = a.onFdg8144(h1, "MOONSHOT", false, "PLAYBOOK_NO_TRIGGER_7907_MOONSHOT", t0 + 121_000L)
        assertEquals(State8144.ARRIVED_EVALUATED, arrived.state)
        assertSame(arrived, a.expire8144(arrived, t0 + 500_000L))               // settled: never EXPIRED_NOT_EVALUATED
        val n = tk.outcomeCounts8153()
        assertEquals(1L, n["OPENED"])
        assertEquals(1L, n["SCHEDULED"])
        assertEquals(1L, n["EVALUATED"])
        assertNull(n["REFUSED"])
        val f = ElectionRescueFunnel8144.counts8144("MOONSHOT")
        assertEquals(1L, f.getValue("CLAIMED"))
        assertEquals(1L, f.getValue("SCHEDULED"))
        assertEquals(1L, f.getValue("EVALUATED"))
        val line = e.statusLine8136()
        assertTrue(line, line.contains("handoff8153(named=1 claims=1"))
        assertTrue(line, line.contains("evaluated=1"))
    }

    // ── 2. the named receiver beats an unconsumed lottery claim ──

    @Test fun theNamedReceiverBeatsAnUnconsumedLotteryClaimButNotAnEvidenceRescue() {
        val views = mapOf("SHITCOIN" to view(60.0), "MOONSHOT" to view(55.0), "EXPRESS" to view(70.0))
        val m = "mint-lottery8153"
        e.registerNative8136(m, 200L, t0, views, t0)
        e.cyclePrimary8136(m, 200L, "SHITCOIN", false, null, t0)
        assertEquals("EXPRESS", e.boundedRescue8141(m, "EXPRESS", "SHITCOIN", "EXPRESS", t0 + 1))   // the hash pick fills the empty slot
        assertEquals("EXPRESS", e.rescueOf8136(m))
        assertEquals(ClaimOutcome8144.GRANTED, e.claimHandoffReceiver8153(m, "MOONSHOT", "SHITCOIN", null, t0 + 2))
        assertEquals("MOONSHOT", e.rescueOf8136(m))
        assertEquals(Terminal8136.CONTRIBUTOR_SHADOW to "RESCUE_PREEMPTED_BY_HANDOFF_RECEIVER_MOONSHOT", e.terminalOf8136(m, "EXPRESS"))
        assertEquals("MOONSHOT", e.boundedRescue8141(m, "EXPRESS", "SHITCOIN", "EXPRESS", t0 + 3))   // the lottery no longer evaluates
        assertTrue(e.statusLine8136().contains("preemptedLottery=1"))
        // An evidence rescue (claimed after the primary refused) is not taken: one executable rescue, no second buyer.
        val m2 = "mint-evidence8153"
        e.registerNative8136(m2, 210L, t0, views, t0)
        e.cyclePrimary8136(m2, 210L, "SHITCOIN", false, null, t0)
        e.notePrimaryRefusal8136(m2, "SHITCOIN", "NATIVE:MCAP_TOO_HIGH", false, t0 + 1)
        assertEquals(ClaimOutcome8144.GRANTED, e.claimRescueOutcome8144(m2, "EXPRESS", "SHITCOIN", native, "MCAP_TOO_HIGH", false,
            listOf(c("EXPRESS", 70.0), c("MOONSHOT", 55.0)), null, t0 + 2))
        assertEquals(ClaimOutcome8144.DENIED_HELD_BY_OTHER, e.claimHandoffReceiver8153(m2, "MOONSHOT", "SHITCOIN", null, t0 + 3))
        assertEquals("EXPRESS", e.rescueOf8136(m2))
        // Named first, the lottery may not fill the slot at all.
        val m3 = "mint-named8153"
        e.registerNative8136(m3, 220L, t0, views, t0)
        e.cyclePrimary8136(m3, 220L, "SHITCOIN", false, null, t0)
        assertEquals("MOONSHOT", e.nameHandoffReceiver8153(m3, "PROJECT_SNIPER", "MOONSHOT", t0 + 1))
        assertNull(e.boundedRescue8141(m3, "EXPRESS", "SHITCOIN", "EXPRESS", t0 + 2))
        assertEquals("", e.rescueOf8136(m3))
        // A second name does not displace the first open one (one named receiver per candidate).
        assertEquals("MOONSHOT", e.nameHandoffReceiver8153(m3, "TREASURY", "EXPRESS", t0 + 3))
        assertEquals(ClaimOutcome8144.DENIED_OUTRANKED, e.claimHandoffReceiver8153(m3, "EXPRESS", "SHITCOIN", null, t0 + 4))
    }

    // ── 3. a stale candidate is never prolonged ──

    @Test fun aStaleCandidateIsNeverProlonged() {
        val m = "mint-stale8153"
        val t = tk.open8153(null, m, "MOONSHOT", "PROJECT_SNIPER", "TOO_OLD_8144", 10L, t0, t0)!!
        assertEquals(t0 + tk.TASK_TTL_MS_8153, t.validUntilMs)
        assertSame(t, tk.refresh8153(t, "MOONSHOT", 10L, false, t0 + 10_000L, t0 + 10_000L))   // receiver no longer valid
        assertSame(t, tk.refresh8153(t, "MOONSHOT", 10L, true, t0, t0 + 10_000L))              // no fresh evidence
        assertSame(t, tk.refresh8153(t, "EXPRESS", 10L, true, t0 + 10_000L, t0 + 10_000L))     // another lane's read
        val lapsedAt = t0 + tk.TASK_TTL_MS_8153 + 1L
        assertSame(t, tk.refresh8153(t, "MOONSHOT", 10L, true, lapsedAt, lapsedAt))             // lapsed: never revived
        assertFalse(tk.due8153(t, "MOONSHOT", lapsedAt))
        val stale = tk.expire8153(t, lapsedAt)
        assertEquals(Outcome8153.REFUSED, stale.outcome)
        assertEquals("STALE_NOT_SCHEDULED", stale.verdict)
        // Valid refreshes every 30 s never carry it past the hard cap from creation.
        var live = t
        var at = t0 + 30_000L
        while (at <= t0 + 300_000L) { live = tk.refresh8153(live, "MOONSHOT", 10L, true, at, at); at += 30_000L }
        assertEquals(t0 + tk.TASK_MAX_AGE_MS_8153, live.validUntilMs)
        assertTrue(tk.due8153(live, "MOONSHOT", t0 + tk.TASK_MAX_AGE_MS_8153))
        assertFalse(tk.due8153(live, "MOONSHOT", t0 + tk.TASK_MAX_AGE_MS_8153 + 1L))
        // A candidate version left two buckets behind is not consumed under the old authorisation.
        assertTrue(tk.due8153(t, "MOONSHOT", t0 + 1_000L, 11L))
        assertFalse(tk.due8153(t, "MOONSHOT", t0 + 1_000L, 12L))
        // A settled task is not re-opened by the same hand-off inside the hold, only by one emitted after it settled.
        val settled = tk.onFdg8153(t, "MOONSHOT", true, null, t0 + 2_000L)
        assertSame(settled, tk.open8153(settled, m, "MOONSHOT", "PROJECT_SNIPER", "R", 10L, t0 + 3_000L, t0 + 3_000L))
        assertNotSame(settled, tk.open8153(settled, m, "MOONSHOT", "PROJECT_SNIPER", "R", 10L, t0 + 3_000L, t0 + 3_000L, newHandoffAtMs = t0 + 2_500L))
        // Never the sender, never CORE.
        assertNull(tk.open8153(null, m, "PROJECT_SNIPER", "PROJECT_SNIPER", "R", 10L, t0, t0))
        assertNull(tk.open8153(null, m, "CORE", "PROJECT_SNIPER", "R", 10L, t0, t0))
    }

    @Test fun everyTaskEndsExactlyOneNamedWay() {
        val t = tk.open8153(null, "mint-ends8153", "MOONSHOT", "PROJECT_SNIPER", "R", 10L, t0, t0)!!
        val end = t0 + tk.TASK_TTL_MS_8153 + 1L
        val waitingData = tk.block8153(t, "MOONSHOT", Block8153.DATA, "DEFERRED_NO_TOKEN_STATE", t0 + 1L)
        assertTrue(waitingData.pending)
        assertEquals(Outcome8153.DEFERRED_DATA to "DEFERRED_NO_TOKEN_STATE", tk.expire8153(waitingData, end).let { it.outcome to it.verdict })
        val scheduled = tk.scheduled8153(t, "MOONSHOT", t0 + 1L)
        assertEquals("SCHEDULED_NO_FDG_VERDICT_x1", tk.expire8153(scheduled, end).verdict)
        val held = tk.block8153(t, "MOONSHOT", Block8153.WAIT, "SLOT_HELD_BY_EXPRESS", t0 + 1L)
        assertEquals(Outcome8153.REFUSED to "NOT_SCHEDULED:SLOT_HELD_BY_EXPRESS", tk.expire8153(held, end).let { it.outcome to it.verdict })
        val judged = tk.block8153(t, "MOONSHOT", Block8153.JUDGEMENT, "MEME_PWIN_GATE_6604", t0 + 1L)
        assertEquals(Outcome8153.REFUSED to "RECEIVER_REFUSED:MEME_PWIN_GATE_6604", judged.outcome to judged.verdict)
        val allowed = tk.onFdg8153(t, "MOONSHOT", true, null, t0 + 1L)
        assertEquals(Outcome8153.EVALUATED to "FDG_ALLOW", allowed.outcome to allowed.verdict)
        // A settled task takes no second outcome; another lane's verdict, or one older than the task, is not its evaluation.
        assertSame(allowed, tk.onFdg8153(allowed, "MOONSHOT", false, "X", t0 + 2L))
        assertSame(allowed, tk.block8153(allowed, "MOONSHOT", Block8153.BINDING, "RUG", t0 + 2L))
        assertSame(t, tk.onFdg8153(t, "SHITCOIN", true, null, t0 + 1L))
        assertSame(t, tk.onFdg8153(t, "MOONSHOT", true, null, t0 - 10_000L))
    }

    // ── 4. one executable owner under concurrency ──

    @Test fun oneExecutableRescueUnderConcurrentClaims() {
        val m = "mint-race8153"
        e.registerNative8136(m, 300L, t0, mapOf("SHITCOIN" to view(60.0), "MOONSHOT" to view(55.0), "EXPRESS" to view(90.0),
            "QUALITY" to view(80.0)), t0)
        e.cyclePrimary8136(m, 300L, "SHITCOIN", false, null, t0)
        e.notePrimaryRefusal8136(m, "SHITCOIN", "NATIVE:MCAP_TOO_HIGH", false, t0 + 1)
        assertEquals("MOONSHOT", e.nameHandoffReceiver8153(m, "PROJECT_SNIPER", "MOONSHOT", t0 + 1))
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val executable = ConcurrentHashMap.newKeySet<String>()
        val lottery = ConcurrentHashMap.newKeySet<String>()
        val cs = listOf(c("EXPRESS", 90.0), c("QUALITY", 80.0), c("MOONSHOT", 55.0))
        val roles = listOf("MOONSHOT", "EXPRESS", "QUALITY", "LOTTERY")
        repeat(12) {
            for (role in roles) {
                pool.execute {
                    start.await()
                    if (role == "MOONSHOT") {
                        if (e.claimHandoffReceiver8153(m, "MOONSHOT", "SHITCOIN", null, t0 + 2).executable) executable.add(role)
                    } else if (role == "LOTTERY") {
                        e.boundedRescue8141(m, "EXPRESS", "SHITCOIN", "EXPRESS", t0 + 2)?.let { lottery.add(it) }
                    } else if (e.claimRescueOutcome8144(m, role, "SHITCOIN", native, "MCAP_TOO_HIGH", false, cs, null, t0 + 2).executable) {
                        executable.add(role)
                    }
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(setOf("MOONSHOT"), executable.toSet())
        assertEquals("MOONSHOT", e.rescueOf8136(m))
        assertTrue(lottery.all { it == "MOONSHOT" })
        assertEquals(listOf("MOONSHOT"), listOf("SHITCOIN", "MOONSHOT", "EXPRESS", "QUALITY").filter { e.executableRescue8144(m, it, t0 + 3) })
        assertEquals(1L, ElectionRescueFunnel8144.counts8144("MOONSHOT").getValue("CLAIMED"))
        assertEquals(0L, ElectionRescueFunnel8144.counts8144("EXPRESS").getValue("CLAIMED"))
        // The sniper itself as primary: its own hand-off makes the receiver executable without a separate refusal record.
        val s = "mint-sniperprimary8153"
        e.registerNative8136(s, 310L, t0, mapOf("PROJECT_SNIPER" to view(0.0, eligible = false, reason = "PHASE_HANDOFF_8136_IGNITION_TO_MOONSHOT_FLOW_UNKNOWN"),
            "MOONSHOT" to view(60.0)), t0)
        e.cyclePrimary8136(s, 310L, "PROJECT_SNIPER", false, null, t0)
        e.nameHandoffReceiver8153(s, "PROJECT_SNIPER", "MOONSHOT", t0)
        assertEquals(ClaimOutcome8144.GRANTED, e.claimHandoffReceiver8153(s, "MOONSHOT", "PROJECT_SNIPER", null, t0 + 1))
        assertTrue(e.executableRescue8144(s, "MOONSHOT", t0 + 2))
    }

    // ── 5. fit refusals transfer, safety refusals stay binding ──

    @Test fun aFitRefusalTransfersToACompatibleReceiverAndASafetyRefusalStaysBinding() {
        assertEquals(Block8153.FIT, block("NATIVE_REFUSED:MCAP_TOO_LOW"))
        assertEquals(Block8153.FIT, block("PHASE_HANDOFF_8136_EXPANDING_TO_MOONSHOT"))
        assertEquals(Block8153.FIT, block("TREASURY_ROLE_FLOOR_8031"))
        assertEquals(Block8153.FIT, block("TOO_OLD_8144: 900s > 600s"))
        assertEquals(Block8153.FIT, block("LANE_QUARANTINE_6002"))                 // this lane cannot trade it; another may
        assertEquals(Block8153.BINDING, block("TOKEN_SAFETY_BLOCKED"))
        assertEquals(Block8153.BINDING, block("MCAP_TOO_LOW_RUG_RISK"))           // a fit word next to a safety word is safety
        assertEquals(Block8153.BINDING, block("FREEZE_AUTHORITY_PRESENT"))
        assertEquals(Block8153.DATA, block("DEFERRED_NO_TOKEN_STATE"))
        assertEquals(Block8153.DATA, block("DEFERRED_RESCUE_ARBITRATION_ERROR"))
        assertEquals(Block8153.JUDGEMENT, block("MEME_PWIN_GATE_6604"))
        assertEquals(Block8153.JUDGEMENT, block("LOW_SCORE_41"))
        assertEquals(Block8153.WAIT, block("SLOT_HELD_BY_EXPRESS"))
        // The task hands on: never back to the sender, CORE, or a lane that already refused.
        val cands = mapOf("MOONSHOT" to 90.0, "PROJECT_SNIPER" to 99.0, "CORE" to 95.0, "SHITCOIN" to 60.0, "EXPRESS" to 70.0)
        val t = tk.open8153(null, "mint-fit8153", "MOONSHOT", "PROJECT_SNIPER", "R", 10L, t0, t0)!!
        val t1 = tk.transfer8153(t, "MOONSHOT", "MCAP_TOO_HIGH", cands, t0 + 1_000L)
        assertEquals("EXPRESS", t1.lane)
        assertTrue(t1.pending)
        assertEquals(setOf("MOONSHOT"), t1.tried)
        assertTrue(t1.verdict, t1.verdict.startsWith("TRANSFERRED_FROM_MOONSHOT"))
        val t2 = tk.transfer8153(t1, "EXPRESS", "TOO_OLD", cands, t0 + 2_000L)
        assertEquals("SHITCOIN", t2.lane)
        val t3 = tk.transfer8153(t2, "SHITCOIN", "MCAP_TOO_HIGH", cands, t0 + 3_000L)
        assertEquals(Outcome8153.REFUSED to "FIT_NO_COMPATIBLE_RECEIVER:MCAP_TOO_HIGH", t3.outcome to t3.verdict)
        assertSame(t1, tk.transfer8153(t1, "MOONSHOT", "X", cands, t0 + 2_000L))   // only the current receiver hands on
        // A safety refusal is binding: no transfer.
        val safe = tk.block8153(t, "MOONSHOT", Block8153.BINDING, "TOKEN_SAFETY_BLOCKED", t0 + 1_000L)
        assertEquals(Outcome8153.REFUSED to "BINDING:TOKEN_SAFETY_BLOCKED", safe.outcome to safe.verdict)
        assertEquals("MOONSHOT", safe.lane)
        // The ledger follows the same rule on a native read.
        val m = "mint-fitledger8153"
        val h0 = a.emit8144(null, m, "PROJECT_SNIPER", "MOONSHOT", "PHASE_HANDOFF_8136_EXPANDING_TO_MOONSHOT", t0)
        val h1 = a.onNative8144(h0, listOf(LaneRead8144("MOONSHOT", true, 60, "OK"), LaneRead8144("EXPRESS", true, 55, "RIDE")), t0)
        assertEquals("MOONSHOT", h1.receiver)
        val moved = a.onNativeTransfer8153(h1, listOf(LaneRead8144("MOONSHOT", false, 20, "MCAP_TOO_HIGH"), LaneRead8144("EXPRESS", true, 55, "RIDE"),
            LaneRead8144("SHITCOIN", true, 40, "OK"), LaneRead8144("PROJECT_SNIPER", false, 0, "X")), t0 + 1_000L, fit)
        assertEquals(State8144.RECEIVER_QUALIFIED, moved.state)
        assertEquals("EXPRESS", moved.receiver)
        assertEquals(setOf("MOONSHOT"), moved.tried8153)
        val rug = a.onNativeTransfer8153(h1, listOf(LaneRead8144("MOONSHOT", false, 20, "RUG_DETECTED"), LaneRead8144("EXPRESS", true, 55, "RIDE")),
            t0 + 1_000L, fit)
        assertEquals(State8144.ARRIVED_EVALUATED to "NATIVE_REFUSED:RUG_DETECTED", rug.state to rug.verdict)
        // The election follows a transfer; a primary's judgement or a token-safety refusal grants nobody.
        val em = "mint-fitelection8153"
        e.registerNative8136(em, 400L, t0, mapOf("SHITCOIN" to view(60.0), "MOONSHOT" to view(55.0), "EXPRESS" to view(50.0)), t0)
        e.cyclePrimary8136(em, 400L, "SHITCOIN", false, null, t0)
        e.nameHandoffReceiver8153(em, "PROJECT_SNIPER", "MOONSHOT", t0)
        assertEquals(ClaimOutcome8144.GRANTED, e.claimHandoffReceiver8153(em, "MOONSHOT", "SHITCOIN", null, t0 + 1))
        e.retargetReceiver8153(em, "MOONSHOT", "EXPRESS", "MCAP_TOO_HIGH", t0 + 2)
        assertTrue(e.isNamedReceiver8144(em, "EXPRESS"))
        assertEquals("", e.rescueOf8136(em))
        assertEquals(Terminal8136.INELIGIBLE to "HANDOFF_FIT_REFUSED:MCAP_TOO_HIGH", e.terminalOf8136(em, "MOONSHOT"))
        assertEquals(ClaimOutcome8144.GRANTED, e.claimHandoffReceiver8153(em, "EXPRESS", "SHITCOIN", null, t0 + 3))
        val jm = "mint-judgement8153"
        e.registerNative8136(jm, 410L, t0, mapOf("SHITCOIN" to view(60.0), "MOONSHOT" to view(55.0)), t0)
        e.cyclePrimary8136(jm, 410L, "SHITCOIN", false, null, t0)
        e.notePrimaryRefusal8136(jm, "SHITCOIN", "NATIVE:RUG_RISK", true, t0 + 1)
        assertEquals(ClaimOutcome8144.DENIED_JUDGEMENT, e.claimHandoffReceiver8153(jm, "MOONSHOT", "SHITCOIN", null, t0 + 2))
        val sm = "mint-safety8153"
        e.registerNative8136(sm, 420L, t0, mapOf("SHITCOIN" to view(60.0), "MOONSHOT" to view(55.0)), t0)
        e.cyclePrimary8136(sm, 420L, "SHITCOIN", false, null, t0)
        assertEquals(ClaimOutcome8144.DENIED_INELIGIBLE, e.claimHandoffReceiver8153(sm, "MOONSHOT", "SHITCOIN", "TOKEN_SAFETY_BLOCKED", t0 + 1))
        assertEquals(Terminal8136.INELIGIBLE to "TOKEN_SAFETY_BLOCKED", e.terminalOf8136(sm, "MOONSHOT"))
        assertEquals("", e.rescueOf8136(sm))
    }

    // ── 6. a SHADOW_NOT_PRIMARY lane that is the receiver gets evaluated ──

    @Test fun aReceiverKeptWaitingIsNotStrandedAndIsEvaluatedWhenTheSlotFrees() {
        val m = "mint-shadow8153"
        e.registerNative8136(m, 500L, t0, mapOf("SHITCOIN" to view(60.0), "MOONSHOT" to view(55.0), "EXPRESS" to view(70.0)), t0)
        e.cyclePrimary8136(m, 500L, "SHITCOIN", false, null, t0)
        e.notePrimaryRefusal8136(m, "SHITCOIN", "NATIVE:MCAP_TOO_HIGH", false, t0 + 1)
        assertEquals(ClaimOutcome8144.GRANTED, e.claimRescueOutcome8144(m, "EXPRESS", "SHITCOIN", native, "MCAP_TOO_HIGH", false,
            listOf(c("EXPRESS", 70.0), c("MOONSHOT", 55.0)), null, t0 + 2))
        // The sniper's hand-off names MOONSHOT; its task is live but the slot is held by EXPRESS's evidence rescue.
        assertEquals("MOONSHOT", e.nameHandoffReceiver8153(m, "PROJECT_SNIPER", "MOONSHOT", t0 + 3))
        tk.openTask8153(m, "MOONSHOT", "PROJECT_SNIPER", "PHASE_HANDOFF_8136_EXPANDING_TO_MOONSHOT", 500L, t0 + 3, t0 + 3)
        assertEquals(ClaimOutcome8144.DENIED_HELD_BY_OTHER, e.claimHandoffReceiver8153(m, "MOONSHOT", "SHITCOIN", null, t0 + 4))
        tk.blockTask8153(m, "MOONSHOT", Block8153.WAIT, "SLOT_HELD_BY_EXPRESS", t0 + 4)
        // The cycle did not let it in this time: it waits (shadow), it is not stranded INELIGIBLE for the version.
        e.noteNotEvaluated8136(m, "MOONSHOT", "SHITCOIN", true, 55.0, t0 + 5)
        assertEquals(Terminal8136.CONTRIBUTOR_SHADOW to "RECEIVER_TASK_WAITING_SLOT_EXPRESS", e.terminalOf8136(m, "MOONSHOT"))
        assertTrue(e.isNamedReceiver8144(m, "MOONSHOT"))
        assertTrue(e.mayOwn8136(m, "MOONSHOT"))
        // The evidence rescue is gated before the FDG and releases the slot: the receiver is consumed on the next cycle.
        e.noteNotEvaluated8136(m, "EXPRESS", "SHITCOIN", true, 70.0, t0 + 6)
        assertEquals("", e.rescueOf8136(m))
        assertEquals(ClaimOutcome8144.GRANTED, e.claimHandoffReceiver8153(m, "MOONSHOT", "SHITCOIN", null, t0 + 7))
        assertTrue(e.executableRescue8144(m, "MOONSHOT", t0 + 7))
        e.noteScheduled8144(m, "MOONSHOT", t0 + 8)
        tk.onFdgVerdict8153(m, "MOONSHOT", true, null, t0 + 9)
        e.onFdgVerdict8136(m, "MOONSHOT", 500L, true, null, false, true, 55.0, t0 + 9)
        assertEquals(Terminal8136.RESCUE_EVALUATED to "FDG_ALLOW", e.terminalOf8136(m, "MOONSHOT"))
        assertEquals(Outcome8153.EVALUATED, tk.taskOf8153(m, t0 + 10)!!.outcome)
        // Without a live task the old rule still applies: a held slot ends the receiver by name.
        val m2 = "mint-notask8153"
        e.registerNative8136(m2, 510L, t0, mapOf("SHITCOIN" to view(60.0), "MOONSHOT" to view(55.0), "EXPRESS" to view(70.0)), t0)
        e.cyclePrimary8136(m2, 510L, "SHITCOIN", false, null, t0)
        e.notePrimaryRefusal8136(m2, "SHITCOIN", "NATIVE:MCAP_TOO_HIGH", false, t0 + 1)
        e.claimRescueOutcome8144(m2, "EXPRESS", "SHITCOIN", native, "MCAP_TOO_HIGH", false, listOf(c("EXPRESS", 70.0), c("MOONSHOT", 55.0)), null, t0 + 2)
        e.nameHandoffReceiver8153(m2, "PROJECT_SNIPER", "MOONSHOT", t0 + 3)
        e.noteNotEvaluated8136(m2, "MOONSHOT", "SHITCOIN", true, 55.0, t0 + 5)
        assertEquals(Terminal8136.INELIGIBLE to "RECEIVER_SLOT_HELD_BY_EXPRESS", e.terminalOf8136(m2, "MOONSHOT"))
    }

    // ── 7. a launch's next phase is a new hand-off (RCR) ──

    @Test fun aLaunchsNextPhaseIsANewHandoffNotSwallowedByTheSettledHold() {
        val m = "mint-rcr8153"
        val birth = "PHASE_HANDOFF_8136_BIRTH_TO_SHITCOIN_NO_EARLY_INTEREST"
        val h0 = a.emit8144(null, m, "PROJECT_SNIPER", "SHITCOIN", birth, t0)
        val settled = a.onNative8144(h0, listOf(LaneRead8144("SHITCOIN", false, 10, "LOW_SCORE"), LaneRead8144("EXPRESS", false, 5, "NO_RIDE")), t0)
        assertEquals(State8144.ARRIVED_EVALUATED, settled.state)
        assertSame(settled, a.emit8144(settled, m, "PROJECT_SNIPER", "SHITCOIN", birth, t0 + 60_000L))
        assertSame(settled, a.emit8144(settled, m, "PROJECT_SNIPER", "MOONSHOT", "TOO_OLD_8144: 900s > 600s", t0 + 60_000L))
        val expanding = a.emit8144(settled, m, "PROJECT_SNIPER", "MOONSHOT", "PHASE_HANDOFF_8136_EXPANDING_TO_MOONSHOT", t0 + 60_000L)
        assertNotSame(settled, expanding)
        assertEquals(State8144.EMITTED, expanding.state)
        assertEquals("MOONSHOT", expanding.named)
        // Same phase, the cap crossed the runner floor: the runner lane is a new receiver.
        assertTrue(a.newHandoff8153(settled, "MOONSHOT", "PHASE_HANDOFF_8136_BIRTH_TO_MOONSHOT_FLOW_UNKNOWN_NONE"))
        assertFalse(a.newHandoff8153(expanding.copy(state = State8144.NO_QUALIFIED_RECEIVER), "MOONSHOT", "PHASE_HANDOFF_8136_IGNITION_TO_MOONSHOT_SETUP_UNPROVEN"))
        assertEquals(LaunchLifecycle8136.Stage8136.EXPANDING, a.stageOf8153("PHASE_HANDOFF_8136_EXPANDING_TO_MOONSHOT"))
        assertEquals(LaunchLifecycle8136.Stage8136.PRE_IGNITION, a.stageOf8153("PHASE_HANDOFF_8136_PRE_IGNITION_TO_SHITCOIN"))
        assertNull(a.stageOf8153("TOO_OLD_8144: 900s > 600s"))
    }

    // ── wiring: the task is consumed by the real lane gate, every cycle ──

    @Test fun theLaneGateConsumesTheTaskAndEveryOutcomeIsFedBack() {
        val o = src("engine/SpecialistOwnership7951.kt")
        assertTrue(o.contains("if (HandoffTaskRuntime8153.admits8153(l, mint, primaryLane, nowMs)) return true"))
        assertTrue(o.contains("HandoffTaskRuntime8153.onNotScheduled8153(mint, l)"))
        assertTrue(o.contains("HandoffReceiverTasks8153.onFdgVerdict8153(mint, lane7951(lane), shouldTrade, blockReason, nowMs)"))
        assertTrue(o.contains("HandoffTaskRuntime8153.onYield8153(mint, lane7951(primary), named, ops)"))
        val el = src("engine/SpecialistElection8136.kt")
        assertTrue(el.contains("HandoffReceiverTasks8153.markScheduled8153(mint, l, nowMs)"))
        assertTrue(el.contains("HandoffReceiverTasks8153.isDue8153(mint, canon8136(lane), nowMs)"))
        val h = src("engine/truth/AdmissionHandoff8144.kt")
        assertTrue(h.contains("val next = followTask8153(live, judged, reads, atMs)"))
        assertTrue(h.contains("rt.onReceiverQualified8153(after.mint, after.from, receiver, after.reason, eligible, after.emittedAtMs, atMs, atMs)"))
        // BotService evaluates each lane every cycle through this gate: desk-qualified by the election (incl. the task),
        // then handOffAllowed7960, which now consumes the task before the 7960 primary-refusal rules.
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("SpecialistElection8136.electionQualified8144(ts.mint, l)"))
        assertTrue(bot.contains("SpecialistOwnership7951.handOffAllowed7960(l, ts.mint, primaryLane)"))
        assertTrue(bot.contains("shouldRunBuyLaneForCycle(ts, \"MOONSHOT\", cyclePrimaryLane)"))
        assertTrue(bot.contains("SpecialistElection8136.noteScheduled8144(ts.mint, lane)"))
    }

    // 8159 regression: the native "not ready YET" state must not close a launch hand-off.
    @Test fun hydrationPendingDoesNotBecomePermanentNativeRefusal() {
        val mint = "mint-hydrating8159"
        val initial = a.emit8144(null, mint, "PROJECT_SNIPER", "MOONSHOT",
            "PHASE_HANDOFF_8136_BIRTH_TO_MOONSHOT_FLOW_UNKNOWN", t0)
        val awaiting = listOf(LaneRead8144("MOONSHOT", false, 0, "NATIVE_BIRTH_METADATA_HYDRATING"))
        assertEquals(Block8153.DATA, block("NATIVE_BIRTH_METADATA_HYDRATING"))
        assertEquals(Block8153.DATA, block("NATIVE_LANE_LIQ_UNHYDRATED_DEFER"))
        assertEquals(Block8153.DATA, block("NATIVE_PRICE_NOT_FRESH"))
        assertSame(initial, a.onNativeTransfer8153(initial, awaiting, t0 + 1_000L, fit))

        val qualified = a.onNativeTransfer8153(initial,
            listOf(LaneRead8144("MOONSHOT", true, 62, "MOONSHOT_NATIVE_ALLOW")), t0 + 2_000L, fit)
        assertEquals(State8144.RECEIVER_QUALIFIED, qualified.state)
        assertSame(qualified, a.onNativeTransfer8153(qualified,
            listOf(LaneRead8144("MOONSHOT", false, 0, "NATIVE_LANE_LIQ_UNHYDRATED_DEFER")),
            t0 + 3_000L, fit))
        // A genuine adverse native judgement still terminates this receiver.
        val negative = a.onNativeTransfer8153(qualified,
            listOf(LaneRead8144("MOONSHOT", false, 10, "NATIVE_SCORE_TOO_LOW")),
            t0 + 4_000L, fit)
        assertEquals(State8144.ARRIVED_EVALUATED, negative.state)
    }

    // 8159 regression: the task can wait for market data while the election remains retryable.
    @Test fun transientGateCannotPermanentlyIneligibleTheNamedReceiver() {
        val mint = "mint-gate-retry8159"
        e.registerNative8136(mint, 8159L, t0,
            mapOf("SHITCOIN" to view(60.0), "MOONSHOT" to view(63.0)), t0)
        e.cyclePrimary8136(mint, 8159L, "SHITCOIN", false, null, t0)
        assertEquals("MOONSHOT", e.nameHandoffReceiver8153(mint, "PROJECT_SNIPER", "MOONSHOT", t0))
        tk.openTask8153(mint, "MOONSHOT", "PROJECT_SNIPER",
            "PHASE_HANDOFF_8136_EXPANDING_TO_MOONSHOT", 8159L, t0, t0)
        e.noteGateReason8144(mint, "MOONSHOT", "NATIVE_LANE_LIQ_UNHYDRATED_DEFER")
        tk.blockTask8153(mint, "MOONSHOT", Block8153.DATA, "NATIVE_LANE_LIQ_UNHYDRATED_DEFER", t0 + 1L)
        e.noteNotEvaluated8136(mint, "MOONSHOT", "SHITCOIN", true, 63.0, t0 + 2L)
        assertEquals(Terminal8136.CONTRIBUTOR_SHADOW, e.terminalOf8136(mint, "MOONSHOT")?.first)
        assertTrue(e.mayOwn8136(mint, "MOONSHOT"))
        assertTrue(tk.isDue8153(mint, "MOONSHOT", t0 + 3L, 8159L))
        assertEquals(ClaimOutcome8144.GRANTED, e.claimHandoffReceiver8153(
            mint, "MOONSHOT", "SHITCOIN", null, t0 + 4L))
        // Even after a claimed rescue was deferred at its lane gate, a future call can claim again.
        e.noteGateReason8144(mint, "MOONSHOT", "NATIVE_PRICE_NOT_FRESH")
        tk.blockTask8153(mint, "MOONSHOT", Block8153.DATA, "NATIVE_PRICE_NOT_FRESH", t0 + 5L)
        e.noteNotEvaluated8136(mint, "MOONSHOT", "SHITCOIN", true, 63.0, t0 + 6L)
        assertEquals("", e.rescueOf8136(mint))
        assertEquals(Terminal8136.CONTRIBUTOR_SHADOW, e.terminalOf8136(mint, "MOONSHOT")?.first)
        assertEquals(ClaimOutcome8144.GRANTED, e.claimHandoffReceiver8153(
            mint, "MOONSHOT", "SHITCOIN", null, t0 + 7L))
    }

}
