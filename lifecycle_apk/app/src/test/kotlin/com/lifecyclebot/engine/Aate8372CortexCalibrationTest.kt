package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexCalibration7901
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8372 — preserve learned evidence, but predict arithmetic net profit rather than clipped geometric utility. */
class Aate8372CortexCalibrationTest {

    @Test fun aProfitableRunnerTailKeepsItsArithmeticNetReturn() {
        val scores = List(100) { 7.0 }
        val outcomes = List(10) { 800.0 } + List(90) { -15.0 }
        val net = CortexCalibration7901.fitNet8372(scores, outcomes)
        val logUtility = CortexCalibration7901.fitIso8366(scores, outcomes)
        // The same observed cohort: 10% * +800% + 90% * -15% = +66.5% arithmetic net return.
        assertTrue(net.valid)
        assertTrue(net.measured)
        assertTrue(net.flat) // A known cohort mean does not itself prove per-token ranking.
        assertEquals(100, net.n)
        assertEquals(1, net.xs.size)
        assertEquals(66.5, net.expectedNetPct8372(7.0), 1e-10)
        // 8366 clipped +800% to +300%, then exponentiated a mean logarithm: a different objective, below zero here.
        assertTrue(logUtility.expectedPct8366(7.0) < 0.0)
        val geometric = (kotlin.math.exp(0.1 * kotlin.math.ln(4.0) + 0.9 * kotlin.math.ln(0.85)) - 1.0) * 100.0
        assertEquals(geometric, logUtility.expectedPct8366(7.0), 1e-10)
    }

    @Test fun identicalScoresHaveOnePredictionWhateverTheArrivalOrder() {
        val pairs = List(10) { 0.0 to -20.0 } +
            listOf(1.0 to 800.0) + List(9) { 1.0 to -15.0 } +
            List(10) { 2.0 to 100.0 }
        val expected = CortexCalibration7901.fitNet8372(pairs.map { it.first }, pairs.map { it.second })
        val orders = listOf(pairs, pairs.reversed(), pairs.drop(13) + pairs.take(13), pairs.sortedByDescending { it.second })
        for (order in orders) {
            val fit = CortexCalibration7901.fitNet8372(order.map { it.first }, order.map { it.second })
            assertTrue(fit.valid)
            assertEquals(3, fit.xs.size)
            assertArrayEquals(expected.xs, fit.xs, 0.0)
            assertArrayEquals(expected.ys, fit.ys, 0.0)
            assertEquals(66.5, fit.expectedNetPct8372(1.0), 1e-10)
        }
        // Pooling weights are observation counts, not the number of unique predictor scores.
        val pooled = CortexCalibration7901.fitNet8372(List(9) { 0.0 } + listOf(1.0), List(9) { 10.0 } + listOf(-10.0))
        assertEquals(1, pooled.xs.size)
        assertEquals(8.0, pooled.expectedNetPct8372(0.0), 1e-12)
    }

    @Test fun aLosingCohortRemainsNegativeWithoutAnySignInversion() {
        val fit = CortexCalibration7901.fitNet8372(List(80) { 4.0 }, List(80) { if (it % 2 == 0) -8.0 else -12.0 })
        assertTrue(fit.valid)
        assertTrue(fit.measured)
        assertEquals(-10.0, fit.expectedNetPct8372(-100.0), 1e-10)
        assertEquals(-10.0, fit.expectedNetPct8372(4.0), 1e-10)
        assertEquals(-10.0, fit.expectedNetPct8372(100.0), 1e-10)
    }

