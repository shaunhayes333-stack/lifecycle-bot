package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.cortex.CortexLedger7885
import org.junit.Assert.*
import org.junit.Test

class Aate8404CostBasisTest {
    private val lane = "QUALITY"
    private fun grade(book: CortexLedger7885, shadow: Boolean) = book.grade(lane, emptyList(), emptyList(),
        doubleArrayOf(), -5.0, -2.0, shadowNet8404 = shadow)!!
    private fun read(book: CortexLedger7885): Double {
        val raw = book.fuse(lane, emptyList())
        return Cortex7885.calibratedRead8372(false, raw, 1.0, null, 3.0,
            book.fuseCostBasis8404(lane, emptyList(), "", 3.0))
    }

    @Test fun assessmentUsesOneFrozenOffsetForProjectionAndFinalSubtraction() {
        val source = java.io.File("src/main/kotlin/com/lifecyclebot/engine/cortex/Cortex7885.kt").readText()
        val assessment = source.substringAfter("fun assess(").substringBefore("private class Locked8325")
        assertEquals("a concurrent refresh must not mix two cost bases", 1,
            Regex("executableOffset8187\\(lane\\)").findAll(assessment).count())
        // superseded by V5.0.8418 — no shadow cost credit: the assessment no longer projects a cost-basis fusion (8404:
        // "regime, offset8404), offset8404)"); every read is the frozen fusion minus the one full offset.
        assertTrue(assessment.contains("fp.first, offset8404)"))
        assertFalse(assessment.contains("fuseCostBasis8404("))
        assertTrue(assessment.contains("locked8325.executionOffset8404, locked8325.costFused8404)"))
    }

    // superseded by V5.0.8418 §NO_SHADOW_COST_CREDIT — 8404 credited the execution offset back in proportion to a book's
    // shadow-marked share (a shadow -5 read -5, a mixed book -6.5). That was right only if every shadow net had paid the whole
    // executableOffset8187 (impact + latency chase + fees + the realised-vs-label shortfall), which no shadow fill models, so
    // the credit is removed: a shadow-marked contribution reads exactly like a forward one (-5 - 3 = -8).
    @Test fun executableShadowLossIsNotChargedTwiceAndStoredLossNeverBecomesProfit() {
        val book = CortexLedger7885()
        grade(book, true)
        assertEquals(-8.0, read(book), 1e-9)
        assertEquals(-5.0, book.fuse(lane, emptyList()).edgePct, 1e-9)   // the stored raw loss is unchanged
        val restored = CortexLedger7885().also { it.decode(book.encode()) }
        assertEquals(-8.0, read(restored), 1e-9)
        assertEquals(1.0, restored.lanes.getValue(lane).shadowWeight8404, 0.0)   // the basis stays recorded (no reset)
    }

    @Test fun mixedPopulationChargesOnlyForwardShareAndRetainsThatBasisThroughRestart() {
        val book = CortexLedger7885()
        grade(book, false)
        assertEquals(-8.0, read(book), 1e-9)
        grade(book, true)
        assertEquals("a shadow share never lowers the charged cost", -8.0, read(book), 1e-9)
        val restored = CortexLedger7885().also { it.decode(book.encode()) }
        assertEquals(-8.0, read(restored), 1e-9)
    }

    @Test fun sameValueShadowRevisionChangesBasisOnceWithoutAddingASample() {
        val book = CortexLedger7885()
        val trace = grade(book, false)
        fun revise() = book.revise8006(lane, emptyList(), emptyList(), doubleArrayOf(), trace,
            -5.0, -5.0, -2.0, -2.0, shadowNet8404 = true)
        revise()
        assertEquals(-8.0, read(book), 1e-9)
        revise()
        assertEquals(1.0, book.fuse(lane, emptyList()).laneN, 0.0)
        assertEquals(-8.0, read(book), 1e-9)
    }

    @Test fun sameReturnWithNewIndependentWitnessUpdatesTheOriginalContribution() {
        val book = CortexLedger7885()
        val trace = book.grade(lane, emptyList(), emptyList(), doubleArrayOf(), 95.0, 100.0)!!
        assertEquals(0.0, book.lanes.getValue(lane).witnessed8324, 0.0)
        book.revise8006(lane, emptyList(), emptyList(), doubleArrayOf(), trace,
            95.0, 95.0, 100.0, 100.0, witnessedOld8324 = false, witnessedNew8324 = true)
        assertEquals(1.0, book.lanes.getValue(lane).witnessed8324, 0.0)
        assertEquals(1.0, book.lanes.getValue(lane).n, 0.0)
        assertEquals(95.0, book.lanes.getValue(lane).rawMean8187(), 0.0)
    }

    @Test fun contributionBasisDecaysWithItsOriginalValueAndLegacyRowsGetNoInventedCredit() {
        val stat = CortexLedger7885.Stat()
        val stamp = stat.add(-5.0, false)
        stat.scale(0.5)
        stat.add(-5.0, false, shadowNet8404 = true)
        stat.revise(-5.0, -5.0, false, false, stat.revisionWeight(stamp), shadowNew8404 = true)
        assertEquals(stat.n, stat.shadowWeight8404, 0.0)
        // superseded by V5.0.8418 — no credit (8404: -2.0); the prediction is the plain bounded mean.
        assertEquals(-5.0, stat.predictionWithShadowCredit8404(3.0), 1e-9)
        val legacy = CortexLedger7885.Stat().also { it.decode("1.0,-5.0,25.0,0.0,0.0,0.0,0.0,INDEPENDENT_CURVE_8365") }
        assertEquals(-5.0, legacy.predictionWithShadowCredit8404(3.0), 1e-9)
    }

    @Test fun scoreboardAndExecutableCopyCarryPaidCostsWithoutChangingRawReturns() {
        val board = com.lifecyclebot.engine.cortex.CortexScoreboard7885()
        val bucket = com.lifecyclebot.engine.cortex.CortexScoreboard7885.Bucket.STRONG
        board.record(lane, bucket, false, -5.0, -2.0, shadowNet8404 = true)
        val book = board.books.getValue(lane)
        book.applyOffset8187(3.0)
        // superseded by V5.0.8418 — the shadow-marked record pays the full offset (8404 read -5.0 here).
        assertEquals(-8.0, book.byBucket[bucket.ordinal].mean(), 1e-9)
        val copy = Cortex7885.executableCopy8187(book.byBucket[bucket.ordinal], 3.0, 0.0, Double.NaN)
        assertEquals(-8.0, copy.mean(), 1e-9)
        assertEquals(-5.0, book.byBucket[bucket.ordinal].rawMean8187(), 1e-9)
    }
}
