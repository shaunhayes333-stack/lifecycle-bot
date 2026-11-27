package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaunchReplay8744
import com.lifecyclebot.engine.truth.LaunchReplay8744.Forward8856
import com.lifecyclebot.engine.truth.LaunchReplay8744.Ladder
import com.lifecyclebot.engine.truth.LaunchReplay8744.ProofSet9047
import com.lifecyclebot.engine.truth.LaunchReplay8744.Result
import com.lifecyclebot.engine.truth.LaunchReplay8744.Rule
import com.lifecyclebot.engine.truth.LaunchSniper8748
import com.lifecyclebot.engine.truth.SignalReplay8828
import com.lifecyclebot.engine.truth.SignalTape8828
import org.junit.Assert.*
import org.junit.Test

/** Regression for an armed +20s rule remaining cached while the measured need became +28s. */
class Aate9047LaunchProofLatencyTest {
    private fun field(owner: Any, name: String) =
        owner.javaClass.getDeclaredField(name).also { it.isAccessible = true }

    /** Drive the real live getter without changing any learned record or leaving process state behind. */
    private fun withPublished(proofs: ProofSet9047, body: ((Long) -> Unit) -> Unit) {
        val published = field(LaunchReplay8744, "publishedProofs9047")
        val oldPublished = published.get(LaunchReplay8744)
        @Suppress("UNCHECKED_CAST")
        val landed = field(LaunchSniper8748, "landed").get(LaunchSniper8748) as java.util.ArrayDeque<Long>
        val oldLanded = synchronized(landed) { landed.toList() }
        try {
            published.set(LaunchReplay8744, proofs)
            body { delay -> synchronized(landed) { landed.clear(); repeat(3) { landed.addLast(delay) } } }
        } finally {
            published.set(LaunchReplay8744, oldPublished)
            synchronized(landed) { landed.clear(); landed.addAll(oldLanded) }
        }
    }

    private fun rule(delay: Long, cap: Double = 5_000.0) =
        Rule(cap, 3, 5.0, 35.0, Ladder.DOCTRINE, delay)

    private fun replay(delay: Long, gain: Double = 15.0, cap: Double = 5_000.0): Result =
        LaunchReplay8744.stats8748(rule(delay, cap), List(120) { if (it % 4 == 0) gain + 4.0 else gain })!!

    private fun forward(delay: Long, gain: Double = 12.0, cap: Double = 5_000.0) =
        Forward8856(rule(delay, cap), 1_700_000_000_000L).apply {
            repeat(120) { add(if (it % 4 == 0) gain + 4.0 else gain) }
        }

    @Test fun theProductionGetterRechecksCurrentMeasuredLatencyWithoutAnotherReplay() {
        val fast = replay(20_000L, 30.0)
        val slow = replay(30_000L, 15.0)
        withPublished(ProofSet9047(6L, listOf(fast, slow), emptyList())) { measured ->
            measured(20_000L)
            assertEquals(fast, LaunchReplay8744.proven8748())
            measured(28_000L)
            assertEquals(28_000L, LaunchSniper8748.requiredDelayMs8749())
            assertEquals(slow, LaunchReplay8744.proven8748())
            measured(30_001L)
            assertNull(LaunchReplay8744.proven8748())
            assertNull(LaunchReplay8744.shadow8748())
            measured(15_000L)
            assertEquals(fast, LaunchReplay8744.proven8748())
        }
    }

    @Test fun theSamePublishedGenerationSelectsAnAlreadyGradedSlowerRuleAsLatencyChanges() {
        val fast = replay(20_000L, 30.0)
        val slow = replay(30_000L, 15.0)
        val proofs = ProofSet9047(7L, listOf(fast, slow), emptyList())

        assertEquals(fast, proofs.provenAt(20_000L))
        assertEquals(slow, proofs.provenAt(28_000L))
        assertEquals(slow, proofs.provenAt(30_000L))
        assertNull("a +30s grade cannot authorize an ungraded +30.001s delay", proofs.provenAt(30_001L))
        assertEquals("latency selection does not rerun or replace the evidence generation", 7L, proofs.generation)
        assertEquals(fast, proofs.provenAt(15_000L))
    }

