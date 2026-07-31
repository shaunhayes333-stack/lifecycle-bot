package com.lifecyclebot.engine.truth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5.0.6400 — hard-score-floor eradication regression suite.
 *
 * These tests protect the NO_HARD_SCORE_ENTRY_GATE invariant. A
 * failure here means a hard score-floor gate has been reintroduced.
 */
class Bundle6400NoHardScoreFloorTest {

    @Before fun setUp() {
        SoftScoreShaping6400.clearAllForTest()
        NoHardScoreEntryGateGuard6400.clearAllForTest()
    }
    @After fun tearDown() { setUp() }

    // -------- soft shaping math ------------------------------------------
    @Test fun size_multiplier_covers_all_score_bands_and_never_zero_reject() {
        assertEquals(0.55, SoftScoreShaping6400.sizeMultiplierFor(null), 1e-9)
        assertEquals(0.55, SoftScoreShaping6400.sizeMultiplierFor(Double.NaN), 1e-9)
        assertEquals(0.35, SoftScoreShaping6400.sizeMultiplierFor(-10.0), 1e-9)
        assertEquals(0.35, SoftScoreShaping6400.sizeMultiplierFor(0.0), 1e-9)
        assertEquals(0.45, SoftScoreShaping6400.sizeMultiplierFor(3.0), 1e-9)
        assertEquals(0.60, SoftScoreShaping6400.sizeMultiplierFor(7.0), 1e-9)
        assertEquals(0.80, SoftScoreShaping6400.sizeMultiplierFor(15.0), 1e-9)
        assertEquals(1.00, SoftScoreShaping6400.sizeMultiplierFor(25.0), 1e-9)
        // NONE of these ever produce zero — no low score is a rejection.
        for (s in listOf(-10.0, 0.0, 3.0, 7.0, 9.0, 15.0)) {
            assertTrue("score=$s must yield a positive size multiplier",
                SoftScoreShaping6400.sizeMultiplierFor(s) > 0.0)
        }
    }

    // -------- publish records low-score as ALLOWED, never rejected -------
    @Test fun publish_low_score_counts_as_allowed_not_rejected() {
        // A candidate with score=7 vs referenceFloor=22 — the old regression
        // signature. Under 6400 this MUST count as allowed+shaped, and the
        // forbidden counter must stay zero.
        val start = SoftScoreShaping6400.forbiddenScoreFloorRejectCount.get()
        val s = SoftScoreShaping6400.publish(
            mint = "MINT_LOW", symbol = "SYM", lane = "MOONSHOT",
            rawScore = 7.0, referenceFloor = 22.0)
        assertEquals(0.60, s.sizeMultiplier, 1e-9)
        assertTrue(s.softSignals.contains("LOW_SCORE_SIZE_REDUCTION"))
        assertEquals(1L, SoftScoreShaping6400.lowScoreAllowedCount.get())
        assertEquals(1L, SoftScoreShaping6400.lowScoreShapedCount.get())
        assertEquals(start, SoftScoreShaping6400.forbiddenScoreFloorRejectCount.get())
    }

    // -------- missing/null score does not fail closed --------------------
    @Test fun missing_score_yields_neutral_shaping_not_rejection() {
        val s = SoftScoreShaping6400.publish(
            mint = "MINT_MISSING", symbol = "?", lane = "SHITCOIN",
            rawScore = Double.NaN, referenceFloor = 22.0)
        assertFalse(s.scoreAvailable)
        assertEquals(0.55, s.sizeMultiplier, 1e-9)
        assertTrue(s.softSignals.contains("SCORE_UNAVAILABLE"))
        assertEquals(0L, SoftScoreShaping6400.forbiddenScoreFloorRejectCount.get())
    }

    // -------- startup invariant: NO_HARD_SCORE_ENTRY_GATE ----------------
    @Test fun startup_invariant_passes_by_default() {
        val r = NoHardScoreEntryGateGuard6400.check()
        assertTrue(r.passed)
        assertFalse(r.hardScoreGateActive)
        assertEquals(0L, r.scoreOnlyHardRejects)
        assertEquals("SOFT_SHAPING_ONLY", r.scorePolicy)
    }

    @Test fun startup_invariant_fails_loudly_on_forbidden_reject() {
        SoftScoreShaping6400.reportForbiddenScoreFloorReject(
            "MINT_REGRESS", callsite = "SomeExecutor.kt:123",
            floor = 22.0, score = 7.0)
        val r = NoHardScoreEntryGateGuard6400.check()
        assertFalse(r.passed)
        assertEquals(1L, r.scoreOnlyHardRejects)
        assertTrue(r.evidence.any { it.contains("forbiddenScoreFloorRejects") })
    }

    @Test fun startup_invariant_fails_when_hard_gate_flag_active() {
        NoHardScoreEntryGateGuard6400.setHardGateActive(true)
        val r = NoHardScoreEntryGateGuard6400.check()
        assertFalse(r.passed)
        assertTrue(r.hardScoreGateActive)
    }

    // -------- low-score candidates across all bands: none rejected -------
    @Test fun low_score_candidates_all_bands_remain_eligible() {
        for (score in listOf(-10.0, 0.0, 1.0, 3.0, 7.0, 9.0, 15.0)) {
            val s = SoftScoreShaping6400.publish(
                mint = "M_$score", symbol = "S", lane = "MOONSHOT",
                rawScore = score, referenceFloor = 22.0)
            assertTrue("score=$score must have positive size multiplier",
                s.sizeMultiplier > 0.0)
        }
        // ALL ended up allowed, zero forbidden hard-rejects.
        assertEquals(0L, SoftScoreShaping6400.forbiddenScoreFloorRejectCount.get())
        assertTrue(SoftScoreShaping6400.lowScoreAllowedCount.get() >= 5L)
    }

    // -------- forbidden-reject trip-wire fires with mint+callsite --------
    @Test fun forbidden_reject_trip_wire_records_callsite() {
        SoftScoreShaping6400.reportForbiddenScoreFloorReject(
            "MINT_TW", callsite = "LegacyExecutor.kt:2222",
            floor = 22.0, score = 5.0)
        assertEquals(1L, SoftScoreShaping6400.forbiddenScoreFloorRejectCount.get())
    }

    // -------- mechanical-minimum block is distinct from score reject -----
    @Test fun mechanical_min_block_is_distinct_from_score_reject() {
        SoftScoreShaping6400.recordMechanicalMinBlock()
        assertEquals(1L, SoftScoreShaping6400.lowScoreMechanicalBlockCount.get())
        // Never touches the forbidden score-reject counter.
        assertEquals(0L, SoftScoreShaping6400.forbiddenScoreFloorRejectCount.get())
    }
}
