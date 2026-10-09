package com.lifecyclebot.engine

import com.lifecyclebot.engine.chart.ChartLibrary7950
import com.lifecyclebot.engine.truth.LiveReceiptSpent7959
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.7959 — one buy receipt pays for one position; the chart reader wakes on a broad crypto library. */
class Aate7959SpentReceiptTest {
    @Test fun aReceiptClosedByAFullCloseIsSpent() {
        // 7kEtLE: bought at t=1000, fully closed at t=5000, wallet still held it, re-adopted with the same receipt.
        assertTrue(LiveReceiptSpent7959.isSpent(1_000L, 5_000L))
        assertTrue(LiveReceiptSpent7959.isSpent(5_000L, 5_000L))
        // A fresh buy after the close is its own receipt.
        assertFalse(LiveReceiptSpent7959.isSpent(6_000L, 5_000L))
        // Never closed: the receipt is live.
        assertFalse(LiveReceiptSpent7959.isSpent(1_000L, null))
        assertFalse(LiveReceiptSpent7959.isSpent(1_000L, 0L))
    }

    @Test fun tokensThatOutliveTwoClosesAreParkedNotLooped() {
        LiveReceiptSpent7959.resetForTest()
        assertFalse(LiveReceiptSpent7959.shouldPark("7kEtLE"))   // first time: re-adopt at the observed mark
        assertTrue(LiveReceiptSpent7959.shouldPark("7kEtLE"))    // sold again and still held: park
        assertFalse(LiveReceiptSpent7959.shouldPark("other"))
        assertTrue(LiveReceiptSpent7959.status().contains("parked=1"))
        LiveReceiptSpent7959.resetForTest()
    }

    @Test fun broadCryptoLibraryReadsMemesOnceAnchored() {
        val quarter = ChartLibrary7950.CAPACITY / 4
        // 5.0.7958 live: 13,260 motifs, 13,076 crypto, 184 meme/live.
        assertFalse(ChartLibrary7950.matureFor7959(13_260, 13_076, 184))
        assertTrue(ChartLibrary7950.matureFor7959(13_560, 13_076, ChartLibrary7950.MIN_MEME_7959))
        assertFalse(ChartLibrary7950.matureFor7959(quarter - 1, 13_076, 5_000))
        assertFalse(ChartLibrary7950.matureFor7959(quarter, 1_999, 5_000))
    }
}
