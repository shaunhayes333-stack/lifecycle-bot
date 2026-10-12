package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

/** Every processor handling a partial must preserve the requested raw amount. */
class Aate8079PartialQuantityTruthTest {
    private val wallet = BigInteger("142650442934")

    @Test fun simpleSixtyPercentNeverBecomesFullWallet() {
        val requestedUi = 85590.265760
        val selected = ProcessorAmountPlanner.partialRequestedRaw8079(wallet, requestedUi, 6, null)
        assertEquals(BigInteger("85590265760"), selected)
        assertTrue(selected!! < wallet)
    }

    @Test fun amountAndFractionAreIndependentUpperBounds() {
        val a = ProcessorAmountPlanner.partialRequestedRaw8079(wallet, 140000.0, 6, 0.25)
        assertEquals(BigInteger("35662610733"), a)
        val b = ProcessorAmountPlanner.partialRequestedRaw8079(wallet, 80000.0, 6, 0.75)
        assertEquals(BigInteger("80000000000"), b)
    }

    @Test fun missingUnpricedAndTinyPartialsNeverDrainWallet() {
        assertNull(ProcessorAmountPlanner.partialRequestedRaw8079(wallet, Double.NaN, 6, null))
        assertNull(ProcessorAmountPlanner.partialRequestedRaw8079(wallet, 0.0000001, 6, null))
        assertNull(ProcessorAmountPlanner.partialRequestedRaw8079(wallet, 10.0, -1, null))
        assertEquals(wallet, ProcessorAmountPlanner.partialRequestedRaw8079(wallet, 99999999.0, 6, null))
    }

    @Test fun regressionSourceIncludesRpcAndRecoveredPartialSizing() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ProcessorAmountPlanner.kt").readText()
        assertTrue(src.contains("partialRequestedRaw8079(availableRaw, requestedUiQty, decimals, fraction)"))
        assertTrue(src.contains("partialRequestedRaw8079(availableRaw, requestedUiQty, decimals, null)"))
        assertTrue(src.contains("PARTIAL_FULL_WALLET_BLOCKED_8079"))
    }
}
