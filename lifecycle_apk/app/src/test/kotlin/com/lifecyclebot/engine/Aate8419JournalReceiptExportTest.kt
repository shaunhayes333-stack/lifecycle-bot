package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8419 — port audit of fix/8365: the CSV footer and getStatsFiltered already classify closes by their net realised
 * SOL receipt (V5.0.8365), but the PDF tax report and the Journal screen's tiles still read the quoted mark percentage.
 * Both now read the same receipt, through one pure row function, and a close with no economic basis stays unclassified.
 * The LLM's auto-tune config context no longer presents the unwired minDiscoveryScore knob (V5.0.8365 removed it from
 * the tuner, the prompts and the proposal keys).
 */
class Aate8419JournalReceiptExportTest {
    private fun src(name: String) = File("src/main/kotlin/com/lifecyclebot/$name").readText()

    private fun row(side: String, markPct: Double, soldCostBasisSol: Double, grossProceedsSol: Double, feeSol: Double) =
        TradeJournal.JournalEntry(
            ts = 1_000L, symbol = "PHANTOM", mint = "mint", side = side, entryPrice = 1.0, exitPrice = 1.1606,
            solAmount = grossProceedsSol, pnlSol = grossProceedsSol - soldCostBasisSol - feeSol, pnlPct = markPct,
            reason = "", mode = "LIVE", score = 0.0, durationMins = 1.0, phase = "", tradingMode = "MOONSHOT",
            tradingModeEmoji = "", feeSol = feeSol, netPnlSol = grossProceedsSol - soldCostBasisSol - feeSol,
            soldCostBasisSol = soldCostBasisSol, grossProceedsSol = grossProceedsSol,
        )

    @Test fun quotedRallyNeverClassifiesARealisedSolLossAsAWin() {
        // the mark says +16.06 %; the receipt returned 0.014598 SOL for 0.031 SOL of sold basis: a -52.9 % loss
        val e = row("SELL", 16.06, soldCostBasisSol = 0.031, grossProceedsSol = 0.014598, feeSol = 0.0)
        val pct = JournalReceiptAccounting6663.realisedReturnPct8419(e)
        assertEquals(-52.909677419, pct!!, 1e-8)
        assertTrue("a realised loss is a loss whatever the mark says", pct <= -2.0)
        assertTrue("the quoted mark is untouched on the row", e.pnlPct > 0.5)
    }

    @Test fun partialExitIsReadOnItsSoldBasisNetOfFees() {
        val e = row("PARTIAL_SELL", -3.0, soldCostBasisSol = 0.01, grossProceedsSol = 0.012, feeSol = 0.0005)
        val pct = JournalReceiptAccounting6663.realisedReturnPct8419(e)
        assertEquals(15.0, pct!!, 1e-9)
        assertTrue("a mark that says loss cannot hide a realised win either", pct >= 0.5)
    }

    @Test fun buysAndSellsWithoutAnEconomicBasisStayUnclassified() {
        assertNull(JournalReceiptAccounting6663.realisedReturnPct8419(row("BUY", 0.0, 0.031, 0.0, 0.0)))
        // no sold basis, no entry cost, no cash: there is nothing to divide by, and the mark is never the fallback
        assertNull(JournalReceiptAccounting6663.realisedReturnPct8419(row("SELL", 42.0, 0.0, 0.0, 0.0)))
    }

    @Test fun pdfReportAndJournalTilesReadTheReceiptNotTheMark() {
        val journal = src("engine/TradeJournal.kt")
        val pdf = journal.substringAfter("fun exportPdf(").substringBefore("fun exportIrs8949(")
        assertFalse("PDF win rate on the mark", pdf.contains("isWin(entry.pnlPct)") || pdf.contains("isDecisive(it.pnlPct)"))
        assertFalse("PDF total on the recorded pnl", pdf.contains("sells.sumOf { it.pnlSol }"))
        assertTrue(pdf.contains("JournalReceiptAccounting6663.netReturnPct(row.second.costBasisSol, row.second.netGainSol)"))
        assertTrue(pdf.contains("val pct8419 = JournalReceiptAccounting6663.netReturnPct(acct.costBasisSol, acct.netGainSol)"))
        assertTrue(pdf.contains("Unclassified Sells:"))
        val ui = src("ui/JournalActivity.kt")
        assertTrue(ui.contains("com.lifecyclebot.engine.JournalReceiptAccounting6663.realisedReturnPct8419(it)"))
        assertFalse(ui.contains("terminal8015.filter { it.pnlPct >= 0.5 }"))
        assertTrue("the TRADES tile still counts terminal sells", ui.contains("tvJournalCount.text = terminal8015.size.toString()"))
    }

    @Test fun autoTuneConfigContextDoesNotPresentTheUnwiredDiscoveryKnob() {
        val hooks = src("engine/SentienceHooks.kt")
        val cfg = hooks.substringAfter("val cfgLine = try {").substringBefore("} catch (_: Throwable) { \"config unavailable\" }")
        assertFalse(cfg.contains("minDiscoveryScore="))
        assertTrue(cfg.contains("minLiquidityUsd="))
        assertFalse(LlmParameterTuner.isAllowedKey("minDiscoveryScore"))
    }
}
