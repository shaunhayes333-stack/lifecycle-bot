package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.cortex.CortexAudit8169
import com.lifecyclebot.engine.cortex.CortexLabelHygiene8144
import com.lifecyclebot.engine.cortex.CrossAssetCortex7931
import com.lifecyclebot.engine.truth.CandidateMarkFresh8094
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.ObservedMark8239
import com.lifecyclebot.engine.truth.WindowJobBudget8239
import com.lifecyclebot.engine.truth.CanonicalTokenAmount
import com.lifecyclebot.network.CandidateTapeLease8239
import com.lifecyclebot.network.WalletTokenAccountTotals8239
import com.lifecyclebot.perps.DynamicAltTokenRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 8239: learning uses dated, executable evidence; bounded data work can always retry after a refusal. */
class Aate8239LearningDataTruthTest {
    private val t0 = 1_800_000_000_000L

    @Test fun unknownNativeAcceptanceRemainsObservedOnlyEvenWithASoftReason() {
        val h = CortexLabelHygiene8144
        for (reason in listOf<String?>(null, "EDGE_GATE_SOFT_SCORE", "NO_TRIGGER", "DATA_INCOMPLETE_DEFER_8136")) {
            assertFalse(h.executableDecision8144(false, reason))
            assertFalse(h.executableDecision8144(false, reason, false))
            assertFalse(h.assess8144(false, reason, null, t0).authority)
        }
        assertTrue(h.executableDecision8144(false, "EDGE_GATE_SOFT_SCORE", true))
        assertFalse(h.executableDecision8144(false, "ROUTE_UNAVAILABLE", true))
        assertFalse(h.executableDecision8144(false, "NATIVE_MCAP_TOO_HIGH", true))
        // A sealed admission retains actual execution evidence independent of a legacy reason string.
        assertTrue(h.executableDecision8144(true, "LEGACY_SAFETY_REASON", null))
    }

    @Test fun slowMutableVoterReadCannotBeBackdatedToItsRequest() {
        val completed = Cortex7885.readCompletedAt8239(t0, t0 + 20_700L)
        assertEquals(t0 + 20_700L, completed)
        assertTrue(CortexAudit8169.voterReadLeaks8169(completed, t0, 5_000L))
        assertFalse(CortexAudit8169.voterReadLeaks8169(Cortex7885.readCompletedAt8239(t0, t0 + 900L), t0, 5_000L))
        // A backwards wall clock cannot place the completed read before its request.
        assertEquals(t0, Cortex7885.readCompletedAt8239(t0, t0 - 1L))
        assertFalse(Cortex7885.persistablePending8144(t0 + 1L, t0))
    }

    @Test fun readingACacheDoesNotRefreshItsSourceTimestampOrPriceAHorizonEarly() {
        val mark = ObservedMark8239(1.25, t0, "ACTUAL_POOL_OBSERVATION")
        assertTrue(mark.fresh(t0 + 45_000L, 45_000L))
        assertFalse(mark.fresh(t0 + 45_001L, 45_000L))
        assertFalse(mark.fresh(t0 + 120_000L, 45_000L))
        assertFalse(mark.fresh(t0 - 1L, 45_000L))
        assertFalse(mark.atHorizon(t0, 60_000L, 15_000L))
        val after = mark.copy(observedAtMs = t0 + 60_001L)
        assertTrue(after.atHorizon(t0, 60_000L, 15_000L))
        assertFalse(after.copy(observedAtMs = t0 + 75_001L).atHorizon(t0, 60_000L, 15_000L))
        assertEquals(t0, mark.observedAtMs)
    }

    @Test fun crossAssetCandidateCreationRetainsTheMatchingMarketObservationTime() {
        val actual = ObservedMark8239(1.25, t0 - 10_000L, "ACTUAL_ASSET_FEED")
        val resolver = CrossAssetCortex7931
        assertEquals(actual, resolver.candidateObservation8239(1.25, listOf(actual), t0))
        assertNull(resolver.candidateObservation8239(1.50, listOf(actual), t0))
        assertNull(resolver.candidateObservation8239(1.25, listOf(actual.copy(observedAtMs = t0 + 1)), t0))
        assertNull(resolver.candidateObservation8239(1.25, listOf(actual.copy(observedAtMs = t0 - 120_001)), t0))
        assertNull(resolver.candidateObservation8239(1.25, emptyList(), t0))
    }

