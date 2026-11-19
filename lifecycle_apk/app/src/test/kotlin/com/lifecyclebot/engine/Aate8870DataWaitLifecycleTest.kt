package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.CandidateAdmission8136 as Admission
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Candidate8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.DataWaitOwner8870
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Terminal8136
import com.lifecyclebot.engine.truth.FdgGate8365
import com.lifecyclebot.engine.truth.FeatureKind8283
import com.lifecyclebot.engine.truth.FeatureRevision8283
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The 8869 data waits: real new observations invalidate their decision; one desk's old wait cannot spend another's. */
class Aate8870DataWaitLifecycleTest {
    private val now = 1_900_000_000_000L

    @Suppress("UNCHECKED_CAST")
    private fun <T> waitMap8870(name: String): ConcurrentHashMap<String, T> =
        Admission::class.java.getDeclaredField(name).let { field ->
            field.isAccessible = true
            field.get(Admission) as ConcurrentHashMap<String, T>
        }

    private fun awaitBlocked8870(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.isAlive && thread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
        assertEquals("the test must contend on the actual production key", Thread.State.BLOCKED, thread.state)
    }

    private fun candidate(mint: String) = Candidate8136(mint, "SHITCOIN", null,
        priceAgeMs = Long.MAX_VALUE, expectedGrossPct = 30.0, costPct = 4.0,
        genericScore = 60.0, genericFloor = 30.0,
        missingInputs = listOf("shape:UNKNOWN", "tape:UNKNOWN", "age:UNKNOWN"))

    @Test fun optionalGapsDoNotBecomeDependenciesOfAnActualStalePriceWait() {
        val c = candidate("waitCausal8870")
        val waiting = Admission.decide8136(c)
        assertEquals(Terminal8136.DEFER_DATA, waiting.terminal)
        assertEquals(listOf("price"), Admission.missingOf8144(waiting))
        assertTrue(waiting.trace.contains("optionalUnobserved8170=shape:UNKNOWN,tape:UNKNOWN,age:UNKNOWN"))
        val answered = Admission.decide8136(c.copy(priceAgeMs = 1_000L))
        assertNotEquals("unchanged optional gaps do not keep an answered price pending", Terminal8136.DEFER_DATA, answered.terminal)
        val unsafe = Admission.decide8136(c.copy(priceAgeMs = 1_000L, safetyRefusal = "CONFIRMED_RUG"))
        assertEquals("SAFETY_REFUSED_8136", unsafe.reason)
        for (critical in listOf("liquidity:UNKNOWN", "quote:UNKNOWN", "route:UNKNOWN", "sellability:UNKNOWN", "decimals:UNKNOWN")) {
            val d = Admission.decide8136(c.copy(priceAgeMs = 1_000L, missingInputs = c.missingInputs + critical))
            assertEquals(critical, Terminal8136.DEFER_DATA, d.terminal)
            assertEquals(listOf(critical), Admission.missingOf8144(d))
        }
    }

    @Test fun acceptedEvidenceInvalidatesRealPolicyAndLegacyMemosAtTheSameCandidateVersion() {
        val mint = "memoEvidence8870"
        val c = candidate(mint)
        val refusal = Admission.decide8136(c)
        val ts = TokenState(mint = mint)
        val policy = FdgGate8365.inContext8365(ts, c.lane, "EXPLORATION_8347", paper8393 = false) {
            FdgGate8365.policyKey8393()
        }
        val keys = listOf("LIVE|$mint|${c.lane}|71", "LIVE|OUTSIDE|$mint|${c.lane}|71",
            "LIVE|$policy|$mint|${c.lane}|71", "LIVE|$policy|$mint|${c.lane}|72")
        val other = c.copy(mint = "${mint}Other")
        val otherKey = "LIVE|$policy|${other.mint}|${c.lane}|71"
        for (key in keys) {
            Admission.rememberMemo8870(key, c, refusal, now)
            assertNotNull(key, Admission.memoDecision8870(key, c, now + 1))
        }
        Admission.rememberMemo8870(otherKey, other, refusal, now)
        // Numeric price/feature values and candidate version may be unchanged; a validated newer observation still
        // retires the cached decision, so the next read must assess its current freshness and evidence again.
        Admission.evidenceArrived8252(mint, setOf("price"), now + 2)
        for (key in keys) assertNull(key, Admission.memoDecision8870(key, c, now + 3))
        assertNotNull("another mint's decision is untouched", Admission.memoDecision8870(otherKey, other, now + 3))
        Admission.evidenceArrived8252(other.mint, setOf("price"), now + 4)
    }