    @Test fun arithmeticPavaIsMonotoneAndInterpolatesInNetPercentagePoints() {
        val scores = listOf(0.0, 1.0, 2.0, 3.0)
        val outcomes = listOf(-20.0, 15.0, 5.0, 60.0)
        val fit = CortexCalibration7901.fitNet8372(scores, outcomes)
        assertTrue(fit.valid)
        assertArrayEquals(doubleArrayOf(0.0, 1.5, 3.0), fit.xs, 1e-12)
        assertArrayEquals(doubleArrayOf(-20.0, 10.0, 60.0), fit.ys, 1e-12)
        assertEquals(35.0, fit.expectedNetPct8372(2.25), 1e-12)
        var previous = Double.NEGATIVE_INFINITY
        repeat(81) { i ->
            val prediction = fit.expectedNetPct8372(-0.5 + i * 0.05)
            assertTrue(prediction >= previous - 1e-12)
            previous = prediction
        }
    }

    @Test fun theExistingConsistencyCheckUsesTheSameNetTargetWithoutErasingTokenPredictions() {
        val good = CortexCalibration7901()
        val flat = CortexCalibration7901()
        val inverted = CortexCalibration7901()
        repeat(80) { i ->
            val score = i.toDouble()
            good.learn("CORE", score, 0.0, 1_000.0 + score * 10.0, score)
            flat.learn("CORE", score, 0.0, 6.0, score)
            inverted.learn("CORE", score, 0.0, 40.0 - score, score)
        }
        // The old clipped-log target erased all the variation above +300%; arithmetic net returns still rank it.
        assertTrue(good.uncalibrated8366("CORE"))
        assertTrue(good.netFit8372("CORE")!!.calibrated)
        assertFalse(good.uncalibrated8372("CORE"))
        assertFalse(good.suspends8372("CORE", 0.5))
        // Positive expected return is not proof of discrimination, and failed rank proof is not a zero return.
        assertTrue(flat.uncalibrated8372("CORE"))
        assertTrue(flat.suspends8372("CORE", 0.5))
        assertEquals(6.0, flat.netFit8372("CORE")!!.expectedNetPct8372(40.0), 1e-12)
        assertTrue(inverted.uncalibrated8372("CORE"))
        assertTrue(inverted.netFit8372("CORE")!!.valid)
        // Before the existing sample floor, no new rank veto is introduced.
        val thin = CortexCalibration7901()
        thin.learn("CORE", 0.0, 0.0, 5.0, 0.0)
        assertFalse(thin.uncalibrated8372("CORE"))
        assertFalse(thin.suspends8372("CORE", 0.5))
    }

    @Test fun invalidNumbersAreNotInventedAsFlatZeroOutcomes() {
        val empty = CortexCalibration7901.fitNet8372(emptyList(), emptyList())
        assertFalse(empty.valid)
        assertFalse(empty.measured)
        assertTrue(empty.expectedNetPct8372(1.0).isNaN())
        val partial = CortexCalibration7901.fitNet8372(
            listOf(Double.NaN, 1.0, 2.0, 3.0, 4.0),
            listOf(20.0, Double.POSITIVE_INFINITY, -9.0, 11.0),
        )
        assertTrue(partial.valid)
        assertEquals(2, partial.n)
        assertTrue(partial.expectedNetPct8372(Double.NaN).isNaN())
        assertEquals(-9.0, partial.expectedNetPct8372(2.0), 0.0)
        assertEquals(11.0, partial.expectedNetPct8372(3.0), 0.0)
        assertNull(CortexCalibration7901().netFit8372("UNSEEN"))
    }

    @Test fun largeFiniteVerifiedReturnsAreNotCappedOrOverflowedBySumming() {
        val fit = CortexCalibration7901.fitNet8372(listOf(1.0, 1.0), listOf(1e308, 1e308))
        assertTrue(fit.valid)
        assertEquals(1e308, fit.expectedNetPct8372(1.0), 1e294)
        val book = CortexCalibration7901()
        book.learn("MOONSHOT", 0.0, 0.0, 5_000.123456789, 1.234567890123)
        val restored = CortexCalibration7901()
        restored.decode(book.encode())
        assertEquals(5_000.123456789, restored.netFit8372("MOONSHOT")!!.expectedNetPct8372(1.234567890123), 0.0)
        assertEquals(1.234567890123, restored.netFit8372("MOONSHOT")!!.xs.single(), 0.0)
    }