    @Test fun dynamicMetadataRefreshCannotRenewAStoredMarketPrice() {
        val registry = DynamicAltTokenRegistry
        val token = DynamicAltTokenRegistry.DynToken("mint", "TEST", "Test", price = 1.25,
            lastUpdatedMs = t0 - 120_000L, priceObservedAtMs8239 = t0 - 120_000L)
        val metadataOnly = token.copy(logoUrl = "new-logo", isTrending = true, lastUpdatedMs = t0)
        assertNull(registry.observedPrice8239(metadataOnly, t0))
        val fresh = metadataOnly.copy(priceObservedAtMs8239 = t0 - 1_000L)
        assertEquals(t0 - 1_000L, registry.observedPrice8239(fresh, t0)?.observedAtMs)
        assertNull(registry.observedPrice8239(fresh.copy(priceObservedAtMs8239 = 0L), t0))
        assertEquals(fresh.priceObservedAtMs8239, registry.mergedPriceTime8239(fresh, metadataOnly.copy(price = 0.0)))
        assertEquals(0L, registry.mergedPriceTime8239(fresh, metadataOnly.copy(price = 2.0, priceObservedAtMs8239 = 0L)))
    }

    @Test fun invalidNumericAndUnknownMarksHaveTheSameBoundedDeadlineAsMissingMarks() {
        for (price in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0)) {
            assertFalse(ObservedMark8239(price, t0, "POOL").fresh(t0, 45_000L))
        }
        assertFalse(ObservedMark8239(1.0, 0L, "POOL").fresh(t0, 45_000L))
        assertFalse(ObservedMark8239(1.0, t0, "").fresh(t0, 45_000L))
        val labeler = ForwardReturnLabeler7731
        assertNull(labeler.unavailableHorizon8239(0L, false, false))
        assertEquals(60, labeler.unavailableHorizon8239(24L * 3_600_000L, false, false))
        assertEquals(240, labeler.unavailableHorizon8239(24L * 3_600_000L, true, false))
        assertNull(labeler.unavailableHorizon8239(24L * 3_600_000L, true, true))
        assertTrue(CandidateMarkFresh8094.stale8094(1.0, t0 + 1L, t0))
        assertFalse(CandidateMarkFresh8094.stale8094(1.0, t0, t0 + 1L))
    }

    @Test fun aSaturatedExecutorReleasesRejectedKeysAndNeverRunsThemOnTheCaller() {
        val pool = ThreadPoolExecutor(1, 1, 1L, TimeUnit.SECONDS,
            ArrayBlockingQueue<Runnable>(1), ThreadPoolExecutor.AbortPolicy())
        val finished = CountDownLatch(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ran = AtomicInteger(0)
        val thread = AtomicReference<String>()
        val admission = KeyedTaskAdmission8239(Executor { work ->
            pool.execute { try { work.run() } finally { finished.countDown() } }
        })
        try {
            assertEquals(KeyedTaskAdmission8239.Result.ACCEPTED, admission.submit("active") {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                ran.incrementAndGet()
            })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(KeyedTaskAdmission8239.Result.ACCEPTED, admission.submit("queued") { ran.incrementAndGet() })
            assertEquals(KeyedTaskAdmission8239.Result.COALESCED, admission.submit("queued") { fail("coalesced work must not run") })
            assertEquals(KeyedTaskAdmission8239.Result.REJECTED, admission.submit("retry") { fail("rejected work must not run") })
            assertFalse(admission.pending("retry"))
            assertEquals(2, admission.size())
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(0, admission.size())
            assertEquals(KeyedTaskAdmission8239.Result.ACCEPTED, admission.submit("retry") {
                thread.set(Thread.currentThread().name)
                ran.incrementAndGet()
            })
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals(3, ran.get())
            assertEquals(0, admission.size())
            assertNotEquals(Thread.currentThread().name, thread.get())
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun aFailedWorkerReleasesItsKeyBeforeAnotherSubmission() {
        val work = ArrayList<Runnable>()
        val admission = KeyedTaskAdmission8239(Executor { work.add(it) })
        assertEquals(KeyedTaskAdmission8239.Result.ACCEPTED, admission.submit("broken") { throw IllegalStateException("worker failed") })
        try { work.removeAt(0).run(); fail("worker failure expected") } catch (_: IllegalStateException) {}
        assertFalse(admission.pending("broken"))
        assertEquals(KeyedTaskAdmission8239.Result.ACCEPTED, admission.submit("broken") {})
        work.removeAt(0).run()
        assertEquals(0, admission.size())
    }

    @Test fun creditRefusalsDoNotSpendTheEightCandleJobSlots() {
        val budget = WindowJobBudget8239(8, 180)
        repeat(200) { assertFalse(budget.claim(t0) { false }) }
        assertEquals(0, budget.used())
        repeat(8) { assertTrue(budget.claim(t0) { true }) }
        assertEquals(8, budget.used())
        assertFalse(budget.claim(t0) { fail("scheduler full must not claim provider credits"); true })
        assertTrue(budget.claim(t0 + 60_000L) { true })
        budget.cancel(t0 + 60_000L)
        assertEquals(8, budget.used())
        assertTrue(budget.claim(t0 + 3_600_000L) { true })
        assertEquals(1, budget.used())
    }

    @Test fun candleJobHourlyCapStillBindsAcrossMinuteWindows() {
        val budget = WindowJobBudget8239(2, 3)
        assertTrue(budget.claim(t0) { true })
        assertTrue(budget.claim(t0) { true })
        assertTrue(budget.claim(t0 + 60_000L) { true })
        assertFalse(budget.claim(t0 + 120_000L) { true })
        assertEquals(3, budget.used())
    }

    @Test fun candidateTapeResidentsSurviveRepeatedDemandButYieldToHeldPositions() {
        val lease = CandidateTapeLease8239
        assertFalse(lease.mayReplace(false, false, t0, 1, 1, t0 + 1_000L))
        assertFalse(lease.mayReplace(false, false, t0, 1, 1, t0 + 44_999L))
        assertTrue(lease.mayReplace(false, false, t0, 1, 1, t0 + 45_000L))
        assertTrue(lease.mayReplace(false, false, t0, 1, 2, t0 + 5_000L))
        assertFalse(lease.mayReplace(false, false, t0, 2, 1, t0 + 60_000L))
        assertTrue(lease.mayReplace(false, false, t0, 2, 1, t0 + 180_000L))
        assertTrue(lease.mayReplace(false, true, t0, 2, 0, t0 + 1L))
        assertFalse(lease.mayReplace(true, true, t0, 2, 2, t0 + 600_000L))
        assertFalse(lease.mayReplace(false, false, t0, 0, 2, t0 - 1L))
        assertFalse(lease.mayReplace(false, false, 0L, 0, 2, t0))
    }

    @Test fun ownerEnumerationSumsSeparateAccountsWithoutLosingIntegerPrecision() {
        val totals = HashMap<String, CanonicalTokenAmount>()
        val reader = WalletTokenAccountTotals8239(totals)
        assertTrue(reader.record("mint", "18446744073709551615", 6))
        assertTrue(reader.record("mint", "9608150799", 6))
        assertTrue(reader.record("mint", "0", 6))
        assertEquals("18446744083317702414", totals.getValue("mint").raw.toString())
        assertEquals(6, totals.getValue("mint").decimals)
        assertTrue(reader.record("another", "1000", 9))
        assertEquals("1000", totals.getValue("another").raw.toString())
    }

    @Test fun ownerEnumerationCannotCallAConflictingOrMalformedSubtotalExact() {
        for (bad in listOf("" to 6, "NaN" to 6, "5.5" to 6, "-1" to 6, "1" to -1, "1" to 19, "1" to 9)) {
            val totals = HashMap<String, CanonicalTokenAmount>()
            val reader = WalletTokenAccountTotals8239(totals)
            assertTrue(reader.record("mint", "100", 6))
            assertFalse(reader.record("mint", bad.first, bad.second))
            assertFalse(totals.containsKey("mint"))
            // A later good row must not turn the known incomplete mint back into exact proof.
            assertFalse(reader.record("mint", "200", 6))
            assertFalse(totals.containsKey("mint"))
        }
        val totals = HashMap<String, CanonicalTokenAmount>()
        val reader = WalletTokenAccountTotals8239(totals)
        assertFalse(reader.record("", "1", 6))
        assertTrue(reader.record("zero", "0", 6))
        assertFalse(reader.record("zero", "1", 9))
        assertTrue(reader.record("frozen", "10", 6))
        assertTrue(reader.record("frozen", "20", 6, excluded = true))
        assertFalse(totals.containsKey("frozen"))
    }
}