    @Test fun forwardProofCanSupplyAQualifiedLongerDelayWithoutOverridingAnEligibleReplayProof() {
        val fromReplay = replay(20_000L, 8.0)
        val highForward = forward(40_000L, 50.0)
        val proofs = ProofSet9047(8L, listOf(fromReplay), listOf(highForward))

        assertEquals("keep the existing replay-before-forward policy", fromReplay, proofs.provenAt(20_000L))
        val slower = proofs.provenAt(28_000L)
        assertNotNull(slower)
        assertEquals(40_000L, slower!!.rule.delayMs)
        assertEquals(120, slower.n)
        assertEquals(highForward.exBest, slower.exTop3, 0.0)
        assertNull(proofs.provenAt(40_001L))
    }

    @Test fun theBestEligibleProofKeepsItsOwnGradeRatherThanBorrowingTheFastRulesReturn() {
        val tooFast = replay(20_000L, 100.0)
        val qualified = replay(30_000L, 10.0)
        val betterQualified = replay(40_000L, 20.0)
        val proofs = ProofSet9047(9L, listOf(tooFast, qualified, betterQualified), emptyList())

        assertEquals(betterQualified, proofs.provenAt(25_000L))
        assertEquals(betterQualified.exTop3, proofs.provenAt(25_000L)!!.exTop3, 0.0)
        assertNotEquals(tooFast.exTop3, proofs.provenAt(25_000L)!!.exTop3, 0.0)
    }

    @Test fun publicationDetachesTheForwardBookAndCallerOwnedCandidateLists() {
        val fast = replay(20_000L)
        val f = forward(40_000L)
        val replayInputs = mutableListOf(fast)
        val forwardInputs = mutableListOf(f)
        val proofs = ProofSet9047(10L, replayInputs, forwardInputs)
        val original = proofs.provenAt(30_000L)!!

        replayInputs.clear()
        forwardInputs.clear()
        repeat(200) { f.add(-100.0) }

        assertEquals(fast, proofs.provenAt(20_000L))
        assertEquals("a worker's next grading pass cannot alter the published proof", original, proofs.provenAt(30_000L))
        assertNull("a new publication must independently judge the now-losing book",
            ProofSet9047(11L, emptyList(), listOf(f)).provenAt(30_000L))
    }

    @Test fun unprovenOrWrongLadderCandidatesRemainShadowOnlyAtTheirOwnLatency() {
        val thin = replay(30_000L).copy(n = 60)
        val losing = replay(40_000L).copy(exTop3 = -2.0, exTop3Se = 1.0)
        val wrongLadder = replay(50_000L).let { it.copy(rule = it.rule.copy(ladder = Ladder.MOONBAG)) }
        val proofs = ProofSet9047(12L, listOf(thin, losing, wrongLadder), emptyList())

        assertNull(proofs.provenAt(25_000L))
        assertEquals(thin, proofs.shadowAt(25_000L))
        assertEquals(losing, proofs.shadowAt(35_000L))
        assertNull(proofs.shadowAt(40_001L))
    }

    @Test fun fallbackEligibilityUsesTheExistingMinimumAndTheActualGradedDelay() {
        assertTrue(LaunchSniper8748.latencyFits9047(30_000L, 28_000L))
        assertFalse(LaunchSniper8748.latencyFits9047(20_000L, 28_000L))
        assertTrue(LaunchSniper8748.latencyFits9047(10_000L, 0L))
        assertFalse(LaunchSniper8748.latencyFits9047(9_999L, 0L))
        assertFalse(LaunchSniper8748.latencyFits9047(0L, 20_000L))
    }