    @Test fun learningAndAnExactLateRevisionInvalidateTheArithmeticCacheWithoutAddingGrades() {
        val c = CortexCalibration7901()
        // Cold-start edge == prior still has a revision stamp (8371); the new arithmetic map must respect it.
        val firstStamp = c.learn("MOONSHOT", 0.0, 0.0, -15.0, 7.0)
        val firstFit = c.netFit8372("MOONSHOT")!!
        assertTrue(firstStamp.isFinite())
        assertEquals(-15.0, firstFit.expectedNetPct8372(7.0), 0.0)
        c.learn("MOONSHOT", 0.0, 0.0, -5.0, 7.0)
        val secondFit = c.netFit8372("MOONSHOT")!!
        assertNotSame(firstFit, secondFit)
        assertEquals(-10.0, secondFit.expectedNetPct8372(7.0), 1e-12)
        assertTrue(c.revise8322("MOONSHOT", 0.0, 0.0, -15.0, 800.0, firstStamp))
        val revised = c.netFit8372("MOONSHOT")!!
        assertNotSame(secondFit, revised)
        assertEquals(2, revised.n)
        assertEquals(397.5, revised.expectedNetPct8372(7.0), 1e-12)
        assertTrue(c.encode().getString(CortexCalibration7901.ISO_KEY_PREFIX_8366 + "MOONSHOT").startsWith("7.0:800.0:"))
    }

    @Test fun restartRebuildsFromExistingRawPairsAndPreservesTheirRevisionIdentity() {
        val original = CortexCalibration7901()
        var winnerStamp = Double.NaN
        repeat(100) { i ->
            val stamp = original.learn("SHITCOIN", 0.0, 0.0, if (i < 10) 800.0 else -15.0, 7.0)
            if (i == 0) winnerStamp = stamp
        }
        val saved = original.encode()
        val restored = CortexCalibration7901()
        restored.decode(saved)
        val before = restored.netFit8372("SHITCOIN")!!
        assertTrue(before.measured)
        assertEquals(100, before.n)
        assertEquals(66.5, before.expectedNetPct8372(7.0), 1e-10)
        assertEquals(saved.getString("SHITCOIN"), restored.encode().getString("SHITCOIN"))
        assertTrue(restored.revise8322("SHITCOIN", 0.0, 0.0, 800.0, 1_000.0, winnerStamp))
        assertEquals(100, restored.netFit8372("SHITCOIN")!!.n)
        assertEquals(68.5, restored.netFit8372("SHITCOIN")!!.expectedNetPct8372(7.0), 1e-10)
        // Another original decision is not rewritten, and the original in-memory book was not aliased by decode.
        assertEquals(66.5, original.netFit8372("SHITCOIN")!!.expectedNetPct8372(7.0), 1e-10)
    }

    @Test fun anOldRawPairRingLoadsDirectlyWithoutAResetOrManufacturedObservations() {
        val old = JSONObject()
            .put("QUALITY", "5.0,1.0,2.0,1.0,2.0")
            .put(CortexCalibration7901.ISO_KEY_PREFIX_8366 + "QUALITY", "5.0:800.0:-0.1;5.0:-15.0:-0.2")
        val c = CortexCalibration7901()
        c.decode(old)
        assertEquals(2, c.netFit8372("QUALITY")!!.n)
        assertEquals(392.5, c.netFit8372("QUALITY")!!.expectedNetPct8372(5.0), 1e-12)
        assertFalse(c.netFit8372("QUALITY")!!.measured)
        assertEquals(5.0, c.encode().getString("QUALITY").split(',')[0].toDouble(), 0.0)
        // Both diagnostic and arithmetic views use exactly the same historical evidence.
        assertEquals(2, c.isoFit8366("QUALITY")!!.n)
    }

