package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7875VerifiedFillBasisTest {
    @Test fun fillEconomicsProveTheEntryPrice() {
        // 0.043 SOL for 1,000,000 tokens at $150/SOL = $0.00000645 per token.
        assertEquals(0.00000645, verifiedFillPriceUsd7875(0.043, 1_000_000.0, 150.0)!!, 1e-12)
        assertNull(verifiedFillPriceUsd7875(0.0, 1_000_000.0, 150.0))
        assertNull(verifiedFillPriceUsd7875(0.043, 0.0, 150.0))
        assertNull(verifiedFillPriceUsd7875(0.043, 1_000_000.0, 0.0))
        assertNull(verifiedFillPriceUsd7875(Double.NaN, 1_000_000.0, 150.0))
    }

    @Test fun verifiedBuyCommitDerivesBasisInsteadOfCrashing() {
        val exec = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val commit = exec.substringAfter("fun commitVerifiedLiveBuySideEffects6637(").substringBefore("val verifiedTrade = Trade(")
        assertTrue(commit.contains("verifiedFillEntryPrice7875(ts, actualCostSol, qtyUi)"))
        assertFalse(commit.contains("ts.position.entryPrice.takeIf { it.isFinite() && it > 0.0 }"))
        val helper = exec.substringAfter("private fun verifiedFillEntryPrice7875").substringBefore("    /**")
        assertTrue(helper.contains("entryPriceSource = \"VERIFIED_FILL_7875\""))
    }

    @Test fun starvedReadsTheWalletNotPendingIntents() {
        assertTrue(ToolkitSignalSheet.capitalStarved7875(0.0))
        val sheet = File("src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt").readText()
        assertTrue(sheet.contains("capitalStarved=${'$'}{capitalStarved7875(sharedCash)}"))
    }

    @Test fun redispatchIsCountedEveryTimeButLoggedOncePerClose() {
        val c = File("src/main/kotlin/com/lifecyclebot/engine/truth/SellFinalityUniqueCounter7231.kt").readText()
        assertTrue(c.contains("if (existing.redispatchCount.get() == 1L) ForensicLogger.lifecycle("))
    }
}
