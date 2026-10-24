package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test

/** Real production receipt/selection math; no Android service or market provider is involved. */
class Aate8372SourceEpisodeTest {
    private fun row(token: String = "mintA@1000", lane: String = "EXPRESS", at: Long = 1000L,
                    pnl: Double = -10.0, mode: String = "LIVE", sources: List<String> = listOf("DEX_TRENDING")) =
        ScannerSourceEpisodes8372.Outcome(token, "$token|$lane|$at", mode, sources, pnl, at)

    @Test fun sameTokenEpisodeAcrossLanesContributesOnlyOnce() {
        val book = ScannerSourceEpisodes8372()
        assertTrue(book.record(row()))
        assertFalse(book.record(row(lane = "MOONSHOT", pnl = 400.0)))
        assertFalse(book.record(row()))
        assertEquals(1L, book.snapshot("DEX_TRENDING")!!.samples)
        assertEquals(-10.0, book.snapshot("DEX_TRENDING")!!.mean, 0.0)
    }

    @Test fun laterWitnessedWinnerCorrectsLossWithoutIncreasingN() {
        val book = ScannerSourceEpisodes8372()
        val first = row()
        assertTrue(book.record(first))
        assertTrue(book.revise(first.copy(pnlPct = 1250.0)))
        val s = book.snapshot("DEX_TRENDING")!!
        assertEquals(1L, s.samples)
        assertEquals(1L, s.wins)
        assertEquals(1250.0, s.mean, 0.0)
        assertFalse(book.revise(first.copy(pnlPct = 1250.0)))
    }

    @Test fun aDifferentLaneCannotRewriteOwnersOutcome() {
        val book = ScannerSourceEpisodes8372()
        book.record(row())
        assertFalse(book.revise(row(lane = "MOONSHOT", pnl = 100.0)))
        assertFalse(book.revise(row(pnl = 100.0, sources = listOf("RAYDIUM_NEW_POOL"))))
        assertFalse(book.revise(row(at = 1001L, pnl = 100.0)))
        assertEquals(-10.0, book.snapshot("DEX_TRENDING")!!.mean, 0.0)
    }

    @Test fun aLaterEpisodeOfSameTokenCanTeachAgainAndModeIsExplicit() {
        val book = ScannerSourceEpisodes8372()
        assertTrue(book.record(row()))
        assertTrue(book.record(row(token = "mintA@2000", at = 2000L, pnl = 15.0)))
        assertTrue(book.record(row(token = "mintA@3000", at = 3000L, pnl = 4.0, mode = "PAPER")))
        assertFalse(book.record(row(token = "mintB@3000", at = 3000L, mode = "UNKNOWN")))
        assertEquals(3L, book.snapshot("DEX_TRENDING")!!.samples)
    }

    @Test fun familyUnionDoesNotCountCompoundAndAliasesAsExtraTokens() {
        val book = ScannerSourceEpisodes8372()
        book.record(row(sources = listOf("DEX_TRENDING,DEX_BOOSTED", "DEX_TRENDING", "DEX_BOOSTED")))
        assertEquals(1L, book.snapshot("DEX_TRENDING")!!.samples)
        assertEquals(1L, book.snapshot("DEX_BOOSTED")!!.samples)
        assertEquals(1L, book.familySnapshot(listOf("DEX"))!!.samples)
        assertEquals(1L, book.familySnapshot(listOf("TRENDING", "BOOSTED"))!!.samples)
    }

    @Test fun receiptsAndCorrectionsSurviveRestore() {
        val book = ScannerSourceEpisodes8372()
        val first = row()
        book.record(first)
        book.revise(first.copy(pnlPct = 80.0))
        val restored = ScannerSourceEpisodes8372()
        restored.restore(book.export())
        assertFalse(restored.record(first))
        assertTrue(restored.revise(first.copy(pnlPct = -7.0)))
        assertEquals(1L, restored.snapshot("DEX_TRENDING")!!.samples)
        assertEquals(0L, restored.snapshot("DEX_TRENDING")!!.wins)
        assertEquals(-7.0, restored.snapshot("DEX_TRENDING")!!.mean, 0.0)
    }

    @Test fun boundedRetirementRejectsOldReplayButKeepsNewEpisodes() {
        val book = ScannerSourceEpisodes8372(capacity = 2)
        book.record(row())
        book.record(row(token = "mintB@2000", at = 2000L))
        book.record(row(token = "mintC@3000", at = 3000L))
        assertEquals(2, book.size())
        assertFalse(book.record(row()))
        assertFalse(book.revise(row(pnl = 500.0)))
        val restored = ScannerSourceEpisodes8372(capacity = 2)
        restored.restore(book.export())
        assertEquals(2, restored.size())
        assertFalse(restored.record(row()))
        assertEquals(2L, restored.snapshot("DEX_TRENDING")!!.samples)
    }