    @Test fun anExistingFireNeverBorrowsTheNewRulesWindowOrRenewsItsSourceClock() {
        val wall = 1_800_000_000_000L
        val fire = LaunchSniper8748.Fire("latency-fire-9047", wall + 8_000L, 2_000L, 5_100.0,
            "graded-at-20s", 120, 7.0, 1.0, 0.1, true,
            triggerEventMs = wall, gradedDelayMs8968 = 20_000L, sourceEventId9043 = "print-9047")
        val identity = fire.opportunityId9043

        assertTrue(LaunchSniper8748.liveFireAtDelay9047(fire, wall + 9_000L, 20_000L))
        assertFalse("a faster grade cannot carry a newly slower runtime",
            LaunchSniper8748.liveFireAtDelay9047(fire, wall + 9_000L, 28_000L))
        assertTrue(LaunchSniper8748.liveFireAtDelay9047(fire, wall + 19_999L, 15_000L))
        assertFalse("latency recovery does not renew the source-event deadline",
            LaunchSniper8748.liveFireAtDelay9047(fire, wall + 20_001L, 15_000L))
        assertEquals(identity, fire.opportunityId9043)
        assertEquals(20_000L, fire.gradedDelayMs8968)
        assertEquals(wall, fire.triggerEventMs)
        assertFalse(LaunchSniper8748.liveFireAtDelay9047(fire.copy(armed = false), wall + 9_000L, 10_000L))
    }

    @Test fun aSignalKeepsItsExistingEvidenceWindowAndItsSeparateActualLatencyGrade() {
        val wall = 1_800_000_000_000L
        val nonFast = LaunchSniper8748.Fire("signal-window-9047", wall, 2_000L, 5_100.0,
            "SIGNAL8828:non-fast", 120, 7.0, 1.0, 0.1, true,
            triggerEventMs = wall, gradedDelayMs8968 = 0L, sourceEventId9043 = "signal-print-9047",
            proofDelayMs9047 = 20_000L)

        assertEquals(120_000L, LaunchSniper8748.window8968(nonFast.gradedDelayMs8968))
        assertTrue("a proven non-fast signal keeps its established 120s evidence lifetime",
            LaunchSniper8748.liveFireAtDelay9047(nonFast, wall + 120_000L, 20_000L))
        assertFalse(LaunchSniper8748.liveFireAtDelay9047(nonFast, wall + 120_001L, 20_000L))
        assertFalse("the lifetime cannot substitute for the record's actual latency grade",
            LaunchSniper8748.liveFireAtDelay9047(nonFast, wall + 1_000L, 28_000L))
        assertFalse("unknown latency proof is never inferred from the clock fallback",
            LaunchSniper8748.liveFireAtDelay9047(nonFast.copy(proofDelayMs9047 = 0L), wall + 1_000L, 10_000L))

        val fast = nonFast.copy(ruleId = "SIGNAL8828:fast", gradedDelayMs8968 = 30_000L)
        assertTrue(LaunchSniper8748.liveFireAtDelay9047(fast, wall + 30_000L, 20_000L))
        assertFalse(LaunchSniper8748.liveFireAtDelay9047(fast, wall + 30_001L, 20_000L))
        assertFalse("the fast signal's extra bar is a window, not a +30s replay grade",
            LaunchSniper8748.liveFireAtDelay9047(fast, wall + 1_000L, 28_000L))
    }

