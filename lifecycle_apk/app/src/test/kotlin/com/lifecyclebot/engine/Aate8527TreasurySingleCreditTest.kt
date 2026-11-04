package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * A canonical Executor sell owns the single treasury debit/credit.
 * The nine private native desks only project a settled position close.
 * The old 5.9.495z17 desk hooks independently credited the same profit,
 * including paper->live cross-mode calls because isPaper was omitted.
 */
class Aate8527TreasurySingleCreditTest {
    private fun source(relative: String) =
        File("src/main/kotlin/com/lifecyclebot/" + relative).readText()

    @Test fun nativeFinalizersDoNotCreditTreasuryOrFireOnChainTransferAgain() {
        for (owner in listOf(
            "ShitCoinTraderAI", "MoonshotTraderAI", "QualityTraderAI",
            "BlueChipTraderAI", "CashGenerationAI",
        )) {
            val code = source("v3/scoring/${owner}.kt")
            assertFalse("${owner} duplicate treasury credit",
                Regex("TreasuryManager\\s*\\.\\s*contributeFromMemeSell\\s*\\(").containsMatchIn(code))
            assertFalse("${owner} duplicate treasury scalp credit",
                Regex("TreasuryManager\\s*\\.\\s*contributeFullyFromTreasuryScalp\\s*\\(").containsMatchIn(code))
        }
        val moonshot = source("v3/scoring/MoonshotTraderAI.kt")
        assertFalse("Moonshot native closure must not credit the shared paper wallet",
            moonshot.contains("BotService.status.paperWalletSol ="))
        assertTrue("native Moonshot split remains a pure local sizing estimate",
            moonshot.contains("TreasuryManager.currentSplitPct()"))
    }

    @Test fun executorKeepsItsSingleConfirmedSellAllocationOwner() {
        val executor = source("engine/Executor.kt")
        assertTrue(executor.contains("canonicalPaperSellCommitted6474 && pnl > 0"))
        assertTrue(executor.contains("TreasuryManager.contributeFromMemeSell(pnl, WalletManager.lastKnownSolPrice, isPaper = true)"))
        assertTrue(executor.contains("TreasuryManager.contributeFromMemeSell(pnl, WalletManager.lastKnownSolPrice)"))
        val treasury = source("engine/TreasuryManager.kt")
        assertTrue("contribution has real on-chain effects and cannot be replayed from lane projections",
            treasury.contains("triggerOnChainTransferIfLive(contribSol"))
    }
}