    @Test fun tiedOldestTimesDoNotCorruptRestoreWatermark() {
        val book = ScannerSourceEpisodes8372(capacity = 2)
        book.record(row())
        book.record(row(token = "mintB@1000"))
        book.record(row(token = "mintC@2000", at = 2000L))
        val restored = ScannerSourceEpisodes8372(capacity = 2)
        restored.restore(book.export())
        assertEquals(book.size(), restored.size())
        assertEquals(book.snapshot("DEX_TRENDING"), restored.snapshot("DEX_TRENDING"))
    }

    @Test fun durableAckRequiresExactOwnerReturnButForeignLaneIsADedupe() {
        val committed = ScannerSourceEpisodes8372()
        val original = row()
        assertFalse(committed.acknowledges(original))
        committed.record(original)
        assertTrue(committed.acknowledges(original))
        assertTrue(committed.acknowledges(row(lane = "MOONSHOT", pnl = 500.0)))
        assertFalse(committed.acknowledges(original.copy(pnlPct = 80.0)))
        committed.revise(original.copy(pnlPct = 80.0))
        assertTrue(committed.acknowledges(original.copy(pnlPct = 80.0)))
        assertFalse(committed.acknowledges(original))
    }

    @Test fun durableRetirementAndCapacityRejectionAreTerminalNotInfiniteRetries() {
        val committed = ScannerSourceEpisodes8372(capacity = 2)
        committed.record(row(token = "mintB", at = 2000L))
        committed.record(row(token = "mintC", at = 3000L))
        assertTrue(committed.acknowledges(row(at = 1000L)))
        assertFalse(committed.acknowledges(row(token = "mintD", at = 4000L)))
        committed.record(row(token = "mintD", at = 4000L))
        assertTrue(committed.acknowledges(row(token = "mintB", at = 2000L)))
        assertTrue(committed.acknowledges(row(pnl = Double.NaN)))
        assertTrue(committed.acknowledges(row(sources = emptyList())))
    }

    @Test fun unpricedUnknownAndNonFiniteOutcomesDoNotBecomeTrainingSamples() {
        val book = ScannerSourceEpisodes8372()
        assertFalse(book.record(row(pnl = Double.NaN)))
        assertFalse(book.record(row(pnl = Double.POSITIVE_INFINITY)))
        assertFalse(book.record(row(at = 0L)))
        assertFalse(book.record(row(sources = emptyList())))
        assertFalse(book.record(row(sources = listOf(""))))
        assertEquals(0, book.size())
    }

    @Test fun lowWinRatePositiveNetEvIsNotPenalisedForWinRateAlone() {
        val book = ScannerSourceEpisodes8372()
        repeat(150) { i ->
            book.record(row(token = "mint$i", at = i + 1L, pnl = if (i % 5 == 0) 100.0 else -1.0))
        }
        val s = book.snapshot("DEX_TRENDING")!!
        assertEquals(0.2, s.winRate, 1e-12)
        assertTrue(s.mean > 0.0)
        assertTrue(s.supported())
        assertTrue(s.netAwareCentred() >= 0.0)
    }

    @Test fun highWinRateNegativeNetEvIsNotPromotedForWinRateAlone() {
        val book = ScannerSourceEpisodes8372()
        repeat(150) { i ->
            book.record(row(token = "mint$i", at = i + 1L, pnl = if (i % 5 == 0) -50.0 else 1.0))
        }
        val s = book.snapshot("DEX_TRENDING")!!
        assertEquals(0.8, s.winRate, 1e-12)
        assertTrue(s.mean < 0.0)
        assertTrue(s.supported())
        assertTrue(s.netAwareCentred() <= 0.0)
    }

    @Test fun aSingleHugeWinnerAndImmatureSamplesCannotReplaceLegacyAuthority() {
        val book = ScannerSourceEpisodes8372()
        repeat(20) { i -> book.record(row(token = "mint$i", at = i + 1L, pnl = if (i == 0) 2000.0 else -10.0)) }
        assertFalse(book.snapshot("DEX_TRENDING")!!.supported())
        val immature = ScannerSourceEpisodes8372()
        repeat(19) { i -> immature.record(row(token = "mint$i", at = i + 1L, pnl = 5.0)) }
        assertFalse(immature.snapshot("DEX_TRENDING")!!.supported())
    }
}
