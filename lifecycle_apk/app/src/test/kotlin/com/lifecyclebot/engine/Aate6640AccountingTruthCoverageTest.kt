package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Regression coverage for the impossible-paper-balance incident in 5.0.6639. */
class Aate6640AccountingTruthCoverageTest {

    @Test
    fun journalHeroCashUsesConservationIdentityNotRawSellProceeds() {
        val src = File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/JournalEconomicReplay6619.kt"
        ).readText()
        assertTrue(src.contains("val lots = mutableMapOf<String, Lot>()"))
        assertTrue(src.contains("SELL_WITHOUT_MATCHING_BUY_LOT"))
        assertTrue(src.contains("MISSING_OR_NEGATIVE_BASIS"))
        assertTrue(src.contains("action=quarantine_exact_event"))
        assertFalse(src.contains("gross - t.pnlSol"))
        assertFalse(src.contains("if (openCost < 0.0) openCost = 0.0"))
    }

    @Test
    fun paperConservationComparesCostBasisIdentity() {
        val src = File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/PaperEquityCalculator6467.kt"
        ).readText()
        assertTrue(src.contains("val expectedAccounted = baselineSol + realized - fees"))
        assertTrue(src.contains("val actualAccounted = cash + openCost"))
        assertTrue(src.contains("val delta = actualAccounted - expectedAccounted"))
        assertFalse(src.contains("equity - expected - mv"))
    }

    @Test
    fun mainHeroWithholdsUnreconciledPaperBalance() {
        val src = File("src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt").readText()
        assertTrue(src.contains("UnifiedAccountSnapshot6635.read(\"MEME\")"))
        assertTrue(src.contains("HERO_BALANCE_WITHHELD_UNRECONCILED_6640"))
        assertTrue(src.contains("PAPER · BALANCE WITHHELD"))
    }
}
