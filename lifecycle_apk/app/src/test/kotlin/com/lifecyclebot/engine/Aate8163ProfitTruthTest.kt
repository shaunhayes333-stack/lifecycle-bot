package com.lifecyclebot.engine

import java.math.BigInteger
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate8163ProfitTruthTest {
    @Test fun reportedLosingHarvestsCannotBecomeProfitableBecauseOfMarks() {
        assertTrue(ProfitExitTruth8163.profitTaking("protective_peak_partial_2113pct"))
        assertTrue(ProfitExitTruth8163.profitTaking("wallet_growth_harvest_4.3x"))
        assertFalse(ProfitExitTruth8163.clears(0.0308, (0.0308 * 0.86 * 1e9).toLong(), 300))
        assertFalse(ProfitExitTruth8163.clears(0.0308, (0.0308 * 0.20 * 1e9).toLong(), 300))
        assertFalse(ProfitExitTruth8163.profitTaking("strict_sl"))
        assertFalse(ProfitExitTruth8163.profitTaking("emergency_rug"))
        assertFalse(ProfitExitTruth8163.profitTaking("owner_manual_sell"))
    }

    @Test fun realHundredToSixHundredPercentRunnersCanBankQuarterSlices() {
        val slice = ProfitExitTruth8163.sliceCost(0.0308, BigInteger.valueOf(1_000_000L), BigInteger.valueOf(250_000L))!!
        assertEquals(0.0077, slice, 1e-12)
        for (multiple in listOf(2.0, 4.0, 7.0)) {
            assertTrue(ProfitExitTruth8163.clears(slice, (slice * multiple * 1e9).toLong(), 500))
        }
        assertFalse(ProfitExitTruth8163.clears(slice, (slice * 1e9).toLong(), 0))
    }

    @Test fun unknownOrOversoldBasisAndFeesDoNotProveProfit() {
        assertNull(ProfitExitTruth8163.sliceCost(0.03, BigInteger.TEN, BigInteger.valueOf(11)))
        assertNull(ProfitExitTruth8163.sliceCost(Double.NaN, BigInteger.TEN, BigInteger.ONE))
        assertFalse(ProfitExitTruth8163.clears(null, 1_000_000_000, 0))
        assertFalse(ProfitExitTruth8163.clears(0.03, 31_000_000, 500))
        assertFalse(ProfitExitTruth8163.clears(0.03, 32_000_000, 0, 0.003))
    }

    @Test fun realizedPercentFollowsSolAndPartialCost() {
        assertEquals(-80.0, RealizedReturn8163.percent(-0.024, 0.03, 0.03, 22.7)!!, 1e-9)
        assertEquals(-14.0, RealizedReturn8163.percent(-0.001078, 0.0077, 0.0308, 2113.0)!!, 1e-9)
        assertEquals(-10.0, RealizedReturn8163.percent(-0.003, 0.0, 0.03, 73.0)!!, 1e-9)
        assertNull(RealizedReturn8163.percent(-0.003, 0.0, 0.0, 73.0))
    }

    @Test fun executionAndDiscoveryWiringCannotBypassTheNewChecks() {
        val root = File("src/main/kotlin/com/lifecyclebot/engine")
        val executor = File(root, "Executor.kt").readText()
        assertTrue(executor.contains("ProfitExitTruth8163.basisPreflight8239(ts, reason, sellUnits)"))
        assertTrue(executor.contains("ProfitExitTruth8163.acceptQuote(ts, reason, activeQuote7228"))
        val scanner = File(root, "SolanaMarketScanner.kt").readText()
        assertFalse(scanner.contains("if (scanRaydiumApiNewPools8128()) return"))
        assertFalse(scanner.contains("poolName.contains(\"/USDC\")"))
        val ui = File(root.parentFile, "ui/MainActivity.kt").readText()
        assertTrue(ui.contains(".count { it.mode.equals(portfolioMode, true) }"))
        val price = File(root, "RealPriceLock.kt").readText()
        assertTrue(price.contains("ts.tokenMap.decimals"))
        assertFalse(price.contains("pickDecimals(qty)"))
    }
}
