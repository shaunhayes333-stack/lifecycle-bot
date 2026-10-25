package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.*
import com.lifecyclebot.network.HeliusCurveTape8354
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Aate8386EvidenceReservationTest {
    @Test fun deniedCreditsReleaseStartSlotsAndRecoveryStartsImmediately() {
        val starts = ArrayList<Long>()
        val q = CandleAdmission8386(2, starts) { _, minute, hour -> minute < 4 && hour < 100 }
        repeat(100) {
            assertEquals("reserved", q.reserve("mint", 100_000L, 0))
            q.finish("mint") // exact cancellation path on credit refusal
        }
        assertTrue(starts.isEmpty())
        assertEquals("reserved", q.reserve("mint", 100_001L, 0))
        assertTrue(q.start("mint", 100_002L)); assertFalse(q.start("mint", 100_002L))
        assertEquals(listOf(100_002L), starts)
        q.finish("mint"); assertEquals(1, starts.size) // started slots never refunded
    }
    @Test fun concurrentReservationsRespectWorkerAndMintBounds() {
        val q = CandleAdmission8386(2, ArrayList()) { _, _, _ -> true }
        val pool = Executors.newFixedThreadPool(8)
        val go = CountDownLatch(1)
        try {
            val results = (0 until 32).map { i -> pool.submit<String> { go.await(); q.reserve("mint$i", 100_000L, 3) } }
            go.countDown()
            assertEquals(2, results.count { it.get(10, TimeUnit.SECONDS) == "reserved" })
        } finally { pool.shutdownNow() }
        q.reset()
        assertEquals("reserved", q.reserve("same", 1, 3)); assertEquals("duplicate", q.reserve("same", 1, 3))
    }
    @Test fun onePageReconcilesTwoPageEstimateExactlyOnceAndUncertainSentWorkIsRetained() {
        val r = HeliusCreditEconomy7881.Reservation8386(HeliusCreditEconomy7881.Consumer.SWAP_CANDLES, 200.0, "today")
        r.sent8386(100.0)
        assertEquals(100.0, r.unused8386()!!, 0.0); assertNull(r.unused8386())
        val rejected = HeliusCreditEconomy7881.Reservation8386(HeliusCreditEconomy7881.Consumer.SWAP_CANDLES, 200.0, "today")
        assertEquals(200.0, rejected.unused8386()!!, 0.0)
    }
    private fun mark(px: Double, at: Long, proof: Boolean = false, decimals: Int = 6) =
        LabelMark8386("MintA", "venue", px, at, at + 100, "TEST", decimals = decimals, independentBasis = proof)
    @Test fun cachedTimestampCannotBecomeSynchronousByBeingReadAgain() {
        val m = mark(1.0, 40_000L).copy(readAtMs = 100_000L)
        assertEquals(40_000L, m.observedAtMs)
        assertEquals(2, ForwardReturnLabeler7731.curveWitness8337(m.usd, 3.0, m.observedAtMs, 100_000L))
        assertEquals(1, ForwardReturnLabeler7731.curveWitness8337(1.0, 3.0, 100_000L, 100_000L))
    }
    @Test fun atomicRebaseAndRestoreRetainDecisionAndRunnerBasis() {
        val original = LabelBasis8386(mark(1.0, 1_000, true), 1_000.0)
        val rebased = original.rebase(mark(3.0, 6_000, true))
        val restored = LabelBasis8386.decode(rebased.json())
        assertEquals(3.0, restored.mark.usd, 0.0); assertEquals(3_000.0, restored.capUsd, 0.0)
        assertEquals(1.0, original.mark.usd, 0.0)
        for (multiple in listOf(100.0, 1000.0)) {
            val outcome = mark(3.0 * multiple, 60_000, true)
            assertTrue(LabelEvidence8386.eligible(restored.mark, outcome))
            assertEquals((multiple - 1) * 100, (outcome.usd / restored.mark.usd - 1) * 100, 0.0)
        }
    }
    @Test fun invalidUnitsDecimalsUnknownTimeAndCorrelatedCapsCannotValidateBasis() {
        val entry = mark(1.0, 1_000)
        assertFalse(LabelEvidence8386.eligible(entry, mark(1000.0, 60_000)))
        assertFalse(LabelEvidence8386.eligible(entry, mark(1.0, 60_000).copy(units = "SOL_PER_TOKEN")))
        assertFalse(LabelEvidence8386.eligible(entry, mark(1.0, 60_000, decimals = 9)))
        assertFalse(LabelEvidence8386.eligible(entry.copy(observedAtMs = 0), mark(1.0, 60_000)))
        assertTrue(LabelEvidence8386.eligible(entry, mark(0.9, 60_000)))
    }
    @Test fun proposalRequiresAdditivePointsAndExitScopeNotRelativePercentOrEntryFilter() {
        fun check(unit: String, scope: String, semantics: String, effect: String) =
            LlmParameterTuner.proposalError8386("exitScoreThreshold", -0.05, unit, scope, semantics, effect)
        assertNull(check("score_points", "exit", "additive", "easier_exit"))
        assertNotNull(check("percent", "exit", "relative", "easier_exit"))
        assertNotNull(check("score_points", "entry", "additive", "filter_weak_entries"))
        assertTrue(LlmParameterTuner.explain8386("exitScoreThreshold", 69.7749, 69.7249).contains("effective mode change not asserted"))
        val evidence = com.lifecyclebot.engine.truth.AutoPipelineAdvisor6462.evidenceSections8386("x".repeat(15_000) + "\nEvidence shadow: n=40 positive\nrealised: LIVE n=3 negative")
        assertTrue(evidence.contains("n=40")); assertTrue(evidence.contains("LIVE n=3"))
    }
    @Test fun acknowledgementsAndStalePrintsAreNotFreshDelivery() {
        assertFalse(HeliusCurveTape8354.freshEvent8386(0, 0, 100_000))
        assertFalse(HeliusCurveTape8354.freshEvent8386(60_000, 50_000, 100_000))
        assertFalse(HeliusCurveTape8354.freshEvent8386(90_000, 95_000, 100_000))
        assertTrue(HeliusCurveTape8354.freshEvent8386(100_000, 95_000, 100_000))
    }
}
