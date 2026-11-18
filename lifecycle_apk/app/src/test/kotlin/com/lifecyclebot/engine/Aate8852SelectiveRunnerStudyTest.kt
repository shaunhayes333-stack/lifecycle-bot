package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LabelMark8386
import com.lifecyclebot.engine.truth.RunnerStudy8852
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate8852SelectiveRunnerStudyTest {
    private val at = 1_800_000_000_000L
    private val book = RunnerStudy8852

    private fun mark(mint: String, px: Double, atMs: Long, independent: Boolean = true): LabelMark8386 =
        LabelMark8386(mint, "pump.curve", px, atMs, atMs,
            if (independent) "CURVE_FRAME" else "OFFWATCH", independentBasis = independent)

    @After fun clear() = book.resetForTest()

    @Test fun onlyNativeOrCryptoReadyMayNominateLongResearch() {
        val mint = "mint_not_selected"
        book.observe(mint, mark(mint, 1.0, at + 1_000), at + 1_000, 4.0, true)
        assertTrue(book.activeMints().isEmpty())
        assertTrue(book.nominate(mint, "MOONSHOT", "MEME_NATIVE_READY", 88.0, at))
        assertTrue(book.nominate(mint, "SHITCOIN", "MEME_NATIVE_READY", 79.0, at))
        assertEquals("one mint, multiple ready lanes: no duplicated $10 tickets", 1, book.activeMints().size)
        val meme = File("src/main/kotlin/com/lifecyclebot/engine/market/SpecialistCandidateBooks7803.kt").readText()
        val crypto = File("src/main/kotlin/com/lifecyclebot/perps/CryptoStrategyCandidateBooks7803.kt").readText()
        assertTrue(meme.substringAfter("internal fun markReady(").substringBefore("internal fun markLost(")
            .contains("RunnerStudy8852.nominate("))
        assertFalse(meme.substringAfter("internal fun markQualified(").substringBefore("internal fun markReady(")
            .contains("RunnerStudy8852.nominate("))
        assertTrue(crypto.substringAfter("internal fun ready(").substringBefore("internal fun lose(")
            .contains("RunnerStudy8852.nominate("))
        assertFalse(crypto.substringAfter("internal fun qualify(").substringBefore("internal fun ready(")
            .contains("RunnerStudy8852.nominate("))
    }

    @Test fun sixHour800xIsMarkedOpportunityNotProvenEightThousandDollarFill() {
        val m = "mint800"
        assertTrue(book.nominate(m, "MOONSHOT", "MEME_NATIVE_READY", 92.0, at))
        book.observe(m, mark(m, 1.0, at + 5_000L), at + 5_000L, 4.0, true)
        book.observe(m, mark(m, 800.0, at + 5_000L + book.SIX_HOURS_MS),
            at + 5_000L + book.SIX_HOURS_MS)
        val result = book.resultsForTest().single()
        assertEquals(RunnerStudy8852.State.COMPLETE_MARK_ONLY, result.state)
        assertEquals(7_999.6, result.holdUsd10!!, 0.0001)
        assertEquals(7_999.6, result.peakUsd10!!, 0.0001)
        assertNull("two marks six hours apart cannot establish the intervening exit policy", result.priceOnlyExitUsd10)
        assertEquals(2, result.marks)
        assertEquals(at + 5_000L, result.entryAtMs)
        assertEquals(at + 5_000L + book.SIX_HOURS_MS, result.endAtMs)
        assertTrue(book.line().contains("markedOnly=NO_QUOTES"))
    }

    @Test fun replayCapturesPartialProfitsAndPriceGivebackOnlyOnDensePaths() {
        val m = "mint_dense"
        assertTrue(book.nominate(m, "CRYPTO_MOMENTUM", "CRYPTO_READY", 81.0, at))
        val entryAt = at + 5_000L
        book.observe(m, mark(m, 1.0, entryAt), entryAt, 4.0, true)
        for (i in 1..72) {
            val px = when (i) { 1 -> 2.0; 2 -> 5.0; 3 -> 10.0; else -> 6.0 }
            val stamp = entryAt + i * 5L * 60_000L
            book.observe(m, mark(m, px, stamp), stamp)
        }
        val r = book.resultsForTest().single()
        assertEquals(RunnerStudy8852.State.COMPLETE_MARK_ONLY, r.state)
        assertEquals(59.6, r.holdUsd10!!, 0.0001)
        assertEquals(99.6, r.peakUsd10!!, 0.0001)
        assertEquals(64.732, r.priceOnlyExitUsd10!!, 0.0001)
        assertEquals("PRICE_PEAK_GIVEBACK_35", r.exitReason)
        assertEquals(5 * 60_000L, r.maxGapMs)
    }

    @Test fun missingPriceAndPhantom800xNeverBecomeWinningOrLosingLabels() {
        val m1 = "no_entry"
        book.nominate(m1, "EXPRESS", "MEME_NATIVE_READY", 70.0, at)
        book.matureMissing(at + book.ENTRY_WINDOW_MS + book.EXIT_GRACE_MS + 1)
        val lostEntry = book.resultsForTest().last()
        assertEquals(RunnerStudy8852.State.ENTRY_UNAVAILABLE, lostEntry.state)
        assertNull(lostEntry.holdUsd10)

        val m2 = "no_exit"
        book.nominate(m2, "CORE", "MEME_NATIVE_READY", 79.0, at)
        book.observe(m2, mark(m2, 1.0, at + 1_000), at + 1_000, 4.0, false)
        book.observe(m2, mark(m2, 800.0, at + book.SIX_HOURS_MS + 1_000, independent = false),
            at + book.SIX_HOURS_MS + 1_000)
        book.matureMissing(at + book.SIX_HOURS_MS + book.EXIT_GRACE_MS + 2_000)
        val lostExit = book.resultsForTest().last()
        assertEquals(RunnerStudy8852.State.EXIT_UNAVAILABLE, lostExit.state)
        assertNull(lostExit.holdUsd10)
    }

    @Test fun readyCohortIsBoundedAndCanRenominateAReawakenedMint() {
        repeat(book.MAX_ACTIVE) { i -> assertTrue(book.nominate("mint_$i", "CORE", "READY", 80.0, at)) }
        assertFalse(book.nominate("mint_over_cap", "MOONSHOT", "READY", 99.0, at))
        assertEquals(book.MAX_ACTIVE, book.activeMints().size)
        book.matureMissing(at + book.ENTRY_WINDOW_MS + book.EXIT_GRACE_MS + 1)
        assertTrue(book.activeMints().isEmpty())
        assertTrue(book.nominate("mint_0", "DIP_HUNTER", "REAWAKENED_READY", 86.0,
            at + book.SIX_HOURS_MS + 10_000L))
    }

    @Test fun restartRestoresSixHourEntryAndDoesNotResetItsClock() {
        val m = "restart_mint"
        book.nominate(m, "CASHGEN", "CRYPTO_READY", 71.0, at)
        val entered = at + 2_000L
        book.observe(m, mark(m, 1.0, entered), entered, 4.0, true)
        val saved = book.encode()
        book.resetForTest()
        book.restore(saved, at + 3_000L)
        book.observe(m, mark(m, 3.0, entered + book.SIX_HOURS_MS),
            entered + book.SIX_HOURS_MS)
        assertEquals(29.6, book.resultsForTest().single().holdUsd10!!, 0.0001)
        assertEquals(entered, book.resultsForTest().single().entryAtMs)
    }
}
