package com.lifecyclebot.engine

import com.lifecyclebot.engine.ElectionRescueContract8144.ClaimOutcome8144
import com.lifecyclebot.engine.HandoffReceiverTasks8153.Block8153
import com.lifecyclebot.engine.HandoffReceiverTasks8153.Outcome8153
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

/**
 * A temporary rescue-claim refusal must not become a permanent native veto. Exercise
 * the production election, ownership outcome recorder and task table together, with
 * explicit clocks and unchanged candidate versions. Scheduling stays bounded while
 * pending data/slot tasks let the remaining live receivers have a turn.
 */
class Aate8237HandoffContinuityTest {
    private val election = SpecialistElection8136
    private val tasks = HandoffReceiverTasks8153
    private val priority = HandoffPriority8190
    private val t0 = 8_236_000_000L
    private val version = 8236L
    private val lanes = listOf("QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "EXPRESS", "CORE",
        "MOONSHOT", "PROJECT_SNIPER", "DIP_HUNTER", "MANIPULATED", "TREASURY", "CASHGEN")
    // CORE/CYCLIC have their primary/trunk path; neither has a per-token rescue buy block.
    private val receivers = lanes.filter { it !in election.RESCUE_EXCLUDED_8136 }

    @Before fun setUp() { election.resetForTests8136(); SpecialistOwnership7951.resetForTests7951() }
    @After fun tearDown() { election.resetForTests8136(); SpecialistOwnership7951.resetForTests7951() }

    private fun view(score: Double, eligible: Boolean = true, reason: String = "NATIVE_ALLOW") =
        NativeView8136(eligible, score, reason)

    private fun prepare(mint: String, lane: String, named: Boolean = true,
                        other: Map<String, NativeView8136> = emptyMap()) {
        election.registerNative8136(mint, version, t0,
            mapOf("CORE" to view(70.0), lane to view(65.0)) + other, t0)
        assertEquals("CORE", election.cyclePrimary8136(mint, version, "CORE", false, null, t0))
        election.notePrimaryRefusal8136(mint, "CORE", "NATIVE:MCAP_TOO_HIGH", false, t0 + 1L)
        if (named) assertEquals(lane, election.nameHandoffReceiver8153(mint, "CORE", lane, t0 + 2L))
        assertTrue(tasks.openTask8153(mint, lane, "CORE", "RECEIVER_QUALIFIED", version, t0, t0 + 2L)!!.pending)
    }

    private fun claim(mint: String, lane: String, ineligible: String?, at: Long,
                      contenders: List<Contender8136> = listOf(Contender8136(lane, 65.0))): ClaimOutcome8144 =
        election.claimRescueOutcome8144(mint, lane, "CORE", election.TRIGGER_NATIVE,
            "MCAP_TOO_HIGH", false, contenders, ineligible, at)

    private fun record(mint: String, lane: String, outcome: ClaimOutcome8144, at: Long) =
        SpecialistOwnership7951.recordRescueOutcome(lane, mint, outcome, at)

    private fun complete(mint: String, lane: String, at: Long) {
        election.noteScheduled8144(mint, lane, at)
        tasks.onFdgVerdict8153(mint, lane, true, null, at + 1L, version)
        election.onFdgVerdict8136(mint, lane, version, true, null, false, true, 65.0, at + 1L)
        assertEquals(Outcome8153.EVALUATED, tasks.taskOf8153(mint, at + 2L)!!.outcome)
        assertEquals(Terminal8136.RESCUE_EVALUATED, election.terminalOf8136(mint, lane)?.first)
        assertEquals(1L, ElectionRescueFunnel8144.counts8144(lane).getValue("EVALUATED"))
    }

    @Test fun everyReceiverCanRetryADataDeferralOnTheSameLineage() {
        for (lane in receivers) {
            val mint = "data8236-$lane"
            prepare(mint, lane)
            val original = tasks.taskOf8153(mint, t0 + 2L)!!
            val deferred = claim(mint, lane, "DEFERRED_RECEIVER_SETUP_INPUTS_8190", t0 + 3L)
            assertEquals(ClaimOutcome8144.DENIED_INELIGIBLE, deferred)
            record(mint, lane, deferred, t0 + 3L)
            assertEquals(lane, Terminal8136.DEFERRED_DATA, election.terminalOf8136(mint, lane)?.first)
            assertTrue(lane, election.mayOwn8136(mint, lane))
            val waiting = tasks.taskOf8153(mint, t0 + 4L)!!
            assertTrue(lane, waiting.pending)
            assertEquals("DEFERRED:DEFERRED_RECEIVER_SETUP_INPUTS_8190", waiting.lastBlock)
            assertEquals(original.validUntilMs, waiting.validUntilMs)

            // Fresh proof resolves the gap; the same receiver/version can take the one rescue.
            election.registerNative8136(mint, version, t0 + 4_000L, mapOf(lane to view(65.0)), t0 + 4_000L)
            assertEquals(lane, ClaimOutcome8144.GRANTED,
                election.claimHandoffReceiver8153(mint, lane, "CORE", null, t0 + 4_000L))
            assertEquals(version, tasks.taskOf8153(mint, t0 + 4_000L)!!.version)
            assertNull(election.terminalOf8136(mint, lane)?.first)
            complete(mint, lane, t0 + 4_001L)
        }
        assertEquals(10, receivers.size)
    }

