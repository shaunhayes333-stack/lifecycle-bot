package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8365JournalReceiptTruthTest {

    @Test fun quotedRallyCannotClassifyARealisedSolLossAsAWin() {
        val sell = JournalReceiptAccounting6663.sell(
            solAmount = 0.014598,
            recordedPnl = -0.016402,
            recordedFee = 0.0,
            entryCost = 0.031,
            soldCostBasis = 0.031,
            grossProceeds = 0.014598
        )
        val pct = JournalReceiptAccounting6663.netReturnPct(sell.costBasis, sell.netGain)
        assertEquals(-0.016402, sell.netGain, 1e-10)
        assertEquals(-52.909677419, pct!!, 1e-8)
        assertTrue(pct < 0.0) // even if an unrelated mark says +16.06%
    }

    @Test fun partialExitUsesSoldCostBasisAndIncludesFees() {
        val pct = JournalReceiptAccounting6663.netReturnPct(0.01, 0.002 - 0.0005)
        assertEquals(15.0, pct!!, 1e-9)
        assertNull(JournalReceiptAccounting6663.netReturnPct(0.0, 0.002))
        assertNull(JournalReceiptAccounting6663.netReturnPct(Double.NaN, 0.002))
        assertNull(JournalReceiptAccounting6663.netReturnPct(0.01, Double.POSITIVE_INFINITY))
    }

    @Test fun csvAndJournalStatsClassifyOnEconomicReceiptsOnly() {
        val source = File("src/main/kotlin/com/lifecyclebot/engine/TradeJournal.kt").readText()
        val footer = source.substringAfter("// V5.0.3683 — Footer derived strictly")
            .substringBefore("summary(\"=== SUMMARY")
        assertFalse(footer.contains("isWin(it.entry.pnlPct)"))
        assertFalse(footer.contains("isLoss(it.entry.pnlPct)"))
        assertTrue(footer.contains("netReturnPct(row.acct.costBasisSol, row.acct.netGainSol)"))

        val stats = source.substringAfter("fun getStatsFiltered(entries: List<JournalEntry>)")
            .substringBefore("fun exportPdf(")
        assertFalse(stats.contains("isWin(it.pnlPct)"))
        assertTrue(stats.contains("deriveRowAccounting(entry, 1.0)"))
        assertTrue(source.contains("Unclassified Sells (No Economic Basis)"))
    }
}