    @Test fun theActualSignalReplayFreezesItsEvaluatedDelayAndRequalifiesItsLiveGetter() {
        val names = listOf("records", "proven", "shadow", "status", "gradedDelayMs9025")
        val fields = names.map { field(SignalReplay8828, it) }
        val before = fields.map { it.get(SignalReplay8828) }
        val t = longArrayOf(500L, 1_300L, 2_100L, 10_400L, 20_400L, 30_400L, 40_400L, 80_400L)
        val prices = doubleArrayOf(1.0, 1.01, 1.1, 1.1, 1.1, 1.1, 1.3, 1.5).map { it * 1e-7 }.toDoubleArray()
        val paths = List(120) { i -> LaunchReplay8744.Path8748(t, prices,
            BooleanArray(t.size) { true }, BooleanArray(t.size) { true }, 0,
            sol = DoubleArray(t.size) { 0.2 }, bornMs = 1_700_000_000_000L + i * 200_000L) }
        try {
            val graded = SignalReplay8828.grade8828(paths, 100.0, 0.0, 20_000L)
            assertTrue("the actual tape must produce the quiet-open fixture", graded.any { it.id == SignalTape8828.QUIET_OPEN_9025 })
            assertTrue(graded.all { it.gradedDelayMs9047 == 20_000L })
            SignalReplay8828.evaluate8828(paths, 100.0, 0.0, 20_000L)
            val first = SignalReplay8828.proven8828(20_000L)
            assertNotNull("the fixture independently meets the existing statistical proof bar", first)
            assertEquals(20_000L, first!!.gradedDelayMs9047)
            assertNull("the actual live getter cannot borrow the evidence window as a slower grade",
                SignalReplay8828.proven8828(28_000L))

            SignalReplay8828.evaluate8828(paths, 100.0, 0.0, 30_000L)
            assertEquals(30_000L, SignalReplay8828.proven8828(28_000L)!!.gradedDelayMs9047)
            assertEquals("a later replay cannot restamp an already-captured record", 20_000L, first.gradedDelayMs9047)
        } finally {
            fields.zip(before).forEach { (f, value) -> f.set(SignalReplay8828, value) }
        }
    }

    @Test fun anAdmittedFireKeepsItsOwnGradedStopWhenTheCurrentWinningRuleChanges() {
        val wall = 1_800_000_000_000L
        val mint = "own-stop-9047-${System.nanoTime()}"
        val newWinner = replay(20_000L, 30.0).let { it.copy(rule = it.rule.copy(stopPct = 20.0)) }
        val oldFire = LaunchSniper8748.Fire(mint, wall, 2_000L, 5_100.0,
            "older-40s-rule", 120, 7.0, 1.0, 0.1, true,
            triggerEventMs = wall, gradedDelayMs8968 = 40_000L, sourceEventId9043 = "old-print-9047",
            gradedStopPct9047 = 35.0)
        @Suppress("UNCHECKED_CAST")
        val fires = field(LaunchSniper8748, "fires").get(LaunchSniper8748) as MutableMap<String, LaunchSniper8748.Fire>
        @Suppress("UNCHECKED_CAST")
        val admitted = field(LaunchSniper8748, "admitted8949").get(LaunchSniper8748) as MutableMap<String, Pair<Double, Long>>
        val oldAdmitted = admitted.toMap()
        val counters = listOf("admits8949", "held8949").map {
            field(LaunchSniper8748, it).get(LaunchSniper8748) as java.util.concurrent.atomic.AtomicLong
        }
        val oldCounters = counters.map { it.get() }
        val old = fires.put(mint, oldFire)
        try {
            withPublished(ProofSet9047(13L, listOf(newWinner), emptyList())) { measured ->
                measured(20_000L)
                assertEquals(newWinner, LaunchReplay8744.proven8748())
                LaunchSniper8748.noteAdmission8949(mint, LaunchSniper8748.KIND_8748, "ADMIT", wall + 1_000L)
                assertEquals("the original 35% grade retains the existing 25% ordinary stop cap",
                    25.0, LaunchSniper8748.ruleStopMag8949(mint, wall + 2_000L)!!, 0.0)
                assertEquals(35.0, admitted[mint]!!.first, 0.0)
            }
        } finally {
            if (old == null) fires.remove(mint) else fires[mint] = old
            admitted.clear(); admitted.putAll(oldAdmitted)
            counters.zip(oldCounters).forEach { (counter, value) -> counter.set(value) }
        }
    }
}
