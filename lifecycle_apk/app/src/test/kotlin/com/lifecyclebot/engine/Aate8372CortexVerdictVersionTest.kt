package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexGradeBook8144
import com.lifecyclebot.engine.cortex.CortexGradeRecord8144
import com.lifecyclebot.engine.cortex.LearningEntryTruth8153
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate8372CortexVerdictVersionTest {
    private fun record(id: String, bucket: Int, version: Int) =
        CortexGradeRecord8144('D', id, "$id|CORE", 1_000L, "CORE", "S", "R", "T", "C", "COH", bucket,
            20.0, false, true, false).also { it.scoreVersion8372 = version }

    @Test fun currentVerdictRowDoesNotBorrowHistoricalStrongWins() {
        val book = CortexGradeBook8144()
        listOf(Triple("old", 0, 100.0), Triple("current", 1, -10.0)).forEach { (id, version, net) ->
            book.addDecision(record(id, 2, version))
            book.label(id, net, net + 2.0, net, true, 10_000L)
        }
        val all = LearningEntryTruth8153.verdictRow8153(book, "CORE")
        val current = LearningEntryTruth8153.verdictRow8153(book, "CORE", scoreVersion = 1)
        assertEquals(2, all.strongN)
        assertEquals(45.0, all.strongMean, 1e-12)
        assertEquals(1, current.strongN)
        assertEquals(-10.0, current.strongMean, 1e-12)
        assertEquals(-8.0, current.strongGrossMedian8198, 1e-12)
        assertEquals(2, book.size())
        assertEquals(0, book.decision("old")!!.scoreVersion8372)
    }

    @Test fun resolvedDenominatorUsesTheSameScoreVersionIncludingMissingLabels() {
        val book = CortexGradeBook8144()
        book.addDecision(record("old", 2, 0)); book.label("old", 100.0, 102.0, 100.0, true, 10_000L)
        book.addDecision(record("seen", 2, 1)); book.label("seen", -10.0, -8.0, -10.0, true, 10_000L)
        book.addDecision(record("lost", 2, 1)); book.markLost("lost")
        assertEquals(0.5, LearningEntryTruth8153.verdictRow8153(book, "CORE", 1).strongResolved, 1e-12)
        assertEquals(2.0 / 3.0, LearningEntryTruth8153.verdictRow8153(book, "CORE").strongResolved, 1e-12)
    }

    @Test fun selectedExitRemainsCostedOnceInTheVersionFilteredDisplayAfterRestart() {
        val book = CortexGradeBook8144().also { it.execOffset8187 = { 3.0 } }
        book.addDecision(record("exit", 2, 1)); book.shadowOutcome8372("exit", 7.0, 9.0, 20_000L)
        book.addDecision(record("forward", 0, 1)); book.label("forward", -2.0, 0.0, -2.0, true, 20_000L)
        val restored = CortexGradeBook8144().also { it.execOffset8187 = { 3.0 }; it.decode(book.encode()) }
        val current = LearningEntryTruth8153.verdictRow8153(restored, "CORE", 1)
        // superseded by V5.0.8418 — a SHADOW_EXIT row is stored but never graded evidence (8372 read strongMean 7.0 /
        // gross median 9.0 from the simulated exit): the verdict row, authority and Kelly bucket stats skip it.
        assertTrue(current.strongMean.isNaN())
        assertEquals("SHADOW_EXIT", restored.decision("exit")!!.outcomeSource8372)
        assertEquals(-5.0, current.refuseMean, 1e-12)
        assertEquals(0.0, current.refuseGrossMedian8198, 1e-12)
        assertTrue(LearningEntryTruth8153.verdictRow8153(restored, "CORE", 0).strongMean.isNaN())
    }
}
