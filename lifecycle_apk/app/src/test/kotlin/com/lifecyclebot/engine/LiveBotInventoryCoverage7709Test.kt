package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.LiveExitCoverageGuard7701

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
        assertTrue(gate.contains("WalletAccountCache.snapshot(ttlMs = 5_000L)"))
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

    @Test
    fun unresolved_holding_reserves_one_slot_without_freezing_other_candidates() {
        assertFalse(
            LiveExitCoverageGuard7701.shouldBlockCandidate7712(
                candidateMint = "new-mint",
                unmanagedMints = setOf("held-mint"),
                canonicalMints = emptySet(),
                slotLimit = 2,
            ),
        )
        assertTrue(
            LiveExitCoverageGuard7701.shouldBlockCandidate7712(
                candidateMint = "new-mint",
                unmanagedMints = setOf("held-mint"),
                canonicalMints = emptySet(),
                slotLimit = 1,
            ),
        )
    }

    @Test
    fun candidate_cannot_add_to_its_own_unmanaged_mint() {
        assertTrue(
            LiveExitCoverageGuard7701.shouldBlockCandidate7712(
                candidateMint = "held-mint",
                unmanagedMints = setOf("held-mint"),
                canonicalMints = emptySet(),
                slotLimit = 2,
            ),
        )
    }

    @Test
    fun canonical_quantity_and_unresolved_mint_share_the_slot_budget_once_each() {
        assertTrue(
            LiveExitCoverageGuard7701.shouldBlockCandidate7712(
                candidateMint = "new-mint",
                unmanagedMints = setOf("held-mint", "managed-but-overage"),
                canonicalMints = setOf("managed-but-overage"),
                slotLimit = 2,
            ),
        )
    }
}
