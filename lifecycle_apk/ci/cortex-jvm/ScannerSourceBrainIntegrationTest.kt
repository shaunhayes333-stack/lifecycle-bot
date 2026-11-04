package com.lifecyclebot.engine

import android.content.Context
import org.junit.Assert.*
import org.junit.Test

/** Actual source-brain integration through the standalone harness's in-memory Android preference API. */
class ScannerSourceBrainIntegrationTest {
    private fun awaitCondition(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(1L)
        }
        assertTrue(message, predicate())
    }
    @Test fun sourceReaderNeverPoolsLegacyClosesWithTheSameDecisions() {
        ScannerSourceBrain.init(Context())
        val source = "SOURCE_8372_COHORT_ISOLATION"
        repeat(20) { ScannerSourceBrain.recordOutcome(source, -5.0) }
        assertEquals(20L, ScannerSourceBrain.sourceSnapshot7658(source)!!.samples)
        repeat(20) { i ->
            assertTrue(ScannerSourceBrain.recordDecisionOutcome8372(source, "isolation-token-$i", "isolation-owner-$i",
                "LIVE", 4.0, i + 1L))
        }
        val decision = ScannerSourceBrain.sourceSnapshot7658(source)!!
        assertEquals("DECISION_EPISODES", decision.evidence8372)
        assertEquals(20L, decision.samples)
        assertEquals(4.0, decision.avgPnlPct, 0.0)
        assertTrue(ScannerSourceBrain.intakeMultiplier(source) > 1.0)
        repeat(20) { i ->
            assertTrue(ScannerSourceBrain.reviseDecisionOutcome8372(source, "isolation-token-$i", "isolation-owner-$i",
                "LIVE", -4.0, i + 1L))
        }
        val corrected = ScannerSourceBrain.sourceSnapshot7658(source)!!
        assertEquals(20L, corrected.samples)
        assertEquals(-4.0, corrected.avgPnlPct, 0.0)
        assertEquals(0.0, corrected.winRate, 0.0)
        assertTrue(ScannerSourceBrain.intakeMultiplier(source) < 1.0)
    }

    @Test fun newNoisyCohortKeepsExistingSourceAuthorityAndPurePathwaysStayNeutral() {
        ScannerSourceBrain.init(Context())
        val source = "SOURCE_8372_NOISY_COHORT"
        repeat(20) { ScannerSourceBrain.recordOutcome(source, 2.0) }
        repeat(20) { i ->
            ScannerSourceBrain.recordDecisionOutcome8372(source, "noisy-token-$i", "noisy-owner-$i", "LIVE",
                if (i == 0) 1000.0 else -10.0, 100 + i.toLong())
        }
        val active = ScannerSourceBrain.sourceSnapshot7658(source)!!
        assertEquals("LEGACY_MIXED", active.evidence8372)
        assertEquals(20L, active.samples)
        assertEquals(2.0, active.avgPnlPct, 0.0)
        assertEquals(20L, active.decisionSamples8372)
        assertNull(ScannerSourceBrain.sourceSnapshot7658("SCANNER_DIRECT"))
        assertEquals(1.0, ScannerSourceBrain.intakeMultiplier("SCANNER_DIRECT"), 0.0)
    }

    @Test fun failedPersistenceCannotAcknowledgeShadowSourceDelivery() {
        val context = Context()
        val prefs = context.getSharedPreferences("scanner_source_brain", Context.MODE_PRIVATE)
        prefs.commitSucceeds = false
        ScannerSourceBrain.init(context)
        val source = "SOURCE_8372_ACK"
        val token = "ack-token"
        val owner = "ack-token|EXPRESS|10000"
        assertTrue(ScannerSourceBrain.recordDecisionOutcome8372(source, token, owner, "LIVE", -4.0, 10_000L))
        assertFalse(ScannerSourceBrain.decisionDeliveryAcknowledged8372(source, token, owner, "LIVE", -4.0, 10_000L))
        awaitCondition("Background commit was attempted") { prefs.commitAttempts.get() > 0 }
        assertFalse(ScannerSourceBrain.decisionDeliveryAcknowledged8372(source, token, owner, "LIVE", -4.0, 10_000L))
        prefs.commitSucceeds = true
        awaitCondition("Successful commit eventually acknowledges exact return") {
            ScannerSourceBrain.decisionDeliveryAcknowledged8372(source, token, owner, "LIVE", -4.0, 10_000L)
        }
        // An in-memory correction cannot reuse the earlier durable loss receipt.
        prefs.commitSucceeds = false
        assertTrue(ScannerSourceBrain.reviseDecisionOutcome8372(source, token, owner, "LIVE", 50.0, 10_000L))
        assertFalse(ScannerSourceBrain.decisionDeliveryAcknowledged8372(source, token, owner, "LIVE", 50.0, 10_000L))
        prefs.commitSucceeds = true
        awaitCondition("Corrected winner receives its own durable snapshot acknowledgement") {
            ScannerSourceBrain.decisionDeliveryAcknowledged8372(source, token, owner, "LIVE", 50.0, 10_000L)
        }
    }

    @Test fun legacySourceAndFamilyCannotInvertNetWinningRunnerReturns() {
        ScannerSourceBrain.init(Context())
        val source = "SOURCE_8527_RARE_RUNNERS"
        repeat(180) { ScannerSourceBrain.recordOutcome(source, -1.0) }
        repeat(20) { ScannerSourceBrain.recordOutcome(source, 20.0) }
        val read = ScannerSourceBrain.sourceSnapshot7658(source)!!
        assertEquals("LEGACY_MIXED", read.evidence8372)
        assertEquals(0.10, read.winRate, 0.0)
        assertEquals(1.10, read.avgPnlPct, 0.000001)
        assertEquals("Positive net returns must not be suppressed for low WR", 1.0,
            ScannerSourceBrain.intakeMultiplier(source), 0.0)
        assertEquals(1.0, ScannerSourceBrain.familyIntakeMultiplier6856(source), 0.0)
    }

    @Test fun legacySourceAndFamilyCannotRewardNetLosingHighWinRate() {
        ScannerSourceBrain.init(Context())
        val source = "SOURCE_8527_FREQUENT_SMALL_WINS"
        repeat(180) { ScannerSourceBrain.recordOutcome(source, 1.0) }
        repeat(20) { ScannerSourceBrain.recordOutcome(source, -20.0) }
        val read = ScannerSourceBrain.sourceSnapshot7658(source)!!
        assertEquals(0.90, read.winRate, 0.0)
        assertEquals(-1.10, read.avgPnlPct, 0.000001)
        assertEquals("Negative net returns must not get a positive WR multiplier", 1.0,
            ScannerSourceBrain.intakeMultiplier(source), 0.0)
        assertEquals(1.0, ScannerSourceBrain.familyIntakeMultiplier6856(source), 0.0)
    }

    @Test fun legacyFamilyUsesObservationWeightedMeanNotAverageOfSourceMeans() {
        ScannerSourceBrain.init(Context())
        val family = "SOURCE_8527_WEIGHTED"
        // A small all-winning source has the larger mean, but 100 losses dominate
        // the actual family: (20 * 10 - 100 * 3) / 120 = -0.83333.
        repeat(20) { ScannerSourceBrain.recordOutcome(family + "_SMALL", 10.0) }
        repeat(100) { ScannerSourceBrain.recordOutcome(family + "_LARGE", -3.0) }
        assertTrue("Averaging source means would incorrectly neutralize the losing family",
            ScannerSourceBrain.familyIntakeMultiplier6856(family) < 1.0)
    }

    @Test fun zeroAndInvalidLegacyMeanCannotGainAuthority() {
        ScannerSourceBrain.init(Context())
        val source = "SOURCE_8527_BREAK_EVEN"
        repeat(180) { ScannerSourceBrain.recordOutcome(source, 1.0) }
        repeat(20) { ScannerSourceBrain.recordOutcome(source, -9.0) }
        assertEquals(1.0, ScannerSourceBrain.intakeMultiplier(source), 0.0)
        assertEquals(1.0, ScannerSourceBrain.familyIntakeMultiplier6856(source), 0.0)
        for (mean in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0)) {
            assertEquals(0.0, ScannerSourceBrain.netConsistentCentred8527(0.90, mean), 0.0)
        }
        assertEquals(0.0, ScannerSourceBrain.netConsistentCentred8527(Double.NaN, 10.0), 0.0)
        assertEquals(0.0, ScannerSourceBrain.netConsistentCentred8527(Double.POSITIVE_INFINITY, 10.0), 0.0)
        assertTrue(ScannerSourceBrain.netConsistentCentred8527(0.90, 10.0) > 0.0)
        assertTrue(ScannerSourceBrain.netConsistentCentred8527(0.10, -10.0) < 0.0)
    }
}