    @Test fun aCoalescedEvidenceRevisionAndALateOldAssessmentCannotResurrectTheMemo() {
        val c = candidate("memoConcurrent8870")
        val key = "LIVE|OUTSIDE|${c.mint}|${c.lane}|73"
        val refusal = Admission.decide8136(c)
        val revision = FeatureRevision8283.revision8283(c.mint)
        Admission.rememberMemo8870(key, c, refusal, now, revision)
        // Same path as a producer whose next wake was coalesced: revision advances without explicit cache removal.
        FeatureRevision8283.advance8283(c.mint, setOf(FeatureKind8283.PRICE), now + 1)
        assertNull(Admission.memoDecision8870(key, c, now + 2))
        Admission.rememberMemo8870(key, c, refusal, now + 3, revision)
        assertNull("an assessment started before arrival is still old when it finishes later", Admission.memoDecision8870(key, c, now + 4))
        Admission.rememberMemo8870(key, c, refusal, now + 5)
        assertNotNull("an assessment of the current revision is reusable", Admission.memoDecision8870(key, c, now + 6))
        Admission.evidenceArrived8252(c.mint, setOf("price"), now + 7)
    }

    @Test fun aNoWakeRevisionAlsoInvalidatesTheHeldDeferBehindTheOrdinaryMemo() {
        val mint = "heldRevision8870"
        val lane = "SHITCOIN"
        val missing = listOf("tape:NO_PRINTS")
        val c = Candidate8136(mint, lane, null, missingInputs = missing)
        Admission.holdDeferForTest8252(mint, lane, missing, now)
        assertNotNull(Admission.coalescedDefer8144(mint, lane, c, 1L, "LIVE", now + 1))
        FeatureRevision8283.advance8283(mint, setOf(FeatureKind8283.TAPE), now + 2)
        assertFalse("the source deliberately did not wake the backoff", Admission.reevalDueForTest8252(mint, "tape:NO_PRINTS", now + 3))
        assertNull("changed evidence must reach a real assessment through both caches",
            Admission.coalescedDefer8144(mint, lane, c, 1L, "LIVE", now + 3))
    }

    @Test fun spentWaitBelongsToItsLaneBookAndQuestion() {
        val mint = "waitIsolation8870"
        val missing = listOf("price")
        assertTrue(Admission.deferBudgetForLane8870(mint, "MOONSHOT", "LIVE", missing, now))
        assertFalse(Admission.deferBudgetForLane8870(mint, "MOONSHOT", "LIVE", missing, now + 601_000))
        assertTrue("another lane has not spent its wait", Admission.deferBudgetForLane8870(mint, "EXPRESS", "LIVE", missing, now + 602_000))
        assertTrue("the paper book has not spent its wait", Admission.deferBudgetForLane8870(mint, "MOONSHOT", "PAPER", missing, now + 602_000))
        assertTrue("a shadow rehearsal has its own wait", Admission.deferBudgetForLane8870(mint, "MOONSHOT", "LIVE", missing, now + 602_000, shadowOnly = true))
        assertTrue("a measured move has its own answered/unanswered state", Admission.deferBudgetForLane8870(mint, "MOONSHOT", "LIVE",
            listOf("move:HISTORY_SPARSE"), now + 602_000, owner = DataWaitOwner8870.MOVE))
        Admission.finishDataWait8870(mint, "MOONSHOT", "LIVE", Terminal8136.ADMIT, shadowOnly = true, assessedAtMs = now + 603_000)
        assertFalse("shadow completion cannot renew live", Admission.deferBudgetForLane8870(mint, "MOONSHOT", "LIVE", missing, now + 603_000))
        assertTrue(Admission.deferUnansweredForLane8870(mint, "MOONSHOT", "LIVE", missing, now + 603_000))
    }

