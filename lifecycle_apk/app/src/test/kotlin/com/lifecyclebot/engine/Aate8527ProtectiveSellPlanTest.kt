package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.sell.StopLatencyCore8153
import com.lifecyclebot.engine.truth.AssetClass
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441 as Cpa
import com.lifecyclebot.engine.truth.CanonicalTokenAmount
import com.lifecyclebot.engine.truth.PositionStateLedger6454
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import java.io.File
import java.math.BigInteger

/** Funded basis-uncertain inventory remains sellable without becoming trusted valuation or learning. */
class Aate8527ProtectiveSellPlanTest {
    private val raw = BigInteger.valueOf(10_000L)

    @Before fun reset() {
        Cpa.resetForTest()
        PositionStateLedger6454.resetForTest()
    }
    @After fun cleanup() {
        Cpa.resetForTest()
        PositionStateLedger6454.resetForTest()
    }

    private fun protected(): TokenState {
        val mint = "ProtectivePlan8527" + System.nanoTime()
        val id = "LIVE:$mint:8527"
        assertEquals(Cpa.ProtectiveOwnership7807.CREATED,
            Cpa.ensureProtectiveLiveOwnership7807(id, mint, "P", "MOONSHOT",
                raw, 6, 0.0, 0.0, "verifiedBuy8527", "unknownBasis8527"))
        return TokenState(mint, symbol = "P").apply {
            position = Position(qtyToken = 0.01, isPaperPosition = false,
                tradingMode = "MOONSHOT", positionId = id)
        }
    }

    private fun confirmedPlan(ts: TokenState, walletRaw: BigInteger, decimals: Int = 6): ProcessorAmountPlanner.SellPlan? {
        val canonical = ProcessorAmountPlanner.canonicalSellPosition8527(ts, "CATASTROPHIC_EXIT") ?: return null
        val sellRaw = StopLatencyCore8153.fullExitRaw8153(
            walletRaw, decimals, canonical.remainingQtyRaw, canonical.quantityScale) ?: return null
        return ProcessorAmountPlanner.planSellFromConfirmed(
            ts, "JUPITER", sellRaw, CanonicalTokenAmount(sellRaw, decimals).uiDoubleForDisplay(),
            walletRaw, CanonicalTokenAmount(walletRaw, decimals).uiDoubleForDisplay(), decimals)
    }

    @Test fun fundedQuarantineSizesFromVerifiedWalletAndRemainsUntrusted() {
        val ts = protected()
        val id = ts.position.positionId
        assertFalse(Cpa.openPositions().any { it.positionId == id })
        assertEquals(Cpa.Lifecycle.QUARANTINED,
            ProcessorAmountPlanner.canonicalSellPosition8527(ts, "CATASTROPHIC_EXIT")!!.lifecycle)
        // Never drain more than either actual wallet quantity or this canonical lot.
        assertEquals(8_000L, confirmedPlan(ts, BigInteger.valueOf(8_000L))!!.rawAmount)
        assertEquals(10_000L, confirmedPlan(ts, BigInteger.valueOf(20_000L))!!.rawAmount)
        assertNull(confirmedPlan(ts, BigInteger.ZERO))
        assertNull(confirmedPlan(ts, BigInteger.valueOf(-1L)))
        assertNull(confirmedPlan(ts, raw, 9))
        assertNull(ProcessorAmountPlanner.canonicalSellPosition8527(ts, "TAKE_PROFIT"))
        assertNotNull(ProcessorAmountPlanner.canonicalSellPosition8527(ts, "MANUAL_CLOSE"))
        assertEquals(0.0, Cpa.getPosition(id)!!.entryPriceUsd, 0.0)
        assertEquals(0.0, Cpa.getPosition(id)!!.entryCostSol, 0.0)
        assertEquals(0.0, Cpa.getPosition(id)!!.realizedPnlSol, 0.0)
    }

    @Test fun wrongLotPaperMintFrozenAndPhantomRowsNeverAcquireSellAuthority() {
        val ts = protected()
        assertNull(ProcessorAmountPlanner.canonicalSellPosition8527(
            ts.copy(position = ts.position.copy(positionId = "otherLot")), "HARD_STOP"))
        assertNull(ProcessorAmountPlanner.canonicalSellPosition8527(
            ts.copy(position = ts.position.copy(isPaperPosition = true)), "HARD_STOP"))
        assertNull(ProcessorAmountPlanner.canonicalSellPosition8527(
            ts.copy(mint = "differentMint8527"), "HARD_STOP"))
        assertNull(ProcessorAmountPlanner.canonicalSellPosition8527(
            TokenState("noCanonical8527").apply { position = Position(isPaperPosition = false) }, "HARD_STOP"))
        Cpa.quarantine(ts.position.positionId, "FROZEN_TOKEN_ACCOUNT_7253:verified")
        assertNull(ProcessorAmountPlanner.canonicalSellPosition8527(ts, "HARD_STOP"))
    }

