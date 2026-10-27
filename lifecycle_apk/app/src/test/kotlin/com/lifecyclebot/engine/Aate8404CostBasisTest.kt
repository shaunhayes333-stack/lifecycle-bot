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
        assertTrue(assessment.contains("regime, offset8404), offset8404)"))
        assertTrue(assessment.contains("locked8325.executionOffset8404, locked8325.costFused8404)"))
    }

    @Test fun executableShadowLossIsNotChargedTwiceAndStoredLossNeverBecomesProfit() {
        val book = CortexLedger7885()
        grade(book, true)
        assertEquals(-5.0, read(book), 1e-9)
        assertEquals(-5.0, book.fuse(lane, emptyList()).edgePct, 1e-9)
        val restored = CortexLedger7885().also { it.decode(book.encode()) }
        assertEquals(-5.0, read(restored), 1e-9)
        assertEquals(-5.0, restored.fuse(lane, emptyList()).edgePct, 1e-9)
    }

    @Test fun mixedPopulationChargesOnlyForwardShareAndRetainsThatBasisThroughRestart() {
        val book = CortexLedger7885()
        grade(book, false)
        assertEquals(-8.0, read(book), 1e-9)
        grade(book, true)
        assertEquals(-6.5, read(book), 1e-9)
        val restored = CortexLedger7885().also { it.decode(book.encode()) }
        assertEquals(-6.5, read(restored), 1e-9)
    }

    @Test fun sameValueShadowRevisionChangesBasisOnceWithoutAddingASample() {
        val book = CortexLedger7885()
        val trace = grade(book, false)
        fun revise() = book.revise8006(lane, emptyList(), emptyList(), doubleArrayOf(), trace,
            -5.0, -5.0, -2.0, -2.0, shadowNet8404 = true)
        revise()
        assertEquals(-5.0, read(book), 1e-9)
        revise()
        assertEquals(1.0, book.fuse(lane, emptyList()).laneN, 0.0)
        assertEquals(-5.0, read(book), 1e-9)
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
        assertEquals(-2.0, stat.predictionWithShadowCredit8404(3.0), 1e-9)
        val legacy = CortexLedger7885.Stat().also { it.decode("1.0,-5.0,25.0,0.0,0.0,0.0,0.0,INDEPENDENT_CURVE_8365") }
        assertEquals(-5.0, legacy.predictionWithShadowCredit8404(3.0), 1e-9)
    }

    @Test fun scoreboardAndExecutableCopyCarryPaidCostsWithoutChangingRawReturns() {
        val board = com.lifecyclebot.engine.cortex.CortexScoreboard7885()
        val bucket = com.lifecyclebot.engine.cortex.CortexScoreboard7885.Bucket.STRONG
        board.record(lane, bucket, false, -5.0, -2.0, shadowNet8404 = true)
        val book = board.books.getValue(lane)
        book.applyOffset8187(3.0)
        assertEquals(-5.0, book.byBucket[bucket.ordinal].mean(), 1e-9)
        assertEquals(-5.0, book.rest().mean(), 1e-9)
        val copy = Cortex7885.executableCopy8187(book.byBucket[bucket.ordinal], 3.0, 0.0, Double.NaN)
        assertEquals(-5.0, copy.mean(), 1e-9)
        assertEquals(-5.0, book.byBucket[bucket.ordinal].rawMean8187(), 1e-9)
    }
}