    @Test fun aHeldSlotKeepsTheReceiverPendingUntilTheUnconsumedHolderGoesIdle() {
        val mint = "held8236"
        val lane = "MOONSHOT"
        prepare(mint, lane, named = false, other = mapOf("EXPRESS" to view(90.0)))
        assertEquals(ClaimOutcome8144.GRANTED,
            claim(mint, "EXPRESS", null, t0 + 3L, listOf(Contender8136("EXPRESS", 90.0), Contender8136(lane, 65.0))))
        assertEquals(lane, election.nameHandoffReceiver8153(mint, "CORE", lane, t0 + 4L))
        val held = election.claimHandoffReceiver8153(mint, lane, "CORE", null, t0 + 5L)
        assertEquals(ClaimOutcome8144.DENIED_HELD_BY_OTHER, held)
        record(mint, lane, held, t0 + 5L)
        assertTrue(election.mayOwn8136(mint, lane))
        assertTrue(tasks.taskOf8153(mint, t0 + 5L)!!.pending)
        assertEquals("SLOT_HELD_BY_EXPRESS", tasks.taskOf8153(mint, t0 + 5L)!!.lastBlock)
        tasks.noteWaiting8153(mint, lane, "GATE_UNNAMED", t0 + 6L)
        assertEquals("SLOT_HELD_BY_EXPRESS", tasks.taskOf8153(mint, t0 + 6L)!!.lastBlock)

        // Only a fresh valid read extends the 45 s task, within its original hard cap.
        election.registerNative8136(mint, version, t0 + 30_000L, mapOf(lane to view(65.0)), t0 + 30_000L)
        tasks.refreshTask8153(mint, lane, version, true, t0 + 30_000L, t0 + 30_000L)
        val renewed = tasks.taskOf8153(mint, t0 + 30_000L)!!
        assertEquals(t0 + 2L, renewed.createdAtMs)
        assertEquals(t0 + 75_000L, renewed.validUntilMs)
        assertEquals(ClaimOutcome8144.GRANTED,
            election.claimHandoffReceiver8153(mint, lane, "CORE", null, t0 + 46_000L))
        assertEquals("RESCUE_IDLE_RELEASED_8180", election.terminalOf8136(mint, "EXPRESS")?.second)
        complete(mint, lane, t0 + 46_001L)

        // A rescue that reached FDG is consumed: its idle clock cannot admit a third lane.
        election.registerNative8136(mint, version, t0 + 50_000L, mapOf("QUALITY" to view(99.0)), t0 + 50_000L)
        val third = claim(mint, "QUALITY", null, t0 + 95_000L, listOf(Contender8136("QUALITY", 99.0)))
        assertEquals(ClaimOutcome8144.DENIED_HELD_BY_OTHER, third)
        assertEquals(lane, election.rescueOf8136(mint))
    }

    @Test fun anOutrankedClaimIsRetryableWhenTheOtherLaneLaterRefusesOnItsOwnEvidence() {
        val mint = "rank8236"
        val lane = "TREASURY"
        prepare(mint, lane, named = false, other = mapOf("EXPRESS" to view(90.0)))
        val contenders = listOf(Contender8136(lane, 65.0), Contender8136("EXPRESS", 90.0))
        val ranked = claim(mint, lane, null, t0 + 3L, contenders)
        assertEquals(ClaimOutcome8144.DENIED_OUTRANKED, ranked)
        record(mint, lane, ranked, t0 + 3L)
        assertTrue(election.mayOwn8136(mint, lane))
        assertTrue(tasks.taskOf8153(mint, t0 + 3L)!!.pending)
        assertEquals("DENIED_OUTRANKED", tasks.taskOf8153(mint, t0 + 3L)!!.lastBlock)
        election.registerNative8136(mint, version, t0 + 4_000L,
            mapOf("EXPRESS" to view(0.0, eligible = false, reason = "NO_CONTINUATION")), t0 + 4_000L)
        assertEquals(ClaimOutcome8144.GRANTED, claim(mint, lane, null, t0 + 4_001L, contenders))
        complete(mint, lane, t0 + 4_002L)
    }