    @Test fun anOldQuarantineCannotSellTheReplacementOpenLot() {
        val ts = protected()
        val currentId = ts.position.positionId + ":new"
        assertEquals(Cpa.MutateResult.APPLIED, Cpa.openPosition(
            "newBuy8527", currentId, ts.mint, "P", "MOONSHOT", "run8527",
            0.02, raw, 6, 0.0, false, entryPriceUsd = 2.0, entryPriceSource = "VERIFIED_FILL_8527"))
        assertNull(ProcessorAmountPlanner.canonicalSellPosition8527(ts, "HARD_STOP"))
        val current = ts.copy(position = ts.position.copy(positionId = currentId))
        assertEquals(currentId, ProcessorAmountPlanner.canonicalSellPosition8527(current, "TAKE_PROFIT")!!.positionId)
    }

    @Test fun offChainOwnershipCannotEnterASolanaSellPlan() {
        val ts = protected()
        Cpa.resetForTest()
        assertEquals(Cpa.MutateResult.APPLIED, Cpa.openPosition(
            "otherAsset8527", ts.position.positionId, ts.mint, "P", "CRYPTO_SPOT", "run8527",
            0.02, raw, 6, 0.0, false, entryPriceUsd = 2.0, entryPriceSource = "VERIFIED_FILL_8527",
            assetClass = AssetClass.CRYPTO_ALT))
        assertNull(ProcessorAmountPlanner.canonicalSellPosition8527(ts, "HARD_STOP"))
    }

    @Test fun signedOrdersInvalidateWarmWalletProofAndConfirmedExitRemainsQuantityOnly() {
        val ts = protected()
        val id = ts.position.positionId
        val proof = StopLatencyCore8153.HeldProof8153(raw, 6, "OWNER_MINT_RPC", 2_000L)
        assertTrue(StopLatencyCore8153.usable8153(proof, 3_000L, 1_000L, false))
        assertFalse(StopLatencyCore8153.usable8153(proof, 3_000L, 2_000L, false))
        assertFalse(StopLatencyCore8153.usable8153(proof, 3_000L, 1_000L, true))
        assertEquals(PositionStateLedger6454.ReserveResult.RESERVED,
            PositionStateLedger6454.reserveTerminalSell(id, "CATASTROPHIC_EXIT"))
        assertTrue(Cpa.applyProtectiveSellFill7807(ts.mint, raw, true, "FINALIZED_8527",
            confirmedRemainingRaw = BigInteger.ZERO))
        val closed = Cpa.getPosition(id)!!
        assertEquals(Cpa.Lifecycle.CLOSED, closed.lifecycle)
        assertEquals(BigInteger.ZERO, closed.remainingQtyRaw)
        assertEquals(0.0, closed.realizedPnlSol, 0.0)
        assertEquals(0.0, closed.realizedProceedsSol, 0.0)
        assertNull(ProcessorAmountPlanner.canonicalSellPosition8527(ts, "HARD_STOP"))
        assertFalse(Cpa.openPositions().any { it.positionId == id })
    }

    @Test fun bothColdProofRoutesAndTheHotExecutorUseTheSameCanonicalOwner() {
        fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
        val planner = src("engine/ProcessorAmountPlanner.kt")
        assertEquals(2, Regex("val canonical = canonicalSellPosition8527\\(ts, reason\\)").findAll(planner).count())
        assertFalse(planner.contains("CanonicalPositionAuthority6441.openPositions()"))
        assertTrue(planner.contains("canonical.remainingQtyRaw.min(walletRaw)"))
        assertTrue(planner.contains("canonical.quantityScale != decimals"))
        val executor = src("engine/Executor.kt").substringAfter("private fun hotSellPlan8153(")
            .substringBefore("private fun effectiveJitoTipLamports(")
        assertTrue(executor.contains("ProcessorAmountPlanner.canonicalSellPosition8527(ts, exitReason)"))
        assertTrue(executor.contains("StopLatencyWiring8153.emergencyHolding8153(wallet, ts.mint)"))
        assertTrue(executor.contains("StopLatencyCore8153.fullExitRaw8153("))
        assertFalse(executor.contains("CanonicalPositionAuthority6441.openPositions()"))
    }
}