    @Test fun restoreMergeInvalidatesTheFitAndCopiesRatherThanSharesRawPairs() {
        val receiving = CortexCalibration7901()
        receiving.learn("CORE", 0.0, 0.0, -15.0, 1.0)
        assertEquals(-15.0, receiving.netFit8372("CORE")!!.expectedNetPct8372(1.0), 0.0)
        val recovered = CortexCalibration7901()
        recovered.learn("CORE", 0.0, 0.0, 10.0, 1.0)
        val lastStamp = recovered.learn("CORE", 0.0, 0.0, 20.0, 1.0)
        receiving.mergeFrom8324(recovered)
        assertEquals(2, receiving.netFit8372("CORE")!!.n)
        assertEquals(15.0, receiving.netFit8372("CORE")!!.expectedNetPct8372(1.0), 0.0)
        assertTrue(recovered.revise8322("CORE", 0.0, 0.0, 20.0, 40.0, lastStamp))
        assertEquals(25.0, recovered.netFit8372("CORE")!!.expectedNetPct8372(1.0), 0.0)
        assertEquals(15.0, receiving.netFit8372("CORE")!!.expectedNetPct8372(1.0), 0.0)
    }

    // superseded by V5.0.8418 — the three 8372/8404 shadow-basis tests (forwardAndAlreadyExecutableShadowOutcomesPayTheGapExactlyOnce,
    // arithmeticCacheIncludesTheExecutionOffsetAndOnlyForwardRowsChange, aSelectedShadowRevisionChangesBasisWithoutAddingASecondOutcome)
    // pinned a shadow exit TRAINING the live calibration with no execution offset charged. The live calibration now learns forward
    // labels only; a stored legacy shadow pair is kept on disk and never fitted.
    @Test fun forwardAndAlreadyExecutableShadowOutcomesPayTheGapExactlyOnce() {
        val c = CortexCalibration7901()
        c.learn("CORE", 0.0, 0.0, 2.0, 7.0) // Forward net still owes the three-point fill gap.
        assertTrue("a shadow exit never trains the live calibration",
            c.learn("CORE", 0.0, 0.0, -1.0, 7.0, CortexCalibration7901.TARGET_SHADOW_NET_8372).isNaN())
        assertEquals(-1.0, c.netFit8372("CORE", executionOffset = 3.0)!!.expectedNetPct8372(7.0), 1e-12)
        assertEquals(1, c.netFit8372("CORE", 3.0)!!.n)
        val raw = c.encode().getString(CortexCalibration7901.ISO_KEY_PREFIX_8366 + "CORE").split(';')
        assertEquals(1, raw.size)
        assertEquals(2.0, raw[0].split(':')[1].toDouble(), 0.0)
    }

    @Test fun arithmeticCacheIncludesTheExecutionOffsetAndOnlyForwardRowsChange() {
        val c = CortexCalibration7901()
        c.learn("CORE", 0.0, 0.0, 5.0, 1.0)
        c.learn("CORE", 0.0, 0.0, 4.0, 1.0, CortexCalibration7901.TARGET_SHADOW_NET_8372)   // refused
        val atTwo = c.netFit8372("CORE", 2.0)!!
        assertSame(atTwo, c.netFit8372("CORE", 2.0))
        assertEquals(3.0, atTwo.expectedNetPct8372(1.0), 0.0)
        val atFour = c.netFit8372("CORE", 4.0)!!
        assertNotSame(atTwo, atFour)
        assertEquals(1.0, atFour.expectedNetPct8372(1.0), 0.0)
        val shadow = CortexCalibration7901()
        shadow.learn("CORE", 0.0, 0.0, -1.0, 1.0, CortexCalibration7901.TARGET_SHADOW_NET_8372)
        assertNull(shadow.netFit8372("CORE", 50.0))
    }