    @Test fun arbitrationErrorsDeferAndThenAllowASuccessfulRetryWithoutGrantingOnError() {
        val mint = "error8236"
        val lane = "CASHGEN"
        prepare(mint, lane)
        val outcome = ElectionRescueContract8144.guardedClaim8144 { throw IllegalStateException("arbitration unavailable") }
        assertEquals(ClaimOutcome8144.DEFERRED_ARBITRATION_ERROR, outcome)
        assertFalse(outcome.executable)
        record(mint, lane, outcome, t0 + 3L)
        assertEquals(Terminal8136.DEFERRED_DATA, election.terminalOf8136(mint, lane)?.first)
        assertEquals("DEFERRED:DEFERRED_ARBITRATION_ERROR", tasks.taskOf8153(mint, t0 + 3L)!!.lastBlock)
        assertEquals("", election.rescueOf8136(mint))
        assertEquals(ClaimOutcome8144.GRANTED, claim(mint, lane, null, t0 + 4_000L))
        complete(mint, lane, t0 + 4_001L)
    }

    @Test fun tokenSafetyAndPrimaryJudgementsStayBindingOnTheSameLineage() {
        for (lane in receivers) {
            val mint = "safety8236-$lane"
            prepare(mint, lane)
            val outcome = claim(mint, lane, "TOKEN_SAFETY_BLOCKED", t0 + 3L)
            record(mint, lane, outcome, t0 + 3L)
            assertEquals(ClaimOutcome8144.DENIED_INELIGIBLE, outcome)
            assertFalse(lane, election.mayOwn8136(mint, lane))
            assertEquals(Outcome8153.REFUSED, tasks.taskOf8153(mint, t0 + 3L)!!.outcome)
            assertEquals("BINDING:TOKEN_SAFETY_BLOCKED", tasks.taskOf8153(mint, t0 + 3L)!!.verdict)
            election.registerNative8136(mint, version, t0 + 4_000L, mapOf(lane to view(99.0)), t0 + 4_000L)
            assertEquals(ClaimOutcome8144.DENIED_INELIGIBLE,
                election.claimHandoffReceiver8153(mint, lane, "CORE", null, t0 + 4_001L))
        }
        val mint = "judgement8236"
        prepare(mint, "BLUECHIP")
        election.notePrimaryRefusal8136(mint, "CORE", "FDG:TOKEN_WIDE_SAFETY", true, t0 + 3L)
        val outcome = claim(mint, "BLUECHIP", null, t0 + 4L)
        assertEquals(ClaimOutcome8144.DENIED_JUDGEMENT, outcome)
        record(mint, "BLUECHIP", outcome, t0 + 4L)
        assertEquals("BINDING:PRIMARY_JUDGEMENT", tasks.taskOf8153(mint, t0 + 4L)!!.verdict)
    }

    @Test fun theBoundedSelectorTreatsAllTwelveLanesEqually() {
        val targets = lanes.flatMapIndexed { index, lane ->
            listOf("$lane-a" to (t0 + index), "$lane-b" to (t0 + 100L + index))
        }
        val present = targets.map { it.first }
        val first = priority.select8190(targets, present)
        assertEquals(12, first.size)
        assertEquals(lanes.toSet(), first.map { it.substringBeforeLast('-') }.toSet())
        val lastAdmitted = first.associateWith { t0 }
        val second = priority.select8190(targets, present, lastAdmittedAt = lastAdmitted)
        assertEquals(12, second.size)
        assertTrue(first.toSet().intersect(second.toSet()).isEmpty())
        assertEquals(present.toSet(), (first + second).toSet())
        assertTrue(priority.select8190(targets, present, max = 0).isEmpty())
        assertTrue(priority.select8190(targets, emptyList()).isEmpty())
    }

