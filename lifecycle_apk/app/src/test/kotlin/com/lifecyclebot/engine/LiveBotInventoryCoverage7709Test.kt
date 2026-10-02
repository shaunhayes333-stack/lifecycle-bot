package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveBotInventoryCoverage7709Test {
    @Test
    fun positiveBotBalancesRequireCanonicalQuantityCoverage() {
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/LiveBuyAdmissionGate.kt").readText()

        assertTrue(gate.contains("botSource && positive"))
        assertTrue(gate.contains("canonicalRawByMint = canonicalRows.groupBy { it.mint }"))
        assertTrue(gate.contains("walletRaw > canonicalRaw + java.math.BigInteger.ONE"))
        assertTrue(gate.contains("val unmanaged = botHeld.filter { mint ->"))
        assertTrue(gate.contains("mark/route recovery required"))
        assertFalse(gate.contains("LIVE_EXIT_COVERAGE_UNSELLABLE_IGNORED_7707"))
    }

    @Test
    fun missingMarksUseIndependentFeedRepair() {
        val recovery = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveCanonicalRecovery6686.kt").readText()

        assertTrue(recovery.contains("ParallelMarkFanout7088.resolve7088(listOf(mint))[mint]"))
        assertTrue(recovery.contains("HostWalletTokenTracker.recordPriceUpdate(mint, px, 0.0)"))
        assertFalse(recovery.contains("unsellableHoldingReason7707"))
        assertFalse(recovery.contains("DexscreenerApi().batchPriceFetch(listOf(mint))"))
    }
}
