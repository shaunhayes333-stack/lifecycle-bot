package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.*
import org.junit.Assert.*
import org.junit.Test

class Aate8521CortexCloseRecoveryTest {
    private fun trade(mode: String = "PAPER", mint: String = "crypto|ABC", pid: String = "position-8521") =
        CortexGradeRecord8144('T', pid, "$mint|CORE", 1_000L, "CORE", "BASE_BREAKOUT", "UPTREND",
            "EARLY", "C3_6", "SMALL", CortexScoreboard7885.Bucket.STRONG.ordinal, 8.0,
            true, true, false).also { it.mode = mode; it.scoreVersion8372 = 2 }

    private fun owner(r: CortexGradeRecord8144, mode: String = r.mode, mint: String = "crypto|ABC",
                      pid: String = r.id) = LearningEntryTruth8153.restoredTradeOwner8521(r, pid, mint, mode,
        mode, mint, "CORE", "BASE_BREAKOUT", null)

    @Test fun openTradeRestoredWithoutVotersStillLearnsItsCanonicalCloseOnceForEachMode() {
        for (mode in listOf("LIVE", "PAPER")) {
            val initial = CortexGradeBook8144()
            val original = trade(mode)
            assertTrue(initial.openTrade(original))
            val restored = CortexGradeBook8144().also { it.decode(initial.encode()) }
            val r = restored.trade(original.id)!!
            assertTrue(r.realisedPct.isNaN())
            assertNotNull(restored.closeTrade(r.id, owner(r)!!, 24.5))
            assertEquals(listOf(24.5), restored.realisedSeries("CORE", "BASE_BREAKOUT", null, mode))
            assertEquals(8.0, r.predictedEdgePct, 0.0)
            assertEquals(2, r.scoreVersion8372)
            assertEquals(CortexScoreboard7885.Bucket.STRONG.ordinal, r.bucket)
            assertNull(owner(r))
            val again = CortexGradeBook8144().also { it.decode(restored.encode()) }
            assertNull(owner(again.trade(r.id)!!))
            assertEquals(listOf(24.5), again.realisedSeries("CORE", "BASE_BREAKOUT", null, mode))
        }
    }

    @Test fun restoredPredictionNeverMovesAcrossLotAssetOrMode() {
        val r = trade()
        assertNull(owner(r, mode = "LIVE"))
        assertNull(owner(r, mode = "SHADOW"))
        assertNull(owner(r, mint = "crypto|XYZ"))
        assertNull(owner(r, pid = "new-entry-same-asset"))
        assertTrue(r.realisedPct.isNaN())
    }

    @Test fun sealedStrategyWinsWithoutRewritingTheStoredForecast() {
        val book = CortexGradeBook8144()
        val r = trade()
        book.openTrade(r)
        val exact = LearningEntryTruth8153.restoredTradeOwner8521(r, r.id, "crypto|ABC", "PAPER",
            "PAPER", "crypto|ABC", "QUALITY", "PULLBACK", null)!!
        assertNotNull(book.closeTrade(r.id, exact, -7.0))
        assertEquals(listOf(-7.0), book.realisedSeries("QUALITY", "PULLBACK", null, "PAPER"))
        assertEquals("CORE", r.lane)
        assertEquals("BASE_BREAKOUT", r.setup)
        assertEquals(8.0, r.predictedEdgePct, 0.0)
    }

    @Test fun unknownVoterReadDoesNotInventAStrongForecast() {
        val (r, route) = LearningEntryTruth8153.unboundTrade8153("unread", "mint", "PAPER", 1_000L,
            "CORE", "PAPER", "mint", "CORE", "BASE_BREAKOUT", null)!!
        val book = CortexGradeBook8144()
        book.openTrade(r)
        book.closeTrade(r.id, route, 35.0)
        assertEquals(LearningEntryTruth8153.NO_READ_BUCKET_8153, r.bucket)
        assertTrue(r.predictedEdgePct.isNaN())
        assertTrue(book.realisedSeries("CORE", null, CortexScoreboard7885.Bucket.STRONG.ordinal, "PAPER").isEmpty())
        assertEquals(listOf(35.0), book.realisedSeries("CORE", null, null, "PAPER"))
    }

    @Test fun everyCheckpointPartMustBeReadableBeforeAnyRestoreIsPublished() {
        val keys = listOf(CortexForwardEpoch8521.BOOKS, CortexForwardEpoch8521.GRADES, CortexForwardEpoch8521.PENDING)
        val data = keys.associateWith { "saved-$it" }
        for (bad in keys) {
            val read = arrayListOf<String>()
            val failed = CortexForwardEpoch8521.readStoredRows({ k -> read += k; if (k == bad) null else data[k] }, { it == bad })
            assertNull(failed)
            assertEquals(keys, read)
            val recovered = CortexForwardEpoch8521.readStoredRows(data::get) { false }!!
            assertEquals(data[keys[0]], recovered.books)
            assertEquals(data[keys[1]], recovered.grades)
            assertEquals(data[keys[2]], recovered.pending)
        }
    }

    @Test fun emptyFirstBootAndTransientReadFailureRemainDifferentStates() {
        val firstBoot = CortexForwardEpoch8521.readStoredRows({ null }, { false })!!
        assertNull(firstBoot.books)
        assertNull(firstBoot.grades)
        assertNull(firstBoot.pending)
        assertNull(CortexForwardEpoch8521.readStoredRows({ null }, { it == CortexForwardEpoch8521.PENDING }))
    }
}