    @Test fun actualWindowAndSliceRotatePendingTasksWithoutExtendingTheirDeadlines() {
        val mints = (0 until 36).map { "scheduled8236-$it" }
        val original = mints.mapIndexed { index, mint ->
            tasks.openTask8153(mint, receivers[index % receivers.size], "CORE", "RECEIVER_QUALIFIED",
                version, t0 + index, t0 + index)!!
        }.associateBy { it.mint }
        val seen = mutableSetOf<String>()
        repeat(3) { cycle ->
            val now = t0 + 500L + cycle * 4_000L
            val window = priority.windowTargets8190(mints, now)
            // Re-reading the window cannot spend the selection turn or displace its targets.
            assertEquals(window, priority.windowTargets8190(mints, now))
            val slice = priority.dueTargets8190(mints, now)
            assertEquals(window, slice)
            assertEquals(priority.MAX_TARGETS_8190, slice.size)
            assertTrue(seen.intersect(slice.toSet()).isEmpty())
            for (mint in slice) {
                val before = original.getValue(mint)
                priority.noteWorkerAdmitted8237(mint, now)
                tasks.markScheduled8153(mint, before.lane, now)
                tasks.blockTask8153(mint, before.lane, Block8153.DATA, "DEFERRED_NO_TOKEN_STATE", now)
                val waiting = tasks.taskOf8153(mint, now)!!
                assertTrue(waiting.pending)
                assertEquals(before.createdAtMs, waiting.createdAtMs)
                assertEquals(before.validUntilMs, waiting.validUntilMs)
                assertEquals(1, waiting.schedules)
            }
            seen += slice
        }
        assertEquals(mints.toSet(), seen)
        assertEquals(receivers.toSet(), seen.map { original.getValue(it).lane }.toSet())
        val expiredAt = t0 + tasks.TASK_TTL_MS_8153 + mints.size + 1L
        assertTrue(priority.dueTargets8190(mints, expiredAt).isEmpty())
        assertTrue(mints.all { tasks.taskOf8153(it, expiredAt)?.outcome == Outcome8153.DEFERRED_DATA })
    }

    @Test fun partialWorkerCapacityLeavesTheUnadmittedPrefixFirstNextCycle() {
        val mints = (0 until 12).map { "partial8237-$it" }
        mints.forEachIndexed { index, mint ->
            tasks.openTask8153(mint, receivers[index % receivers.size], "CORE", "RECEIVER_QUALIFIED",
                version, t0 + index, t0 + index)
        }
        val first = priority.dueTargets8190(mints, t0 + 500L)
        assertEquals(12, first.size)
        // Selecting repeatedly cannot claim service that the worker pool did not give.
        assertEquals(first, priority.dueTargets8190(mints, t0 + 501L))
        first.take(6).forEach { priority.noteWorkerAdmitted8237(it, t0 + 502L) }
        val remaining = first.drop(6)
        val second = priority.dueTargets8190(mints, t0 + 4_500L)
        assertEquals(remaining, second.take(6))
        // Pending live buys may consume all capacity: no credit for any of these mints.
        second.forEach { priority.noteWorkerUnavailable8237(it, "SHARED_CAPACITY", t0 + 4_501L) }
        assertEquals(second, priority.dueTargets8190(mints, t0 + 8_500L))
        second.take(6).forEach { priority.noteWorkerAdmitted8237(it, t0 + 8_501L) }
        assertEquals(mints.toSet(), (first.take(6) + second.take(6)).toSet())
        assertTrue(mints.all { tasks.taskOf8153(it, t0 + 8_502L)?.pending == true })
    }

    @Test fun unpollableTargetsDoNotHideHealthyTargetsBeyondTheBound() {
        val blocked = (0 until 12).map { "blocked8237-$it" }
        val healthy = (0 until 12).map { "healthy8237-$it" }
        val mints = blocked + healthy
        val original = mints.mapIndexed { index, mint ->
            tasks.openTask8153(mint, receivers[index % receivers.size], "CORE", "RECEIVER_QUALIFIED",
                version, t0 + index, t0 + index)!!
        }.associateBy { it.mint }
        val first = priority.dueTargets8190(mints, t0 + 500L)
        assertEquals(blocked, first)
        first.forEachIndexed { index, mint ->
            priority.noteWorkerUnavailable8237(mint,
                if (index % 2 == 0) "SHOULD_POLL_FALSE" else "TIMEOUT_COOLDOWN", t0 + 501L)
        }
        // Both phases now see the healthy tail, without calling/consuming shouldPoll again.
        val second = priority.windowTargets8190(mints, t0 + 4_500L)
        assertEquals(healthy, second)
        assertEquals(second, priority.dueTargets8190(mints, t0 + 4_500L))
        second.take(6).forEach { priority.noteWorkerAdmitted8237(it, t0 + 4_501L) }
        // The healthy half that lost shared capacity stays ahead of explicitly skipped mints.
        val third = priority.dueTargets8190(mints, t0 + 8_500L)
        assertEquals(healthy.drop(6), third.take(6))
        for (mint in blocked) {
            val waiting = tasks.taskOf8153(mint, t0 + 8_500L)!!
            assertTrue(waiting.pending)
            assertEquals(0, waiting.schedules)
            assertEquals(original.getValue(mint).createdAtMs, waiting.createdAtMs)
            assertEquals(original.getValue(mint).validUntilMs, waiting.validUntilMs)
        }
    }
}