    @Test fun actualCompletedAssessmentEndsWaitAndLaterGapStartsANewBoundedEpisode() {
        val mint = "waitAnswered8870"
        val missing = listOf("price")
        assertTrue(Admission.deferBudgetForLane8870(mint, "CORE", "LIVE", missing, now))
        assertFalse(Admission.deferBudgetForLane8870(mint, "CORE", "LIVE", missing, now + 601_000))
        Admission.finishDataWait8870(mint, "CORE", "LIVE", Terminal8136.DEFER_DATA, assessedAtMs = now + 602_000)
        assertFalse("repeated pending assessments cannot refill it", Admission.deferBudgetForLane8870(mint, "CORE", "LIVE", missing, now + 602_000))
        Admission.finishDataWait8870(mint, "CORE", "LIVE", Terminal8136.ADMIT, assessedAtMs = now + 601_500)
        assertFalse("an older completed assessment cannot clear the newer wait", Admission.deferBudgetForLane8870(mint, "CORE", "LIVE", missing, now + 602_500))
        Admission.finishDataWait8870(mint, "CORE", "LIVE", Terminal8136.REFUSE, assessedAtMs = now + 603_000)
        assertFalse("the old unavailability claim ends with the actual judgement", Admission.deferUnansweredForLane8870(mint, "CORE", "LIVE", missing, now + 603_000))
        assertTrue("a later gap gets its own ordinary window", Admission.deferBudgetForLane8870(mint, "CORE", "LIVE", missing, now + 604_000))
        assertFalse("the new window is bounded too", Admission.deferBudgetForLane8870(mint, "CORE", "LIVE", missing, now + 1_205_000))
    }

    @Test fun noticesWithoutResolvedFactsCannotRefillASpentWaitOrClearTheMoveWait() {
        val mint = "waitNoRefill8870"
        val missing = listOf("price")
        assertTrue(Admission.deferBudgetForLane8870(mint, "SHITCOIN", "LIVE", missing, now))
        assertTrue(Admission.deferBudgetForLane8870(mint, "SHITCOIN", "LIVE", listOf("move:HISTORY_SPARSE"), now,
            owner = DataWaitOwner8870.MOVE))
        repeat(3) { n ->
            val at = now + 601_000L + n * 1_000L
            Admission.evidenceArrived8252(mint, setOf("price"), at)
            assertFalse(Admission.deferBudgetForLane8870(mint, "SHITCOIN", "LIVE", missing, at))
        }
        Admission.finishDataWait8870(mint, "SHITCOIN", "LIVE", Terminal8136.ADMIT, assessedAtMs = now + 604_000)
        assertFalse("admission cannot silently restart a later measured-history wait",
            Admission.deferBudgetForLane8870(mint, "SHITCOIN", "LIVE", listOf("move:HISTORY_SPARSE"), now + 605_000,
                owner = DataWaitOwner8870.MOVE))
        Admission.completeDataWait8870(mint, "SHITCOIN", "LIVE", owner = DataWaitOwner8870.MOVE, assessedAtMs = now + 606_000)
        assertTrue(Admission.deferBudgetForLane8870(mint, "SHITCOIN", "LIVE", listOf("move:HISTORY_SPARSE"), now + 606_000,
            owner = DataWaitOwner8870.MOVE))
    }