    @Test fun aSelectedShadowRevisionChangesBasisWithoutAddingASecondOutcome() {
        val c = CortexCalibration7901()
        val stamp = c.learn("CORE", 0.0, 0.0, 2.0, 1.0)
        assertEquals(-1.0, c.netFit8372("CORE", 3.0)!!.expectedNetPct8372(1.0), 0.0)
        assertFalse("a shadow revision never rewrites a forward pair",
            c.revise8322("CORE", 0.0, 0.0, 2.0, -1.0, stamp, CortexCalibration7901.TARGET_SHADOW_NET_8372))
        assertEquals(-1.0, c.netFit8372("CORE", 3.0)!!.expectedNetPct8372(1.0), 0.0)
        val restored = CortexCalibration7901().also { it.decode(c.encode()) }
        assertTrue(restored.revise8322("CORE", 0.0, 0.0, 2.0, 8.0, stamp))
        assertEquals(1, restored.netFit8372("CORE", 3.0)!!.n)
        assertEquals(5.0, restored.netFit8372("CORE", 3.0)!!.expectedNetPct8372(1.0), 0.0)
    }

    @Test fun legacyShadowPairsStayStoredButNeverEnterTheLiveFit8418() {
        val raw = "1.0:40.0:x:" + CortexCalibration7901.TARGET_SHADOW_NET_8372 + ";1.0:5.0:x"
        val c = CortexCalibration7901().also { it.decode(JSONObject().put(CortexCalibration7901.ISO_KEY_PREFIX_8366 + "CORE", raw)) }
        val fit = c.netFit8372("CORE", 2.0)!!
        assertEquals(1, fit.n)
        assertEquals(3.0, fit.expectedNetPct8372(1.0), 0.0)
        assertEquals(2, c.encode().getString(CortexCalibration7901.ISO_KEY_PREFIX_8366 + "CORE").split(';').size)   // never reset
    }

    @Test fun historicalThreeFieldPairsDefaultToForwardBasisWithoutChangingTheirRawStorage() {
        val rawPairs = "5.0:8.0:-0.1;5.0:12.0:-0.2"
        val original = JSONObject().put(CortexCalibration7901.ISO_KEY_PREFIX_8366 + "CORE", rawPairs)
        val c = CortexCalibration7901().also { it.decode(original) }
        assertEquals(7.0, c.netFit8372("CORE", 3.0)!!.expectedNetPct8372(5.0), 0.0)
        assertEquals(rawPairs, c.encode().getString(CortexCalibration7901.ISO_KEY_PREFIX_8366 + "CORE"))
    }

    @Test fun consistencyAndPredictionShareTheSameCostAdjustedBasis() {
        val c = CortexCalibration7901()
        // superseded by V5.0.8418 — the 8404 version mixed 40 shadow pairs (charged no offset) into this fit. Forward pairs only:
        // both rows now pay the same offset, and the fit's consistency and prediction read the same forward basis.
        repeat(40) {
            c.learn("CORE", 0.0, 0.0, 2.0, 0.0)
            c.learn("CORE", 1.0, 0.0, 4.0, 1.0)
        }
        assertFalse(c.uncalibrated8372("CORE", 0.0))
        assertFalse(c.uncalibrated8372("CORE", 3.0))
        assertFalse(c.suspends8372("CORE", 0.5, 3.0))
        val fit = c.netFit8372("CORE", 3.0)!!
        assertTrue(fit.calibrated)
        assertEquals(-1.0, fit.expectedNetPct8372(0.0), 0.0)
        assertEquals(1.0, fit.expectedNetPct8372(1.0), 0.0)
    }

    @Test fun unknownTargetBasisCannotSilentlyBecomeExecutableEvidence() {
        val c = CortexCalibration7901()
        assertTrue(c.learn("CORE", 0.0, 0.0, 10.0, 1.0, "UNKNOWN").isNaN())
        assertNull(c.netFit8372("CORE", 3.0))
        val stamp = c.learn("CORE", 0.0, 0.0, 2.0, 1.0)
        assertFalse(c.revise8322("CORE", 0.0, 0.0, 2.0, 100.0, stamp, "UNKNOWN"))
        assertEquals(-1.0, c.netFit8372("CORE", 3.0)!!.expectedNetPct8372(1.0), 0.0)
    }
}