    @Test fun olderProgressCannotMutateDependenciesOrRestartANewerAssessmentWhileItWaitsForTheKey() {
        val mint = "waitAtomicProgress8870"
        val missing = listOf("price", "liquidity:UNKNOWN")
        assertTrue(Admission.deferBudgetForLane8870(mint, "SHITCOIN", "LIVE", missing, now))
        val defers = waitMap8870<Admission.Defer8136>("defers8136")
        val dependencies = waitMap8870<Set<String>>("firstMissing8204")
        val key = defers.keys.single { it.endsWith("|$mint") }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val newer = Thread {
            try {
                defers.compute(key) { _, current ->
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    current!!.copy(assessedAtMs8870 = now + 200L)
                }
            } catch (e: Throwable) { failure.compareAndSet(null, e) }
        }
        val older = Thread {
            try { Admission.deferBudgetForLane8870(mint, "SHITCOIN", "LIVE", listOf("price"), now + 100L) }
            catch (e: Throwable) { failure.compareAndSet(null, e) }
        }
        try {
            newer.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            older.start()
            awaitBlocked8870(older)
            assertEquals("dependencies must change under the same lock as their timer", missing.toSet(), dependencies[key])
        } finally {
            release.countDown()
            newer.join(5_000L)
            older.join(5_000L)
        }
        try {
            assertFalse(newer.isAlive)
            assertFalse(older.isAlive)
            assertNull(failure.get())
            assertEquals(now, defers[key]!!.firstMs)
            assertEquals(now + 200L, defers[key]!!.assessedAtMs8870)
            assertEquals(missing.toSet(), dependencies[key])
        } finally { defers.remove(key); dependencies.remove(key) }
    }

    @Test fun completionKeepsTheKeyUntilItsOldDependenciesAreRemovedBeforeANewGapCanStart() {
        val mint = "waitAtomicComplete8870"
        assertTrue(Admission.deferBudgetForLane8870(mint, "SHITCOIN", "LIVE", listOf("price"), now))
        val defers = waitMap8870<Admission.Defer8136>("defers8136")
        val dependencies = waitMap8870<Set<String>>("firstMissing8204")
        val key = defers.keys.single { it.endsWith("|$mint") }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val heldDependencies = Thread {
            try {
                dependencies.compute(key) { _, current ->
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    current
                }
            } catch (e: Throwable) { failure.compareAndSet(null, e) }
        }
        val completion = Thread {
            try { Admission.finishDataWait8870(mint, "SHITCOIN", "LIVE", Terminal8136.ADMIT, assessedAtMs = now + 100L) }
            catch (e: Throwable) { failure.compareAndSet(null, e) }
        }
        val nextGap = Thread {
            try { Admission.deferBudgetForLane8870(mint, "SHITCOIN", "LIVE", listOf("liquidity:UNKNOWN"), now + 200L) }
            catch (e: Throwable) { failure.compareAndSet(null, e) }
        }
        try {
            heldDependencies.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            completion.start()
            awaitBlocked8870(completion)
            assertNotNull("completion cannot expose a timer-less half-deleted episode", defers[key])
            nextGap.start()
            awaitBlocked8870(nextGap)
        } finally {
            release.countDown()
            heldDependencies.join(5_000L)
            completion.join(5_000L)
            nextGap.join(5_000L)
        }
        try {
            assertFalse(heldDependencies.isAlive)
            assertFalse(completion.isAlive)
            assertFalse(nextGap.isAlive)
            assertNull(failure.get())
            assertEquals(now + 200L, defers[key]!!.firstMs)
            assertEquals(setOf("liquidity:UNKNOWN"), dependencies[key])
            assertTrue(Admission.deferUnansweredForLane8870(mint, "SHITCOIN", "LIVE", listOf("liquidity:UNKNOWN"), now + 201L))
        } finally { defers.remove(key); dependencies.remove(key) }
    }
}
